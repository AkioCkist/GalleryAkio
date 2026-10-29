package com.appslab.gallery

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens photos from the Photos grid and goes back, a few times, and reports the frame stats
 * of just that (`dumpsys gfxinfo`). Slow enough to screen-record. Needs media permission.
 */
@RunWith(AndroidJUnit4::class)
class ViewerTransitionTest {
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

    @Test
    fun openAndClose() {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("media_grid").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2_000)
        val pkg = instrumentation.targetContext.packageName
        shell("dumpsys gfxinfo $pkg reset")
        // Tiles at different spots, so the motion starts from different places.
        for (spot in listOf(Offset(0.85f, 0.16f), Offset(0.5f, 0.52f), Offset(0.5f, 0.72f))) {
            visibleGrid().performTouchInput { click(Offset(width * spot.x, height * spot.y)) }
            Thread.sleep(1_500)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(1_500)
        }
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
