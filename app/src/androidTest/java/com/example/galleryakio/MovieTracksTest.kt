package com.example.galleryakio

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.galleryakio.ui.components.AudioSyncOffset
import com.example.galleryakio.ui.components.movieRenderers
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays movie_test.mkv (two video, five audio, two subtitle tracks), pushed to the app's external
 * files folder, where MediaStore won't list it in the gallery. No screen: only sound and sync.
 */
@RunWith(AndroidJUnit4::class)
class MovieTracksTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tag = "MovieTracks"

    private fun file(name: String): File? = File(context.getExternalFilesDir(null), name).takeIf { it.exists() }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun build(offset: AudioSyncOffset = AudioSyncOffset(), item: MediaItem): ExoPlayer = main {
        ExoPlayer.Builder(context, movieRenderers(context, offset)).build().apply {
            setMediaItem(item)
            // Test tones, on the user's own phone.
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    private fun waitReady(player: ExoPlayer) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (main { player.playbackState } != Player.STATE_READY && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertEquals(Player.STATE_READY, main { player.playbackState })
    }

    @Test
    fun everyAudioAndVideoTrackPlays() {
        val movie = file("movie_test.mkv")
        assumeTrue(movie != null)
        val decoders = CopyOnWriteArrayList<String>()
        val player = build(item = MediaItem.fromUri(Uri.fromFile(movie)))
        main {
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    decoders += decoderName
                }
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    decoders += decoderName
                }
            })
        }
        // A video renderer without a surface has nothing to output to.
        val texture = SurfaceTexture(2).apply { setDefaultBufferSize(640, 360) }
        val surface = Surface(texture)
        main { player.setVideoSurface(surface) }
        waitReady(player)
        for (type in listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)) {
            val groups = main { player.currentTracks.groups.filter { it.type == type } }
            assertTrue(groups.size >= 2)
            for (group in groups) {
                val format = group.getTrackFormat(0)
                main {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
                }
                Thread.sleep(1_500)
                val selected = main { player.currentTracks.groups.first { it.mediaTrackGroup == group.mediaTrackGroup }.isSelected }
                val before = main { if (type == C.TRACK_TYPE_AUDIO) player.audioDecoderCounters?.renderedOutputBufferCount else player.videoDecoderCounters?.renderedOutputBufferCount } ?: 0
                Thread.sleep(1_000)
                val after = main { if (type == C.TRACK_TYPE_AUDIO) player.audioDecoderCounters?.renderedOutputBufferCount else player.videoDecoderCounters?.renderedOutputBufferCount } ?: 0
                val error = main { player.playerError }
                Log.i(tag, "${format.sampleMimeType} ${format.channelCount}ch ${format.language} ${format.width}x${format.height} supported=${group.isSupported} selected=$selected buffers=${after - before} decoder=${decoders.lastOrNull()} error=$error")
                assertTrue("${format.sampleMimeType}", selected && after > before && error == null)
            }
        }
        main {
            player.release()
            surface.release()
            texture.release()
        }
    }

    private fun firstCueAtMs(delayMs: Long, track: Int, extra: MediaItem.SubtitleConfiguration? = null): Pair<Long, String> {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(file("movie_test.mkv")))
        if (extra != null) builder.setSubtitleConfigurations(listOf(extra))
        val player = build(item = builder.build())
        val cues = CopyOnWriteArrayList<Pair<Long, String>>()
        main {
            player.subtitleDelayMilliseconds = delayMs
            player.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    val text = cueGroup.cues.firstOrNull()?.text?.toString()
                    if (text != null) cues += player.currentPosition to text
                }
            })
        }
        waitReady(player)
        main {
            val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }[track]
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        }
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (cues.isEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        main { player.release() }
        assertTrue(cues.isNotEmpty())
        return cues.first()
    }

    @Test
    fun subtitlesShowAndShiftWithTheirDelay() {
        assumeTrue(file("movie_test.mkv") != null)
        val (plainAt, plain) = firstCueAtMs(0, 0)
        val (laterAt, later) = firstCueAtMs(1_000, 0)
        val (earlierAt, _) = firstCueAtMs(-1_000, 0)
        val (assAt, ass) = firstCueAtMs(0, 1)
        Log.i(tag, "srt '$plain' at $plainAt ms; delay +1000 '$later' at $laterAt ms; delay -1000 at $earlierAt ms; ass '$ass' at $assAt ms")
        assertEquals(5_000.0, plainAt.toDouble(), 150.0)
        assertEquals(6_000.0, laterAt.toDouble(), 150.0)
        assertEquals(4_000.0, earlierAt.toDouble(), 150.0)
        assertEquals(5_000.0, assAt.toDouble(), 150.0)

        val side = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file("side.srt")))
            .setId("external:side").setMimeType(MimeTypes.APPLICATION_SUBRIP).build()
        val (fileAt, fileText) = firstCueAtMs(0, 2, side)
        Log.i(tag, "side file '$fileText' at $fileAt ms")
        assertEquals("Side file line 1", fileText)
    }

    private fun ffmpegLoaded() = File("/proc/self/maps").readText().contains("libavcodec")

    /** What an ordinary clip pays for the FFmpeg decoders being there. Run alone, in a fresh process. */
    @Test
    fun ordinaryClipCost() {
        val clip = file("clip.mp4")
        assumeTrue(clip != null)
        val item = MediaItem.fromUri(Uri.fromFile(clip))
        fun open(label: String, make: () -> ExoPlayer) {
            repeat(4) {
                val start = SystemClock.elapsedRealtimeNanos()
                val player = main {
                    make().apply {
                        setMediaItem(item)
                        volume = 0f
                        prepare()
                    }
                }
                val built = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                waitReady(player)
                val ready = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                val decoders = main { "video=${player.videoFormat?.sampleMimeType} audio=${player.audioFormat?.sampleMimeType}" }
                main { player.release() }
                Log.i(tag, "$label build=$built ms ready=$ready ms ffmpegLoaded=${ffmpegLoaded()} $decoders")
            }
        }
        Log.i(tag, "ffmpeg loaded before anything: ${ffmpegLoaded()}")
        open("default") { ExoPlayer.Builder(context).build() }
        open("movie") { ExoPlayer.Builder(context, movieRenderers(context, AudioSyncOffset())).build() }
    }

    /** Median of a frame's video time minus the wall clock at its release: moves with the delay, as sound runs at a steady rate. */
    private fun videoLead(frames: List<Pair<Long, Long>>): Long = frames.map { (pts, release) -> pts - release }.sorted().let { it[it.size / 2] }

    @Test
    fun audioDelayShiftsThePicture() {
        assumeTrue(file("movie_test.mkv") != null)
        val offset = AudioSyncOffset()
        val player = build(offset, MediaItem.fromUri(Uri.fromFile(file("movie_test.mkv"))))
        val frames = CopyOnWriteArrayList<Pair<Long, Long>>()
        // Frame times only flow to a surface, and the renderer needs one to count as ready at speed.
        val texture = SurfaceTexture(1).apply { setDefaultBufferSize(640, 360) }
        val surface = Surface(texture)
        main {
            player.setVideoSurface(surface)
            player.setVideoFrameMetadataListener(VideoFrameMetadataListener { pts, release, _, _ -> frames += pts / 1000 to release / 1_000_000 })
        }
        waitReady(player)
        fun measure(ms: Long): Long {
            offset.us = ms * 1000
            Thread.sleep(1_000)
            frames.clear()
            Thread.sleep(2_000)
            assertTrue(frames.size > 20)
            return videoLead(frames.toList())
        }
        val base = measure(0)
        val later = measure(500) - base
        val earlier = measure(-300) - base
        val back = measure(0) - base
        Log.i(tag, "video lead vs offset 0: +500 -> $later ms, -300 -> $earlier ms, back to 0 -> $back ms")
        main {
            player.release()
            surface.release()
            texture.release()
        }
        assertEquals(500.0, later.toDouble(), 60.0)
        assertEquals(-300.0, earlier.toDouble(), 60.0)
        assertEquals(0.0, back.toDouble(), 60.0)
    }
}
