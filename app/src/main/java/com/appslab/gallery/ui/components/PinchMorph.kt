package com.appslab.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslab.gallery.data.GridStyle
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.ui.theme.CqMono
import kotlin.math.floor
import kotlin.math.roundToInt

/** Something drawn during a pinch: a photo or a date header. Same list for every column count. */
internal class MorphNode(val key: String, val item: MediaItem?, val label: String?)

/**
 * Pixel rects (content coordinates: y = 0 is the top of the scrollable content) of every
 * [MorphNode] for one column count. Stored flat: left, top, right, bottom per node.
 */
internal class MorphGeometry(val rects: FloatArray) {
    fun left(i: Int) = rects[i * 4]
    fun top(i: Int) = rects[i * 4 + 1]
    fun right(i: Int) = rects[i * 4 + 2]
    fun bottom(i: Int) = rects[i * 4 + 3]
    fun height(i: Int) = bottom(i) - top(i)

    /** First node whose bottom is below [y]; rects are sorted by top, so binary search. */
    fun firstBelow(y: Float, count: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (bottom(mid) < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun lastAbove(y: Float, count: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (top(mid) > y) hi = mid - 1 else lo = mid
        }
        return lo
    }
}

/**
 * Samsung-Gallery-style pinch: every photo moves and resizes continuously between the
 * layout for ⌊level⌋ columns and the one for ⌈level⌉ columns, following the fingers.
 * The photo that was under the fingers when the pinch began stays under them.
 */
internal class MorphState(
    val nodes: List<MorphNode>,
    private val geometryFor: (Int) -> MorphGeometry,
    val anchor: Int,
    /** Where inside the anchor the fingers were, as a fraction of its height. */
    private val anchorFraction: Float,
    startLevel: Float,
    startFingerY: Float,
) {
    var level by mutableFloatStateOf(startLevel)
    var fingerY by mutableFloatStateOf(startFingerY)
    private val cache = HashMap<Int, MorphGeometry>()

    fun geometry(columns: Int): MorphGeometry = cache.getOrPut(columns) { geometryFor(columns) }

    /** Scroll offset that keeps the anchor under the fingers in the layout for [columns]. */
    fun scrollFor(columns: Int): Float {
        val g = geometry(columns)
        return g.top(anchor) - (fingerY - anchorFraction * g.height(anchor))
    }

    val from get() = floor(level).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    val to get() = (from + 1).coerceAtMost(MAX_COLUMNS)
    val t get() = (level - from).coerceIn(0f, 1f)

    /** Index range of nodes visible in either layout. Contiguous, because node order never changes. */
    fun visibleRange(viewportHeight: Float): IntRange {
        val n = nodes.size
        if (n == 0) return IntRange.EMPTY
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (c in intArrayOf(from, to)) {
            val g = geometry(c)
            val s = scrollFor(c)
            first = minOf(first, g.firstBelow(s, n))
            last = maxOf(last, g.lastAbove(s + viewportHeight, n))
        }
        return first..last
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * t

    fun left(i: Int) = lerp(geometry(from).left(i), geometry(to).left(i))
    fun top(i: Int) = lerp(geometry(from).top(i) - scrollFor(from), geometry(to).top(i) - scrollFor(to))
    fun right(i: Int) = lerp(geometry(from).right(i), geometry(to).right(i))
    fun bottom(i: Int) = lerp(geometry(from).bottom(i) - scrollFor(from), geometry(to).bottom(i) - scrollFor(to))
}

/**
 * Draws the morphing grid. Positions are read in the layout phase only, so a frame of
 * pinching re-places the visible tiles without recomposing them.
 */
@Composable
internal fun MorphLayer(
    state: MorphState,
    viewportHeight: Float,
    style: GridStyle,
    darkMode: Boolean,
    thumbnailPx: Int,
    modifier: Modifier = Modifier,
) {
    val range by remember(state, viewportHeight) { derivedStateOf { state.visibleRange(viewportHeight) } }
    val shape = RoundedCornerShape(style.cornerRadius.dp)
    val tileColor = if (darkMode) Color(0xFF2A2A2A) else Color(0xFFEEEEEE)
    val borderColor = if (darkMode) Color(0xFF404040) else Color(0xFFBDBDBD)
    val headerColor = if (darkMode) Color.White else Color(0xFF212121)

    Box(modifier.fillMaxSize()) {
        for (i in range) {
            val node = state.nodes[i]
            key(node.key) {
                // Report a 0×0 size so the parent never clamps or centres us, then place
                // the content at its interpolated position.
                val place = Modifier.layout { measurable, constraints ->
                    val l = state.left(i)
                    val t = state.top(i)
                    val w = (state.right(i) - l).roundToInt().coerceAtLeast(1)
                    val h = (state.bottom(i) - t).roundToInt().coerceAtLeast(1)
                    val p = measurable.measure(Constraints.fixed(w, h))
                    layout(constraints.minWidth, constraints.minHeight) { p.place(l.roundToInt(), t.roundToInt()) }
                }
                if (node.item != null) {
                    Box(
                        place
                            .clip(shape)
                            .background(tileColor)
                            .let { if (style.border) it.border(1.dp, borderColor, shape) else it },
                    ) {
                        MediaThumbnail(node.item, thumbnailPx, Modifier.fillMaxSize())
                    }
                } else {
                    Box(place, contentAlignment = Alignment.Center) {
                        Text(node.label.orEmpty(), color = headerColor, fontSize = 20.sp, fontFamily = CqMono, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
