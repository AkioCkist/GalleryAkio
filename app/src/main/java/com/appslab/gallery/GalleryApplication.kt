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

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

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
