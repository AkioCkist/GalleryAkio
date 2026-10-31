package com.example.galleryakio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toCoilUri
import kotlin.math.roundToInt
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.buffer

/** A tile crops to its shorter side: below this share of the size asked for, a thumbnail looks soft. */
private const val MIN_SHORT_SIDE = 0.7f

/** Keeps a panorama's thumbnail from growing huge when its short side is brought up to size. */
private const val MAX_LONG_SIDE = 4096

/**
 * Grid thumbnail of [uri] at about [size] px. [sourceShortSide] is the shorter side of the
 * photo, or 0 to take whatever MediaStore gives (videos). [modified] tells edits of a file apart.
 */
data class MediaThumb(val uri: Uri, val size: Int, val sourceShortSide: Int = 0, val modified: Long = 0, val photo: Boolean = false)

/**
 * Loads [MediaThumb]s from MediaStore's own thumbnail cache (Android 10+), the way the
 * system gallery does: no need to open and decode the full photo, and videos get a frame
 * without spinning up a media extractor. Falls back to decoding the file when MediaStore
 * has no thumbnail (or below Android 10).
 *
 * MediaStore hands back the thumbnail embedded in a camera photo as is, often 160 px wide
 * however big the photo or the request. Those are made again from the photo, once: the
 * result stays in Coil's disk cache.
 */
class ThumbnailFetcher(
    private val data: MediaThumb,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return loadFromMediaStore() }
            // A format MediaStore can't make a thumbnail of: made here too, so it's only read once.
            if (data.photo) {
                runCatching { madeFromPhoto() }.getOrNull()?.let {
                    return ImageFetchResult(image = it.asImage(), isSampled = true, dataSource = DataSource.DISK)
                }
            }
        }
        val (fallback, _) = imageLoader.components.newFetcher(data.uri.toCoilUri(), options, imageLoader) ?: return null
        return fallback.fetch()
    }

    @OptIn(InternalCoroutinesApi::class)
    @RequiresApi(29)
    private suspend fun loadFromMediaStore(): FetchResult {
        // Cancel the binder call as soon as the tile scrolls away, so a fast fling doesn't
        // queue up thumbnails nobody will see.
        val signal = CancellationSignal()
        val handle = currentCoroutineContext()[Job]
            ?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) signal.cancel() }
        try {
            val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(data.size, data.size), signal)
            val wanted = minOf((data.size * MIN_SHORT_SIDE).toInt(), data.sourceShortSide)
            val shown = if (minOf(bitmap.width, bitmap.height) >= wanted) bitmap else runCatching { madeFromPhoto() }.getOrNull() ?: bitmap
            return ImageFetchResult(image = shown.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } finally {
            handle?.dispose()
        }
    }

    @RequiresApi(29)
    private suspend fun madeFromPhoto(): Bitmap? {
        val cache = imageLoader.diskCache ?: return null
        val key = "thumb:${data.uri}:${data.modified}:${data.size}"
        val hardware = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
        cache.openSnapshot(key)?.use { saved ->
            BitmapFactory.decodeFile(saved.data.toString(), hardware)?.let { return it }
        }
        currentCoroutineContext().ensureActive()
        val source = ImageDecoder.createSource(options.context.contentResolver, data.uri)
        // Software, to be able to save it below.
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = minOf(1f, data.size.toFloat() / minOf(width, height), MAX_LONG_SIDE.toFloat() / maxOf(width, height))
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
        }.getOrNull() ?: decodedByCoil() ?: return null
        cache.openEditor(key)?.let { editor ->
            val saved = runCatching {
                cache.fileSystem.sink(editor.data).buffer().outputStream().use { out ->
                    if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }.getOrDefault(false)
            if (saved) editor.commit() else editor.abort()
        }
        return bitmap.copy(Bitmap.Config.HARDWARE, false) ?: bitmap
    }

    /** By the decoders added to Coil, for what Android's own can't read. */
    private suspend fun decodedByCoil(): Bitmap? {
        val request = ImageRequest.Builder(options.context)
            .data(data.uri)
            .size(data.size, data.size)
            .scale(Scale.FILL)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        return (imageLoader.execute(request).image as? BitmapImage)?.bitmap
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, options, imageLoader)
    }
}
