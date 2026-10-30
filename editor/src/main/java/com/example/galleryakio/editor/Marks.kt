package com.example.galleryakio.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Immutable

internal enum class PenKind(val alpha: Int, val widthScale: Float, val square: Boolean) {
    PEN(255, 1f, false),
    MARKER(220, 2.2f, false),
    HIGHLIGHTER(110, 4f, true),
}

internal enum class TextStyleKind(val typeface: Typeface) {
    REGULAR(Typeface.DEFAULT),
    LIGHT(Typeface.create("sans-serif-light", Typeface.NORMAL)),
    BOLD(Typeface.DEFAULT_BOLD),
    SERIF(Typeface.SERIF),
    ITALIC(Typeface.create(Typeface.SERIF, Typeface.ITALIC)),
    MONO(Typeface.MONOSPACE),
    CURSIVE(Typeface.create("cursive", Typeface.NORMAL)),
}

/** Drawn over the finished picture; every length is a fraction of its width, or height for y. */
@Immutable
internal sealed interface Mark

@Immutable
internal data class StrokeMark(val points: List<Float>, val color: Int, val width: Float, val kind: PenKind) : Mark

@Immutable
internal data class TextMark(
    val id: Long,
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0.08f,
    val color: Int = android.graphics.Color.WHITE,
    val style: TextStyleKind = TextStyleKind.REGULAR,
    val align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
    val background: Boolean = false,
) : Mark

/** Same code for the preview and the export, so they can't differ. */
internal fun List<Mark>.drawOn(canvas: Canvas, width: Float, height: Float) {
    forEach { mark ->
        when (mark) {
            is StrokeMark -> mark.draw(canvas, width, height)
            is TextMark -> mark.draw(canvas, width, height)
        }
    }
}

private fun StrokeMark.draw(canvas: Canvas, width: Float, height: Float) {
    if (points.size < 2) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = if (kind.square) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeWidth = this@draw.width * kind.widthScale * width
        color = this@draw.color
        alpha = kind.alpha
    }
    val path = Path()
    path.moveTo(points[0] * width, points[1] * height)
    if (points.size == 2) path.lineTo(points[0] * width + 0.1f, points[1] * height)
    var i = 2
    while (i + 1 < points.size) {
        // Through the midpoints, which turns a finger's corners into curves.
        val px = points[i - 2] * width
        val py = points[i - 1] * height
        val x = points[i] * width
        val y = points[i + 1] * height
        path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
        i += 2
    }
    canvas.drawPath(path, paint)
}

private fun TextMark.layout(width: Float): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@layout.color
        textSize = size * width
        typeface = this@layout.style.typeface
    }
    val wrap = (width * 0.92f).toInt().coerceAtLeast(1)
    return if (Build.VERSION.SDK_INT >= 23) {
        StaticLayout.Builder.obtain(text, 0, text.length, paint, wrap).setAlignment(align).build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, wrap, align, 1f, 0f, false)
    }
}

private fun StaticLayout.usedWidth(): Float = (0 until lineCount).maxOfOrNull { getLineWidth(it) } ?: 0f

private fun TextMark.draw(canvas: Canvas, width: Float, height: Float) {
    if (text.isBlank()) return
    val layout = layout(width)
    val used = layout.usedWidth()
    canvas.save()
    canvas.translate(x * width - layout.width / 2f, y * height - layout.height / 2f)
    if (background) {
        val pad = size * width * 0.3f
        val left = when (align) {
            Layout.Alignment.ALIGN_CENTER -> (layout.width - used) / 2f
            Layout.Alignment.ALIGN_OPPOSITE -> layout.width - used
            else -> 0f
        }
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(170, 0, 0, 0) }
        canvas.drawRoundRect(RectF(left - pad, -pad / 2f, left + used + pad, layout.height + pad / 2f), pad, pad, plate)
    }
    layout.draw(canvas)
    canvas.restore()
}

/** Where a text sits, as fractions of the picture: what a finger has to hit. */
internal fun TextMark.bounds(width: Float, height: Float): RectF {
    val layout = layout(width)
    val halfW = layout.usedWidth() / 2f / width
    val halfH = layout.height / 2f / height
    return RectF(x - halfW, y - halfH, x + halfW, y + halfH)
}
