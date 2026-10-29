package com.appslab.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens a photo and pinch-zooms it in slow, real-time steps. Needs media permission already granted. */
@RunWith(AndroidJUnit4::class)
class ViewerZoomTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun pinch(spreadFrom: Float, spreadTo: Float, cx: Float = 540f, cy: Float = 1200f, steps: Int = 40) {
        val root = rule.onRoot()
        root.performTouchInput {
            down(0, Offset(cx - spreadFrom, cy))
            down(1, Offset(cx + spreadFrom, cy))
        }
        for (i in 1..steps) {
            val d = spreadFrom + (spreadTo - spreadFrom) * i / steps
            root.performTouchInput {
                moveTo(0, Offset(cx - d, cy))
                moveTo(1, Offset(cx + d, cy))
            }
            Thread.sleep(25)
        }
        root.performTouchInput {
            up(0)
            up(1)
        }
    }

    private fun shot(name: String) {
        // Shell-owned path: survives the test APK being uninstalled after the run.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/zoom_$name.png").close()
        Thread.sleep(500)
    }

    @Test
    fun openPhotoAndZoom() {
        // setContent can land after the splash screen, so "no hierarchy yet" just means keep waiting.
        rule.waitUntil(20_000) { runCatching { rule.onAllNodesWithTag("media_grid").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        Thread.sleep(2_000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap 540 1065").close()
        Thread.sleep(2_500)
        shot("1_open")
        pinch(spreadFrom = 100f, spreadTo = 300f) // zoom in ×3
        Thread.sleep(2_000)
        shot("2_zoomed")
        pinch(spreadFrom = 100f, spreadTo = 200f, cx = 300f, cy = 900f) // zoom in more, off-centre
        Thread.sleep(2_000)
        shot("3_zoomed_more")
        pinch(spreadFrom = 300f, spreadTo = 100f) // zoom back out
        Thread.sleep(3_000)
        shot("4_out")
    }

    @Test
    fun openPhotoAndDoubleTap() {
        rule.waitUntil(20_000) { runCatching { rule.onAllNodesWithTag("media_grid").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        Thread.sleep(2_000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap 540 1065").close()
        Thread.sleep(2_500)
        shakyDoubleTap(Offset(300f, 900f)) // zoom in
        Thread.sleep(1_500)
        shot("dt_1_in")
        shakyDoubleTap(Offset(700f, 1400f)) // zoom out
        Thread.sleep(1_500)
        shot("dt_2_out")
        shakyDoubleTap(Offset(800f, 1000f)) // zoom in again
        Thread.sleep(1_500)
        shot("dt_3_in")
        shakyDoubleTap(Offset(540f, 1170f)) // zoom out again
        Thread.sleep(1_500)
        shot("dt_4_out")
    }

    /** Like a real finger, each tap wobbles a few pixels (well under touch slop). */
    private fun shakyDoubleTap(at: Offset) = rule.onRoot().performTouchInput {
        repeat(2) {
            down(at)
            moveBy(Offset(3f, -2f))
            moveBy(Offset(-2f, 3f))
            up()
            advanceEventTime(120)
        }
    }

}
