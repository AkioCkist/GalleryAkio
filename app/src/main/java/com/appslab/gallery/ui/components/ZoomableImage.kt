package com.appslab.gallery.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/**
 * Pinch / double-tap zoomable image. Single-finger drags pass through to the
 * parent pager while not zoomed, so swiping between photos keeps working.
 */
@Composable
fun ZoomableImage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

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

    val request = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(item.uri)
            // Decode at screen size; Coil keeps the thumbnail as placeholder meanwhile.
            .precision(Precision.INEXACT)
            .placeholderMemoryCacheKey("thumb:${item.id}")
            .build()
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                offset = Offset.Zero
                                scale.animateTo(1f)
                            } else {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                                scale.animateTo(DOUBLE_TAP_SCALE)
                            }
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multiTouch = event.changes.size > 1
                        if (multiTouch || scale.value > 1.01f) {
                            val newScale = (scale.value * zoom).coerceIn(1f, MAX_SCALE)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the pinch point under the fingers.
                            val zoomed = (offset + center - centroid) * (newScale / scale.value) - center + centroid
                            offset = clampOffset(zoomed + pan, newScale)
                            scope.launch { scale.snapTo(newScale) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.x
                translationY = offset.y
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
