package com.appslab.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives a real two-finger pinch on the Photos grid in slow, real-time steps so the
 * morph can be watched or screen-recorded. Needs media permission already granted.
 */
@RunWith(AndroidJUnit4::class)
class PinchZoomTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun pinch(spreadFrom: Float, spreadTo: Float, steps: Int = 40) {
        val grid = rule.onAllNodesWithTag("media_grid")[0]
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
            Thread.sleep(25)
        }
        grid.performTouchInput {
            up(0)
            up(1)
        }
    }

    @Test
    fun pinchOutThenIn() {
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("media_grid").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2_000)
        pinch(spreadFrom = 80f, spreadTo = 420f) // spread: bigger photos, fewer columns
        Thread.sleep(1_500)
        pinch(spreadFrom = 420f, spreadTo = 60f) // pinch: smaller photos, more columns
        Thread.sleep(1_500)
        pinch(spreadFrom = 400f, spreadTo = 120f)
        Thread.sleep(1_500)
    }
}
