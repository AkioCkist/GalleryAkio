package com.example.galleryakio.editor

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVignetteFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Longer side of the copy that previews work on: sharp on a phone, ~1/5 of a 12 MP photo's pixels. */
internal const val PROXY_SIDE = 1600

internal enum class ImageFormat(val label: String, val mime: String, val extension: String, val hasQuality: Boolean) {
    JPEG("JPEG", "image/jpeg", "jpg", true),
    PNG("PNG", "image/png", "png", false),
    WEBP("WebP", "image/webp", "webp", true),
}

/** Software bitmap of the image the right way up (Coil applies EXIF), at most [maxSide] if given. */
internal suspend fun loadBitmap(context: Context, uri: Uri, maxSide: Int?): Bitmap {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .apply {
            if (maxSide != null) size(maxSide, maxSide).precision(Precision.INEXACT) else size(Size.ORIGINAL)
        }
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    return ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: throw IllegalStateException("Couldn't read the image")
}

/** Turn and flip only: what the crop tool shows. */
internal fun Bitmap.oriented(recipe: EditRecipe): Bitmap {
    if (recipe.turns == 0 && !recipe.flipped) return this
    val matrix = Matrix().apply {
        postRotate(90f * recipe.turns)
        if (recipe.flipped) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

internal fun Bitmap.cropped(crop: CropFraction): Bitmap {
    if (crop.isFull) return this
    val x = (crop.left * width).roundToInt().coerceIn(0, width - 1)
    val y = (crop.top * height).roundToInt().coerceIn(0, height - 1)
    val w = (crop.width * width).roundToInt().coerceIn(1, width - x)
    val h = (crop.height * height).roundToInt().coerceIn(1, height - y)
    return Bitmap.createBitmap(this, x, y, w, h)
}

/** Straighten and skew: same size, zoomed in just enough to fill it. */
internal fun Bitmap.transformed(recipe: EditRecipe): Bitmap {
    val matrix = recipe.geometryMatrix(width, height) ?: return this
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(this, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}

/** Faces as fractions of the picture, widened from the detector's box to take in the whole face. */
internal fun detectFaces(bitmap: Bitmap): List<RectF> {
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build(),
    )
    return try {
        Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0))).map { face ->
            val box = face.boundingBox
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            RectF(box.left / w, box.top / h, box.right / w, box.bottom / h).apply { inset(-width() * 0.08f, -height() * 0.12f) }
        }
    } finally {
        detector.close()
    }
}

/** Shadows, highlights, Spot color, Face effects, sharpness and vignette, on the GPU. */
internal fun Bitmap.withPixelEffects(context: Context, recipe: EditRecipe, faces: List<RectF>): Bitmap {
    if (!recipe.hasPixelEffects) return this
    val adjust = recipe.adjust
    val filters = buildList<GPUImageFilter> {
        if (adjust.shadows != 0f || adjust.highlights != 0f || recipe.spot.values.any { !it.isZero } || !recipe.face.isZero) {
            add(ToneFilter(adjust.shadows, adjust.highlights, recipe.spot, recipe.face, faces))
        }
        if (adjust.sharpen != 0f) add(GPUImageSharpenFilter(adjust.sharpen * 2f))
        if (adjust.vignette != 0f) {
            add(GPUImageVignetteFilter(PointF(0.5f, 0.5f), floatArrayOf(0f, 0f, 0f), 0.75f - adjust.vignette * 0.55f, 1.05f - adjust.vignette * 0.2f))
        }
    }
    val gpu = GPUImage(context)
    gpu.setFilter(if (filters.size == 1) filters[0] else GPUImageFilterGroup(filters))
    return gpu.getBitmapWithFilterApplied(this)
}

/** The colours the preview gets from its colour filter, and the marks it draws on top. */
internal fun Bitmap.finished(recipe: EditRecipe): Bitmap {
    val matrix = recipe.colorMatrix()
    val plain = matrix == android.graphics.ColorMatrix()
    if (plain && recipe.marks.isEmpty()) return this
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(this, 0f, 0f, Paint().apply { if (!plain) colorFilter = ColorMatrixColorFilter(matrix) })
    recipe.marks.drawOn(canvas, width.toFloat(), height.toFloat())
    return out
}

/** Renders [recipe] on the original and saves it as a new file, leaving the original alone. */
internal suspend fun exportImage(
    context: Context,
    source: Uri,
    displayName: String,
    recipe: EditRecipe,
    format: ImageFormat,
    quality: Int,
): Uri = withContext(Dispatchers.Default) {
    val rendered = runCatching {
        var bitmap = loadBitmap(context, source, null)
        fun step(next: Bitmap) {
            if (next !== bitmap) bitmap.recycle()
            bitmap = next
        }
        coroutineContext.ensureActive()
        step(bitmap.oriented(recipe))
        step(bitmap.transformed(recipe))
        step(bitmap.cropped(recipe.crop))
        coroutineContext.ensureActive()
        val faces = if (recipe.face.isZero) emptyList() else detectFaces(bitmap)
        step(bitmap.withPixelEffects(context, recipe, faces))
        step(bitmap.finished(recipe))
        bitmap
    }.getOrElse { error ->
        if (error is OutOfMemoryError) throw IllegalStateException("This image is too large to edit on this phone")
        throw error
    }
    coroutineContext.ensureActive()
    withContext(Dispatchers.IO) {
        try {
            writeImage(context, source, displayName, rendered, format, quality)
        } finally {
            rendered.recycle()
        }
    }
}

private fun writeImage(context: Context, source: Uri, displayName: String, bitmap: Bitmap, format: ImageFormat, quality: Int): Uri {
    val resolver = context.contentResolver
    val base = displayName.substringBeforeLast('.')
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_edit.${format.extension}")
        put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Gallery")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create the file")
    try {
        val compress = when (format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(compress, quality, it) } == true
        check(written) { "Couldn't write the file" }
        // Not worth failing the export over.
        runCatching { copyExif(context, source, uri, bitmap) }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

private val KEPT_EXIF_TAGS = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
)

/** Keeps when and where it was taken. The pixels are already upright, so orientation is reset. */
private fun copyExif(context: Context, source: Uri, target: Uri, bitmap: Bitmap) {
    val resolver = context.contentResolver
    // Android blanks GPS tags unless the file is opened this way, which needs the permission.
    val original = if (Build.VERSION.SDK_INT >= 29 &&
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    ) MediaStore.setRequireOriginal(source) else source
    val from = resolver.openInputStream(original)?.use { ExifInterface(it) } ?: return
    resolver.openFileDescriptor(target, "rw")?.use { descriptor ->
        val to = ExifInterface(descriptor.fileDescriptor)
        KEPT_EXIF_TAGS.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
        to.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
        to.saveAttributes()
    }
}
