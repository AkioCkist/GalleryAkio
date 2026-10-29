package com.appslab.gallery

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import android.os.Build
import com.appslab.gallery.data.MediaRepository
import com.appslab.gallery.data.SettingsRepository
import com.appslab.gallery.data.search.MediaIndexer

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    /** Created on first use: ML Kit clients load their models at construction. */
    val mediaIndexer: MediaIndexer by lazy { MediaIndexer(this) }

    override fun onCreate() {
        super.onCreate()
        mediaRepository = MediaRepository(this)
        settingsRepository = SettingsRepository(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(150)
            .build()
}
