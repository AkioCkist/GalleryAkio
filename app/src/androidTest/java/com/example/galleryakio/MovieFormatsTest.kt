package com.example.galleryakio

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.galleryakio.ui.components.AudioSyncOffset
import com.example.galleryakio.ui.components.movieRenderers
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Plays every file in the app's external "formats" folder (where MediaStore won't list them) and
 * logs whether picture and sound really come out. Muted: this runs on someone's own phone.
 */
@RunWith(AndroidJUnit4::class)
class MovieFormatsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The same files on VLC: what the viewer falls back to. */
    @Test
    fun playEveryFileOnVlc() {
        val context = instrumentation.targetContext
        val files = context.getExternalFilesDir("formats")?.listFiles()?.filter { it.length() > 0 }?.sortedBy(File::getName).orEmpty()
        assumeTrue(files.isNotEmpty())
        // Silence for the whole instance: volume calls only reach an output already open.
        val vlc = LibVLC(context, arrayListOf("--gain=0", "--no-sub-autodetect-file"))
        val texture = SurfaceTexture(4).apply { setDefaultBufferSize(640, 360) }
        for (file in files) {
            var error = false
            val player = MediaPlayer(vlc)
            val media = Media(vlc, Uri.fromFile(file))
            media.setHWDecoderEnabled(true, false)
            player.media = media
            player.setEventListener { if (it.type == MediaPlayer.Event.EncounteredError) error = true }
            main {
                player.vlcVout.setVideoSurface(texture)
                player.vlcVout.setWindowSize(640, 360)
                player.vlcVout.attachViews()
                player.play()
            }
            Thread.sleep(2_500)
            val stats = media.stats
            Log.i("MovieFormats", "VLC ${file.name}|error=$error|time=${player.time}|pictures=${stats?.displayedPictures}|video=${stats?.decodedVideo}|audio=${stats?.decodedAudio}|tracks v${player.videoTracksCount} a${player.audioTracksCount}")
            media.release()
            player.stop()
            player.vlcVout.detachViews()
            player.release()
        }
        vlc.release()
        texture.release()
    }

    @Test
    fun playEveryFile() {
        val context = instrumentation.targetContext
        val files = context.getExternalFilesDir("formats")?.listFiles()?.sortedBy(File::getName).orEmpty()
        assumeTrue(files.isNotEmpty())
        val texture = SurfaceTexture(3).apply { setDefaultBufferSize(640, 360) }
        val surface = Surface(texture)
        for (file in files) {
            var videoDecoder = "-"
            var audioDecoder = "-"
            val player = main {
                ExoPlayer.Builder(context, movieRenderers(context, AudioSyncOffset())).build().apply {
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                            videoDecoder = decoderName
                        }
                        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                            audioDecoder = decoderName
                        }
                    })
                    setVideoSurface(surface)
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    playWhenReady = true
                    prepare()
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val done = main { player.playbackState == Player.STATE_READY || player.playerError != null }
                if (done) break
                Thread.sleep(50)
            }
            Thread.sleep(1_500)
            val line = main {
                val error = player.playerError
                val tracks = player.currentTracks.groups.joinToString(",") { group ->
                    val kind = when (group.type) {
                        C.TRACK_TYPE_VIDEO -> "v"
                        C.TRACK_TYPE_AUDIO -> "a"
                        C.TRACK_TYPE_TEXT -> "t"
                        else -> "?"
                    }
                    kind + ":" + (group.getTrackFormat(0).sampleMimeType ?: "?").substringAfter('/') + (if (group.isSupported) "" else "!")
                }
                val frames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                val sound = player.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                "${file.name}|${error?.errorCodeName ?: "ok"}|frames=$frames|$videoDecoder|sound=$sound|$audioDecoder|$tracks"
            }
            Log.i("MovieFormats", line)
            main { player.release() }
        }
        surface.release()
        texture.release()
    }
}
