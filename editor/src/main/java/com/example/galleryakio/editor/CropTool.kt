package com.example.galleryakio.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MIN_CROP = 0.08f
private val RATIOS = listOf("Free" to null, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "4:3" to 4f / 3f, "9:16" to 9f / 16f, "16:9" to 16f / 9f)

/**
 * The whole picture with a crop frame on it. Straighten and skew are a canvas matrix over one
 * bitmap, so the dial moves it on the GPU without rendering anything.
 */
@Composable
internal fun CropCanvas(
    picture: Bitmap,
    recipe: EditRecipe,
    ratio: Float?,
    showGrid: Boolean,
    onCrop: (CropFraction) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val grab = with(density) { 36.dp.toPx() }
    val latest by rememberUpdatedState(recipe.crop)
    val latestRatio by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onCrop)
    val done by rememberUpdatedState(onCommit)
    val colors = remember(recipe.preset, recipe.presetStrength, recipe.adjust) { android.graphics.ColorMatrixColorFilter(recipe.colorMatrix()) }
    val paint = remember(colors) { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = colors } }
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer { }
            .pointerInput(picture.width, picture.height) {
                val inset = 20.dp.toPx()
                var edges = Edges()
                detectDragGestures(
                    onDragStart = { at ->
                        val area = fitted(picture, size.width.toFloat(), size.height.toFloat(), inset)
                        edges = Edges.at(at, latest.toRect(area), grab)
                    },
                    onDragEnd = { done() },
                    onDragCancel = { done() },
                ) { pointer, drag ->
                    pointer.consume()
                    val area = fitted(picture, size.width.toFloat(), size.height.toFloat(), inset)
                    change(latest.dragged(edges, drag.x / area.width, drag.y / area.height, latestRatio?.let { it * area.height / area.width }))
                }
            },
    ) {
        val area = fitted(picture, size.width, size.height, 20.dp.toPx())
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            // Straightened, the picture reaches past its own area.
            native.clipRect(area.left, area.top, area.right, area.bottom)
            native.translate(area.left, area.top)
            native.scale(area.width / picture.width, area.height / picture.height)
            recipe.geometryMatrix(picture.width, picture.height)?.let { native.concat(it) }
            native.drawBitmap(picture, Matrix(), paint)
            native.restore()
        }
        val frame = recipe.crop.toRect(area)
        // Dimmed outside the frame.
        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = area.topLeft, size = area.size)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipRect(frame.left, frame.top, frame.right, frame.bottom)
            native.translate(area.left, area.top)
            native.scale(area.width / picture.width, area.height / picture.height)
            recipe.geometryMatrix(picture.width, picture.height)?.let { native.concat(it) }
            native.drawBitmap(picture, Matrix(), paint)
            native.restore()
        }
        val line = Color.White.copy(alpha = 0.55f)
        if (showGrid) {
            for (i in 1..2) {
                val x = frame.left + frame.width * i / 3f
                val y = frame.top + frame.height * i / 3f
                drawLine(line, Offset(x, frame.top), Offset(x, frame.bottom), 1.dp.toPx())
                drawLine(line, Offset(frame.left, y), Offset(frame.right, y), 1.dp.toPx())
            }
        }
        drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
        val arm = 22.dp.toPx()
        val thick = 3.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + arm * dx, y), thick)
            drawLine(Color.White, Offset(x, y), Offset(x, y + arm * dy), thick)
        }
        corner(frame.left, frame.top, 1f, 1f)
        corner(frame.right, frame.top, -1f, 1f)
        corner(frame.left, frame.bottom, 1f, -1f)
        corner(frame.right, frame.bottom, -1f, -1f)
    }
}

/** Which sides a drag moves: all four to move the whole frame. */
private data class Edges(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    companion object {
        fun at(at: Offset, frame: Rect, grab: Float): Edges {
            val left = abs(at.x - frame.left) < grab
            val right = abs(at.x - frame.right) < grab && !left
            val top = abs(at.y - frame.top) < grab
            val bottom = abs(at.y - frame.bottom) < grab && !top
            val inX = at.x > frame.left - grab && at.x < frame.right + grab
            val inY = at.y > frame.top - grab && at.y < frame.bottom + grab
            val edges = Edges(left && inY, top && inX, right && inY, bottom && inX)
            return if (edges == Edges()) Edges(true, true, true, true) else edges
        }
    }
}

private fun CropFraction.dragged(edges: Edges, dx: Float, dy: Float, ratio: Float?): CropFraction {
    if (edges.left && edges.top && edges.right && edges.bottom) {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return CropFraction(left + x, top + y, right + x, bottom + y)
    }
    var l = if (edges.left) (left + dx).coerceIn(0f, right - MIN_CROP) else left
    var t = if (edges.top) (top + dy).coerceIn(0f, bottom - MIN_CROP) else top
    var r = if (edges.right) (right + dx).coerceIn(left + MIN_CROP, 1f) else right
    var b = if (edges.bottom) (bottom + dy).coerceIn(top + MIN_CROP, 1f) else bottom
    if (ratio != null) {
        // Follows the width, growing away from the side held still, and stops at the picture's edge.
        var w = r - l
        var h = w / ratio
        val roomY = if (edges.top) b else 1f - t
        if (h > roomY) {
            h = roomY
            w = h * ratio
        }
        if (edges.left) l = r - w else r = l + w
        if (edges.top) t = b - h else b = t + h
    }
    return CropFraction(l, t, r, b)
}

/** The largest frame of [ratio] (of fractions, not pixels) centred in the picture. */
internal fun centeredCrop(ratio: Float): CropFraction {
    val w = min(1f, ratio)
    val h = min(1f, 1f / ratio)
    return CropFraction((1f - w) / 2f, (1f - h) / 2f, (1f + w) / 2f, (1f + h) / 2f)
}

private fun fitted(picture: Bitmap, width: Float, height: Float, inset: Float): Rect {
    val w = max(1f, width - inset * 2f)
    val h = max(1f, height - inset * 2f)
    val scale = min(w / picture.width, h / picture.height)
    val fw = picture.width * scale
    val fh = picture.height * scale
    return Rect(Offset((width - fw) / 2f, (height - fh) / 2f), Size(fw, fh))
}

private fun CropFraction.toRect(area: Rect) = Rect(
    area.left + left * area.width,
    area.top + top * area.height,
    area.left + right * area.width,
    area.top + bottom * area.height,
)

/** Flip, turn and ratio, floating over the bottom of the frame as on Samsung. */
@Composable
internal fun CropActions(
    ratioLabel: String,
    onFlip: () -> Unit,
    onTurn: () -> Unit,
    onRatio: (Float?, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.height(48.dp).liquid(RoundedCornerShape(50)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIcon("Flip", onFlip) { Icon(Icons.Rounded.Flip, "Flip", tint = Color.White, modifier = Modifier.size(20.dp)) }
        PillIcon("Rotate", onTurn) { Icon(Icons.Rounded.Rotate90DegreesCcw, "Rotate", tint = Color.White, modifier = Modifier.size(20.dp).graphicsLayer { scaleX = -1f }) }
        Box {
            PillIcon("Aspect ratio", { menu = true }) {
                Text(ratioLabel, color = Color.White, fontSize = 12.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                RATIOS.forEach { (label, value) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menu = false
                        onRatio(value, label)
                    })
                }
            }
        }
    }
}

@Composable
private fun PillIcon(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
