package com.example.galleryakio.ui.components

import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

/*
 * What a film needs beyond a phone clip, after VLC for Android's track dialog and player options:
 * track choice, sync, external subtitles, picture shape, speed, A-B repeat, sleep timer. All of
 * it stays idle until used: the panel is composed only while open, the subtitle view only while
 * a subtitle track is on, and the effects below return at once with nothing set.
 */

private const val PREFS = "video_advanced"
private const val KEY_SUBTITLE_SP = "subtitle_sp"
private const val KEY_SUBTITLE_BACKGROUND = "subtitle_background"
private const val KEY_TAP_SEEK_SECONDS = "tap_seek_seconds"
internal const val TRACK_OFF = "off"
private const val AUDIO_DELAY_STEP_MS = 50L
private const val SUBTITLE_DELAY_STEP_MS = 100L
private const val MAX_DELAY_MS = 60_000L
private const val DEFAULT_SUBTITLE_SP = 18
private val SUBTITLE_SIZES = listOf(14, 18, 22, 28)
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val BOOSTS_DB = listOf(0, 3, 6, 10)
private val SLEEP_MINUTES = listOf(0, 15, 30, 60, 90)
private const val DEFAULT_TAP_SEEK_SECONDS = 10
private const val MAX_TAP_SEEK_SECONDS = 600
private val TAP_SEEK_PRESETS = listOf(1, 2, 5, 10)
/**
 * How long a run of tap seeks lasts after its last seek, fade included. A single tap is only
 * reported once the wait for a second one is over (300 ms), which leaves about 700 ms to tap on.
 */
private const val TAP_SEEK_RUN_MS = 1_000L
private const val TAP_SEEK_FADE_MS = 150

/** How the picture fills the screen; [aspect] overrides the video's own proportions. */
enum class VideoFrameMode(val label: String, val aspect: Float? = null) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_21_9("21:9", 21f / 9f),
}

/** Read on the playback thread for every audio position, so it's only a field. */
class AudioSyncOffset {
    @Volatile var us = 0L
}

/**
 * Audio later (or earlier) than the picture. ExoPlayer times video frames by this position, so
 * reporting it ahead shows the frames sooner, which is the sound arriving later.
 */
@OptIn(UnstableApi::class)
private class OffsetAudioSink(sink: AudioSink, private val offset: AudioSyncOffset) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else position + offset.us
    }
}

/**
 * The phone's decoders first; FFmpeg only for what they can't play (DTS, TrueHD, MPEG-2...),
 * like VLC. A clip the phone can play never reaches the FFmpeg code.
 */
@OptIn(UnstableApi::class)
fun movieRenderers(context: Context, offset: AudioSyncOffset): RenderersFactory =
    object : NextRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink? =
            super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)?.let { OffsetAudioSink(it, offset) }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

/** One row of the panel's track lists; [id] means something only to the engine that made it. */
class EngineTrack(val id: Any, val title: String, val details: String, val selected: Boolean, val supported: Boolean = true)

/**
 * What the panel and its effects need from whichever engine plays the video, and the settings
 * they share. Tracks, sync and an added subtitle file are kept per video.
 */
abstract class VideoEngine(context: Context, private val videoId: Long) {
    protected val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Chosen tracks by type: the engine's own id for them, or [TRACK_OFF]. */
    protected val savedTracks = HashMap<Int, String>()
    protected var subtitleUri: String? = null
        private set
    protected var subtitleMime: String? = null
        private set

    /** Bumped when the track lists change; only the panel reads it. */
    var tracksVersion by mutableIntStateOf(0)
        protected set
    var audioDelayMs by mutableLongStateOf(0L)
        private set
    var subtitleDelayMs by mutableLongStateOf(0L)
        private set
    var frameMode by mutableStateOf(VideoFrameMode.FIT)
    var speed by mutableFloatStateOf(1f)
        private set
    var boostDb by mutableIntStateOf(0)
    var loopFromMs by mutableStateOf<Long?>(null)
    var loopToMs by mutableStateOf<Long?>(null)
    /** On the [SystemClock.elapsedRealtime] clock. */
    var sleepAt by mutableStateOf<Long?>(null)
    var subtitleSp by mutableIntStateOf(prefs.getInt(KEY_SUBTITLE_SP, DEFAULT_SUBTITLE_SP))
        private set
    var subtitleBackground by mutableStateOf(prefs.getBoolean(KEY_SUBTITLE_BACKGROUND, false))
        private set
    var tapSeekSeconds by mutableIntStateOf(prefs.getInt(KEY_TAP_SEEK_SECONDS, DEFAULT_TAP_SEEK_SECONDS))
        private set

    /** False when the engine draws subtitles itself, so size and background aren't ours to set. */
    open val styledSubtitles = true
    open val boosts = BOOSTS_DB

    init {
        prefs.getString(videoId.toString(), null)?.let { saved ->
            runCatching {
                val json = JSONObject(saved)
                for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
                    json.optString("track$type").takeIf { it.isNotEmpty() }?.let { savedTracks[type] = it }
                }
                audioDelayMs = json.optLong("audioDelay")
                subtitleDelayMs = json.optLong("subtitleDelay")
                subtitleUri = json.optString("subtitleUri").takeIf { it.isNotEmpty() }
                subtitleMime = json.optString("subtitleMime").takeIf { it.isNotEmpty() }
            }
        }
        // Without the grant the file can't be read, and that would fail the whole video.
        subtitleUri?.let { uri ->
            if (appContext.contentResolver.persistedUriPermissions.none { it.uri.toString() == uri && it.isReadPermission }) forgetSubtitleFile()
        }
    }

    abstract val positionMs: Long
    abstract val durationMs: Long
    abstract val isPlaying: Boolean
    abstract fun play()
    abstract fun pause()
    abstract fun seekTo(ms: Long)
    abstract fun tracks(type: Int): List<EngineTrack>
    protected abstract fun applyTrack(type: Int, track: EngineTrack)
    protected abstract fun applyTrackOff(type: Int)
    protected abstract fun applySpeed(speed: Float)
    protected abstract fun applyAudioDelay(ms: Long)
    protected abstract fun applySubtitleDelay(ms: Long)
    /** Starts the boost; the result ends it. */
    abstract fun startBoost(db: Int): () -> Unit
    protected abstract fun loadSubtitleFile()

    protected fun save() {
        val json = JSONObject()
        savedTracks.forEach { (type, id) -> json.put("track$type", id) }
        if (audioDelayMs != 0L) json.put("audioDelay", audioDelayMs)
        if (subtitleDelayMs != 0L) json.put("subtitleDelay", subtitleDelayMs)
        subtitleUri?.let { json.put("subtitleUri", it) }
        subtitleMime?.let { json.put("subtitleMime", it) }
        val editor = prefs.edit()
        if (json.length() == 0) editor.remove(videoId.toString()) else editor.putString(videoId.toString(), json.toString())
        editor.apply()
    }

    protected fun forgetSubtitleFile() {
        if (savedTracks[C.TRACK_TYPE_TEXT] == subtitleId()) savedTracks.remove(C.TRACK_TYPE_TEXT)
        subtitleUri = null
        subtitleMime = null
    }

    protected fun subtitleId() = "external:$subtitleUri"

    fun selectTrack(type: Int, track: EngineTrack) {
        applyTrack(type, track)
        savedTracks[type] = track.id.toString()
        save()
    }

    fun trackOff(type: Int) {
        applyTrackOff(type)
        savedTracks[type] = TRACK_OFF
        save()
    }

    fun changeSpeed(value: Float) {
        speed = value
        applySpeed(value)
    }

    fun changeAudioDelay(ms: Long) {
        audioDelayMs = ms.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        applyAudioDelay(audioDelayMs)
        save()
    }

    fun changeSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        applySubtitleDelay(subtitleDelayMs)
        save()
    }

    fun changeSubtitleSp(sp: Int) {
        subtitleSp = sp
        prefs.edit().putInt(KEY_SUBTITLE_SP, sp).apply()
    }

    fun changeSubtitleBackground(on: Boolean) {
        subtitleBackground = on
        prefs.edit().putBoolean(KEY_SUBTITLE_BACKGROUND, on).apply()
    }

    fun changeTapSeekSeconds(seconds: Int) {
        tapSeekSeconds = seconds
        prefs.edit().putInt(KEY_TAP_SEEK_SECONDS, seconds).apply()
    }

    fun addSubtitleFile(uri: Uri, mime: String) {
        runCatching { appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        subtitleUri = uri.toString()
        subtitleMime = mime
        savedTracks[C.TRACK_TYPE_TEXT] = subtitleId()
        save()
        loadSubtitleFile()
    }
}

/** ExoPlayer, which plays nearly everything a phone records or downloads. */
@OptIn(UnstableApi::class)
class ExoVideoEngine(context: Context, videoId: Long) : VideoEngine(context, videoId) {
    private lateinit var player: ExoPlayer
    val audioOffset = AudioSyncOffset().also { it.us = audioDelayMs * 1000 }
    /** A subtitle track is on: only then does the viewer hold a subtitle view. */
    var subtitlesShown by mutableStateOf(false)
        private set

    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration.coerceAtLeast(0)
    override val isPlaying get() = player.isPlaying
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(ms: Long) = player.seekTo(ms)

    private fun withSubtitleFile(item: ExoMediaItem.Builder): ExoMediaItem.Builder {
        val uri = subtitleUri
        val mime = subtitleMime
        if (uri == null || mime == null) return item.setSubtitleConfigurations(emptyList())
        return item.setSubtitleConfigurations(
            listOf(ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(uri)).setId(subtitleId()).setMimeType(mime).build()),
        )
    }

    /** The video's item for a new player, with its added subtitle file if it has one. */
    fun mediaItem(item: ExoMediaItem.Builder): ExoMediaItem = withSubtitleFile(item).build()

    fun attach(player: ExoPlayer) {
        this.player = player
        if (subtitleDelayMs != 0L) player.subtitleDelayMilliseconds = subtitleDelayMs
    }

    fun onTracksChanged(current: Tracks) {
        tracksVersion++
        subtitlesShown = current.isTypeSelected(C.TRACK_TYPE_TEXT)
        if (current.isEmpty) return
        savedTracks.forEach { (type, id) -> restore(current, type, id) }
    }

    private fun restore(current: Tracks, type: Int, id: String) {
        val params = player.trackSelectionParameters
        // Switched off for now, like the picture while playing in the background.
        if (type in params.disabledTrackTypes) return
        if (id == TRACK_OFF) {
            player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(type, true).build()
            return
        }
        for (group in current.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).id != id) continue
                if (!group.isTrackSelected(i) && group.isTrackSupported(i)) override(group, i)
                return
            }
        }
    }

    private fun override(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    override fun tracks(type: Int): List<EngineTrack> {
        val rows = ArrayList<EngineTrack>()
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                rows += EngineTrack(format.id ?: "${rows.size}", trackTitle(format, rows.size + 1), trackDetails(format), group.isTrackSelected(i), group.isTrackSupported(i))
            }
        }
        return rows
    }

    override fun applyTrack(type: Int, track: EngineTrack) {
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).id == track.id) {
                    override(group, i)
                    return
                }
            }
        }
    }

    override fun applyTrackOff(type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
    }

    override fun applySpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun applyAudioDelay(ms: Long) {
        audioOffset.us = ms * 1000
    }

    override fun applySubtitleDelay(ms: Long) {
        player.subtitleDelayMilliseconds = ms
    }

    override fun startBoost(db: Int): () -> Unit {
        val effect = runCatching {
            LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain(db * 100)
                enabled = true
            }
        }.getOrNull()
        return { effect?.release() }
    }

    /** Loading a side file means preparing the video again, from where it is. */
    override fun loadSubtitleFile() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        val item = player.currentMediaItem ?: return
        player.setMediaItem(withSubtitleFile(item.buildUpon()).build(), false)
        player.prepare()
    }

    /** A subtitle file that stopped loading (moved, deleted) must not keep the video from playing. */
    fun recoverFrom(error: PlaybackException): Boolean {
        if (subtitleUri == null || error.errorCode !in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) return false
        forgetSubtitleFile()
        save()
        val item = player.currentMediaItem ?: return false
        player.setMediaItem(withSubtitleFile(item.buildUpon()).build(), false)
        player.prepare()
        return true
    }
}

/** A-B repeat, sleep timer, volume boost: nothing runs unless one of them is set. */
@Composable
fun VideoAdvancedEffects(engine: VideoEngine) {
    val from = engine.loopFromMs
    val to = engine.loopToMs
    LaunchedEffect(engine, from, to) {
        if (from == null || to == null) return@LaunchedEffect
        while (isActive) {
            if (engine.positionMs !in from..to) engine.seekTo(from)
            delay(50)
        }
    }
    val sleepAt = engine.sleepAt
    LaunchedEffect(engine, sleepAt) {
        if (sleepAt == null) return@LaunchedEffect
        delay((sleepAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        engine.pause()
        engine.sleepAt = null
    }
    val boost = engine.boostDb
    DisposableEffect(engine, boost) {
        val stop = if (boost == 0) null else engine.startBoost(boost)
        onDispose { stop?.invoke() }
    }
}

/** What the badge shows: the seconds a run of tap seeks has covered that way. */
class TapSeekRun(val forward: Boolean, val seconds: Int)

/**
 * Double tap with the controls hidden: the left half goes back, the right half ahead. As on YouTube
 * every further tap during the run is one more step, so n quick taps make n - 1 steps.
 */
class TapSeek(private val engine: VideoEngine) {
    var run by mutableStateOf<TapSeekRun?>(null)
        private set
    /** Not the engine's position: VLC only reports that a few times a second, which would lose quick taps. */
    private var target = 0L

    val running get() = run != null

    fun onDoubleTap(rightSide: Boolean) = step(rightSide, if (running) 2 else 1)

    fun onTap(rightSide: Boolean) = step(rightSide, 1)

    fun endRun() {
        run = null
    }

    private fun step(forward: Boolean, steps: Int) {
        val seconds = engine.tapSeekSeconds * steps
        val current = run
        val from = if (current != null) target else engine.positionMs
        val moved = from + (if (forward) seconds else -seconds) * 1000L
        val end = engine.durationMs
        target = (if (end > 0) moved.coerceAtMost(end) else moved).coerceAtLeast(0)
        engine.seekTo(target)
        run = TapSeekRun(forward, if (current?.forward == forward) current.seconds + seconds else seconds)
    }
}

/** Over that half of the screen while a run lasts. Composed only then, and takes no touches. */
@Composable
fun BoxScope.TapSeekBadge(seek: TapSeek) {
    val run = seek.run ?: return
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(run) {
        alpha.animateTo(1f, tween(TAP_SEEK_FADE_MS))
        delay(TAP_SEEK_RUN_MS - 2 * TAP_SEEK_FADE_MS)
        alpha.animateTo(0f, tween(TAP_SEEK_FADE_MS))
        seek.endRun()
    }
    Column(
        Modifier
            .align(if (run.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.5f)
            .graphicsLayer { this.alpha = alpha.value },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Its own dark backing: white on a bright picture would be lost.
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (run.forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
            Text(
                "${if (run.forward) "+" else "−"}${run.seconds} s",
                color = Color.White,
                fontSize = 15.sp,
            )
        }
    }
}

/** The picture's box inside the zoomable area. */
fun Modifier.videoFrame(mode: VideoFrameMode, videoAspect: Float): Modifier = when (mode) {
    VideoFrameMode.FIT -> aspectRatio(videoAspect)
    VideoFrameMode.STRETCH -> fillMaxSize()
    VideoFrameMode.FILL -> clipToBounds().layout { measurable, constraints ->
        val width = max(constraints.maxWidth, (constraints.maxHeight * videoAspect).roundToInt())
        val height = max(constraints.maxHeight, (constraints.maxWidth / videoAspect).roundToInt())
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
        }
    }
    else -> aspectRatio(mode.aspect ?: videoAspect)
}

/**
 * Subtitles over the picture but outside its zoom, so they stay readable and on screen. Cues go
 * straight to the view: they never recompose anything. Picture subtitles (PGS, VobSub) are placed
 * relative to the frame, hence the same proportions.
 */
@OptIn(UnstableApi::class)
@Composable
fun BoxScope.VideoSubtitles(player: Player, advanced: VideoEngine, videoAspect: Float) {
    val mode = advanced.frameMode
    val box = when (mode) {
        VideoFrameMode.FILL, VideoFrameMode.STRETCH -> Modifier.fillMaxSize()
        else -> Modifier.aspectRatio(mode.aspect ?: videoAspect)
    }
    val sp = advanced.subtitleSp
    val background = advanced.subtitleBackground
    val view = remember { arrayOfNulls<SubtitleView>(1) }
    AndroidView(
        factory = { context ->
            SubtitleView(context).also {
                view[0] = it
                it.setCues(player.currentCues.cues)
            }
        },
        update = {
            it.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            it.setStyle(
                CaptionStyleCompat(
                    Color.White.toArgb(),
                    if (background) Color.Black.copy(alpha = 0.6f).toArgb() else Color.Transparent.toArgb(),
                    Color.Transparent.toArgb(),
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    Color.Black.toArgb(),
                    null,
                ),
            )
        },
        onRelease = { view[0] = null },
        modifier = box.align(Alignment.Center),
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                view[0]?.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}

private val TAB_LABELS = listOf("VIDEO", "AUDIO", "SUBTITLES", "PLAYBACK")

/** The popup behind the viewer's More menu. Composed only while open. */
@Composable
fun VideoAdvancedPanel(engine: VideoEngine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    // The picker sends the app to the background, which pauses the video.
    var resumeAfterPick by remember { mutableStateOf(false) }
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val mime = subtitleMime(displayName(context, uri))
            if (mime != null) engine.addSubtitleFile(uri, mime)
            else Toast.makeText(context, "Not a subtitle file (SRT, ASS, SSA, VTT, TTML)", Toast.LENGTH_SHORT).show()
        }
        if (resumeAfterPick) engine.play()
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    GlassDialog(
        onDismiss = onDismiss,
        title = "ADVANCED",
        buttons = { GlassDialogButton("Close", onClick = onDismiss) },
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TAB_LABELS.forEachIndexed { i, label -> Pill(label, selected = tab == i) { tab = i } }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> VideoTab(engine)
                1 -> AudioTab(engine)
                2 -> SubtitleTab(engine) {
                    resumeAfterPick = engine.isPlaying
                    pickSubtitle.launch(arrayOf("*/*"))
                }
                else -> PlaybackTab(engine)
            }
        }
    }
}

@Composable
private fun VideoTab(engine: VideoEngine) {
    TrackList(engine, C.TRACK_TYPE_VIDEO, offLabel = null, empty = "No picture track")
    Section("PICTURE SHAPE")
    Pills(VideoFrameMode.entries, { it.label }, { it == engine.frameMode }) { engine.frameMode = it }
}

@Composable
private fun AudioTab(engine: VideoEngine) {
    TrackList(engine, C.TRACK_TYPE_AUDIO, offLabel = "Off", empty = "No audio track")
    Section("DELAY")
    Stepper(engine.audioDelayMs, AUDIO_DELAY_STEP_MS, engine::changeAudioDelay)
    Section("VOLUME BOOST")
    Pills(engine.boosts, { if (it == 0) "Off" else "+$it dB" }, { it == engine.boostDb }) { engine.boostDb = it }
}

@Composable
private fun SubtitleTab(engine: VideoEngine, onPick: () -> Unit) {
    TrackList(engine, C.TRACK_TYPE_TEXT, offLabel = "Off", empty = null)
    Spacer(Modifier.height(4.dp))
    Pill("ADD SUBTITLE FILE", selected = false, onClick = onPick)
    Section("DELAY")
    Stepper(engine.subtitleDelayMs, SUBTITLE_DELAY_STEP_MS, engine::changeSubtitleDelay)
    if (engine.styledSubtitles) {
        Section("SIZE")
        Pills(SUBTITLE_SIZES, { "$it sp" }, { it == engine.subtitleSp }, engine::changeSubtitleSp)
        Section("BACKGROUND")
        Pills(listOf(false, true), { if (it) "On" else "Off" }, { it == engine.subtitleBackground }, engine::changeSubtitleBackground)
    }
}

@Composable
private fun PlaybackTab(engine: VideoEngine) {
    val content = LocalGlassContent.current
    Section("SPEED")
    Pills(SPEEDS, { "${it}x" }, { it == engine.speed }, engine::changeSpeed)
    Section("DOUBLE TAP TO SEEK")
    var customSeek by remember { mutableStateOf(engine.tapSeekSeconds.takeIf { it !in TAP_SEEK_PRESETS }?.toString().orEmpty()) }
    val parsedSeek = customSeek.toIntOrNull()?.takeIf { it in 1..MAX_TAP_SEEK_SECONDS }
    Pills(TAP_SEEK_PRESETS, { "$it s" }, { it == engine.tapSeekSeconds }) {
        customSeek = ""
        engine.changeTapSeekSeconds(it)
    }
    Spacer(Modifier.height(6.dp))
    GlassTextField(
        customSeek,
        {
            customSeek = it
            it.toIntOrNull()?.takeIf { s -> s in 1..MAX_TAP_SEEK_SECONDS }?.let(engine::changeTapSeekSeconds)
        },
        isError = customSeek.isNotEmpty() && parsedSeek == null,
        suffix = "custom, s",
    )
    Section("JUMP TO TIME")
    var jump by remember { mutableStateOf("") }
    val parsed = parseClock(jump)
    GlassTextField(jump, { jump = it }, isError = jump.isNotEmpty() && parsed == null, suffix = "h:m:s", onDone = {
        if (parsed != null) engine.seekTo(parsed.coerceIn(0, engine.durationMs))
    })
    Section("A-B REPEAT")
    val from = engine.loopFromMs
    val to = engine.loopToMs
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(if (from == null) "SET A" else "A ${formatDuration(from)}", selected = from != null) {
            engine.loopFromMs = engine.positionMs
            engine.loopToMs = null
        }
        Pill(if (to == null) "SET B" else "B ${formatDuration(to)}", selected = to != null) {
            if (from != null && engine.positionMs > from) engine.loopToMs = engine.positionMs
        }
        if (from != null) Pill("CLEAR", selected = false) {
            engine.loopFromMs = null
            engine.loopToMs = null
        }
    }
    Section("SLEEP TIMER")
    val sleepAt = engine.sleepAt
    Pills(SLEEP_MINUTES, { if (it == 0) "Off" else "$it min" }, { it == 0 && sleepAt == null }) {
        engine.sleepAt = if (it == 0) null else SystemClock.elapsedRealtime() + it * 60_000L
    }
    if (sleepAt != null) {
        Text(
            "Pauses in about ${((sleepAt - SystemClock.elapsedRealtime()) / 60_000L + 1)} min",
            color = content.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TrackList(engine: VideoEngine, type: Int, offLabel: String?, empty: String?) {
    val context = LocalContext.current
    val content = LocalGlassContent.current
    // Read so that the list follows the engine's tracks.
    engine.tracksVersion
    val rows = engine.tracks(type)
    if (rows.isEmpty() && empty != null) {
        Text(empty, color = content.copy(alpha = 0.55f), fontSize = 13.sp)
        return
    }
    if (offLabel != null) GlassChoiceRow(offLabel, selected = rows.none { it.selected }) { engine.trackOff(type) }
    for (row in rows) {
        GlassChoiceRow(
            row.title,
            selected = row.selected,
            description = if (row.supported) row.details.ifEmpty { null } else "${row.details} · can't play on this phone",
        ) {
            if (row.supported) engine.selectTrack(type, row)
            else Toast.makeText(context, "This phone can't play that track", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = LocalGlassContent.current.copy(alpha = 0.55f),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val content = LocalGlassContent.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) content else content.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) (if (content == Color.White) Color.Black else Color.White) else content, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun <T> Pills(options: List<T>, label: (T) -> String, selected: (T) -> Boolean, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option -> Pill(label(option), selected(option)) { onSelect(option) } }
    }
}

/** Minus, the value (tap it to reset), plus. Positive means later. */
@Composable
private fun Stepper(valueMs: Long, stepMs: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill("−", selected = false) { onChange(valueMs - stepMs) }
        Box(Modifier.width(110.dp).clickable { onChange(0) }, contentAlignment = Alignment.Center) {
            Text(
                if (valueMs == 0L) "0 ms" else String.format(Locale.US, "%+d ms", valueMs),
                color = LocalGlassContent.current,
                fontSize = 15.sp,
            )
        }
        Pill("+", selected = false) { onChange(valueMs + stepMs) }
    }
}

@OptIn(UnstableApi::class)
private fun trackTitle(format: Format, number: Int): String {
    val language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf(String::isNotEmpty) ?: it }
    val label = format.label?.takeIf { it.isNotBlank() }
    return when {
        language != null && label != null && !label.equals(language, ignoreCase = true) -> "$language · $label"
        else -> language ?: label ?: "Track $number"
    }
}

@OptIn(UnstableApi::class)
private fun trackDetails(format: Format): String = buildList {
    // Subtitles parsed while reading the file report their format here instead.
    add(codecName(format.codecs?.takeIf { format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES } ?: format.sampleMimeType ?: format.codecs))
    if (format.width > 0 && format.height > 0) add("${format.width}×${format.height}")
    if (format.frameRate > 0) add(String.format(Locale.US, "%.3g fps", format.frameRate))
    if (format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084) add("HDR10")
    if (format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG) add("HLG")
    when {
        format.channelCount == 6 -> add("5.1")
        format.channelCount == 8 -> add("7.1")
        format.channelCount == 2 -> add("Stereo")
        format.channelCount == 1 -> add("Mono")
        format.channelCount > 0 -> add("${format.channelCount} ch")
    }
    if (format.bitrate > 0) add("${format.bitrate / 1000} kb/s")
    if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
    if (format.id?.startsWith("external:") == true) add("File")
}.joinToString(" · ")

private fun codecName(mime: String?): String = when (mime) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_VP8 -> "VP8"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_TTML -> "TTML"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> mime?.substringAfter('/')?.uppercase(Locale.US) ?: "Unknown"
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

private fun subtitleMime(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase(Locale.US)) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
    else -> null
}

/** "90" seconds, "1:30" or "1:02:30"; null if it's none of those. */
private fun parseClock(text: String): Long? {
    val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3 || parts.any { it < 0 }) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1000
}
