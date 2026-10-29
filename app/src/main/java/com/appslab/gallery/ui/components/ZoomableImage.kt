package com.appslab.gallery.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.lerp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.appslab.gallery.data.MediaItem
import kotlinx.coroutines.launch

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private val DOUBLE_TAP_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Pinch / double-tap zoomable photo, decoded at screen size with the grid thumbnail as placeholder. */
@Composable
fun ZoomableImage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(item.uri)
            // Decode at screen size; Coil keeps the thumbnail as placeholder meanwhile.
            .precision(Precision.INEXACT)
            // Whatever size the grid it was opened from decoded.
            .placeholderMemoryCacheKey(cachedThumbnailKey(context, item.id))
            .build()
    }
    ZoomBox(resetKey = item.id, isCurrentPage = isCurrentPage, onTap = onTap, modifier = modifier) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Whether the viewer's current page is zoomed in; written by [ZoomBox], read by the viewer's gestures. */
class ViewerZoom {
    var zoomed = false
}

val LocalViewerZoom = staticCompositionLocalOf { ViewerZoom() }

/**
 * Pinch / double-tap zoom for any content (photos and videos). Single-finger drags pass
 * through to the parent pager while not zoomed, so swiping between items keeps working.
 */
@Composable
fun ZoomBox(
    resetKey: Any,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Tell the viewer whether the photo on screen is zoomed (a swipe down then pans instead of closing).
    val zoomState = LocalViewerZoom.current
    LaunchedEffect(isCurrentPage, zoomState) {
        if (isCurrentPage) snapshotFlow { scale.value > 1.01f }.collect { zoomState.zoomed = it }
    }
    // Reset zoom when the user swipes away.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            scale.snapTo(1f)
            offset = Offset.Zero
        }
    }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1f)) / 2f
        val maxY = (size.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { tap ->
                        val zoomIn = scale.value <= 1.05f
                        val target = if (zoomIn) DOUBLE_TAP_SCALE else 1f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val targetOffset = if (zoomIn) clampOffset((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE) else Offset.Zero
                        val startScale = scale.value
                        val startOffset = offset
                        scope.launch {
                            // Move and scale as one motion: setting the offset up front made the
                            // photo jump sideways first and only then start growing.
                            scale.animateTo(target, DOUBLE_TAP_SPEC) {
                                val t = if (target == startScale) 1f else (value - startScale) / (target - startScale)
                                offset = lerp(startOffset, targetOffset, t)
                            }
                        }
                    },
                )
            }
            .pointerInput(resetKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Tracked here, not read back from the Animatable: snapTo runs later, so
                    // several events in one frame would otherwise all see a stale scale.
                    var current = scale.value
                    var pinched = false
                    // One finger on a zoomed photo only starts panning past touch slop. Until
                    // then its jitter is left unconsumed, or the tap detector would treat every
                    // tap as a drag and double-tap could never zoom back out.
                    var dragging = false
                    var drift = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // A finger landing or lifting moves the centroid by half the spread;
                        // treating that as a pan makes the photo jump.
                        val fingersChanged = event.changes.any { it.pressed != it.previousPressed }
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (pressed > 1) {
                            dragging = true
                        } else if (!dragging && current > 1.01f && !fingersChanged) {
                            drift += event.calculatePan()
                            dragging = drift.getDistance() > viewConfiguration.touchSlop
                        }
                        if (dragging && (pressed > 1 || current > 1.01f) && !fingersChanged && centroid.isSpecified) {
                            val newScale = (current * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the pinch point under the fingers.
                            val zoomed = (offset + center - centroid) * (newScale / current) - center + centroid
                            val next = clampOffset(zoomed + event.calculatePan(), newScale)
                            // One NaN here would stick (NaN survives every later clamp) and
                            // leave the photo invisible, i.e. a black screen.
                            if (newScale.isFinite() && next.isFinite && (newScale != current || next != offset)) {
                                current = newScale
                                offset = next
                                pinched = true
                                scope.launch { scale.snapTo(newScale) }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // Only after a real pinch: a plain tap ending here would otherwise cancel
                    // the double-tap zoom that starts on the very same finger lift.
                    if (pinched && current <= 1.01f) {
                        offset = Offset.Zero
                        scope.launch { scale.animateTo(1f) }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
