package com.example.galleryakio.editor

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CropRotate
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TOOL_TRIM = 0
private const val TOOL_CROP = 1
private const val TOOL_FILTERS = 2
private const val TOOL_TONE = 3
private const val TOOL_SPEED = 4
private val TOOLS = listOf(
    Icons.Rounded.ContentCut to "Trim",
    Icons.Rounded.CropRotate to "Transform",
    Icons.Rounded.Workspaces to "Filters",
    Icons.Rounded.WbSunny to "Tone",
    Icons.Rounded.Speed to "Speed",
)
private val TONE_PARAMS = Param.entries.filter { !it.imageOnly }
private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
private const val STRIP_FRAMES = 8
private const val STRIP_HEIGHT_PX = 120

/**
 * Previews on one player: colours through the TextureView's layer paint (a GPU colour filter),
 * turn, flip and crop through Compose layers, and speed on the player. Nothing touches pixels
 * until the export.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoEditor(uri: Uri, displayName: String, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(VideoEdit()) }
    val history = remember { History(VideoEdit()) { edit = it } }
    var tool by remember { mutableIntStateOf(TOOL_TRIM) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var durationMs by remember { mutableLongStateOf(0L) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
    }
    val texture = remember { arrayOfNulls<TextureView>(1) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0) videoSize = size
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration > 0) durationMs = player.duration
                if (state == Player.STATE_ENDED) {
                    player.seekTo(edit.startMs)
                    player.play()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Stays inside the trimmed part. Polled, since a player can't be told about an end point
    // without being prepared again.
    LaunchedEffect(player, edit.startMs, edit.endMs) {
        while (true) {
            val end = edit.endMs
            if (end != null && player.isPlaying && player.currentPosition >= end) player.seekTo(edit.startMs)
            delay(50)
        }
    }
    LaunchedEffect(edit.speed) { player.setPlaybackSpeed(edit.speed) }
    LaunchedEffect(edit.muted) { player.volume = if (edit.muted) 0f else 1f }
    LaunchedEffect(edit.recipe.preset, edit.recipe.presetStrength, edit.recipe.adjust) {
        texture[0]?.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(edit.recipe.colorMatrix()) })
    }

    val frames by produceState<List<Bitmap>>(emptyList(), uri) {
        value = withContext(Dispatchers.IO) { loadStrip(context, uri) }
    }
    val cropPicture by produceState<Bitmap?>(null, tool, edit.startMs) {
        if (tool != TOOL_CROP) return@produceState
        value = withContext(Dispatchers.IO) { frameAt(context, uri, edit.startMs) }
    }

    val dirty = edit != VideoEdit()
    DiscardGuard(dirty, onClose)
    val backdrop = rememberLayerBackdrop()
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    var cropRatioLabel by remember { mutableStateOf("Free") }
    var toneParam by remember { mutableIntStateOf(0) }
    val toneOptions = remember { TONE_PARAMS.map { it.icon() to it.label } }

    CompositionLocalProvider(LocalEditorBackdrop provides backdrop) {
    Box(Modifier.fillMaxSize()) {
        // Glass here sits on black, which never changes: it costs nothing per video frame.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).drawBehind { drawRect(Color.Black) })
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                canUndo = edit.let { history.canUndo },
                canRedo = edit.let { history.canRedo },
                canRevert = dirty,
                onUndo = history::undo,
                onRedo = history::redo,
                onRevert = { history.commit(VideoEdit()) },
                onSave = {
                    player.pause()
                    progress = 0f
                    exportJob = scope.launch {
                        try {
                            onSaved(exportVideo(context, uri, displayName, edit) { progress = it })
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            Toast.makeText(context, error.exportMessage(), Toast.LENGTH_LONG).show()
                        } finally {
                            exportJob = null
                        }
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val still = cropPicture?.let { remember(it, edit.recipe.turns, edit.recipe.flipped) { it.oriented(edit.recipe) } }
                if (tool == TOOL_CROP && still != null) {
                    CropCanvas(
                        picture = still,
                        recipe = edit.recipe,
                        ratio = cropRatio,
                        showGrid = true,
                        onCrop = { history.preview(edit.copy(recipe = edit.recipe.copy(crop = it))) },
                        onCommit = { history.commit(edit) },
                    )
                    CropActions(
                        ratioLabel = cropRatioLabel,
                        onFlip = { history.commit(edit.copy(recipe = edit.recipe.flippedOver())) },
                        onTurn = {
                            history.commit(edit.copy(recipe = edit.recipe.turned()))
                            cropRatio = null
                            cropRatioLabel = "Free"
                        },
                        onRatio = { value, label ->
                            cropRatio = value
                            cropRatioLabel = label
                            if (value != null) history.commit(edit.copy(recipe = edit.recipe.copy(crop = centeredCrop(value * still.height / still.width))))
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = if (isPlaying) "Pause" else "Play") {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        VideoPreview(videoSize, edit.recipe, texture, player)
                        if (!isPlaying) {
                            Box(Modifier.size(64.dp).liquid(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(196.dp), contentAlignment = Alignment.BottomCenter) {
                when (tool) {
                    TOOL_TRIM -> TrimPanel(frames, durationMs, edit, { history.preview(it); player.seekTo(it.startMs) }, history::commit)
                    TOOL_CROP -> Text("Drag the frame to crop", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp))
                    TOOL_FILTERS -> FilterPanel(frames.firstOrNull(), edit.recipe, { history.preview(edit.copy(recipe = it)) }, { history.commit(edit.copy(recipe = it)) })
                    TOOL_TONE -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val param = TONE_PARAMS[toneParam]
                        OptionPicker(toneOptions, changed = { TONE_PARAMS[it].get(edit.recipe.adjust) != 0f }, toneParam, onSelect = { toneParam = it })
                        RulerDial(
                            param.get(edit.recipe.adjust),
                            param.min,
                            1f,
                            { history.preview(edit.copy(recipe = edit.recipe.copy(adjust = param.set(edit.recipe.adjust, it)))) },
                            { history.commit(edit.copy(recipe = edit.recipe.copy(adjust = param.set(edit.recipe.adjust, it)))) },
                        )
                    }
                    TOOL_SPEED -> SpeedPanel(edit) { history.commit(it) }
                }
            }
            ToolBar(TOOLS, tool) { tool = it }
        }
    }
    }
    exportJob?.let { job -> ExportOverlay(progress) { job.cancel() } }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(size: VideoSize, recipe: EditRecipe, texture: Array<TextureView?>, player: ExoPlayer) {
    // The view comes first even with no size yet: the player only reports one once it has a surface.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val crop = recipe.crop
        val sideways = recipe.turns % 2 == 1
        val raw = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 1f
        val oriented = if (sideways) 1f / raw else raw
        val cropped = oriented * crop.width / crop.height
        val density = LocalDensity.current
        // The window: the cropped picture fitted to the space.
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val windowWidth = minOf(boxWidth, boxHeight * cropped)
        val windowHeight = windowWidth / cropped
        // The whole turned picture at that scale, slid so its crop sits in the window.
        val fullWidth = windowWidth / crop.width
        val fullHeight = windowHeight / crop.height
        val shiftX = fullWidth * (0.5f - crop.left) - windowWidth / 2f
        val shiftY = fullHeight * (0.5f - crop.top) - windowHeight / 2f
        Box(
            with(density) { Modifier.size(windowWidth.toDp(), windowHeight.toDp()) }.clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val viewWidth = if (sideways) fullHeight else fullWidth
            val viewHeight = if (sideways) fullWidth else fullHeight
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also {
                        texture[0] = it
                        it.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(recipe.colorMatrix()) })
                        player.setVideoTextureView(it)
                    }
                },
                onRelease = {
                    player.clearVideoTextureView(it)
                    texture[0] = null
                },
                modifier = with(density) { Modifier.requiredSize(viewWidth.toDp(), viewHeight.toDp()) }
                    .graphicsLayer {
                        translationX = shiftX
                        translationY = shiftY
                        // The flip comes after the turn: it's the outer of the two here.
                        scaleX = if (recipe.flipped) -1f else 1f
                        rotationZ = if (recipe.flipped) -90f * recipe.turns else 90f * recipe.turns
                    },
            )
        }
    }
}

@Composable
private fun TrimPanel(frames: List<Bitmap>, durationMs: Long, edit: VideoEdit, onPreview: (VideoEdit) -> Unit, onCommit: (VideoEdit) -> Unit) {
    val end = edit.endMs ?: durationMs
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(clock(edit.startMs), color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${clock(end - edit.startMs)} selected", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(clock(end), color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxSize().padding(vertical = 6.dp, horizontal = 8.dp)) {
                frames.forEach { frame ->
                    val image = remember(frame) { frame.asImageBitmap() }
                    Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (durationMs > 0) {
                RangeSlider(
                    value = edit.startMs.toFloat()..end.toFloat(),
                    onValueChange = { range ->
                        if (range.endInclusive - range.start >= 500f) {
                            onPreview(edit.copy(startMs = range.start.toLong(), endMs = range.endInclusive.toLong().takeIf { it < durationMs }))
                        }
                    },
                    onValueChangeFinished = { onCommit(edit) },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.35f),
                        inactiveTrackColor = Color.Black.copy(alpha = 0.6f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SpeedPanel(edit: VideoEdit, onChange: (VideoEdit) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SPEEDS.forEach { speed ->
                Chip(if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x", speed == edit.speed) { onChange(edit.copy(speed = speed)) }
            }
        }
        GlassIcon(if (edit.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (edit.muted) "Keep sound" else "Remove sound") {
            onChange(edit.copy(muted = !edit.muted))
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

private fun loadStrip(context: android.content.Context, uri: Uri): List<Bitmap> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
        (0 until STRIP_FRAMES).mapNotNull { index ->
            scaledFrame(retriever, duration * 1000 * index / STRIP_FRAMES, STRIP_HEIGHT_PX)
        }
    } catch (_: Exception) {
        emptyList()
    } finally {
        retriever.release()
    }
}

private fun frameAt(context: android.content.Context, uri: Uri, ms: Long): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        scaledFrame(retriever, ms * 1000, PROXY_SIDE)
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private fun scaledFrame(retriever: MediaMetadataRetriever, atUs: Long, side: Int): Bitmap? {
    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(atUs, option, side, side)
    val full = retriever.getFrameAtTime(atUs, option) ?: return null
    val scale = side.toFloat() / maxOf(full.width, full.height)
    return Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
}
