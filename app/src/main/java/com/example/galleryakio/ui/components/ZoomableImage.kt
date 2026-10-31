package com.example.galleryakio.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.example.galleryakio.data.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private val DOUBLE_TAP_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Above this a photo counts as zoomed in. */
internal const val ZOOMED_SCALE = 1.01f

/** Where [ZoomBox] is zoomed to. [pixelWidth] and [pixelHeight] let a photo zoom in to its own pixels. */
class ZoomState(private val pixelWidth: Int = 0, private val pixelHeight: Int = 0) {
    val scale = Animatable(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var fling: Job? = null

    val maxScale: Float
        get() {
            if (pixelWidth <= 0 || pixelHeight <= 0 || size.width == 0 || size.height == 0) return MAX_SCALE
            return maxOf(MAX_SCALE, pixelWidth.toFloat() / size.width, pixelHeight.toFloat() / size.height)
        }
}

/**
 * Pinch / double-tap zoomable photo, decoded at screen size with the grid thumbnail as placeholder.
 * [SharpTiles] brings in the file's own pixels for the part on screen while zoomed.
 */
@Composable
fun ZoomableImage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onZoomGesture: () -> Unit = {},
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
    val tiled = !item.isGif && !item.isSvg
    val zoom = remember(item.id, tiled) { if (tiled) ZoomState(item.width, item.height) else ZoomState() }
    ZoomBox(resetKey = item.id, isCurrentPage = isCurrentPage, onTap = onTap, modifier = modifier, zoom = zoom, onZoomGesture = onZoomGesture) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (tiled) SharpTiles(item.uri, zoom, isCurrentPage)
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
 * With [onDoubleTap] (videos) a double tap does that instead and only pinch zooms.
 * [onZoomGesture] runs once per pinch or drag, as soon as it moves the content.
 */
@Composable
fun ZoomBox(
    resetKey: Any,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null,
    zoom: ZoomState = remember { ZoomState() },
    onZoomGesture: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val zoomGesture by rememberUpdatedState(onZoomGesture)
    val flingDecay = rememberSplineBasedDecay<Offset>()
    val doubleTapZooms = onDoubleTap == null
    val scope = rememberCoroutineScope()
    val scale = zoom.scale
    var offset by zoom::offset
    var size by zoom::size

    // Tell the viewer whether the photo on screen is zoomed (a swipe down then pans instead of closing).
    val zoomState = LocalViewerZoom.current
    LaunchedEffect(isCurrentPage, zoomState) {
        if (isCurrentPage) snapshotFlow { scale.value > 1.01f }.collect { zoomState.zoomed = it }
    }
    // Reset zoom when the user swipes away.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            zoom.fling?.cancel()
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
            .pointerInput(resetKey, doubleTapZooms) {
                if (!doubleTapZooms) {
                    detectTapGestures(onTap = { tap() }, onDoubleTap = { doubleTap?.invoke() })
                    return@pointerInput
                }
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
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A finger that stops a fling is not a tap: the bars stay as they are.
                    if (zoom.fling?.isActive == true) down.consume()
                    zoom.fling?.cancel()
                    // Tracked here, not read back from the Animatable: snapTo runs later, so
                    // several events in one frame would otherwise all see a stale scale.
                    var current = scale.value
                    var pinched = false
                    // One finger on a zoomed photo moves it from the first pixel, but only counts
                    // as a drag past touch slop. Until then its jitter is left unconsumed, or the
                    // tap detector would treat every tap as a drag and double-tap could never
                    // zoom back out.
                    var dragging = false
                    var drift = Offset.Zero
                    val tracker = VelocityTracker()
                    do {
                        val event = awaitPointerEvent()
                        // Includes the lift, whose late timestamp reads as no speed if the finger
                        // had come to rest. With two fingers there is no speed to carry on with.
                        if (event.changes.size == 1) tracker.addPointerInputChange(event.changes[0]) else tracker.resetTracking()
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
                        if ((pressed > 1 || current > 1.01f) && !fingersChanged && centroid.isSpecified) {
                            val newScale = (current * event.calculateZoom()).coerceIn(1f, zoom.maxScale)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the pinch point under the fingers.
                            val zoomed = (offset + center - centroid) * (newScale / current) - center + centroid
                            val next = clampOffset(zoomed + event.calculatePan(), newScale)
                            // One NaN here would stick (NaN survives every later clamp) and
                            // leave the photo invisible, i.e. a black screen.
                            if (newScale.isFinite() && next.isFinite && (newScale != current || next != offset)) {
                                if (newScale != current) scope.launch { scale.snapTo(newScale) }
                                current = newScale
                                offset = next
                                if (dragging && !pinched) {
                                    pinched = true
                                    zoomGesture()
                                }
                            }
                            if (dragging) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // Only after a real pinch: a plain tap ending here would otherwise cancel
                    // the double-tap zoom that starts on the very same finger lift.
                    if (pinched && current <= 1.01f) {
                        offset = Offset.Zero
                        scope.launch { scale.animateTo(1f) }
                    } else if (pinched) {
                        val velocity = tracker.calculateVelocity()
                        zoom.fling = scope.launch {
                            // The photo carries on from the finger and slows to a stop at the edges.
                            Animatable(offset, Offset.VectorConverter).animateDecay(Offset(velocity.x, velocity.y), flingDecay) {
                                val clamped = clampOffset(value, scale.value)
                                if (clamped == offset && clamped != value) zoom.fling?.cancel()
                                offset = clamped
                            }
                        }
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
