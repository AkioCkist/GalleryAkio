package com.example.galleryakio

import android.content.ContentUris
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.MediaType
import com.example.galleryakio.ui.components.ZoomableImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pinches the biggest photos on the phone and screenshots them, for judging sharpness by eye:
 * "fast" right after the pinch, "settled" once the tiles are in. Needs media permission already granted.
 */
@RunWith(AndroidJUnit4::class)
class ZoomSharpnessTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun biggest(where: String?): MediaItem? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.MIME_TYPE)
        return instrumentation.targetContext.contentResolver.query(collection, columns, where, null, "${MediaStore.MediaColumns.WIDTH} DESC")?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                displayName = c.getString(1),
                type = MediaType.PHOTO,
                dateAdded = 0,
                dateTaken = 0,
                dateModified = 0,
                size = 0,
                mimeType = c.getString(4),
                relativePath = "",
                bucketName = "",
                width = c.getInt(2),
                height = c.getInt(3),
            )
        }
    }

    private fun pinch(from: Float, to: Float, cx: Float = 540f, cy: Float = 1170f, steps: Int = 30) {
        val root = rule.onRoot()
        root.performTouchInput {
            down(0, Offset(cx - from, cy))
            down(1, Offset(cx + from, cy))
        }
        for (i in 1..steps) {
            val d = from + (to - from) * i / steps
            root.performTouchInput {
                moveTo(0, Offset(cx - d, cy))
                moveTo(1, Offset(cx + d, cy))
            }
            Thread.sleep(16)
        }
        root.performTouchInput {
            up(0)
            up(1)
        }
    }

    /** The rule's clock only moves when told to, and delays and frames all run on it. */
    private fun wait(millis: Int) = repeat(millis / 50) {
        rule.mainClock.advanceTimeBy(50)
        Thread.sleep(50)
    }

    private fun shot(name: String) {
        // Not /sdcard: MediaStore would index it into the gallery. Read to the end to wait for it.
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/sharp_$name.png"),
        ).use { it.readBytes() }
    }

    /** A slight pinch, below the zoom where the photo's own pixels are one to one with the screen. */
    @Test
    fun pinchSlightly() {
        var current by mutableStateOf<MediaItem?>(null)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                current?.let { item -> key(item.id) { ZoomableImage(item, isCurrentPage = true, onTap = {}) } }
            }
        }
        val jpeg = "${MediaStore.MediaColumns.MIME_TYPE}='image/jpeg'"
        val photos = listOf(
            "mid" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}>=1300 AND ${MediaStore.MediaColumns.WIDTH}<2000 AND ${MediaStore.MediaColumns.WIDTH}>${MediaStore.MediaColumns.HEIGHT}"),
            "large" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}<${MediaStore.MediaColumns.HEIGHT}*2"),
            "w4000" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}>=3300 AND ${MediaStore.MediaColumns.WIDTH}<=4200 AND ${MediaStore.MediaColumns.WIDTH}>${MediaStore.MediaColumns.HEIGHT}"),
            "rot90" to biggest("$jpeg AND ${MediaStore.Images.ImageColumns.ORIENTATION}=90"),
            "heic" to biggest("${MediaStore.MediaColumns.MIME_TYPE} IN ('image/heic','image/heif')"),
        )
        for ((name, item) in photos) {
            if (item == null) {
                Log.i("ZoomSharpness", "$name: no such photo")
                continue
            }
            Log.i("ZoomSharpness", "$name: ${item.displayName} ${item.width}x${item.height} ${item.mimeType}")
            rule.runOnIdle { current = item }
            wait(2_500)
            pinch(from = 200f, to = 300f) // ×1.5
            wait(3_000)
            shot("${name}_1.5x")
        }
    }

    /** Zooming back in right after zooming out: "again" is shot before a fresh decode could finish. */
    @Test
    fun zoomOutAndBackIn() {
        var current by mutableStateOf<MediaItem?>(null)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                current?.let { item -> key(item.id) { ZoomableImage(item, isCurrentPage = true, onTap = {}) } }
            }
        }
        val jpeg = "${MediaStore.MediaColumns.MIME_TYPE}='image/jpeg'"
        val photos = listOf(
            "large" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}<${MediaStore.MediaColumns.HEIGHT}*2"),
            "heic" to biggest("${MediaStore.MediaColumns.MIME_TYPE} IN ('image/heic','image/heif')"),
        )
        for ((name, item) in photos) {
            if (item == null) {
                Log.i("ZoomSharpness", "$name: no such photo")
                continue
            }
            Log.i("ZoomSharpness", "$name: ${item.displayName} ${item.width}x${item.height} ${item.mimeType}")
            rule.runOnIdle { current = item }
            wait(2_500)
            pinch(from = 200f, to = 300f) // ×1.5
            wait(100)
            shot("${name}_first")
            wait(4_000)
            shot("${name}_settled")
            pinch(from = 300f, to = 200f) // back to ×1
            wait(500)
            pinch(from = 200f, to = 300f)
            wait(100)
            shot("${name}_again")
        }
    }

    @Test
    fun pinchBiggestPhotos() {
        var current by mutableStateOf<MediaItem?>(null)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                current?.let { item -> key(item.id) { ZoomableImage(item, isCurrentPage = true, onTap = {}) } }
            }
        }
        val jpeg = "${MediaStore.MediaColumns.MIME_TYPE}='image/jpeg'"
        val photos = listOf(
            "wide" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}>${MediaStore.MediaColumns.HEIGHT}*2"),
            "large" to biggest("$jpeg AND ${MediaStore.MediaColumns.WIDTH}<${MediaStore.MediaColumns.HEIGHT}*2"),
            "rot90" to biggest("$jpeg AND ${MediaStore.Images.ImageColumns.ORIENTATION}=90"),
            "rot270" to biggest("$jpeg AND ${MediaStore.Images.ImageColumns.ORIENTATION}=270"),
            "heic" to biggest("${MediaStore.MediaColumns.MIME_TYPE} IN ('image/heic','image/heif')"),
            "png" to biggest("${MediaStore.MediaColumns.MIME_TYPE}='image/png'"),
        )
        for ((name, item) in photos) {
            if (item == null) {
                Log.i("ZoomSharpness", "$name: no such photo")
                continue
            }
            Log.i("ZoomSharpness", "$name: ${item.displayName} ${item.width}x${item.height} ${item.mimeType}")
            rule.runOnIdle { current = item }
            wait(2_500)
            shot("${name}_1x")
            pinch(from = 100f, to = 400f) // ×4
            wait(100)
            shot("${name}_4x_fast")
            wait(3_000)
            shot("${name}_4x_settled")
            pinch(from = 100f, to = 400f) // to the limit
            wait(100)
            shot("${name}_max_fast")
            wait(3_000)
            shot("${name}_max_settled")
        }
    }
}
