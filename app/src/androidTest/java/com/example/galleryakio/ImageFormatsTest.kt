package com.example.galleryakio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.BitmapImage
import coil3.Image
import coil3.SingletonImageLoader
import coil3.executeBlocking
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.example.galleryakio.data.developRaw
import java.io.File
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Logs what each image in the app's external "images" folder (not listed by MediaStore) gets from each way of reading it. */
@RunWith(AndroidJUnit4::class)
class ImageFormatsTest {
    /**
     * Which extensions MediaStore files as images, the only ones the gallery can list. Tiny stubs
     * in the app's own Android/media folder, scanned and removed again right here.
     */
    @Test
    fun whichExtensionsGetListed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.externalMediaDirs.first().also { it.mkdirs() }
        val extensions = ("3fr apng ari arw avif bay bmp cap cr2 cr3 crw cur cut dcr dcs dds dib dng drf eip emf erf exr fax fff fits flif gif gpr hdp hdr heic heif hif ico iiq " +
            "jfif jp2 jpe jpeg jpg jxl jxr k25 kdc mdc mef mos mrw nef nrw obm orf pbm pcx pef pgm png ppm psb psd ptx pxn qoi r3d raf raw rw2 rwl rwz sr2 srf srw svg svgz tga tif tiff viff wdp webp wmf wpg x3f xbm xpm xv").split(' ')
        val files = extensions.map { File(dir, "zz_format_probe.$it").apply { writeBytes(ByteArray(64)) } }
        fun scan(paths: List<File>) {
            val done = CountDownLatch(paths.size)
            MediaScannerConnection.scanFile(context, paths.map { it.path }.toTypedArray(), null) { _, _ -> done.countDown() }
            done.await(30, TimeUnit.SECONDS)
        }
        try {
            scan(files)
            val listed = HashMap<String, String>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'zz_format_probe.%'", null, null,
            )?.use { c -> while (c.moveToNext()) listed[c.getString(0).substringAfterLast('.')] = c.getString(1) }
            Log.i("ImageFormats", "LISTED ${listed.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value.substringAfter('/')}" }}")
            Log.i("ImageFormats", "NOT LISTED ${extensions.filter { it !in listed }.joinToString(" ")}")
        } finally {
            files.forEach { it.delete() }
            scan(files)
        }
    }

    /** Mean difference from the reference over a grid of points, for those made from it: did it decode to the same picture? */
    private fun difference(reference: Bitmap, image: Image?): String {
        val bitmap = (image as? BitmapImage)?.bitmap ?: return "-"
        if (bitmap.width * reference.height != bitmap.height * reference.width) return "-"
        val soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        var total = 0L
        var count = 0
        for (gy in 1 until 30) for (gx in 1 until 30) {
            val a = reference.getPixel(reference.width * gx / 30, reference.height * gy / 30)
            val b = soft.getPixel(soft.width * gx / 30, soft.height * gy / 30)
            total += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            count += 3
        }
        return (total / count).toString()
    }

    /** Small copies of what each test file decodes to, previews and developed RAW, to judge colours and orientation by eye. */
    @Test
    fun writeContactPictures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.getExternalFilesDir("images")?.listFiles()?.sortedBy { it.name }.orEmpty()
        assumeTrue(files.isNotEmpty())
        val out = context.getExternalFilesDir("contact")!!
        val loader = SingletonImageLoader.get(context)
        fun save(file: File, develop: Boolean) {
            val request = ImageRequest.Builder(context).data(Uri.fromFile(file)).memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)
                .size(320, 320).apply { if (develop) developRaw() }.build()
            val bitmap = (runCatching { loader.executeBlocking(request).image }.getOrNull() as? BitmapImage)?.bitmap ?: return
            val name = file.name + if (develop) ".developed.jpg" else ".jpg"
            File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        for (file in files) {
            save(file, false)
            if (file.name.startsWith("sample.")) save(file, true)
        }
    }

    /** How long a RAW file takes through the viewer's two paths: its stored preview, and developed for zooming. */
    @Test
    fun rawTimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.getExternalFilesDir("images")?.listFiles()?.filter { it.name.startsWith("sample.") }?.sortedBy { it.extension }.orEmpty()
        assumeTrue(files.isNotEmpty())
        val loader = SingletonImageLoader.get(context)
        for (file in files) {
            fun read(develop: Boolean): String {
                val start = SystemClock.elapsedRealtime()
                val request = ImageRequest.Builder(context).data(Uri.fromFile(file)).memoryCachePolicy(CachePolicy.DISABLED)
                    .apply { if (develop) developRaw().size(Size.ORIGINAL).maxBitmapSize(Size(4096, 4096)) else size(1080, 2340) }.build()
                val image = runCatching { loader.executeBlocking(request).image }.getOrNull()
                return "${image?.width}x${image?.height} ${SystemClock.elapsedRealtime() - start}ms"
            }
            Log.i("ImageFormats", "RAW ${file.extension}|preview ${read(false)}|developed ${read(true)}|${file.length() shr 20}MB")
        }
    }

    @Test
    fun readEveryFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.getExternalFilesDir("images")?.listFiles()?.sortedBy { it.extension }.orEmpty()
        assumeTrue(files.isNotEmpty())
        val loader = SingletonImageLoader.get(context)
        val reference = files.first { it.name == "still.png" }.let { BitmapFactory.decodeFile(it.path) }
        for (file in files) {
            val uri = Uri.fromFile(file)
            val start = SystemClock.elapsedRealtime()
            val request = ImageRequest.Builder(context).data(uri).memoryCachePolicy(CachePolicy.DISABLED).size(1080, 2340).build()
            val result = runCatching { loader.executeBlocking(request).image }.getOrNull()
            val coilMs = SystemClock.elapsedRealtime() - start
            val coil = when (result) {
                null -> "-"
                is BitmapImage -> "${result.bitmap.width}x${result.bitmap.height}"
                else -> "${result.javaClass.simpleName}:${result.width}x${result.height}"
            }
            val region = runCatching {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.path, false)?.let { "${it.width}x${it.height}".also { _ -> it.recycle() } }
            }.getOrNull() ?: "-"
            Log.i("ImageFormats", "${file.name}|coil=$coil ${coilMs}ms|diff=${difference(reference, result)}|region=$region")
        }
    }
}
