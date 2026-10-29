package com.appslab.gallery.ui.components

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.data.MediaThumb
import com.appslab.gallery.ui.theme.CqMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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

private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 3_000L

/**
 * ExoPlayer for the current pager page only; other pages show a still frame,
 * so swiping through a mixed album never holds more than one decoder.
 *
 * The video draws into a TextureView (a SurfaceView ignores Compose transforms), so it
 * zooms and pans with the same gestures as photos. Controls are drawn in Compose and
 * shown together with the viewer's own bars: [controlsVisible] / [onControlsChange].
 * [bottomInset] is the height of the viewer's bottom bar, which the seek bar sits on.
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

    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            if (rememberPosition) seekTo(VideoPositions.get(context, item.id))
            playWhenReady = true
            prepare()
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var aspect by remember {
        mutableFloatStateOf(if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9f)
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.duration ?: 0L) }
    /** Set while the user drags the seek bar; the bar follows the finger, not the player. */
    var scrubbing by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (player.duration > 0) duration = player.duration
                // Finished: bring the controls back so the replay button is in reach.
                if (ended) onControlsChange(true)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
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

    // Progress only needs polling while someone can see it.
    LaunchedEffect(player, controlsVisible) {
        while (controlsVisible && isActive) {
            position = player.currentPosition
            delay(if (isPlaying) 100 else 500)
        }
    }
    // Like every video app: the controls fade out once playback runs on its own.
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && scrubbing == null) {
            delay(AUTO_HIDE_MS)
            onControlsChange(false)
        }
    }

    fun togglePlay() {
        when {
            ended -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekBy(delta: Long) {
        val target = (player.currentPosition + delta).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
        position = target
    }

    Box(modifier.fillMaxSize()) {
        ZoomBox(resetKey = item.id, isCurrentPage = true, onTap = { onControlsChange(!controlsVisible) }) {
            Box(Modifier.aspectRatio(aspect)) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also(player::setVideoTextureView) },
                    onRelease = { player.clearVideoTextureView(it) },
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
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Replay10, "Back 10 seconds", size = 56.dp) { seekBy(-SEEK_STEP_MS) }
                RoundButton(
                    when {
                        ended -> Icons.Rounded.Replay
                        isPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    if (isPlaying) "Pause" else "Play",
                    size = 76.dp,
                    onClick = ::togglePlay,
                )
                RoundButton(Icons.Rounded.Forward10, "Forward 10 seconds", size = 56.dp) { seekBy(SEEK_STEP_MS) }
            }
        }

        AnimatedVisibility(
            controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
        ) {
            val shown = scrubbing ?: position
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeLabel(formatDuration(shown))
                SeekBar(
                    fraction = if (duration > 0) shown.toFloat() / duration else 0f,
                    onScrub = { f -> scrubbing = (f * duration).toLong() },
                    onScrubEnd = {
                        scrubbing?.let { player.seekTo(it); position = it }
                        scrubbing = null
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TimeLabel(formatDuration(duration))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(text, color = Color.White, fontFamily = CqMono, fontSize = 13.sp, modifier = Modifier.width(44.dp))
}

/** Thin seek bar: tap or drag anywhere on its 32 dp tall touch area. The knob grows while dragging. */
@Composable
private fun SeekBar(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    onScrub((o.x / size.width).coerceIn(0f, 1f))
                    onScrubEnd()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; onScrub((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onScrubEnd() },
                    onDragCancel = { dragging = false; onScrubEnd() },
                ) { change, _ ->
                    change.consume()
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val f = fraction.coerceIn(0f, 1f)
        val track = if (dragging) 5.dp else 3.dp
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(f).height(track).clip(RoundedCornerShape(50)).background(Color.White))
        val knob = if (dragging) 16.dp else 12.dp
        val x = with(density) { (constraints.maxWidth * f).toDp() } - knob / 2
        Box(
            Modifier
                .offset(x = x)
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
