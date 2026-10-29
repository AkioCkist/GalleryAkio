package com.appslab.gallery.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.appslab.gallery.GalleryApplication
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.data.MediaThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Remembers playback positions per video id for the "remember video position" setting. */
object VideoPositions {
    private const val PREFS = "video_positions"

    fun get(context: Context, id: Long): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(id.toString(), 0L)

    fun put(context: Context, id: Long, position: Long, duration: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        // Finished (or nearly) videos start from the beginning next time.
        if (duration > 0 && position > duration - 2000) prefs.remove(id.toString())
        else prefs.putLong(id.toString(), position)
        prefs.apply()
    }
}

private const val AUTO_HIDE_MS = 3_000L
private val FRAME_WIDTH = 56.dp
private val STRIP_HEIGHT = 52.dp
private val ControlBackground = Color.Black.copy(alpha = 0.45f)

/** The strip stops where the finger lets go: scrubbing needs precision, not momentum. */
private val NoFling = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float) = initialVelocity
}

/** Reaching either end of the strip must not start swiping to the next photo. */
private val KeepScroll = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
}

/**
 * ExoPlayer for the current pager page only; other pages show a still frame,
 * so swiping through a mixed album never holds more than one decoder.
 *
 * The video draws into a TextureView (a SurfaceView ignores Compose transforms), so it
 * zooms and pans with the same gestures as photos. Controls follow Samsung Gallery:
 * frame capture, a play/time pill and mute above a filmstrip that scrubs under a fixed
 * playhead. They show together with the viewer's own bars ([controlsVisible] /
 * [onControlsChange]); [bottomInset] is the height of the viewer's bottom bar.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: MediaItem,
    isCurrentPage: Boolean,
    rememberPosition: Boolean,
    controlsVisible: Boolean,
    onControlsChange: (Boolean) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val still = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(MediaThumb(item.uri, 1024))
            .placeholderMemoryCacheKey(cachedThumbnailKey(context, item.id))
            .build()
    }
    // Pages next to the current one only show a still: building (and later releasing) a
    // player for them cost a decoder each and stalled swiping and leaving the viewer.
    if (!isCurrentPage) {
        AsyncImage(still, item.displayName, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize())
        return
    }

    val scope = rememberCoroutineScope()
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            if (rememberPosition) seekTo(VideoPositions.get(context, item.id))
            playWhenReady = true
            prepare()
        }
    }
    val textureView = remember { arrayOfNulls<TextureView>(1) }
    var isPlaying by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var aspect by remember {
        mutableFloatStateOf(if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9f)
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.duration ?: 0L) }
    val strip = rememberScrollState()
    val scrubbing by strip.interactionSource.collectIsDraggedAsState()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (player.duration > 0) duration = player.duration
                // Finished: bring the controls back so replay is in reach.
                if (ended) onControlsChange(true)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoSize = size
                    aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (rememberPosition) VideoPositions.put(context, item.id, player.currentPosition, player.duration)
            player.release()
        }
    }

    // While visible, the strip follows playback every frame, so the playhead glides. Only the
    // scroll offset moves each frame (a re-placement, no recomposition); the clock state is
    // written once per second. Writing it every frame recomposed the whole player 60-120
    // times a second and visibly dropped video frames while the controls were up.
    LaunchedEffect(player, controlsVisible, scrubbing) {
        if (!controlsVisible || scrubbing) return@LaunchedEffect
        while (isActive) {
            val now = player.currentPosition
            if (now / 1000 != position / 1000) position = now
            if (duration > 0 && strip.maxValue > 0) {
                val target = (strip.maxValue * now / duration).toInt()
                if (target != strip.value) strip.scrollTo(target)
            }
            if (isPlaying) withFrameNanos {} else delay(250)
        }
    }
    // Dragging the strip seeks to whatever frame sits under the playhead. Key frames only
    // while moving (fast), then one exact seek where the finger stops.
    LaunchedEffect(player, scrubbing) {
        if (!scrubbing) return@LaunchedEffect
        val wasPlaying = player.isPlaying
        player.pause()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        var target = player.currentPosition
        try {
            snapshotFlow { strip.value }.collect { v ->
                if (strip.maxValue > 0 && duration > 0) {
                    target = duration * v / strip.maxValue
                    player.seekTo(target)
                    if (target / 1000 != position / 1000) position = target
                }
            }
        } finally {
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo(target)
            position = target
            if (wasPlaying) player.play()
        }
    }
    // Like every video app: the controls fade out once playback runs on its own.
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(AUTO_HIDE_MS)
            onControlsChange(false)
        }
    }

    // Loaded here, not inside the controls: those leave composition each time they hide,
    // which threw the frames away and made every reappearance load them again.
    val frames = rememberFilmstripFrames(item, duration)

    fun togglePlay() {
        when {
            ended || (duration > 0 && player.currentPosition >= duration - 300) -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun captureFrame() {
        val view = textureView[0] ?: return
        val bitmap = if (videoSize.width > 0) view.getBitmap(videoSize.width, videoSize.height) else view.bitmap
        bitmap ?: return
        val name = item.displayName.substringBeforeLast('.') + "_" + formatDuration(player.currentPosition).replace(':', '-') + ".jpg"
        scope.launch {
            val repo = (context.applicationContext as GalleryApplication).mediaRepository
            val saved = repo.saveImage(bitmap, name)
            Toast.makeText(context, if (saved) "Frame saved to Pictures/Gallery" else "Couldn't save frame", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier.fillMaxSize()) {
        ZoomBox(resetKey = item.id, isCurrentPage = true, onTap = { onControlsChange(!controlsVisible) }) {
            Box(Modifier.aspectRatio(aspect)) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { textureView[0] = it; player.setVideoTextureView(it) } },
                    onRelease = { player.clearVideoTextureView(it); textureView[0] = null },
                    modifier = Modifier.fillMaxSize(),
                )
                // The still stays on top until the first frame is drawn, so opening a video never flashes black.
                if (!firstFrame) {
                    AsyncImage(still, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }

        AnimatedVisibility(
            controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(Icons.Outlined.PhotoCamera, "Capture frame", onClick = ::captureFrame)
                    Spacer(Modifier.weight(1f))
                    PlayPill(
                        icon = when {
                            // Scrubbing to the very end leaves the player paused there, not "ended".
                            ended || (!isPlaying && duration > 0 && position >= duration - 1000) -> Icons.Rounded.Replay
                            isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        position = position,
                        duration = duration,
                        onClick = ::togglePlay,
                    )
                    Spacer(Modifier.weight(1f))
                    CircleButton(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) "Unmute" else "Mute") {
                        muted = !muted
                        player.volume = if (muted) 0f else 1f
                    }
                }
                Filmstrip(frames, strip)
            }
        }
    }
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ControlBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PlayPill(icon: ImageVector, position: Long, duration: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(ControlBackground)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(clock(position), color = Color.White, fontSize = 15.sp)
        Text("  /  ", color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp)
        Text(clock(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

/** "00:13" style, with hours only when needed. */
private fun clock(millis: Long): String {
    val total = millis.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

/** Strips of the last few videos, so swiping back to one (or reopening it) shows its frames at once. */
private val stripCache = LruCache<Long, SnapshotStateList<ImageBitmap?>>(4)

/**
 * Frames for the filmstrip, about one per two seconds so longer videos get a longer strip.
 * Fills in as they decode; a load cut short (the user left) resumes with the missing ones.
 */
@Composable
private fun rememberFilmstripFrames(item: MediaItem, duration: Long): List<ImageBitmap?> {
    val context = LocalContext.current
    val count = ((duration / 2000).toInt()).coerceIn(8, 60)
    val frames = remember(item.id, count) {
        stripCache[item.id]?.takeIf { it.size == count }
            ?: mutableStateListOf<ImageBitmap?>().apply { repeat(count) { add(null) } }.also { stripCache.put(item.id, it) }
    }
    val heightPx = with(LocalDensity.current) { STRIP_HEIGHT.roundToPx() }
    LaunchedEffect(frames, duration > 0) {
        if (duration <= 0 || frames.none { it == null }) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, item.uri)
                for (i in 0 until count) {
                    if (!isActive) break
                    if (frames[i] != null) continue
                    val atUs = duration * 1000 * i / count
                    val frame = runCatching { frameAt(retriever, atUs, heightPx) }.getOrNull() ?: continue
                    frames[i] = frame.asImageBitmap()
                }
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }
    }
    return frames
}

/**
 * Row of frames under a fixed centre playhead. Half a screen of padding on each side lets
 * the first and last frame reach the playhead, so scroll 0..max maps to 0..duration.
 */
@Composable
private fun Filmstrip(frames: List<ImageBitmap?>, state: ScrollState) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(STRIP_HEIGHT + 8.dp), contentAlignment = Alignment.Center) {
        val half = maxWidth / 2
        Row(
            Modifier
                .fillMaxSize()
                .nestedScroll(KeepScroll)
                .horizontalScroll(state, flingBehavior = NoFling)
                .padding(vertical = 4.dp),
        ) {
            Spacer(Modifier.width(half))
            frames.forEach { frame ->
                Box(Modifier.width(FRAME_WIDTH).fillMaxHeight().background(Color.White.copy(alpha = 0.08f))) {
                    if (frame != null) Image(frame, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(half))
        }
        // Playhead.
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White))
    }
}

private fun frameAt(retriever: MediaMetadataRetriever, atUs: Long, heightPx: Int): Bitmap? {
    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(atUs, option, heightPx * 2, heightPx)
    val full = retriever.getFrameAtTime(atUs, option) ?: return null
    val width = full.width * heightPx / full.height.coerceAtLeast(1)
    return Bitmap.createScaledBitmap(full, width.coerceAtLeast(1), heightPx, true).also { if (it !== full) full.recycle() }
}
