package com.example.galleryakio.ui.components

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Rational
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.runtime.State
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import com.example.galleryakio.VideoPlaybackService
import com.example.galleryakio.data.VideoLeaveAction
import com.kyant.backdrop.Backdrop
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.galleryakio.GalleryApplication
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.MediaThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

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
/** Android refuses a PiP window outside these proportions. */
private const val PIP_MIN_ASPECT = 0.42f
private const val PIP_MAX_ASPECT = 2.39f
/** A scrub seek counts as shown once a picture this close to its target is rendered. */
private const val SEEK_ARRIVED_MS = 100L
/**
 * ...or after this long, in case it never reports in. Cutting a seek short restarts the decode
 * from its key frame, so backwards through a long group of pictures nothing ever showed.
 */
private const val SEEK_PATIENCE_MS = 1_000L
/** One scrub preview per this much video... */
private const val PREVIEW_STEP_MS = 400L
/** A scrub shows the nearest copy up to this many steps away (1.2 s). */
private const val PREVIEW_REACH = 3
/** ...this many pixels on its long side (a blurry stand-in, it only shows mid-scrub)... */
private const val PREVIEW_LONG_SIDE = 320
/** ...and at most this much memory per video (some 100 previews, 40 s of video). */
private const val PREVIEW_CACHE_BYTES = 24 shl 20
/** Mid-scrub, a preview stands in while the picture on screen is further than this from the target. */
private const val PREVIEW_GAP_MS = 150L
/** After a scrub, how long a preview may wait for the exact picture to replace it. */
private const val PREVIEW_HOLD_MS = 1_500L
/** How often the glass catches up with a moving picture: each time costs one slower frame. */
private const val GLASS_REFRESH_MS = 1_000L
private val FRAME_WIDTH = 56.dp
private val STRIP_HEIGHT = 52.dp
private val CONTROL_SIZE = 36.dp

/** The strip moves this much faster than the finger, so a short drag covers more of the video. */
private const val SCRUB_SPEED = 1.34f

/**
 * Where the current video's controls go: the viewer draws them in its own bottom overlay,
 * outside the pager it records for its glass. Drawn inside the pager they were glass within
 * the recorded backdrop, so every viewer glass control re-drew them (and a second recording
 * of the video) on each video frame: the HUD ran at ~20 fps.
 */
class VideoControlsHost {
    var content by mutableStateOf<(@Composable (Backdrop) -> Unit)?>(null)

    /**
     * While set, the viewer's glass bends this copy of the picture instead of the live video.
     * With the video in its backdrop every glass control was blurred again on each video frame
     * (20 ms of GPU, dropping video frames); over a still one their blur stays cached.
     */
    var backdropStill by mutableStateOf<ImageBitmap?>(null)
}

/**
 * Small copies of pictures already shown, one per [PREVIEW_STEP_MS] of video. Scrubbing back
 * (or forward faster than the decoder keeps up) decodes again from the group of pictures' key
 * frame, which in phone and downloaded videos is 3-9 s back: over a second of decoding for one
 * picture. Meanwhile the nearest copy stands in, so the picture follows the finger.
 * Claimed on the playback thread, filled on the main thread.
 */
private class ScrubPreviews {
    private val cache = object : LruCache<Long, ImageBitmap>(PREVIEW_CACHE_BYTES) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }
    private val pending = HashSet<Long>()

    /** True (once) when [bucket] has no preview yet and the caller should take it. */
    @Synchronized
    fun claim(bucket: Long): Boolean = cache[bucket] == null && pending.add(bucket)

    @Synchronized
    fun put(bucket: Long, frame: ImageBitmap?) {
        pending.remove(bucket)
        if (frame != null) cache.put(bucket, frame)
    }

    /** The copy closest to [ms], up to [PREVIEW_REACH] steps either way. */
    fun near(ms: Long): ImageBitmap? {
        val bucket = ms / PREVIEW_STEP_MS
        for (d in 0..PREVIEW_REACH) {
            cache[bucket - d]?.let { return it }
            if (d > 0) cache[bucket + d]?.let { return it }
        }
        return null
    }

    fun clear() = cache.evictAll()
}

/**
 * ExoPlayer for the current pager page only; other pages show a still frame,
 * so swiping through a mixed album never holds more than one decoder.
 *
 * The video draws into a TextureView (a SurfaceView ignores Compose transforms), so it
 * zooms and pans with the same gestures as photos. Controls follow Samsung Gallery:
 * frame capture, a play/time pill and mute above a filmstrip that scrubs under a fixed
 * playhead. They are handed to [controlsHost] and show together with the viewer's own
 * bars ([controlsVisible] / [onControlsChange]).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    item: MediaItem,
    isCurrentPage: Boolean,
    /** The viewer is closing or a swipe is past halfway to another page. */
    leaving: Boolean,
    rememberPosition: Boolean,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    leaveAction: VideoLeaveAction,
    controlsVisible: Boolean,
    onControlsChange: (Boolean) -> Unit,
    controlsHost: VideoControlsHost,
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
        ExoPlayer.Builder(context)
            // Playing on with the app away, it has to yield to calls and other players by itself,
            // stop when headphones come out, and keep the CPU awake with the screen off.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
            setMediaItem(
                ExoMediaItem.Builder()
                    .setUri(item.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.displayName).build())
                    .build(),
            )
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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
    var scrubbing by remember { mutableStateOf(false) }
    // Presentation time of the last picture handed to the screen, from the playback thread.
    val shownUs = remember(player) { AtomicLong(Long.MIN_VALUE) }
    val previews = remember(player) { ScrubPreviews() }
    // Drawn over the video mid-scrub while the decoder catches up.
    var preview by remember(player) { mutableStateOf<ImageBitmap?>(null) }

    // A copy of the picture, taken just after it reaches the screen: a small GPU read-back,
    // about two or three times a second of video.
    fun capturePreview(bucket: Long) {
        val view = textureView[0]
        val frame = if (view != null && view.isAvailable) {
            val w = if (aspect >= 1f) PREVIEW_LONG_SIDE else (PREVIEW_LONG_SIDE * aspect).toInt()
            val h = if (aspect >= 1f) (PREVIEW_LONG_SIDE / aspect).toInt() else PREVIEW_LONG_SIDE
            // One pixel bigger all round, then trimmed: scaling down samples the decoder's
            // padding at the edges, which showed as a green line along the preview.
            view.getBitmap(w.coerceAtLeast(1) + 2, h.coerceAtLeast(1) + 2)
                ?.let { Bitmap.createBitmap(it, 1, 1, it.width - 2, it.height - 2) }
                ?.asImageBitmap()
        } else null
        previews.put(bucket, frame)
    }

    DisposableEffect(player) {
        val main = Handler(Looper.getMainLooper())
        val frames = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
            shownUs.set(presentationTimeUs)
            val bucket = presentationTimeUs / 1000 / PREVIEW_STEP_MS
            if (presentationTimeUs >= 0 && previews.claim(bucket)) {
                val untilShown = ((releaseTimeNs - System.nanoTime()) / 1_000_000).coerceIn(0, 100)
                main.postDelayed({ capturePreview(bucket) }, untilShown + 8)
            }
        }
        player.setVideoFrameMetadataListener(frames)
        onDispose {
            player.clearVideoFrameMetadataListener(frames)
            main.removeCallbacksAndMessages(null)
            previews.clear()
        }
    }

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

    LaunchedEffect(player, loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Built on first need, since it isn't free and most videos are just watched in the viewer.
    // Declared after the player's effect, so it goes first: a session must not outlive its player.
    val session = remember(player) { arrayOfNulls<MediaSession>(1) }
    fun ensureSession() {
        if (session[0] != null) return
        val back = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        session[0] = MediaSession.Builder(context, player)
            .setId("video-${item.id}")
            .setSessionActivity(PendingIntent.getActivity(context, 0, back, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
    DisposableEffect(player) {
        onDispose {
            session[0]?.let { current ->
                if (VideoPlaybackService.session === current) {
                    VideoPlaybackService.stop(context)
                    VideoPlaybackService.session = null
                }
                current.release()
            }
        }
    }

    val activity = context as? ComponentActivity
    val canPip = remember {
        Build.VERSION.SDK_INT >= 26 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    // Set by the PiP button, whose own ON_PAUSE isn't the user leaving.
    val enteringPip = remember(player) { booleanArrayOf(false) }

    @RequiresApi(26)
    fun pipParams(auto: Boolean): PictureInPictureParams {
        val ratio = aspect.coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setAutoEnterEnabled(auto)
                    setSeamlessResizeEnabled(false)
                }
            }
            .build()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || activity == null) return
        // The system's PiP window takes its play / pause buttons from the session.
        ensureSession()
        enteringPip[0] = true
        if (!runCatching { activity.enterPictureInPictureMode(pipParams(auto = false)) }.getOrDefault(false)) enteringPip[0] = false
    }

    // Going home flies a playing video into PiP: Android 12 does it from these params, older
    // versions need asking as the user leaves.
    val autoPip = canPip && activity != null && leaveAction == VideoLeaveAction.PICTURE_IN_PICTURE && isPlaying
    DisposableEffect(autoPip, aspect) {
        if (autoPip) ensureSession()
        if (Build.VERSION.SDK_INT >= 31 && canPip && activity != null) activity.setPictureInPictureParams(pipParams(autoPip))
        val onLeave = Runnable { if (autoPip && Build.VERSION.SDK_INT < 31) enterPip() }
        activity?.addOnUserLeaveHintListener(onLeave)
        onDispose {
            activity?.removeOnUserLeaveHintListener(onLeave)
            if (Build.VERSION.SDK_INT >= 31 && canPip && activity != null) activity.setPictureInPictureParams(pipParams(false))
        }
    }

    // Leaving the app. The service has to start while the app is still in front, hence ON_PAUSE;
    // the rest waits for ON_STOP, which a PiP window doesn't reach until it is closed.
    val leave by rememberUpdatedState(leaveAction)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        var inBackground = false
        fun videoTrack(on: Boolean) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
                .build()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE ->
                    if (leave == VideoLeaveAction.KEEP_PLAYING && player.isPlaying && !enteringPip[0] && activity?.isInPictureInPictureMode != true) {
                        ensureSession()
                        VideoPlaybackService.session = session[0]
                        VideoPlaybackService.start(context)
                        inBackground = true
                    }
                // Sound only while nothing can show the picture: no decoding it for nobody.
                Lifecycle.Event.ON_STOP -> if (inBackground) videoTrack(false) else player.pause()
                Lifecycle.Event.ON_START -> if (inBackground) videoTrack(true)
                Lifecycle.Event.ON_RESUME -> {
                    enteringPip[0] = false
                    if (inBackground) {
                        inBackground = false
                        VideoPlaybackService.stop(context)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Leaving: drop the decoder now, on the playback thread, during the motion. Waiting for its
    // end made taking the surface away and release() block the main thread together for some
    // 70 ms, a visible hitch just as the grid or the next video came in.
    // A back gesture or a swipe can still be cancelled, hence the way back.
    LaunchedEffect(player, leaving) {
        if (leaving) player.stop()
        else if (player.playbackState == Player.STATE_IDLE) player.prepare()
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
    // Dragging the strip seeks to whatever frame sits under the playhead, exactly, on every
    // move. Scrubbing mode makes that keep up: it drops seeks overtaken by newer ones, decodes
    // forward within a group of pictures instead of flushing back to its key frame, and holds
    // playback (and audio) until it ends. Key-frame-only seeks with a flush each, as before,
    // showed a new picture only every second or two of video.
    //
    // A new seek waits until the last one's picture is out (or SEEK_PATIENCE_MS passes): sent
    // every frame, each seek cut the previous decode short and pictures came 3-5 times a second.
    // While the picture on screen lags the target, the nearest [ScrubPreviews] copy covers it.
    LaunchedEffect(player, scrubbing) {
        if (!scrubbing) {
            // Done: the preview stays until the exact picture is out to replace it.
            if (preview != null) {
                withTimeoutOrNull(PREVIEW_HOLD_MS) {
                    while (abs(shownUs.get() / 1000 - player.currentPosition) > SEEK_ARRIVED_MS) withFrameNanos {}
                }
                preview = null
            }
            return@LaunchedEffect
        }
        // Paused (or finished) stays paused; playing carries on from the new spot afterwards.
        if (!player.isPlaying) player.pause()
        player.setScrubbingModeEnabled(true)
        var sent = -1L
        var sentAt = 0L
        try {
            while (isActive) {
                withFrameNanos { now ->
                    if (strip.maxValue <= 0 || duration <= 0) return@withFrameNanos
                    val target = duration * strip.value / strip.maxValue
                    if (target / 1000 != position / 1000) position = target
                    val shownMs = shownUs.get() / 1000
                    // An exact seek shows the frame at or just before the target.
                    val arrived = abs(shownMs - sent) <= SEEK_ARRIVED_MS
                    val waited = (now - sentAt) / 1_000_000 >= SEEK_PATIENCE_MS
                    if (target != sent && (sent < 0 || arrived || waited)) {
                        player.seekTo(target)
                        sent = target
                        sentAt = now
                    }
                    // Same instance most frames, so this only recomposes when the copy changes.
                    preview = if (abs(shownMs - target) > PREVIEW_GAP_MS) previews.near(target) else null
                }
            }
        } finally {
            // Wherever the strip came to rest, even if the last move was still held back.
            val target = if (strip.maxValue > 0 && duration > 0) duration * strip.value / strip.maxValue else sent
            if (target >= 0 && target != sent) player.seekTo(target)
            if (target >= 0) position = target
            player.setScrubbingModeEnabled(false)
        }
    }
    // While the picture moves under the controls. Left in place as they fade out, when the
    // backdrop stops being recorded anyway; a stopped picture costs nothing live, and looks sharper.
    LaunchedEffect(player, controlsVisible, isPlaying, scrubbing) {
        if (!isPlaying && !scrubbing) {
            controlsHost.backdropStill = null
            return@LaunchedEffect
        }
        if (!controlsVisible) return@LaunchedEffect
        while (isActive) {
            previews.near(player.currentPosition)?.let { controlsHost.backdropStill = it }
            delay(GLASS_REFRESH_MS)
        }
    }
    DisposableEffect(player, controlsHost) {
        onDispose { controlsHost.backdropStill = null }
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

    // Remembered once and read through state, so handing it over doesn't recompose the
    // viewer's overlay on every recomposition of the player.
    val visible by rememberUpdatedState(controlsVisible)
    val looping by rememberUpdatedState(loop)
    val setLoop by rememberUpdatedState(onLoopChange)
    val controls: @Composable (Backdrop) -> Unit = remember(player) {
        { backdrop ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleButton(backdrop, Icons.Outlined.PhotoCamera, "Capture frame", visible, onClick = ::captureFrame)
                    Spacer(Modifier.width(8.dp))
                    CircleButton(
                        backdrop,
                        if (looping) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        if (looping) "Stop looping" else "Loop video",
                        visible,
                        tint = if (looping) Color.White else Color.White.copy(alpha = 0.55f),
                    ) { setLoop(!looping) }
                    Spacer(Modifier.weight(1f))
                    PlayPill(
                        backdrop = backdrop,
                        enabled = visible,
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
                    if (canPip && activity != null) {
                        CircleButton(backdrop, Icons.Rounded.PictureInPictureAlt, "Picture in picture", visible, onClick = ::enterPip)
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleButton(
                        backdrop,
                        if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        if (muted) "Unmute" else "Mute",
                        visible,
                    ) {
                        muted = !muted
                        player.volume = if (muted) 0f else 1f
                    }
                }
                Filmstrip(frames, strip, enabled = visible, onScrub = { scrubbing = it })
            }
        }
    }
    DisposableEffect(controlsHost, controls) {
        controlsHost.content = controls
        onDispose { if (controlsHost.content === controls) controlsHost.content = null }
    }

    ZoomBox(
        resetKey = item.id,
        isCurrentPage = true,
        onTap = { onControlsChange(!controlsVisible) },
        modifier = modifier,
        onDoubleTap = {
            // Pausing brings the controls up, the only sign of it while they were hidden.
            if (player.isPlaying) onControlsChange(true)
            togglePlay()
        },
    ) {
        Box(Modifier.aspectRatio(aspect)) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { textureView[0] = it; player.setVideoTextureView(it) } },
                onRelease = { player.clearVideoTextureView(it); textureView[0] = null },
                modifier = Modifier.fillMaxSize(),
            )
            preview?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            // The still stays on top until the first frame is drawn, so opening a video never flashes black.
            if (!firstFrame) {
                AsyncImage(still, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CircleButton(
    backdrop: Backdrop,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(CONTROL_SIZE)
            .liquidGlass(backdrop, darkMode = true, lensHeight = 6.dp, lensAmount = 12.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Whether the activity is in a picture-in-picture window, where the viewer shows only the video. */
@Composable
fun rememberInPictureInPicture(): State<Boolean> {
    val activity = LocalContext.current as? ComponentActivity
    val inPip = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

@Composable
private fun PlayPill(backdrop: Backdrop, enabled: Boolean, icon: ImageVector, position: Long, duration: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .height(CONTROL_SIZE)
            .liquidGlass(backdrop, darkMode = true, lensHeight = 6.dp, lensAmount = 12.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(clock(position), color = Color.White, fontSize = 14.sp)
        Text(" / ", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        Text(clock(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
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
 *
 * Dragged by hand rather than through horizontalScroll: the strip runs [SCRUB_SPEED] times
 * the finger and glides on after a flick. [onScrub] is true from touch until the glide stops.
 * Never dispatching nested scroll, reaching either end can't start swiping the pager either.
 */
@Composable
private fun Filmstrip(frames: List<ImageBitmap?>, state: ScrollState, enabled: Boolean, onScrub: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val glide = remember { arrayOfNulls<Job>(1) }
    val drag = rememberDraggableState { state.dispatchRawDelta(-it * SCRUB_SPEED) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(STRIP_HEIGHT + 8.dp), contentAlignment = Alignment.Center) {
        val half = maxWidth / 2
        Row(
            Modifier
                .fillMaxSize()
                .draggable(
                    drag,
                    Orientation.Horizontal,
                    enabled = enabled,
                    onDragStarted = {
                        glide[0]?.cancel()
                        onScrub(true)
                    },
                    onDragStopped = { velocity ->
                        glide[0] = scope.launch {
                            state.scroll { with(fling) { performFling(-velocity * SCRUB_SPEED) } }
                            // Not in a finally: a glide cut short by the next touch keeps scrubbing on.
                            onScrub(false)
                        }
                    },
                )
                .horizontalScroll(state, enabled = false)
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
