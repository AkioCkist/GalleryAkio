package com.example.galleryakio.editor

import android.graphics.ColorMatrix
import android.graphics.Matrix
import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** The crop, as fractions of the picture after turning, flipping, straightening and skewing. */
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
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
    val sharpen: Float = 0f,
    val vignette: Float = 0f,
) {
    /** The part of a filter that shows at [strength]: filters only use the colour-matrix values. */
    fun scaled(strength: Float) = Adjustments(
        exposure = exposure * strength,
        brightness = brightness * strength,
        contrast = contrast * strength,
        saturation = saturation * strength,
        temperature = temperature * strength,
        tint = tint * strength,
        hue = hue * strength,
    )
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
    HIGHLIGHTS("Highlights", -1f, { it.highlights }, { a, v -> a.copy(highlights = v) }, imageOnly = true),
    SHADOWS("Shadows", -1f, { it.shadows }, { a, v -> a.copy(shadows = v) }, imageOnly = true),
    SATURATION("Saturation", -1f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    TEMPERATURE("Temperature", -1f, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    TINT("Tint", -1f, { it.tint }, { a, v -> a.copy(tint = v) }),
    HUE("Hue", -1f, { it.hue }, { a, v -> a.copy(hue = v) }),
    SHARPEN("Sharpness", 0f, { it.sharpen }, { a, v -> a.copy(sharpen = v) }, imageOnly = true),
    VIGNETTE("Vignette", 0f, { it.vignette }, { a, v -> a.copy(vignette = v) }, imageOnly = true),
}

/** Colour-matrix looks only, so every one of them works on video too. */
internal val PRESETS = listOf(
    "Original" to Adjustments(),
    "Vivid" to Adjustments(contrast = 0.15f, saturation = 0.35f),
    "Warm" to Adjustments(temperature = 0.45f, saturation = 0.1f),
    "Cool" to Adjustments(temperature = -0.45f),
    "Soft" to Adjustments(contrast = -0.15f, brightness = 0.1f, tint = -0.1f),
    "Fade" to Adjustments(contrast = -0.25f, brightness = 0.12f, saturation = -0.2f),
    "Film" to Adjustments(contrast = 0.1f, saturation = -0.15f, temperature = 0.2f, tint = 0.15f),
    "Dusk" to Adjustments(exposure = -0.1f, temperature = 0.3f, tint = 0.35f, saturation = 0.1f),
    "Forest" to Adjustments(tint = -0.45f, contrast = 0.1f, saturation = -0.1f),
    "Sepia" to Adjustments(saturation = -0.8f, temperature = 0.7f, tint = -0.1f),
    "B&W" to Adjustments(saturation = -1f, contrast = 0.15f),
    "Noir" to Adjustments(saturation = -1f, contrast = 0.5f, exposure = -0.1f),
)

/** A change to one range of colours, each -1..1. */
@Immutable
internal data class SpotShift(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f) {
    val isZero get() = hue == 0f && saturation == 0f && lightness == 0f
}

/** The ranges Spot color works on; the first, All, is every colour. Centres in degrees of hue. */
internal enum class SpotBand(val label: String, val swatch: Long, val center: Float) {
    ALL("All", 0xFF808080, -1f),
    RED("Red", 0xFFB3261E, 0f),
    ORANGE("Orange", 0xFFF29D2E, 30f),
    YELLOW("Yellow", 0xFFFFEB00, 60f),
    GREEN("Green", 0xFF00C22E, 120f),
    CYAN("Cyan", 0xFF0C6CF0, 200f),
    BLUE("Blue", 0xFF0000E0, 240f),
    PURPLE("Purple", 0xFF9B00FF, 285f),
}

@Immutable
internal data class FaceEffect(val smoothness: Float = 0f, val skinTone: Float = 0f) {
    val isZero get() = smoothness == 0f && skinTone == 0f
}

/**
 * Every edit, in the order it's applied: turn, flip, straighten, skew, crop, pixel effects, colour,
 * drawings. Nothing is rendered until asked for: the preview does it on a small copy, the export on
 * the original, and undo only has to keep a list of these.
 */
@Immutable
internal data class EditRecipe(
    val turns: Int = 0,
    val flipped: Boolean = false,
    val straighten: Float = 0f,
    val skewVertical: Float = 0f,
    val skewHorizontal: Float = 0f,
    val crop: CropFraction = CropFraction(),
    val preset: Int = 0,
    val presetStrength: Float = 1f,
    val adjust: Adjustments = Adjustments(),
    val spot: Map<SpotBand, SpotShift> = emptyMap(),
    val face: FaceEffect = FaceEffect(),
    val marks: List<Mark> = emptyList(),
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

    val hasGeometryTransform get() = straighten != 0f || skewVertical != 0f || skewHorizontal != 0f

    /** Needs a pass over the pixels on the GPU; the rest is one colour matrix and a canvas. */
    val hasPixelEffects
        get() = adjust.shadows != 0f || adjust.highlights != 0f || adjust.sharpen != 0f || adjust.vignette != 0f ||
            spot.values.any { !it.isZero } || !face.isZero

    fun colorMatrix(): ColorMatrix {
        val out = PRESETS[preset].second.scaled(presetStrength).colorMatrix()
        out.postConcat(adjust.colorMatrix())
        return out
    }
}

/**
 * Straighten and skew for a [width] x [height] picture, zoomed in just enough that no empty corner
 * comes into the frame. Null if neither is set.
 */
internal fun EditRecipe.geometryMatrix(width: Int, height: Int): Matrix? {
    if (!hasGeometryTransform) return null
    val w = width.toFloat()
    val h = height.toFloat()
    val matrix = Matrix()
    if (skewVertical != 0f || skewHorizontal != 0f) {
        val v = abs(skewVertical) * MAX_SKEW * w
        val u = abs(skewHorizontal) * MAX_SKEW * h
        val topGrow = if (skewVertical > 0f) v else 0f
        val bottomGrow = if (skewVertical < 0f) v else 0f
        val leftGrow = if (skewHorizontal > 0f) u else 0f
        val rightGrow = if (skewHorizontal < 0f) u else 0f
        matrix.setPolyToPoly(
            floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h),
            0,
            floatArrayOf(
                -topGrow, -leftGrow,
                w + topGrow, -rightGrow,
                w + bottomGrow, h + rightGrow,
                -bottomGrow, h + leftGrow,
            ),
            0,
            4,
        )
    }
    if (straighten != 0f) {
        val rad = Math.toRadians(straighten.toDouble())
        val zoom = abs(cos(rad)).toFloat() + abs(sin(rad)).toFloat() * max(w / h, h / w)
        matrix.postScale(zoom, zoom, w / 2f, h / 2f)
        matrix.postRotate(straighten, w / 2f, h / 2f)
    }
    return matrix
}

private const val MAX_SKEW = 0.3f

/** 4x5, with offsets in the 0..255 scale, as [ColorMatrix] has it. */
internal fun Adjustments.colorMatrix(): ColorMatrix {
    val out = ColorMatrix()
    if (exposure != 0f) {
        val gain = 2f.pow(exposure)
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

/** Undo and redo over recipes. [commit] makes a step; a dial being dragged only calls [preview]. */
internal class History<T>(initial: T, private val onChange: (T) -> Unit) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var committed = initial

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun preview(value: T) = onChange(value)

    /** Back to the last step, dropping whatever was only previewed. */
    fun cancelPreview() = onChange(committed)

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
