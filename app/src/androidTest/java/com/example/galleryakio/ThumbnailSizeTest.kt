package com.example.galleryakio

import android.content.ContentUris
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.executeBlocking
import coil3.request.CachePolicy
import coil3.size.Scale
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.MediaType
import com.example.galleryakio.ui.components.thumbnailRequest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logs the bitmaps the grid really gets across the phone's photo sizes, loaded twice with the
 * memory cache off: the first time and from disk. Needs media permission already granted.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailSizeTest {
    @Test
    fun logThumbnailSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = SingletonImageLoader.get(context)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val width = MediaStore.MediaColumns.WIDTH
        val height = MediaStore.MediaColumns.HEIGHT
        val ranges = listOf(0 to 1000, 1000 to 2000, 2000 to 3000, 3000 to 5000, 5000 to 100_000)
        for (mime in listOf("image/jpeg", "image/heic", "image/png")) {
            for ((low, high) in ranges) {
                val where = "${MediaStore.MediaColumns.MIME_TYPE}='$mime' AND max($width,$height)>=$low AND max($width,$height)<$high"
                val args = arrayOf(MediaStore.MediaColumns._ID, width, height, MediaStore.MediaColumns.DATE_MODIFIED)
                context.contentResolver.query(collection, args, where, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    var seen = 0
                    while (c.moveToNext() && seen < 3) {
                        seen++
                        val id = c.getLong(0)
                        val item = MediaItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = "",
                            type = MediaType.PHOTO,
                            dateAdded = 0,
                            dateTaken = 0,
                            dateModified = c.getLong(3),
                            size = 0,
                            mimeType = mime,
                            relativePath = "",
                            bucketName = "",
                            width = c.getInt(1),
                            height = c.getInt(2),
                        )
                        val loads = listOf(256, 512).joinToString(" ") { bucket ->
                            bucket.toString() + "->" + List(2) {
                                val start = SystemClock.elapsedRealtime()
                                val request = thumbnailRequest(context, item, bucket).scale(Scale.FILL).memoryCachePolicy(CachePolicy.DISABLED).build()
                                val bitmap = (loader.executeBlocking(request).image as? BitmapImage)?.bitmap
                                "${bitmap?.width}x${bitmap?.height} ${SystemClock.elapsedRealtime() - start}ms"
                            }.joinToString(", ")
                        }
                        Log.i("ThumbSize", "$mime ${item.width}x${item.height} $loads")
                    }
                }
            }
        }
    }
}
