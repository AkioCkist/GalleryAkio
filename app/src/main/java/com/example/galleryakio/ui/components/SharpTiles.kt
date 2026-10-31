package com.example.galleryakio.ui.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.maxBitmapSize
import coil3.size.Size as CoilSize
import com.example.galleryakio.data.developRaw
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Tile edge in decoded pixels. */
private const val TILE_PX = 512

/** Mid-pinch every frame can want other tiles; only start decoding once they hold still this long. */
private const val SETTLE_MS = 50L

/** A full screen of tiles is at most about 84 MB, so this also holds some of another zoom level. */
private const val TILE_CACHE_BYTES = 128 * 1024 * 1024

/** A photo read whole takes this much on its long side at most, some 64 MB of memory. RAW keeps its own size. */
private const val WHOLE_MAX_PX = 4096

/** A region decoder takes one call at a time anyway. */
private val TILE_DISPATCHER = Dispatchers.IO.limitedParallelism(1)

/** No antialiasing: it would leave a hairline along every edge where two tiles meet. */
private val TILE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

/** One cell of the grid for [sample], in photo pixels as displayed (EXIF already applied). */
private data class Tile(val sample: Int, val column: Int, val row: Int)

/** The grid cells a view of the photo needs. Empty when the screen-sized photo is good enough. */
private data class TileRange(val sample: Int, val columns: IntRange, val rows: IntRange) {
    /** Those at the centre of the screen first. */
    fun tiles(): List<Tile> {
        val midColumn = (columns.first + columns.last) / 2f
        val midRow = (rows.first + rows.last) / 2f
        return columns.flatMap { c -> rows.map { r -> Tile(sample, c, r) } }
            .sortedBy { (it.column - midColumn) * (it.column - midColumn) + (it.row - midRow) * (it.row - midRow) }
    }
}

/** Largest power of two that still gives at least one photo pixel per screen pixel. */
private fun sampleFor(screenPerPhoto: Float): Int {
    var sample = 1
    while (sample * 2 * screenPerPhoto <= 1f) sample *= 2
    return sample
}

/** Decoded tiles of the photo on screen, those out of view included. */
private class TileCache : LruCache<Tile, Bitmap>(TILE_CACHE_BYTES) {
    override fun sizeOf(key: Tile, value: Bitmap) = value.byteCount
}

/** The photo file, read a rectangle at a time. */
private class TileSource(
    private val file: ParcelFileDescriptor,
    private val decoder: BitmapRegionDecoder,
    /** Stored pixels to displayed pixels. */
    private val orientation: Matrix,
) {
    private val toStored = Matrix().also { orientation.invert(it) }
    val width: Int
    val height: Int

    init {
        val shown = RectF(0f, 0f, decoder.width.toFloat(), decoder.height.toFloat())
        orientation.mapRect(shown)
        width = shown.width().roundToInt()
        height = shown.height().roundToInt()
    }

    private fun fit(view: IntSize) = min(view.width.toFloat() / width, view.height.toFloat() / height)

    fun rangeFor(view: IntSize, scale: Float, offset: Offset): TileRange? {
        if (view.width == 0 || view.height == 0) return null
        val fit = fit(view)
        // Coil decodes the base image down to fit, not to a power of two: any bigger photo has
        // more to show as soon as it is zoomed. A smaller one has nothing to add.
        if (fit >= 1f) return null
        val sample = sampleFor(fit * scale)
        val cx = view.width / 2f
        val cy = view.height / 2f
        val left = cx - width * fit / 2f
        val top = cy - height * fit / 2f
        // Inverse of ZoomBox's graphicsLayer: scale about the centre, then translate.
        fun photoX(screen: Float) = ((screen - cx - offset.x) / scale + cx - left) / fit
        fun photoY(screen: Float) = ((screen - cy - offset.y) / scale + cy - top) / fit
        val size = (TILE_PX * sample).toFloat()
        // One extra cell around the screen, so a slow pan doesn't run into the blurry base.
        val columns = (floor(photoX(0f) / size).toInt() - 1).coerceAtLeast(0)..(floor(photoX(view.width.toFloat()) / size).toInt() + 1).coerceAtMost((width - 1) / (TILE_PX * sample))
        val rows = (floor(photoY(0f) / size).toInt() - 1).coerceAtLeast(0)..(floor(photoY(view.height.toFloat()) / size).toInt() + 1).coerceAtMost((height - 1) / (TILE_PX * sample))
        return if (columns.isEmpty() || rows.isEmpty()) null else TileRange(sample, columns, rows)
    }

    fun shownRect(tile: Tile): Rect {
        val size = TILE_PX * tile.sample
        return Rect(tile.column * size, tile.row * size, min((tile.column + 1) * size, width), min((tile.row + 1) * size, height))
    }

    fun decode(tile: Tile): Bitmap? = runCatching {
        val stored = RectF(shownRect(tile))
        toStored.mapRect(stored)
        val region = Rect(stored.left.roundToInt(), stored.top.roundToInt(), stored.right.roundToInt(), stored.bottom.roundToInt())
        val options = BitmapFactory.Options().apply { inSampleSize = tile.sample }
        val bitmap = decoder.decodeRegion(region, options) ?: return null
        val shown = if (orientation.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, orientation, false)
        // Uploads it to the GPU now rather than in the frame that first draws it.
        shown.prepareToDraw()
        shown
    }.getOrNull()

    fun close() {
        decoder.recycle()
        file.close()
    }
}

/** The whole file at up to [WHOLE_MAX_PX], once zoomed: slower to come in than tiles, and it stays. */
@Composable
private fun WholePicture(uri: Uri, raw: Boolean) {
    val context = LocalContext.current
    val request = remember(uri, raw) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(CoilSize.ORIGINAL)
            .maxBitmapSize(CoilSize(WHOLE_MAX_PX, WHOLE_MAX_PX))
            .memoryCacheKey("whole:$uri")
            .apply { if (raw) developRaw() }
            .crossfade(false)
            .build()
    }
    AsyncImage(request, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/** Null for files the decoder can't read a part of (GIF, AVIF, ...): those just stay screen-sized. */
private fun openTileSource(context: Context, uri: Uri): TileSource? = runCatching {
    val file = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
    try {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(file.fileDescriptor, false)
            ?: throw IllegalArgumentException("not decodable")
        val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        TileSource(file, decoder, orientationMatrix(exif, decoder.width, decoder.height))
    } catch (e: Exception) {
        file.close()
        throw e
    }
}.getOrNull()

/** Maps the stored pixels onto the displayed photo, with its top left corner at the origin. */
private fun orientationMatrix(exif: ExifInterface?, width: Int, height: Int): Matrix {
    val matrix = Matrix()
    when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
    matrix.mapRect(bounds)
    matrix.postTranslate(-bounds.left, -bounds.top)
    return matrix
}

/**
 * Draws the visible part of the photo at full resolution over the screen-sized image while zoomed.
 * It sits inside [ZoomBox]'s graphicsLayer, so tiles are drawn in unzoomed coordinates: the layer
 * is replayed at its scale rather than rasterized first, so their extra pixels still reach the screen.
 */
@Composable
fun SharpTiles(uri: Uri, zoom: ZoomState, isCurrentPage: Boolean, raw: Boolean = false, developNow: Boolean = false) {
    val context = LocalContext.current
    val tiles = remember(uri) { mutableStateMapOf<Tile, Bitmap>() }
    var source by remember(uri) { mutableStateOf<TileSource?>(null) }
    var currentSample by remember(uri) { mutableStateOf(0) }
    val cache = remember(uri) { TileCache() }
    val zoomed by remember(zoom) { derivedStateOf { zoom.scale.value > ZOOMED_SCALE } }
    // No reading it a part at a time: RAW, and what Android's region decoder doesn't take.
    var whole by remember(uri) { mutableStateOf(false) }
    if (developNow) whole = true

    DisposableEffect(cache) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) cache.evictAll()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = cache.evictAll()
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    // The file stays open and its tiles stay cached until the page is left, not just until the
    // photo is zoomed back out: zooming in again then shows them at once.
    LaunchedEffect(uri, isCurrentPage) {
        if (!isCurrentPage) return@LaunchedEffect
        snapshotFlow { zoomed }.first { it }
        val opened = if (raw) null else withContext(TILE_DISPATCHER) { openTileSource(context, uri) }
        if (opened == null) {
            whole = true
            return@LaunchedEffect
        }
        source = opened
        try {
            snapshotFlow { if (zoomed) opened.rangeFor(zoom.size, zoom.scale.value, zoom.offset) else null }
                .distinctUntilChanged()
                .collectLatest { range ->
                    if (range == null) {
                        tiles.clear()
                        return@collectLatest
                    }
                    val wanted = range.tiles()
                    currentSample = range.sample
                    // Other resolutions stay as the backdrop until this one has filled in.
                    tiles.keys.retainAll { it.sample != range.sample || it in wanted }
                    for (tile in wanted) cache[tile]?.let { tiles[tile] = it }
                    delay(SETTLE_MS)
                    for (tile in wanted) {
                        if (tile in tiles) continue
                        // Cached on the decoding thread: a tile finished as the view moves on still counts.
                        tiles[tile] = withContext(TILE_DISPATCHER) { opened.decode(tile)?.also { cache.put(tile, it) } } ?: continue
                    }
                    tiles.keys.retainAll { it in wanted }
                }
        } finally {
            source = null
            tiles.clear()
            withContext(NonCancellable + TILE_DISPATCHER) {
                opened.close()
                cache.evictAll()
            }
        }
    }

    if (whole) {
        WholePicture(uri, raw)
        return
    }
    val photo = source ?: return
    Canvas(Modifier.fillMaxSize()) {
        val fit = min(size.width / photo.width, size.height / photo.height)
        val left = (size.width - photo.width * fit) / 2f
        val top = (size.height - photo.height * fit) / 2f
        val target = RectF()
        drawIntoCanvas { canvas ->
            // Coarsest first, the resolution being filled in last, on top of everything.
            for ((tile, bitmap) in tiles.entries.sortedByDescending { if (it.key.sample == currentSample) 0 else max(it.key.sample, 1) }) {
                val rect = photo.shownRect(tile)
                target.set(left + rect.left * fit, top + rect.top * fit, left + rect.right * fit, top + rect.bottom * fit)
                canvas.nativeCanvas.drawBitmap(bitmap, null, target, TILE_PAINT)
            }
        }
    }
}
