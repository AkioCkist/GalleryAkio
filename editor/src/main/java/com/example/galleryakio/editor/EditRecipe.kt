package com.example.galleryakio.editor

import android.graphics.ColorMatrix
import androidx.compose.runtime.Immutable
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The crop, as fractions of the turned and flipped picture. */
@Immutable
internal data class CropFraction(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
    val width get() = right - left
    val height get() = bottom - top
}

/** All 0 by default. [Param] holds each one's range. */
@Immutable
internal data class Adjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
) {
    /** Needs a pass over the pixels on the GPU; the rest is one colour matrix. */
    val hasPixelEffects get() = shadows != 0f || highlights != 0f || sharpen != 0f || vignette != 0f
}

internal enum class Param(
    val label: String,
    val min: Float,
    val get: (Adjustments) -> Float,
    val set: (Adjustments, Float) -> Adjustments,
    /** Can't be done on a video frame by a colour matrix, so images only. */
    val imageOnly: Boolean = false,
) {
    EXPOSURE("Exposure", -1f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    BRIGHTNESS("Brightness", -1f, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    CONTRAST("Contrast", -1f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    SATURATION("Saturation", -1f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    TEMPERATURE("Warmth", -1f, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    TINT("Tint", -1f, { it.tint }, { a, v -> a.copy(tint = v) }),
    HUE("Hue", -1f, { it.hue }, { a, v -> a.copy(hue = v) }),
    SHADOWS("Shadows", 0f, { it.shadows }, { a, v -> a.copy(shadows = v) }, imageOnly = true),
    HIGHLIGHTS("Highlights", 0f, { it.highlights }, { a, v -> a.copy(highlights = v) }, imageOnly = true),
    SHARPEN("Sharpen", 0f, { it.sharpen }, { a, v -> a.copy(sharpen = v) }, imageOnly = true),
    VIGNETTE("Vignette", 0f, { it.vignette }, { a, v -> a.copy(vignette = v) }, imageOnly = true),
}

/** Colour-matrix looks only, so every one of them works on video too. */
internal val PRESETS = listOf(
    "Original" to Adjustments(),
    "Vivid" to Adjustments(contrast = 0.15f, saturation = 0.35f),
    "Warm" to Adjustments(temperature = 0.45f, saturation = 0.1f),
    "Cool" to Adjustments(temperature = -0.45f),
    "Fade" to Adjustments(contrast = -0.25f, brightness = 0.12f, saturation = -0.2f),
    "Sepia" to Adjustments(saturation = -0.8f, temperature = 0.7f, tint = -0.1f),
    "B&W" to Adjustments(saturation = -1f, contrast = 0.15f),
    "Noir" to Adjustments(saturation = -1f, contrast = 0.5f, exposure = -0.1f),
)

/**
 * Every edit, in the order it's applied: turn, flip, crop, pixel effects, colour. Nothing is
 * rendered until asked for: the preview does it on a small copy, the export on the original,
 * and undo only has to keep a list of these.
 */
@Immutable
internal data class EditRecipe(
    val turns: Int = 0,
    val flipped: Boolean = false,
    val crop: CropFraction = CropFraction(),
    val adjust: Adjustments = Adjustments(),
) {
    /** A quarter turn clockwise of the picture as it looks now, keeping the crop on the same part. */
    fun turned(): EditRecipe = copy(
        // A flip is applied after the turn, so on a flipped picture a turn runs the other way.
        turns = if (flipped) (turns + 3) % 4 else (turns + 1) % 4,
        crop = CropFraction(1f - crop.bottom, crop.left, 1f - crop.top, crop.right),
    )

    fun flippedOver(): EditRecipe = copy(
        flipped = !flipped,
        crop = crop.copy(left = 1f - crop.right, right = 1f - crop.left),
    )
}

/** 4x5, with offsets in the 0..255 scale, as [ColorMatrix] has it. */
internal fun Adjustments.colorMatrix(): ColorMatrix {
    val out = ColorMatrix()
    if (exposure != 0f) {
        val gain = 2f.pow(exposure * 1.5f)
        out.postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
    }
    if (temperature != 0f || tint != 0f) {
        val warm = temperature * 0.25f
        out.postConcat(ColorMatrix().apply { setScale(1f + warm, 1f - tint * 0.2f, 1f - warm, 1f) })
    }
    if (saturation != 0f) out.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
    if (hue != 0f) out.postConcat(hueMatrix(hue * 180f))
    if (contrast != 0f || brightness != 0f) {
        val c = 1f + contrast * (if (contrast > 0f) 1f else 0.7f)
        val shift = 127.5f * (1f - c) + brightness * 70f
        out.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, shift,
                    0f, c, 0f, 0f, shift,
                    0f, 0f, c, 0f, shift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    return out
}

/** The usual hue rotation, which keeps luminance. */
private fun hueMatrix(degrees: Float): ColorMatrix {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val r = 0.213f
    val g = 0.715f
    val b = 0.072f
    return ColorMatrix(
        floatArrayOf(
            r + c * (1 - r) - s * r, g - c * g - s * g, b - c * b + s * (1 - b), 0f, 0f,
            r - c * r + s * 0.143f, g + c * (1 - g) + s * 0.140f, b - c * b - s * 0.283f, 0f, 0f,
            r - c * r - s * (1 - r), g - c * g + s * g, b + c * (1 - b) + s * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Undo and redo over recipes. [commit] makes a step; a slider being dragged only calls [preview]. */
internal class History<T>(initial: T, private val onChange: (T) -> Unit) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var committed = initial

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun preview(value: T) = onChange(value)

    fun commit(value: T) {
        if (value == committed) {
            onChange(value)
            return
        }
        past.addLast(committed)
        if (past.size > MAX_STEPS) past.removeFirst()
        future.clear()
        committed = value
        onChange(value)
    }

    fun undo() {
        val previous = past.removeLastOrNull() ?: return
        future.addLast(committed)
        committed = previous
        onChange(previous)
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        past.addLast(committed)
        committed = next
        onChange(next)
    }

    private companion object {
        const val MAX_STEPS = 100
    }
}
