package com.example.galleryakio.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import com.awxkee.jxlcoderlibjxl.JxlCoder
import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/*
 * What Android can't read by itself, after the list of formats ImageGlass opens. The libraries'
 * own checks copy every file to a temporary one (RAW, TIFF) or take every PNG to be animated, so
 * each goes behind a look at the first bytes here: an ordinary JPEG or PNG never reaches them.
 */

private val RAW_MIME_ENDINGS = listOf(
    "3fr", "arw", "bay", "cap", "cr2", "cr3", "crw", "dcr", "dcs", "drf", "eip", "erf", "fff", "gpr", "iiq", "k25", "kdc",
    "mdc", "mef", "mos", "mrw", "nef", "nrw", "orf", "pef", "ptx", "pxn", "r3d", "raf", "raw", "rw2", "rwl", "rwz", "sr2", "srf", "srw", "x3f",
)

/** RAW file names, for what MediaStore doesn't type: DNG is left out, as Android develops it in full. */
fun isRawImage(mimeType: String?, name: String?): Boolean {
    val subtype = mimeType?.substringBefore(';')?.lowercase()?.substringAfter('/', "").orEmpty()
    if (RAW_MIME_ENDINGS.any { subtype == it || subtype.endsWith("-$it") }) return true
    return name?.substringAfterLast('.', "")?.lowercase() in RAW_MIME_ENDINGS
}

private fun BufferedSource.startsWith(vararg bytes: Int) = rangeEquals(0, bytes.map(Int::toByte).toByteArray().toByteString())

private fun BufferedSource.isTiffHeader() = startsWith(0x49, 0x49, 0x2A, 0x00) || startsWith(0x4D, 0x4D, 0x00, 0x2A)

private fun BufferedSource.hasRawHeader() =
    rangeEquals(0, "FUJIFILMCCD-RAW ".encodeUtf8()) ||
        rangeEquals(0, "FOVb".encodeUtf8()) ||
        rangeEquals(4, "ftypcrx ".encodeUtf8()) ||
        rangeEquals(6, "HEAPCCDR".encodeUtf8()) ||
        startsWith(0x00, 0x4D, 0x52, 0x4D) ||
        startsWith(0x49, 0x49, 0x52, 0x4F) ||
        startsWith(0x4D, 0x4D, 0x4F, 0x52) ||
        startsWith(0x49, 0x49, 0x55, 0x00)

/** Really reading, at a fraction of the size: some files pass on their header and fail after it. */
private fun BufferedSource.androidReads(): Boolean = runCatching {
    BitmapFactory.decodeStream(peek().inputStream(), null, BitmapFactory.Options().apply { inSampleSize = 32 }) != null
}.getOrDefault(false)

/** An animated PNG says so in a chunk ahead of its picture data. */
private fun BufferedSource.isAnimatedPng(): Boolean {
    if (!startsWith(0x89, 0x50, 0x4E, 0x47)) return false
    val look = peek()
    return runCatching {
        look.skip(8)
        while (true) {
            val length = look.readInt().toLong() and 0xFFFFFFFFL
            val type = look.readUtf8(4)
            if (type == "acTL") return true
            if (type == "IDAT" || type == "IEND") return false
            look.skip(length + 4)
        }
        @Suppress("UNREACHABLE_CODE") false
    }.getOrDefault(false)
}

private val DEVELOP_RAW = Extras.Key(default = false)

/** Asks for a RAW file's sensor data, developed, rather than its stored preview. */
fun ImageRequest.Builder.developRaw() = apply { extras[DEVELOP_RAW] = true }

/** [delegate], only for sources that pass [accepts]. */
class GatedDecoder(private val delegate: Decoder.Factory, private val accepts: (SourceFetchResult) -> Boolean) : Decoder.Factory {
    override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
        if (accepts(result)) delegate.create(result, options, imageLoader) else null

    companion object {
        /**
         * What Android reads, it reads from the preview the camera stored, in a tenth of the time: only
         * the rest is developed here, unless asked for. Always developed, as the library's own way to
         * the stored preview left a smeared band across many of them, and one all but black.
         */
        fun raw(delegate: Decoder.Factory) = object : Decoder.Factory {
            override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
                val source = result.source.source()
                val mime = result.mimeType?.substringBefore(';')?.lowercase()
                val looksRaw = isRawImage(mime, null) || source.hasRawHeader() ||
                    // Most RAW is TIFF inside: an untyped one is tried as RAW first, and falls through.
                    (source.isTiffHeader() && (mime == null || mime == "application/octet-stream"))
                if (!looksRaw) {
                    // Asked for by name: a typed DNG's sensor data.
                    if (!options.getExtra(DEVELOP_RAW) || !source.isTiffHeader()) return null
                } else if (!options.getExtra(DEVELOP_RAW) && source.androidReads()) {
                    return null
                }
                return delegate.create(result, options, imageLoader)
            }
        }

        /** Not a typed RAW file such as DNG: those are TIFF inside too, but this would only find their small thumbnail. */
        fun tiff(delegate: Decoder.Factory) = GatedDecoder(delegate) { result ->
            val mime = result.mimeType?.substringBefore(';')?.lowercase()
            val source = result.source.source()
            source.isTiffHeader() && (mime == null || mime == "application/octet-stream" || "tif" in mime) && !source.androidReads()
        }

        /** Frames at their own size: the library would otherwise scale each one up to the screen, in memory. */
        fun animatedPng(delegate: Decoder.Factory) = object : Decoder.Factory {
            override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
                if (result.source.source().isAnimatedPng()) delegate.create(result, options.copy(size = Size.ORIGINAL), imageLoader) else null
        }
    }
}

private enum class ClassicFormat { QOI, JXL, PCX, PNM, XBM, SGI, DIB, HDR, DDS, TGA }

private fun BufferedSource.classicFormat(mimeType: String?): ClassicFormat? {
    val mime = mimeType?.substringBefore(';')?.lowercase()
    return when {
        rangeEquals(0, "qoif".encodeUtf8()) -> ClassicFormat.QOI
        startsWith(0xFF, 0x0A) || rangeEquals(4, "JXL ".encodeUtf8()) -> ClassicFormat.JXL
        rangeEquals(0, "DDS ".encodeUtf8()) -> ClassicFormat.DDS
        rangeEquals(0, "#?".encodeUtf8()) -> ClassicFormat.HDR
        rangeEquals(0, "#define".encodeUtf8()) -> ClassicFormat.XBM
        startsWith(0x01, 0xDA) -> ClassicFormat.SGI
        (0..5).any { rangeEquals(0, "P${it + 1}".encodeUtf8()) } && mime != "image/jpeg" -> ClassicFormat.PNM
        startsWith(0x0A, 0x05) || (mime == "image/pcx" && startsWith(0x0A)) -> ClassicFormat.PCX
        mime == "image/bmp" || mime == "image/x-ms-bmp" || mime == "image/x-dib" ->
            if (startsWith(0x28, 0x00, 0x00, 0x00)) ClassicFormat.DIB else null
        // A header of 40 bytes with no "BM" ahead of it.
        startsWith(0x28, 0x00, 0x00, 0x00) && rangeEquals(12, byteArrayOf(1, 0).toByteString()) -> ClassicFormat.DIB
        looksLikeTga() -> ClassicFormat.TGA
        else -> null
    }
}

/** TGA has no signature: its header's fields have to make sense together. */
private fun BufferedSource.looksLikeTga(): Boolean {
    val look = peek()
    if (!look.request(18)) return false
    val header = look.readByteArray(18)
    val colorMap = header[1].toInt()
    val type = header[2].toInt()
    val bits = header[16].toInt() and 0xFF
    val width = (header[12].toInt() and 0xFF) or ((header[13].toInt() and 0xFF) shl 8)
    val height = (header[14].toInt() and 0xFF) or ((header[15].toInt() and 0xFF) shl 8)
    val mapped = type == 1 || type == 9
    return colorMap in 0..1 && (mapped == (colorMap == 1)) && type in listOf(1, 2, 3, 9, 10, 11) &&
        bits in listOf(8, 15, 16, 24, 32) && width > 0 && height > 0 && (mapped || header[3].toInt() == 0 && header[5].toInt() == 0 && header[6].toInt() == 0)
}

/** Formats simple enough to read here (TGA, PCX, PNM, XBM, SGI, DIB, QOI, Radiance HDR, DDS), and JPEG XL by its library. */
class ClassicImageDecoder private constructor(
    private val format: ClassicFormat,
    private val data: () -> ByteArray,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = data()
        val full = runCatching {
            when (format) {
                ClassicFormat.TGA -> readTga(bytes)
                ClassicFormat.QOI -> readQoi(bytes)
                ClassicFormat.JXL -> JxlCoder.decode(bytes)
                ClassicFormat.PCX -> readPcx(bytes)
                ClassicFormat.PNM -> readPnm(bytes)
                ClassicFormat.XBM -> readXbm(bytes)
                ClassicFormat.SGI -> readSgi(bytes)
                ClassicFormat.DIB -> readDib(bytes)
                ClassicFormat.HDR -> readHdr(bytes)
                ClassicFormat.DDS -> readDds(bytes)
            }
        }.getOrNull() ?: return null
        val bitmap = fitted(full, options.size)
        return DecodeResult(bitmap.asImage(), isSampled = bitmap !== full)
    }

    private fun fitted(bitmap: Bitmap, size: Size): Bitmap {
        val width = size.width.pxOrElse { return bitmap }
        val height = size.height.pxOrElse { return bitmap }
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            val format = source.classicFormat(result.mimeType) ?: return null
            return ClassicImageDecoder(format, { source.readByteArray() }, options)
        }
    }
}

private const val MAX_PIXELS = 100_000_000L

private fun checked(width: Int, height: Int) {
    require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
private fun ByteArray.le16(at: Int) = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.be16(at: Int) = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.le32(at: Int) = le16(at) or (le16(at + 2) shl 16)
private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

private fun bitmapOf(pixels: IntArray, width: Int, height: Int): Bitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

private fun readQoi(d: ByteArray): Bitmap {
    fun be32(at: Int) = (d.u8(at) shl 24) or (d.u8(at + 1) shl 16) or (d.u8(at + 2) shl 8) or d.u8(at + 3)
    val width = be32(4)
    val height = be32(8)
    checked(width, height)
    val pixels = IntArray(width * height)
    val seen = IntArray(64)
    var r = 0
    var g = 0
    var b = 0
    var a = 255
    var at = 14
    var i = 0
    while (i < pixels.size && at < d.size) {
        val head = d.u8(at++)
        var run = 1
        when {
            head == 0xFE -> {
                r = d.u8(at++)
                g = d.u8(at++)
                b = d.u8(at++)
            }
            head == 0xFF -> {
                r = d.u8(at++)
                g = d.u8(at++)
                b = d.u8(at++)
                a = d.u8(at++)
            }
            head shr 6 == 0 -> seen[head].let {
                a = it ushr 24
                r = it shr 16 and 255
                g = it shr 8 and 255
                b = it and 255
            }
            head shr 6 == 1 -> {
                r = (r + (head shr 4 and 3) - 2) and 255
                g = (g + (head shr 2 and 3) - 2) and 255
                b = (b + (head and 3) - 2) and 255
            }
            head shr 6 == 2 -> {
                val next = d.u8(at++)
                val dg = (head and 63) - 32
                g = (g + dg) and 255
                r = (r + dg + (next shr 4) - 8) and 255
                b = (b + dg + (next and 15) - 8) and 255
            }
            else -> run = (head and 63) + 1
        }
        val color = argb(a, r, g, b)
        seen[(r * 3 + g * 5 + b * 7 + a * 11) % 64] = color
        repeat(run) { if (i < pixels.size) pixels[i++] = color }
    }
    return bitmapOf(pixels, width, height)
}

private fun readTga(d: ByteArray): Bitmap {
    val idLength = d.u8(0)
    val mapped = d.u8(1) == 1
    val type = d.u8(2)
    val mapFirst = d.le16(3)
    val mapLength = d.le16(5)
    val mapBits = d.u8(7)
    val width = d.le16(12)
    val height = d.le16(14)
    val bits = d.u8(16)
    val descriptor = d.u8(17)
    checked(width, height)
    var at = 18 + idLength
    val palette = if (mapped) IntArray(mapFirst + mapLength).also { table ->
        val step = (mapBits + 7) / 8
        for (i in 0 until mapLength) table[mapFirst + i] = tgaColor(d, at + i * step, mapBits)
        at += mapLength * step
    } else null
    val step = (bits + 7) / 8
    val gray = type == 3 || type == 11
    fun color(o: Int): Int = when {
        palette != null -> palette[if (step == 1) d.u8(o) else d.le16(o)]
        gray -> d.u8(o).let { argb(255, it, it, it) }
        else -> tgaColor(d, o, bits)
    }
    val pixels = IntArray(width * height)
    if (type >= 9) {
        var i = 0
        while (i < pixels.size) {
            val head = d.u8(at++)
            val count = (head and 0x7F) + 1
            if (head and 0x80 != 0) {
                val value = color(at)
                at += step
                repeat(count) { if (i < pixels.size) pixels[i++] = value }
            } else {
                repeat(count) {
                    if (i < pixels.size) pixels[i++] = color(at)
                    at += step
                }
            }
        }
    } else {
        for (i in pixels.indices) pixels[i] = color(at + i * step)
    }
    val flipRows = descriptor and 0x20 == 0
    val flipColumns = descriptor and 0x10 != 0
    if (!flipRows && !flipColumns) return bitmapOf(pixels, width, height)
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        val row = (if (flipRows) height - 1 - y else y) * width
        for (x in 0 until width) out[y * width + x] = pixels[row + if (flipColumns) width - 1 - x else x]
    }
    return bitmapOf(out, width, height)
}

private fun tgaColor(d: ByteArray, o: Int, bits: Int): Int = when (bits) {
    32 -> argb(d.u8(o + 3), d.u8(o + 2), d.u8(o + 1), d.u8(o))
    24 -> argb(255, d.u8(o + 2), d.u8(o + 1), d.u8(o))
    15, 16 -> d.le16(o).let { argb(255, (it shr 10 and 31) * 255 / 31, (it shr 5 and 31) * 255 / 31, (it and 31) * 255 / 31) }
    else -> d.u8(o).let { argb(255, it, it, it) }
}

private fun readPcx(d: ByteArray): Bitmap {
    val bits = d.u8(3)
    val width = d.le16(8) - d.le16(4) + 1
    val height = d.le16(10) - d.le16(6) + 1
    val planes = d.u8(65)
    val rowBytes = d.le16(66)
    checked(width, height)
    val scan = ByteArray(rowBytes * planes * height)
    var at = 128
    var i = 0
    while (i < scan.size && at < d.size) {
        val v = d.u8(at++)
        if (v >= 0xC0) {
            val value = d[at++]
            repeat(v and 0x3F) { if (i < scan.size) scan[i++] = value }
        } else {
            scan[i++] = v.toByte()
        }
    }
    val pixels = IntArray(width * height)
    val palette = if (bits == 8 && planes == 1 && d.size >= 769 && d.u8(d.size - 769) == 12) d.size - 768 else -1
    for (y in 0 until height) {
        val row = y * rowBytes * planes
        for (x in 0 until width) {
            pixels[y * width + x] = when {
                bits == 8 && planes >= 3 -> argb(255, scan[row + x].toInt() and 255, scan[row + rowBytes + x].toInt() and 255, scan[row + 2 * rowBytes + x].toInt() and 255)
                bits == 8 && palette >= 0 -> (scan[row + x].toInt() and 255).let { argb(255, d.u8(palette + it * 3), d.u8(palette + it * 3 + 1), d.u8(palette + it * 3 + 2)) }
                bits == 8 -> (scan[row + x].toInt() and 255).let { argb(255, it, it, it) }
                else -> ((scan[row + x / 8].toInt() shr (7 - x % 8)) and 1).let { if (it == 1) -1 else argb(255, 0, 0, 0) }
            }
        }
    }
    return bitmapOf(pixels, width, height)
}

private fun readPnm(d: ByteArray): Bitmap {
    val kind = d[1].toInt() - '0'.code
    var at = 2
    fun skipBlanks() {
        while (at < d.size) {
            val c = d[at].toInt().toChar()
            if (c == '#') {
                while (at < d.size && d[at].toInt().toChar() != '\n') at++
            } else if (c.isWhitespace()) {
                at++
            } else {
                return
            }
        }
    }
    fun number(): Int {
        skipBlanks()
        var v = 0
        while (at < d.size && d[at].toInt().toChar().isDigit()) v = v * 10 + (d[at++].toInt() - '0'.code)
        return v
    }
    val width = number()
    val height = number()
    checked(width, height)
    val bitmapKind = kind == 1 || kind == 4
    val max = if (bitmapKind) 1 else number()
    val pixels = IntArray(width * height)
    when (kind) {
        4 -> {
            at++
            val rowBytes = (width + 7) / 8
            for (y in 0 until height) for (x in 0 until width) {
                val bit = (d.u8(at + y * rowBytes + x / 8) shr (7 - x % 8)) and 1
                pixels[y * width + x] = if (bit == 1) argb(255, 0, 0, 0) else -1
            }
        }
        5, 6 -> {
            at++
            val channels = if (kind == 6) 3 else 1
            val step = if (max > 255) 2 else 1
            fun sample(o: Int) = (if (step == 2) d.be16(o) else d.u8(o)) * 255 / max
            for (i in pixels.indices) {
                val o = at + i * channels * step
                pixels[i] = if (channels == 3) argb(255, sample(o), sample(o + step), sample(o + 2 * step)) else sample(o).let { argb(255, it, it, it) }
            }
        }
        else -> for (i in pixels.indices) {
            pixels[i] = when (kind) {
                1 -> if (number() == 1) argb(255, 0, 0, 0) else -1
                2 -> (number() * 255 / max).let { argb(255, it, it, it) }
                else -> argb(255, number() * 255 / max, number() * 255 / max, number() * 255 / max)
            }
        }
    }
    return bitmapOf(pixels, width, height)
}

private fun readXbm(d: ByteArray): Bitmap {
    val text = String(d, Charsets.ISO_8859_1)
    val width = Regex("""#define\s+\S*width\s+(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
    val height = Regex("""#define\s+\S*height\s+(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
    checked(width, height)
    val bytes = Regex("""0[xX]([0-9a-fA-F]{1,2})""").findAll(text.substringAfter('{')).map { it.groupValues[1].toInt(16) }.toList()
    val rowBytes = (width + 7) / 8
    val pixels = IntArray(width * height) { i ->
        val x = i % width
        val value = bytes.getOrElse((i / width) * rowBytes + x / 8) { 0 }
        if ((value shr (x % 8)) and 1 == 1) argb(255, 0, 0, 0) else -1
    }
    return bitmapOf(pixels, width, height)
}

private fun readSgi(d: ByteArray): Bitmap {
    val rle = d.u8(2) == 1
    val perChannel = d.u8(3)
    require(perChannel == 1)
    val width = d.be16(6)
    val height = d.be16(8)
    val channels = d.be16(10).coerceAtLeast(1)
    checked(width, height)
    val plane = Array(channels) { ByteArray(width * height) }
    fun be32(at: Int) = (d.be16(at) shl 16) or d.be16(at + 2)
    for (c in 0 until channels) for (y in 0 until height) {
        val target = plane[c]
        val row = y * width
        if (!rle) {
            System.arraycopy(d, 512 + (c * height + y) * width, target, row, width)
        } else {
            var at = be32(512 + (c * height + y) * 4)
            var x = 0
            while (x < width) {
                val head = d.u8(at++)
                val count = head and 0x7F
                if (count == 0) break
                if (head and 0x80 != 0) {
                    repeat(count) { if (x < width) target[row + x++] = d[at++] }
                } else {
                    val value = d[at++]
                    repeat(count) { if (x < width) target[row + x++] = value }
                }
            }
        }
    }
    // Stored bottom row first.
    val pixels = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val i = (height - 1 - y) * width + x
        fun at(c: Int) = plane[c][i].toInt() and 255
        pixels[y * width + x] = when {
            channels >= 4 -> argb(at(3), at(0), at(1), at(2))
            channels == 3 -> argb(255, at(0), at(1), at(2))
            else -> at(0).let { argb(255, it, it, it) }
        }
    }
    return bitmapOf(pixels, width, height)
}

/** A BMP without its file header: put one back and let Android read it. */
private fun readDib(d: ByteArray): Bitmap {
    val header = d.le32(0)
    val bits = d.le16(14)
    val colors = d.le32(32).let { if (it == 0 && bits <= 8) 1 shl bits else it }
    val pixelsAt = 14 + header + colors * 4
    val out = ByteArrayOutputStream(d.size + 14)
    val fileSize = d.size + 14
    out.write(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()))
    for (v in intArrayOf(fileSize, 0, pixelsAt)) repeat(4) { out.write((v shr (it * 8)) and 0xFF) }
    out.write(d)
    val whole = out.toByteArray()
    return requireNotNull(BitmapFactory.decodeByteArray(whole, 0, whole.size))
}

/** Radiance RGBE, brought into display range with a simple Reinhard curve. */
private fun readHdr(d: ByteArray): Bitmap {
    var at = 0
    fun line(): String {
        val start = at
        while (at < d.size && d[at].toInt() != '\n'.code) at++
        return String(d, start, at++ - start, Charsets.ISO_8859_1)
    }
    while (line().isNotEmpty()) Unit
    val size = line().trim().split(Regex("\\s+"))
    val height = size[1].toInt()
    val width = size[3].toInt()
    require(size[0] == "-Y" && size[2] == "+X")
    checked(width, height)
    val pixels = IntArray(width * height)
    val row = ByteArray(width * 4)
    for (y in 0 until height) {
        if (width in 8..32767 && d.u8(at) == 2 && d.u8(at + 1) == 2) {
            at += 4
            for (c in 0 until 4) {
                var x = 0
                while (x < width) {
                    val head = d.u8(at++)
                    if (head > 128) {
                        val value = d[at++]
                        repeat(head - 128) { row[x++ * 4 + c] = value }
                    } else {
                        repeat(head) { row[x++ * 4 + c] = d[at++] }
                    }
                }
            }
        } else {
            System.arraycopy(d, at, row, 0, width * 4)
            at += width * 4
        }
        for (x in 0 until width) {
            val e = row[x * 4 + 3].toInt() and 255
            fun channel(c: Int): Int {
                if (e == 0) return 0
                val linear = (row[x * 4 + c].toInt() and 255) * 2.0.pow(e - 136.0)
                val curved = linear / (1 + linear)
                return (curved.pow(1 / 2.2) * 255).toInt().coerceIn(0, 255)
            }
            pixels[y * width + x] = argb(255, channel(0), channel(1), channel(2))
        }
    }
    return bitmapOf(pixels, width, height)
}

/** Uncompressed, DXT1, DXT3 and DXT5. */
private fun readDds(d: ByteArray): Bitmap {
    val height = d.le32(12)
    val width = d.le32(16)
    checked(width, height)
    val flags = d.le32(80)
    val fourCc = String(d, 84, 4, Charsets.ISO_8859_1)
    val pixels = IntArray(width * height)
    val at = 128
    if (flags and 4 == 0) {
        val bits = d.le32(88)
        val red = d.le32(92)
        val green = d.le32(96)
        val blue = d.le32(100)
        val alpha = d.le32(104)
        val step = bits / 8
        fun channel(v: Int, mask: Int): Int {
            if (mask == 0) return 255
            val shift = Integer.numberOfTrailingZeros(mask)
            val max = mask ushr shift
            return ((v and mask) ushr shift) * 255 / max
        }
        for (i in pixels.indices) {
            val o = at + i * step
            var v = 0
            for (b in 0 until step) v = v or (d.u8(o + b) shl (8 * b))
            pixels[i] = argb(channel(v, alpha), channel(v, red), channel(v, green), channel(v, blue))
        }
        return bitmapOf(pixels, width, height)
    }
    val blockBytes = if (fourCc == "DXT1") 8 else 16
    require(fourCc == "DXT1" || fourCc == "DXT3" || fourCc == "DXT5")
    var o = at
    for (by in 0 until (height + 3) / 4) for (bx in 0 until (width + 3) / 4) {
        val colorAt = if (blockBytes == 16) o + 8 else o
        val c0 = d.le16(colorAt)
        val c1 = d.le16(colorAt + 2)
        fun r(c: Int) = (c shr 11 and 31) * 255 / 31
        fun g(c: Int) = (c shr 5 and 63) * 255 / 63
        fun b(c: Int) = (c and 31) * 255 / 31
        val table = IntArray(4)
        table[0] = argb(255, r(c0), g(c0), b(c0))
        table[1] = argb(255, r(c1), g(c1), b(c1))
        if (c0 > c1 || blockBytes == 16) {
            table[2] = argb(255, (2 * r(c0) + r(c1)) / 3, (2 * g(c0) + g(c1)) / 3, (2 * b(c0) + b(c1)) / 3)
            table[3] = argb(255, (r(c0) + 2 * r(c1)) / 3, (g(c0) + 2 * g(c1)) / 3, (b(c0) + 2 * b(c1)) / 3)
        } else {
            table[2] = argb(255, (r(c0) + r(c1)) / 2, (g(c0) + g(c1)) / 2, (b(c0) + b(c1)) / 2)
            table[3] = 0
        }
        val alphaTable = IntArray(8)
        if (fourCc == "DXT5") {
            val a0 = d.u8(o)
            val a1 = d.u8(o + 1)
            alphaTable[0] = a0
            alphaTable[1] = a1
            for (i in 2..7) alphaTable[i] = if (a0 > a1) ((8 - i) * a0 + (i - 1) * a1) / 7 else if (i < 6) ((6 - i) * a0 + (i - 1) * a1) / 5 else if (i == 6) 0 else 255
        }
        for (py in 0 until 4) for (px in 0 until 4) {
            val x = bx * 4 + px
            val y = by * 4 + py
            if (x >= width || y >= height) continue
            val index = (d.u8(colorAt + 4 + py) shr (px * 2)) and 3
            var color = table[index]
            if (fourCc == "DXT3") {
                val nibble = (d.u8(o + py * 2 + px / 2) shr (px % 2 * 4)) and 15
                color = (color and 0xFFFFFF) or ((nibble * 17) shl 24)
            } else if (fourCc == "DXT5") {
                val n = (py * 4 + px) * 3
                var bits = 0L
                for (i in 0 until 6) bits = bits or (d.u8(o + 2 + i).toLong() shl (8 * i))
                color = (color and 0xFFFFFF) or (alphaTable[((bits shr n) and 7).toInt()] shl 24)
            }
            pixels[y * width + x] = color
        }
        o += blockBytes
    }
    return bitmapOf(pixels, width, height)
}
