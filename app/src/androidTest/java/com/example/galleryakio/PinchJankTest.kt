package com.example.galleryakio

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinches the Photos grid back and forth and reports the frame stats (`dumpsys gfxinfo`)
 * of just the pinching, as an instrumentation status line. Needs media permission granted.
 */
@RunWith(AndroidJUnit4::class)
class PinchJankTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun visibleGrid() = rule.onAllNodesWithTag("media_grid").let { all ->
        val index = all.fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.left in -1f..1f }
        all[index.coerceAtLeast(0)]
    }

    private fun pinch(spreadFrom: Float, spreadTo: Float, steps: Int = 30) {
        val grid = visibleGrid()
        val cx = 540f
        val cy = 1200f
        grid.performTouchInput {
            down(0, Offset(cx - spreadFrom, cy))
            down(1, Offset(cx + spreadFrom, cy))
        }
        for (i in 1..steps) {
            val d = spreadFrom + (spreadTo - spreadFrom) * i / steps
            grid.performTouchInput {
                moveTo(0, Offset(cx - d, cy))
                moveTo(1, Offset(cx + d, cy))
            }
            Thread.sleep(16)
        }
        grid.performTouchInput {
            up(0)
            up(1)
        }
        Thread.sleep(800)
    }

    @Test
    fun measurePinchFrames() = measure {
        repeat(3) {
            pinch(spreadFrom = 300f, spreadTo = 100f) // towards 6 columns
            pinch(spreadFrom = 100f, spreadTo = 300f) // back
        }
    }

    /** Plain scrolling, as a reference for what a frame of this screen costs anyway. */
    @Test
    fun measureScrollFrames() = measure {
        repeat(3) {
            visibleGrid().performTouchInput { swipeUp(durationMillis = 600) }
            Thread.sleep(800)
            visibleGrid().performTouchInput { swipeDown(durationMillis = 600) }
            Thread.sleep(800)
        }
    }

    private fun measure(block: () -> Unit) {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("media_grid").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2_000)
        val pkg = instrumentation.targetContext.packageName
        shell("dumpsys gfxinfo $pkg reset")
        block()
        val stats = shell("dumpsys gfxinfo $pkg framestats").lineSequence()
            .filter { it.startsWith("Flags") || it.matches(Regex("^[0-9]+,.*")) }
            .joinToString("\n", postfix = "\n") +
            shell("dumpsys gfxinfo $pkg").lineSequence()
            .filter { line -> listOf("Total frames", "Janky", "percentile", "Number").any { it in line } }
            .take(20)
            .joinToString("\n")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$stats\n") })
    }
}
