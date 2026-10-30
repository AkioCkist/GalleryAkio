package com.example.galleryakio.data

import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.toCoilUri
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Grid thumbnail of [uri], at most [size] px on its longer side. */
data class MediaThumb(val uri: Uri, val size: Int)

/**
 * Loads [MediaThumb]s from MediaStore's own thumbnail cache (Android 10+), the way the
 * system gallery does: no need to open and decode the full photo, and videos get a frame
 * without spinning up a media extractor. Falls back to decoding the file when MediaStore
 * has no thumbnail (or below Android 10).
 */
class ThumbnailFetcher(
    private val data: MediaThumb,
    private val options: Options,
    private val imageLoader: ImageLoader,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { return loadFromMediaStore() }
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
            return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } finally {
            handle?.dispose()
        }
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, options, imageLoader)
    }
}
