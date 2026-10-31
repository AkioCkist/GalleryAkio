package com.example.galleryakio

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.center
import androidx.compose.ui.test.centerLeft
import androidx.compose.ui.test.centerRight
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.MediaType
import com.example.galleryakio.data.VideoLeaveAction
import com.example.galleryakio.ui.components.GlassOverlayHost
import com.example.galleryakio.ui.components.VideoControlsHost
import com.example.galleryakio.ui.components.VideoPlayer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real player and panel on their own test screen, over silent test files in the app's external
 * files folder. Only that screen is ever on show, so screenshots can't catch anyone's photos.
 */
@RunWith(AndroidJUnit4::class)
class VideoPanelTest {
    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var controlsVisible by mutableStateOf(false)

    private fun wait(millis: Int) = repeat(millis / 50) {
        rule.mainClock.advanceTimeBy(50)
        Thread.sleep(50)
    }

    private fun shot(name: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/panel_$name.png"),
        ).use { it.readBytes() }
    }

    /** Libraries stored uncompressed in the APK map under its name, not their own, so go by the code mapped from it. */
    private fun nativeCodeMb(): Long = File("/proc/self/maps").readLines()
        .filter { it.contains("base.apk") && it.split(Regex("\\s+"))[1].contains('x') }
        .sumOf { line ->
            val (start, end) = line.substringBefore(' ').split('-')
            end.toLong(16) - start.toLong(16)
        } / (1 shl 20)

    private fun item(file: File, id: Long) = MediaItem(
        id = id,
        uri = Uri.fromFile(file),
        displayName = file.name,
        type = MediaType.VIDEO,
        dateAdded = 0,
        dateTaken = 0,
        dateModified = 0,
        size = file.length(),
        mimeType = "video/mp4",
        relativePath = "",
        bucketName = "",
        width = 640,
        height = 360,
    )

    @Composable
    private fun Player(item: MediaItem, host: VideoControlsHost) {
        VideoPlayer(
            item = item,
            isCurrentPage = true,
            isNearby = true,
            preloadDelayMs = 0,
            isVisible = true,
            isAudible = true,
            leaving = false,
            rememberPosition = false,
            loop = true,
            onLoopChange = {},
            leaveAction = VideoLeaveAction.entries.first(),
            controlsVisible = controlsVisible,
            onControlsChange = { controlsVisible = it },
            controlsHost = host,
        )
    }

    private fun show(file: File, id: Long, host: VideoControlsHost) {
        val item = item(file, id)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // Not an activity to the player: this test activity can't do picture in picture, and says so by crashing.
            CompositionLocalProvider(LocalContext provides ContextWrapper(LocalContext.current)) {
                GlassOverlayHost {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        Player(item, host)
                        val panel = host.advancedPanel
                        var open by remember { mutableStateOf(true) }
                        if (open && panel != null) panel { open = false }
                    }
                }
            }
        }
    }

    private fun visit(name: String, tabs: List<String>) {
        wait(4_000)
        Log.i("VideoPanel", "$name waited")
        shot("${name}_video")
        Log.i("VideoPanel", "$name shot")
        for (tab in tabs) {
            try {
                rule.onNodeWithText(tab).performClick()
            } catch (e: Throwable) {
                Log.e("VideoPanel", "tab $tab: ${e.message?.take(300)}")
                throw e
            }
            wait(400)
            shot("${name}_$tab")
            Log.i("VideoPanel", "$name $tab shot")
        }
    }

    /** Files ExoPlayer gives up on, each from its way of giving up: they should end up playing on VLC. */
    @Test
    fun unplayableFilesFallBackToVlc() {
        val files = instrumentation.targetContext.getExternalFilesDir("fallback")?.listFiles()?.sortedBy(File::getName).orEmpty()
        assumeTrue(files.isNotEmpty())
        var current by mutableStateOf<File?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ContextWrapper(LocalContext.current)) {
                GlassOverlayHost {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        current?.let { file ->
                            key(file.name) { Player(item(file, -2000L - file.name.hashCode()), remember { VideoControlsHost() }) }
                        }
                    }
                }
            }
        }
        for (file in files) {
            rule.runOnIdle { current = file }
            wait(6_500)
            shot("fb_${file.name}_1")
            wait(1_000)
            shot("fb_${file.name}_2")
            Log.i("VideoPanel", "${file.name} native code ${nativeCodeMb()} MB")
        }
        rule.runOnIdle { current = null }
        wait(1_000)
    }

    /** A 12 s file on VLC, past its end: looping should bring the picture back around. */
    @Test
    fun vlcLoops() {
        val file = File(instrumentation.targetContext.getExternalFilesDir("fallback"), "rv20.rm")
        assumeTrue(file.exists())
        show(file, -1004, VideoControlsHost())
        wait(1_000)
        rule.onNodeWithText("CLOSE").performClick()
        wait(16_000)
        shot("fb_loop_1")
        wait(1_000)
        shot("fb_loop_2")
    }

    @Test
    fun ordinaryClipLeavesVlcUnloaded() {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "clip.mp4")
        assumeTrue(file.exists())
        Log.i("VideoPanel", "before anything: native code ${nativeCodeMb()} MB")
        show(file, -1003, VideoControlsHost())
        wait(4_000)
        Log.i("VideoPanel", "clip playing: native code ${nativeCodeMb()} MB")
    }

    private fun doubleTap(left: Boolean) = rule.onRoot().performTouchInput {
        doubleClick(if (left) centerLeft + Offset(width / 8f, 0f) else centerRight - Offset(width / 8f, 0f))
    }

    /** The test file's picture shows its own second counter, so the shots tell where each seek landed. */
    private fun tapSeeks(name: String, file: File, id: Long) {
        assumeTrue(file.exists())
        val prefs = instrumentation.targetContext.getSharedPreferences("video_advanced", Context.MODE_PRIVATE)
        val before = prefs.getInt("tap_seek_seconds", -1)
        try {
            show(file, id, VideoControlsHost())
            wait(3_000)
            rule.onNodeWithText("PLAYBACK").performClick()
            wait(400)
            rule.onNodeWithText("5 s").performClick()
            wait(300)
            shot("seek_${name}_panel")
            rule.onNodeWithText("CLOSE").performClick()
            wait(1_500)
            shot("seek_${name}_start")
            doubleTap(left = false)
            wait(200)
            rule.onNodeWithText("+5 s").assertExists()
            shot("seek_${name}_ahead")
            wait(1_800)
            doubleTap(left = false)
            wait(150)
            doubleTap(left = false)
            wait(200)
            // Four taps in one run: three steps.
            rule.onNodeWithText("+15 s").assertExists()
            wait(1_500)
            shot("seek_${name}_ahead_run")
            doubleTap(left = true)
            wait(200)
            rule.onNodeWithText("−5 s").assertExists()
            wait(1_500)
            shot("seek_${name}_back")
        } finally {
            prefs.edit().apply { if (before < 0) remove("tap_seek_seconds") else putInt("tap_seek_seconds", before) }.commit()
        }
    }

    /** A typed amount, then what taps do once the run is over: bring the controls up, and pause with them up. */
    @Test
    fun tapSeekCustomAmountAndControls() {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "tapseek.mp4")
        assumeTrue(file.exists())
        val prefs = instrumentation.targetContext.getSharedPreferences("video_advanced", Context.MODE_PRIVATE)
        val before = prefs.getInt("tap_seek_seconds", -1)
        try {
            show(file, -1007, VideoControlsHost())
            wait(3_000)
            rule.onNodeWithText("PLAYBACK").performClick()
            wait(400)
            rule.onAllNodes(hasSetTextAction())[0].performTextInput("7")
            wait(300)
            rule.onNodeWithText("CLOSE").performClick()
            wait(1_000)
            doubleTap(left = true)
            wait(200)
            rule.onNodeWithText("−7 s").assertExists()
            wait(1_500)
            rule.onNodeWithText("−7 s").assertDoesNotExist()
            assertEquals(7, prefs.getInt("tap_seek_seconds", -1))

            rule.onRoot().performTouchInput { click(center) }
            wait(500)
            assertTrue(controlsVisible)
            doubleTap(left = false)
            wait(200)
            rule.onNodeWithText("+7 s").assertDoesNotExist()
            // Paused, so the controls don't fade out.
            wait(4_000)
            assertTrue(controlsVisible)
        } finally {
            prefs.edit().apply { if (before < 0) remove("tap_seek_seconds") else putInt("tap_seek_seconds", before) }.commit()
        }
    }

    @Test
    fun tapSeeksOnExo() = tapSeeks("exo", File(instrumentation.targetContext.getExternalFilesDir(null), "tapseek.mp4"), -1005)

    @Test
    fun tapSeeksOnVlc() = tapSeeks("vlc", File(instrumentation.targetContext.getExternalFilesDir(null), "tapseek.wmv"), -1006)

    @Test
    fun exoPanelOverMultiTrackMovie() {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "ui_tracks.mkv")
        assumeTrue(file.exists())
        show(file, -1001, VideoControlsHost())
        visit("exo", listOf("AUDIO", "SUBTITLES", "PLAYBACK"))
        rule.onNodeWithText("SUBTITLES").performClick()
        wait(300)
        // Close the panel to see the picture and the default subtitle track, due at 5 s and 10 s.
        rule.onNodeWithText("CLOSE").performClick()
        repeat(4) { i ->
            wait(1_500)
            shot("exo_subtitle_$i")
        }
        Log.i("VideoPanel", "exo done")
    }

    @Test
    fun vlcPanelOverWmv() {
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "ui_wmv.wmv")
        assumeTrue(file.exists())
        show(file, -1002, VideoControlsHost())
        wait(3_000)
        visit("vlc", listOf("AUDIO", "SUBTITLES", "PLAYBACK"))
        rule.onNodeWithText("CLOSE").performClick()
        wait(1_500)
        shot("vlc_after")
        Log.i("VideoPanel", "vlc done")
    }
}
