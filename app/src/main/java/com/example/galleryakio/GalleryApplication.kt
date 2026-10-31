package com.example.galleryakio

import android.app.Application
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import kotlinx.coroutines.Dispatchers
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.example.galleryakio.data.ClassicImageDecoder
import com.example.galleryakio.data.GatedDecoder
import com.gemalto.jp2.coil.Jpeg2000Decoder
import com.t8rin.psd.coil.PsdDecoder
import com.t8rin.raw_coder.coil.RawDecoder
import com.t8rin.tiff_coder.coil.TiffDecoder
import oupson.apng.coil.AnimatedPngDecoder
import android.os.Build
import com.example.galleryakio.data.MediaRepository
import com.example.galleryakio.data.SettingsRepository
import com.example.galleryakio.data.ThumbnailFetcher
import coil3.memory.MemoryCache
import com.example.galleryakio.data.search.MediaIndexer

/** Thumbnails decoded at once; see newImageLoader. */
private const val THUMBNAIL_DECODERS = 3

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    /** Created on first use: ML Kit clients load their models at construction. */
    val mediaIndexer: MediaIndexer by lazy { MediaIndexer(this) }

    override fun onCreate() {
        super.onCreate()
        // Release builds still record composition stack traces (group keys) by default, which
        // showed up as ~7% of the main thread while scrolling the grid. Only debug needs them.
        if (!BuildConfig.DEBUG) Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        mediaRepository = MediaRepository(this)
        settingsRepository = SettingsRepository(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(ThumbnailFetcher.Factory())
                add(GatedDecoder.raw(RawDecoder.Factory()))
                add(GatedDecoder.tiff(TiffDecoder.Factory()))
                add(GatedDecoder.animatedPng(AnimatedPngDecoder.Factory()))
                add(Jpeg2000Decoder.Factory())
                add(PsdDecoder.Factory())
                add(SvgDecoder.Factory())
                add(ClassicImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            // A gallery is almost all images: give the memory cache more room than the 20 %
            // default so scrolling back and returning from the viewer hits memory, not disk.
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .crossfade(150)
            // Thumbnails are decoded inside the fetcher (MediaStore's loadThumbnail). Left to the
            // default pool, a fling started one decode per core and starved the UI and render
            // threads of CPU; a few at a time keep up with scrolling.
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(THUMBNAIL_DECODERS))
            .build()
}
