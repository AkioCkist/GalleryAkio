package com.example.galleryakio.ui.components

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import com.example.galleryakio.GalleryApplication
import com.example.galleryakio.data.MediaItem
import com.kyant.backdrop.Backdrop
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/** How often the strip and clock catch up: VLC only reports its time a few times a second. */
private const val VLC_TICK_MS = 100L
/** While scrubbing, a new seek goes out this often at most. */
private const val VLC_SCRUB_MS = 120L
/** VLC's own volume scale: 100 is normal, 200 its most. */
private const val VLC_NORMAL_VOLUME = 100
private const val VLC_MAX_VOLUME = 200

/** Stops and releases block for as long as the decoders take to wind down: never on the main thread. */
private val vlcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/** One instance per process, as VLC asks; made on first use, since it loads about 45 MB of code. */
private var vlcInstance: LibVLC? = null

private fun vlc(context: Context): LibVLC = synchronized(vlcScope) {
    vlcInstance ?: LibVLC(context.applicationContext, arrayListOf("--audio-time-stretch", "--no-sub-autodetect-file")).also { vlcInstance = it }
}

/** VLC behind the advanced panel. Calls go to the player once it exists; before that they are held in the settings. */
private class VlcEngine(context: Context, videoId: Long) : VideoEngine(context, videoId) {
    var player: MediaPlayer? = null
    var muted = false
        set(value) {
            field = value
            applyVolume()
        }
    private var boost = 0
    private var subtitleFile: ParcelFileDescriptor? = null
    private var restored = false

    override val styledSubtitles = false
    /** The most VLC goes to. */
    override val boosts = listOf(0, 3, 6)

    override val positionMs get() = player?.time?.coerceAtLeast(0) ?: 0L
    override val durationMs get() = player?.length?.coerceAtLeast(0) ?: 0L
    override val isPlaying get() = player?.isPlaying == true
    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun seekTo(ms: Long) { player?.setTime(ms) }

    private fun current(type: Int) = when (type) {
        C.TRACK_TYPE_VIDEO -> player?.videoTrack
        C.TRACK_TYPE_AUDIO -> player?.audioTrack
        else -> player?.spuTrack
    }

    /** VLC lists a "Disable" entry with id -1 first: the panel has its own Off. */
    override fun tracks(type: Int): List<EngineTrack> {
        val all = when (type) {
            C.TRACK_TYPE_VIDEO -> player?.videoTracks
            C.TRACK_TYPE_AUDIO -> player?.audioTracks
            else -> player?.spuTracks
        } ?: return emptyList()
        val selected = current(type)
        return all.filter { it.id >= 0 }.map { EngineTrack(it.id, it.name, "", it.id == selected) }
    }

    override fun applyTrack(type: Int, track: EngineTrack) {
        setTrack(type, track.id as Int)
        tracksVersion++
    }

    override fun applyTrackOff(type: Int) {
        setTrack(type, -1)
        tracksVersion++
    }

    private fun setTrack(type: Int, id: Int) {
        val player = player ?: return
        when (type) {
            C.TRACK_TYPE_VIDEO -> player.setVideoTrack(id)
            C.TRACK_TYPE_AUDIO -> player.setAudioTrack(id)
            else -> player.setSpuTrack(id)
        }
    }

    override fun applySpeed(speed: Float) {
        player?.setRate(speed)
    }

    override fun applyAudioDelay(ms: Long) {
        player?.setAudioDelay(ms * 1000)
    }

    override fun applySubtitleDelay(ms: Long) {
        player?.setSpuDelay(ms * 1000)
    }

    override fun startBoost(db: Int): () -> Unit {
        boost = db
        applyVolume()
        return {
            boost = 0
            applyVolume()
        }
    }

    private fun applyVolume() {
        val volume = if (muted) 0 else (VLC_NORMAL_VOLUME * 10f.pow(boost / 20f)).toInt().coerceAtMost(VLC_MAX_VOLUME)
        player?.setVolume(volume)
    }

    /** VLC reads no content:// address, but it reads a file descriptor kept open for it. */
    override fun loadSubtitleFile() {
        val player = player ?: return
        val uri = Uri.parse(subtitleUri ?: return)
        val file = runCatching { appContext.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (file == null) {
            forgetSubtitleFile()
            save()
            return
        }
        subtitleFile?.close()
        subtitleFile = file
        player.addSlave(IMedia.Slave.Type.Subtitle, "fd://${file.fd}", true)
    }

    /** Once playback runs and VLC knows the tracks. */
    fun onPlaying() {
        tracksVersion++
        if (restored) return
        restored = true
        applyVolume()
        if (audioDelayMs != 0L) applyAudioDelay(audioDelayMs)
        if (subtitleDelayMs != 0L) applySubtitleDelay(subtitleDelayMs)
        if (speed != 1f) applySpeed(speed)
        savedTracks[C.TRACK_TYPE_TEXT]?.let { if (it == subtitleId()) loadSubtitleFile() }
        savedTracks.forEach { (type, id) ->
            if (id == TRACK_OFF) setTrack(type, -1) else id.toIntOrNull()?.let { setTrack(type, it) }
        }
        tracksVersion++
    }

    fun onTracksChanged() {
        tracksVersion++
    }

    fun close() {
        subtitleFile?.close()
        subtitleFile = null
    }
}

/**
 * The same viewer page on VLC, for what ExoPlayer can't open. No frame-exact scrubbing or playing
 * on with the app away, which rely on ExoPlayer; the current page alone holds a player, so a page
 * next to it shows its still until it is swiped to.
 */
@Composable
internal fun VlcVideoPlayer(
    item: MediaItem,
    isCurrentPage: Boolean,
    isVisible: Boolean,
    leaving: Boolean,
    rememberPosition: Boolean,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    controlsVisible: Boolean,
    onControlsChange: (Boolean) -> Unit,
    controlsHost: VideoControlsHost,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val still = remember(item.uri, rememberPosition) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis(if (rememberPosition) VideoPositions.get(context, item.id) else 0L)
            .placeholderMemoryCacheKey(cachedThumbnailKey(context, item.id))
            .build()
    }
    if (!isCurrentPage) {
        AsyncImage(still, item.displayName, contentScale = ContentScale.Fit, modifier = modifier.fillMaxSize())
        return
    }

    val scope = rememberCoroutineScope()
    val engine = remember(item.id) { VlcEngine(context, item.id) }
    var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
    val pictureView = remember { arrayOfNulls<TextureView>(1) }
    var isPlaying by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var firstFrame by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var aspect by remember {
        mutableFloatStateOf(if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9f)
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.duration ?: 0L) }
    val strip = rememberScrollState()
    var scrubbing by remember { mutableStateOf(false) }
    val latestLoop by rememberUpdatedState(loop)

    // Took long enough to load the first time that a user may have left: the effect's end covers that too.
    DisposableEffect(item.id) {
        var created: MediaPlayer? = null
        var file: ParcelFileDescriptor? = null
        var disposed = false
        val job = scope.launch {
            val made = withContext(Dispatchers.IO) {
                val instance = vlc(context)
                val media = if (item.uri.scheme == "file") {
                    Media(instance, item.uri)
                } else {
                    val opened = context.contentResolver.openFileDescriptor(item.uri, "r") ?: return@withContext null
                    file = opened
                    Media(instance, opened.fileDescriptor)
                }
                media.setHWDecoderEnabled(true, false)
                val mediaPlayer = MediaPlayer(instance)
                mediaPlayer.media = media
                media.release()
                mediaPlayer
            }
            if (made == null) {
                failed = true
                return@launch
            }
            if (disposed) {
                vlcScope.launch { made.release() }
                return@launch
            }
            created = made
            made.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        isPlaying = true
                        ended = false
                        engine.onPlaying()
                        made.currentVideoTrack?.let { track ->
                            if (track.width > 0 && track.height > 0) {
                                val sar = if (track.sarDen > 0 && track.sarNum > 0) track.sarNum.toFloat() / track.sarDen else 1f
                                aspect = track.width * sar / track.height
                            }
                        }
                    }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                    MediaPlayer.Event.Vout -> if (event.voutCount > 0) firstFrame = true
                    MediaPlayer.Event.LengthChanged -> if (event.lengthChanged > 0) duration = event.lengthChanged
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        val again = latestLoop
                        // A stopped player plays again from the start, with its media still set.
                        vlcScope.launch {
                            made.stop()
                            if (again) made.play()
                        }
                        if (!again) {
                            ended = true
                            position = duration
                            onControlsChange(true)
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        failed = true
                        isPlaying = false
                    }
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> engine.onTracksChanged()
                }
            }
            engine.player = made
            player = made
            pictureView[0]?.let { attachVlcViews(made, it) }
            val start = if (rememberPosition) VideoPositions.get(context, item.id) else 0L
            if (start > 0) made.setTime(start)
            if (isVisible) made.play()
        }
        onDispose {
            disposed = true
            job.cancel()
            engine.player = null
            engine.close()
            player = null
            val leftover = created
            val openFile = file
            vlcScope.launch {
                if (leftover != null) {
                    leftover.setEventListener(null)
                    if (rememberPosition) VideoPositions.put(context, item.id, leftover.time, leftover.length)
                    leftover.stop()
                    leftover.vlcVout.detachViews()
                    leftover.release()
                }
                openFile?.close()
            }
        }
    }

    // ExoPlayer does this for itself. Yields to calls and other players, and stops when headphones come out.
    DisposableEffect(player) {
        val current = player ?: return@DisposableEffect onDispose { }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val onChange = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) current.pause()
        }
        val request = if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(onChange)
                .build()
                .also { audio.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(onChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            null
        }
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                current.pause()
            }
        }
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        onDispose {
            context.unregisterReceiver(noisy)
            if (Build.VERSION.SDK_INT >= 26 && request != null) audio.abandonAudioFocusRequest(request)
            else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(onChange)
            }
        }
    }

    val activity = context as? ComponentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // A picture-in-picture window reaches ON_STOP only when it is closed.
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, leaving, isVisible) {
        val current = player ?: return@LaunchedEffect
        if (leaving || !isVisible) current.pause()
    }
    LaunchedEffect(muted, player) { engine.muted = muted }
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) scrubbing = false
    }
    LaunchedEffect(player, controlsVisible, scrubbing) {
        val current = player ?: return@LaunchedEffect
        if (!controlsVisible || scrubbing) return@LaunchedEffect
        while (isActive) {
            val now = current.time
            if (now >= 0) {
                if (now / 1000 != position / 1000) position = now
                if (duration > 0 && strip.maxValue > 0) {
                    val target = (strip.maxValue * now / duration).toInt()
                    if (target != strip.value) strip.scrollTo(target)
                }
            }
            delay(VLC_TICK_MS)
        }
    }
    LaunchedEffect(player, scrubbing) {
        val current = player ?: return@LaunchedEffect
        if (!scrubbing) return@LaunchedEffect
        var sent = -1L
        try {
            while (isActive) {
                if (strip.maxValue > 0 && duration > 0) {
                    val target = duration * strip.value / strip.maxValue
                    if (target / 1000 != position / 1000) position = target
                    if (target != sent) {
                        // Fast: to the nearest key frame, as an exact seek takes VLC far longer.
                        current.setTime(target, true)
                        sent = target
                    }
                }
                delay(VLC_SCRUB_MS)
            }
        } finally {
            if (strip.maxValue > 0 && duration > 0) current.setTime(duration * strip.value / strip.maxValue)
        }
    }
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(AUTO_HIDE_MS)
            onControlsChange(false)
        }
    }
    LaunchedEffect(failed) {
        if (failed) Toast.makeText(context, "Can't play this video", Toast.LENGTH_SHORT).show()
    }

    val frames = rememberFilmstripFrames(item, duration, load = firstFrame)

    fun togglePlay() {
        val current = player ?: return
        when {
            ended -> {
                ended = false
                current.play()
            }
            current.isPlaying -> current.pause()
            else -> current.play()
        }
    }

    fun captureFrame() {
        val bitmap = pictureView[0]?.bitmap ?: return
        val time = player?.time ?: 0L
        val name = item.displayName.substringBeforeLast('.') + "_" + formatDuration(time).replace(':', '-') + ".jpg"
        scope.launch {
            val repo = (context.applicationContext as GalleryApplication).mediaRepository
            val saved = repo.saveImage(bitmap, name)
            Toast.makeText(context, if (saved) "Frame saved to Pictures/Gallery" else "Couldn't save frame", Toast.LENGTH_SHORT).show()
        }
    }

    val canPip = remember {
        Build.VERSION.SDK_INT >= 26 && activity != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || activity == null) return
        val ratio = aspect.coerceIn(PIP_MIN_ASPECT, PIP_MAX_ASPECT)
        runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).build()) }
    }

    val visible by rememberUpdatedState(controlsVisible)
    val looping by rememberUpdatedState(loop)
    val setLoop by rememberUpdatedState(onLoopChange)
    val controls: @Composable (Backdrop) -> Unit = remember(engine) {
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
                            ended -> Icons.Rounded.Replay
                            isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        position = position,
                        duration = duration,
                        onClick = ::togglePlay,
                    )
                    Spacer(Modifier.weight(1f))
                    if (canPip) {
                        CircleButton(backdrop, Icons.Rounded.PictureInPictureAlt, "Picture in picture", visible, onClick = ::enterPip)
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleButton(
                        backdrop,
                        if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        if (muted) "Unmute" else "Mute",
                        visible,
                    ) { muted = !muted }
                }
                Filmstrip(frames, strip, enabled = visible, onScrub = { scrubbing = it })
            }
        }
    }
    DisposableEffect(controlsHost, controls) {
        controlsHost.content = controls
        onDispose { if (controlsHost.content === controls) controlsHost.content = null }
    }
    val advancedPanel: @Composable (() -> Unit) -> Unit = remember(engine) {
        { onDismiss -> VideoAdvancedPanel(engine, onDismiss) }
    }
    DisposableEffect(controlsHost, advancedPanel) {
        controlsHost.advancedPanel = advancedPanel
        onDispose { if (controlsHost.advancedPanel === advancedPanel) controlsHost.advancedPanel = null }
    }
    VideoAdvancedEffects(engine)

    ZoomBox(
        resetKey = item.id,
        isCurrentPage = true,
        onTap = { onControlsChange(!controlsVisible) },
        modifier = modifier,
        onDoubleTap = {
            if (player?.isPlaying == true) onControlsChange(true)
            togglePlay()
        },
    ) {
        Box(Modifier.videoFrame(engine.frameMode, aspect)) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        pictureView[0] = view
                        player?.let { attachVlcViews(it, view) }
                    }
                },
                onRelease = { pictureView[0] = null },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        // The picture fills this box: its shape is the box's to decide, not VLC's.
                        if (size.width > 0 && size.height > 0) {
                            player?.vlcVout?.setWindowSize(size.width, size.height)
                            player?.setAspectRatio("${size.width}:${size.height}")
                            player?.setScale(0f)
                        }
                    },
            )
            if (!firstFrame) {
                AsyncImage(still, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun attachVlcViews(player: MediaPlayer, view: TextureView) {
    val vout = player.vlcVout
    if (vout.areViewsAttached()) return
    vout.setVideoView(view)
    vout.attachViews()
    if (view.width > 0 && view.height > 0) {
        vout.setWindowSize(view.width, view.height)
        player.setAspectRatio("${view.width}:${view.height}")
        player.setScale(0f)
    }
}
