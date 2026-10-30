package com.example.galleryakio.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.roundToInt

/** A titled group of label / value rows in the details sheet. Empty values never make a row. */
class DetailSection(val title: String, val rows: List<Pair<String, String>>)

/** Reads the file, so call it off the main thread. */
fun readMediaDetails(context: Context, item: MediaItem): List<DetailSection> =
    runCatching { if (item.isVideo) videoDetails(context, item) else photoDetails(context, item) }
        .getOrDefault(emptyList())
        .filter { it.rows.isNotEmpty() }

fun canReadMediaLocation(context: Context): Boolean =
    Build.VERSION.SDK_INT < 29 ||
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Android blanks a file's GPS data unless it is opened through this URI, which needs the permission. */
private fun originalUri(context: Context, uri: Uri): Uri =
    if (Build.VERSION.SDK_INT >= 29 && canReadMediaLocation(context)) MediaStore.setRequireOriginal(uri) else uri

private fun rows(vararg rows: Pair<String, String?>): List<Pair<String, String>> =
    rows.mapNotNull { (label, value) -> if (value.isNullOrBlank()) null else label to value }

private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

private fun videoDetails(context: Context, item: MediaItem): List<DetailSection> {
    val uri = originalUri(context, item.uri)
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        fun meta(key: Int) = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }

        var video: MediaFormat? = null
        var audio: MediaFormat? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && mime.startsWith("video/")) video = format
                if (audio == null && mime.startsWith("audio/")) audio = format
            }
        } catch (_: Exception) {
            // Some containers only the retriever can read: its rows still show.
        } finally {
            extractor.release()
        }

        val totalBitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        val captureRate = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.takeIf { it > 0f }
        return listOf(
            DetailSection(
                "Video",
                rows(
                    "Codec" to video?.getString(MediaFormat.KEY_MIME)?.let(::codecName),
                    "Frame rate" to frameRate(retriever, video)?.let { formatFps(it) },
                    "Recorded at" to captureRate?.let { formatFps(it) },
                    "Bitrate" to (video?.intOrNull(MediaFormat.KEY_BIT_RATE)?.toLong() ?: totalBitrate)?.let(::formatBitrate),
                    "Rotation" to meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { "$it°" },
                    "Dynamic range" to video?.let(::dynamicRange),
                ),
            ),
            DetailSection(
                "Audio",
                rows(
                    "Codec" to audio?.getString(MediaFormat.KEY_MIME)?.let(::codecName),
                    "Sample rate" to audio?.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.let { fmt("%.1f kHz", it / 1000f) },
                    "Channels" to audio?.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.let(::channelName),
                    "Bitrate" to audio?.intOrNull(MediaFormat.KEY_BIT_RATE)?.let { formatBitrate(it.toLong()) },
                ),
            ),
            DetailSection(
                "Source",
                rows(
                    "Total bitrate" to totalBitrate?.let(::formatBitrate),
                    "Author" to (meta(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                    "Title" to meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    "Location" to meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709Location),
                ),
            ),
        )
    } finally {
        retriever.release()
    }
}

/** Frames over time when the file says how many, since a track's own figure is often rounded or absent. */
private fun frameRate(retriever: MediaMetadataRetriever, video: MediaFormat?): Float? {
    if (Build.VERSION.SDK_INT >= 28) {
        val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        if (frames != null && duration != null && frames > 0 && duration > 0) return frames * 1000f / duration
    }
    if (video == null || !video.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
    return runCatching { video.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
        .recoverCatching { video.getFloat(MediaFormat.KEY_FRAME_RATE) }
        .getOrNull()
        ?.takeIf { it > 0f }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull()?.takeIf { it > 0 } else null

private fun formatFps(fps: Float): String {
    val rounded = fps.roundToInt()
    return if (kotlin.math.abs(fps - rounded) < 0.05f) "$rounded fps" else fmt("%.2f fps", fps)
}

private fun formatBitrate(bitsPerSecond: Long): String =
    if (bitsPerSecond >= 1_000_000) fmt("%.1f Mbps", bitsPerSecond / 1_000_000f) else "${bitsPerSecond / 1000} kbps"

private fun codecName(mime: String): String = when (mime.lowercase(Locale.US)) {
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/dolby-vision" -> "Dolby Vision"
    "video/mp4v-es" -> "MPEG-4"
    "video/3gpp" -> "H.263"
    "audio/mp4a-latm" -> "AAC"
    "audio/mpeg" -> "MP3"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/flac" -> "FLAC"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/3gpp" -> "AMR-NB"
    "audio/amr-wb" -> "AMR-WB"
    else -> mime.substringAfter('/')
}

private fun channelName(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$count channels"
}

private fun dynamicRange(format: MediaFormat): String? = when (format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)) {
    MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR (PQ)"
    MediaFormat.COLOR_TRANSFER_HLG -> "HDR (HLG)"
    MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
    else -> null
}

/** "+10.7626+106.6601/" (as video files store it) to "10.76260, 106.66010". */
private fun iso6709Location(value: String): String? {
    val match = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lon = match.groupValues[2].toDoubleOrNull() ?: return null
    return fmt("%.5f, %.5f", lat, lon)
}

/** From the image itself, so files without EXIF have it too: a header read, nothing is decoded. */
private fun decodedColorSpace(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < 26) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val space = options.outColorSpace ?: return null
    return if (space.isSrgb) "sRGB" else space.name
}

private fun photoDetails(context: Context, item: MediaItem): List<DetailSection> {
    val exif = context.contentResolver.openInputStream(originalUri(context, item.uri))?.use { ExifInterface(it) }
        ?: return emptyList()
    fun text(tag: String) = exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }
    fun positive(tag: String) = exif.getAttributeDouble(tag, 0.0).takeIf { it > 0 }
    fun int(tag: String) = exif.getAttributeInt(tag, -1).takeIf { it >= 0 }

    val focal = positive(ExifInterface.TAG_FOCAL_LENGTH)?.let { mm ->
        val fullFrame = int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 }
        fmt("%.1f mm", mm) + (fullFrame?.let { " ($it mm equiv.)" } ?: "")
    }
    val location = exif.latLong?.let { fmt("%.5f, %.5f", it[0], it[1]) }
    return listOf(
        DetailSection(
            "Camera",
            rows(
                "Camera" to listOfNotNull(text(ExifInterface.TAG_MAKE), text(ExifInterface.TAG_MODEL)).joinToString(" "),
                "Lens" to text(ExifInterface.TAG_LENS_MODEL),
                "Aperture" to positive(ExifInterface.TAG_F_NUMBER)?.let { fmt("f/%.1f", it) },
                "Shutter" to positive(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                    if (it < 1) "1/${(1 / it).roundToInt()} s" else fmt("%.1f s", it)
                },
                "ISO" to text(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                "Focal length" to focal,
                "Exposure" to exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0).takeIf { it != 0.0 }?.let { fmt("%+.1f EV", it) },
                "Mode" to when (int(ExifInterface.TAG_EXPOSURE_PROGRAM)) {
                    1 -> "Manual"
                    2 -> "Program"
                    3 -> "Aperture priority"
                    4 -> "Shutter priority"
                    else -> null
                },
                "Metering" to when (int(ExifInterface.TAG_METERING_MODE)) {
                    1 -> "Average"
                    2 -> "Center-weighted"
                    3 -> "Spot"
                    4 -> "Multi-spot"
                    5 -> "Pattern"
                    6 -> "Partial"
                    else -> null
                },
                "White balance" to when (int(ExifInterface.TAG_WHITE_BALANCE)) {
                    0 -> "Auto"
                    1 -> "Manual"
                    else -> null
                },
                "Flash" to int(ExifInterface.TAG_FLASH)?.let { if (it and 1 == 1) "Fired" else "Off" },
            ),
        ),
        DetailSection(
            "Image",
            rows(
                "Rotation" to exif.rotationDegrees.takeIf { it != 0 }?.let { "$it°" },
                "Color space" to decodedColorSpace(context, item.uri),
                "Software" to text(ExifInterface.TAG_SOFTWARE),
                "Artist" to text(ExifInterface.TAG_ARTIST),
                "Copyright" to text(ExifInterface.TAG_COPYRIGHT),
                "Description" to text(ExifInterface.TAG_IMAGE_DESCRIPTION),
            ),
        ),
        DetailSection(
            "Place",
            rows(
                "Location" to location,
                "Altitude" to location?.let {
                    exif.getAltitude(Double.NaN).takeUnless { a -> a.isNaN() }?.let { a -> fmt("%.0f m", a) }
                },
            ),
        ),
    )
}
