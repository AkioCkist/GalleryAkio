package com.appslab.gallery.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import com.appslab.gallery.ui.theme.CqMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import androidx.compose.foundation.layout.wrapContentWidth
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val THUMB_HEIGHT = 56.dp
private val DROP_WIDTH = 24.dp
private val TOUCH_WIDTH = 48.dp
private const val JUMP_INTERVAL_MS = 100L

/**
 * A clear glass capsule, like a drop of water on the screen: a faint body, a bright rim
 * that fades downwards, a specular highlight and a soft shadow. Drawn in one pass with no
 * backdrop, so it costs nothing extra on the frames it moves with the scroll. [tint]
 * colours the body (while held).
 */
@Composable
private fun GlassDrop(tint: () -> Color?, modifier: Modifier) {
    Spacer(
        modifier.drawBehind {
            val r = CornerRadius(size.width / 2)
            val rim = 1.dp.toPx()
            // Soft shadow below.
            drawRoundRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, 2.dp.toPx()), size = size, cornerRadius = r)
            // Body: mostly clear, a little milky at the top.
            val color = tint()
            drawRoundRect(
                Brush.verticalGradient(
                    if (color != null) listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.55f))
                    else listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.12f)),
                ),
                size = size, cornerRadius = r,
            )
            // Rim: bright at the top where light catches it, faint at the bottom.
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.25f))),
                topLeft = Offset(rim / 2, rim / 2),
                size = Size(size.width - rim, size.height - rim),
                cornerRadius = CornerRadius(size.width / 2 - rim / 2),
                style = Stroke(rim),
            )
            // Specular highlight near the top-left.
            drawOval(
                Color.White.copy(alpha = 0.55f),
                topLeft = Offset(size.width * 0.22f, size.height * 0.1f),
                size = Size(size.width * 0.28f, size.height * 0.22f),
            )
        },
    )
}

/**
 * Fast-scroll thumb along the right edge: appears while the list scrolls, and dragging it
 * jumps anywhere from the first photo to the last, with the date shown beside it.
 *
 * Positions are only read while placing and drawing, so scrolling moves the thumb without
 * recomposing anything; only the date bubble recomposes, and only while dragging.
 *
 * [progress] is how far the list is scrolled (0 to 1), [onDrag] scrolls it to a fraction,
 * [label] names the spot at a fraction (e.g. its date), and [enabled] hides the thumb on
 * lists too short to need it.
 */
@Composable
fun FastScroller(
    progress: () -> Float,
    scrolling: () -> Boolean,
    enabled: () -> Boolean,
    onDrag: (Float) -> Unit,
    label: (Float) -> String?,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestProgress by rememberUpdatedState(progress)
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackPx by remember { mutableIntStateOf(0) }
    val alpha = remember { Animatable(0f) }
    // Jump the list at most every JUMP_INTERVAL_MS. Each jump composes a whole screen of new
    // rows; doing it on every drag event fell behind the finger. The thumb and the date
    // follow every event, and letting go jumps to the exact spot.
    LaunchedEffect(Unit) {
        snapshotFlow { if (dragging) dragFraction else null }.conflate().collect { f ->
            if (f != null) {
                latestDrag(f)
                delay(JUMP_INTERVAL_MS)
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { (scrolling() || dragging) && enabled() }.collectLatest { active ->
            if (active) alpha.animateTo(1f, tween(120))
            else {
                delay(1200)
                alpha.animateTo(0f, tween(250))
            }
        }
    }
    // Composed only while visible, so a hidden thumb never blocks taps on the tiles under it.
    val shown by remember { derivedStateOf { alpha.value > 0f } }

    Box(
        modifier
            .fillMaxHeight()
            .width(TOUCH_WIDTH)
            .padding(top = topInset, bottom = bottomInset)
            .onSizeChanged { trackPx = it.height },
    ) {
        if (!shown) return@Box
        val thumbOffset = { thumbPx: Int ->
            val f = if (dragging) dragFraction else latestProgress()
            IntOffset(0, ((trackPx - thumbPx) * f.coerceIn(0f, 1f)).roundToInt())
        }
        if (dragging) {
            DateBubble({ label(dragFraction) }, Modifier.align(Alignment.TopEnd).offset { thumbOffset(THUMB_HEIGHT.roundToPx()) })
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { thumbOffset(THUMB_HEIGHT.roundToPx()) }
                .graphicsLayer { this.alpha = alpha.value }
                .size(TOUCH_WIDTH, THUMB_HEIGHT)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFraction = latestProgress().coerceIn(0f, 1f)
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val range = (trackPx - THUMB_HEIGHT.toPx()).coerceAtLeast(1f)
                            dragFraction = (dragFraction + dy / range).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragging = false
                            latestDrag(dragFraction)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            val tint = MaterialTheme.colorScheme.primary
            GlassDrop(
                tint = { if (dragging) tint else null },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .graphicsLayer {
                        // Swells a little while held, without re-laying anything out.
                        val s = if (dragging) 1.12f else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .size(DROP_WIDTH, THUMB_HEIGHT),
            )
        }
    }
}

/** The date at the thumb, to its left; a separate scope, so only it recomposes per drag event. */
@Composable
private fun DateBubble(text: () -> String?, modifier: Modifier) {
    val value = text() ?: return
    // Wider than the scroller's own strip: let it spill out to the left.
    Box(
        modifier.wrapContentWidth(Alignment.End, unbounded = true).padding(end = TOUCH_WIDTH + 4.dp).height(THUMB_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value,
            fontFamily = CqMono,
            fontSize = 13.sp,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** How far [this] list is scrolled, 0 at the top and 1 at the bottom; cheap enough to read per frame. */
fun LazyListState.scrollFraction(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    if (!canScrollForward) return 1f
    val first = visible.first()
    val within = (-first.offset).toFloat() / first.size.coerceAtLeast(1)
    return ((first.index + within) / (info.totalItemsCount - visible.size).coerceAtLeast(1)).coerceIn(0f, 1f)
}

fun LazyStaggeredGridState.scrollFraction(): Float {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0f
    if (!canScrollForward) return 1f
    val first = visible.first()
    val within = (-first.offset.y).toFloat() / first.size.height.coerceAtLeast(1)
    return ((first.index + within) / (info.totalItemsCount - visible.size).coerceAtLeast(1)).coerceIn(0f, 1f)
}
