package com.appslab.gallery.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.target
import com.appslab.gallery.data.GridStyle
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.ui.theme.CqMono
import kotlin.math.floor

/** Something drawn during a pinch: a photo or a date header. Same list for every column count. */
internal class MorphNode(val key: String, val item: MediaItem?, val label: String?)

/**
 * Pixel rects (content coordinates: y = 0 is the top of the scrollable content) of every
 * [MorphNode] for one column count. Stored flat: left, top, right, bottom per node.
 */
internal class MorphGeometry(val rects: FloatArray) {
    fun left(i: Int) = rects[i * 4]
    fun top(i: Int) = rects[i * 4 + 1]
    fun right(i: Int) = rects[i * 4 + 2]
    fun bottom(i: Int) = rects[i * 4 + 3]
    fun height(i: Int) = bottom(i) - top(i)

    /** First node whose bottom is below [y]; rects are sorted by top, so binary search. */
    fun firstBelow(y: Float, count: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (bottom(mid) < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun lastAbove(y: Float, count: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (top(mid) > y) hi = mid - 1 else lo = mid
        }
        return lo
    }
}

/**
 * Samsung-Gallery-style pinch: every photo moves and resizes continuously between the
 * layout for ⌊level⌋ columns and the one for ⌈level⌉ columns, following the fingers.
 * The photo that was under the fingers when the pinch began stays under them.
 */
internal class MorphState(
    val nodes: List<MorphNode>,
    /** Geometry per column count (index = columns); null slots are filled by [geometryFor] on demand. */
    private val geometries: Array<MorphGeometry?>,
    private val geometryFor: (Int) -> MorphGeometry,
    val anchor: Int,
    /** Where inside the anchor the fingers were, as a fraction of its height. */
    private val anchorFraction: Float,
    startLevel: Float,
    startFingerY: Float,
) {
    var level by mutableFloatStateOf(startLevel)
    var fingerY by mutableFloatStateOf(startFingerY)

    fun geometry(columns: Int): MorphGeometry = geometries[columns] ?: geometryFor(columns).also { geometries[columns] = it }

    /** Scroll offset that keeps the anchor under the fingers in the layout for [columns]. */
    fun scrollFor(columns: Int): Float {
        val g = geometry(columns)
        return g.top(anchor) - (fingerY - anchorFraction * g.height(anchor))
    }

    val from get() = floor(level).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    val to get() = (from + 1).coerceAtMost(MAX_COLUMNS)
    val t get() = (level - from).coerceIn(0f, 1f)
}

/**
 * Bitmaps, shaders and header text for one pinch, resolved once per node and kept until the
 * pinch ends, so a frame does no cache lookups or allocations for tiles it has drawn before.
 */
private class MorphTiles(private val context: Context, private val bucket: Int) {
    class Image(bitmap: Bitmap) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val loader = SingletonImageLoader.get(context)
    private val images = HashMap<Int, Image>()
    private val requests = HashMap<Int, Disposable>()
    val headers = HashMap<Int, TextLayoutResult>()
    /** Bumped when a thumbnail that wasn't in memory arrives, to redraw. */
    var loaded by mutableIntStateOf(0)
    var drawn = false

    fun image(i: Int, item: MediaItem): Image? {
        images[i]?.let { return it }
        if (i in requests) return null
        val cached = loader.memoryCache?.let { cachedThumbnail(it, item.id, bucket) }
        if (cached != null) return Image(cached).also { images[i] = it }
        // Not decoded at any size yet (a tile scrolled in by zooming out): fetch it in the background.
        requests[i] = loader.enqueue(
            thumbnailRequest(context, item, bucket)
                .target(onSuccess = { image ->
                    (image as? BitmapImage)?.bitmap?.let {
                        images[i] = Image(it)
                        loaded++
                    }
                })
                .build(),
        )
        return null
    }

    fun cancel() = requests.values.forEach { it.dispose() }
}

/**
 * Draws the morphing grid in a single draw pass: no composition, measuring or placing per
 * tile, so a frame of pinching only re-draws bitmaps at their interpolated rects.
 */
@Composable
internal fun MorphLayer(
    state: MorphState,
    style: GridStyle,
    darkMode: Boolean,
    thumbnailPx: Int,
    modifier: Modifier = Modifier,
    /** Called once the layer has drawn a frame, so the real grid can be hidden without a blank flash. */
    onFirstDraw: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val firstDraw by rememberUpdatedState(onFirstDraw)
    val tiles = remember(state) { MorphTiles(context, thumbnailBucket(thumbnailPx)) }
    DisposableEffect(tiles) { onDispose { tiles.cancel() } }
    val textMeasurer = rememberTextMeasurer()
    val headerStyle = TextStyle(
        color = if (darkMode) Color.White else Color(0xFF212121),
        fontSize = 20.sp,
        fontFamily = CqMono,
        textAlign = TextAlign.Center,
    )
    val radius = with(density) { style.cornerRadius.dp.toPx() }
    val stroke = if (style.border) with(density) { 1.dp.toPx() } else 0f
    val paints = remember(darkMode, stroke) {
        Triple(
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (if (darkMode) Color(0xFF2A2A2A) else Color(0xFFEEEEEE)).toArgb() },
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = stroke
                color = (if (darkMode) Color(0xFF404040) else Color(0xFFBDBDBD)).toArgb()
            },
        )
    }

    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                tiles.loaded
                val (tilePaint, imagePaint, borderPaint) = paints
                val nodes = state.nodes
                val n = nodes.size
                if (n == 0) return@drawBehind
                val t = state.t
                val gA = state.geometry(state.from)
                val gB = state.geometry(state.to)
                val sA = state.scrollFor(state.from)
                val sB = state.scrollFor(state.to)
                // Nodes visible in either layout; contiguous, because node order never changes.
                val first = minOf(gA.firstBelow(sA, n), gB.firstBelow(sB, n))
                val last = maxOf(gA.lastAbove(sA + size.height, n), gB.lastAbove(sB + size.height, n))
                val canvas = drawContext.canvas.nativeCanvas
                for (i in first..last) {
                    val l = gA.left(i) + (gB.left(i) - gA.left(i)) * t
                    val r = gA.right(i) + (gB.right(i) - gA.right(i)) * t
                    val top = (gA.top(i) - sA) + ((gB.top(i) - sB) - (gA.top(i) - sA)) * t
                    val b = (gA.bottom(i) - sA) + ((gB.bottom(i) - sB) - (gA.bottom(i) - sA)) * t
                    val item = nodes[i].item
                    if (item == null) {
                        val text = tiles.headers.getOrPut(i) {
                            textMeasurer.measure(nodes[i].label.orEmpty(), headerStyle, constraints = Constraints(maxWidth = (r - l).toInt().coerceAtLeast(0)))
                        }
                        drawText(text, topLeft = Offset(l + (r - l - text.size.width) / 2, top + (b - top - text.size.height) / 2))
                        continue
                    }
                    val image = tiles.image(i, item)
                    // Placeholder only where there is no bitmap yet: overdraw is what costs here.
                    if (image == null) canvas.drawRoundRect(l, top, r, b, radius, radius, tilePaint)
                    else {
                        // ContentScale.Crop: fill the rect, centred, keeping the bitmap's aspect.
                        // Moves the canvas rather than the shader: changing a shader's local
                        // matrix rebuilds its native shader (and texture) on every frame.
                        val scale = maxOf((r - l) / image.width, (b - top) / image.height)
                        val ox = l + (r - l - image.width * scale) / 2
                        val oy = top + (b - top - image.height * scale) / 2
                        imagePaint.shader = image.shader
                        canvas.save()
                        canvas.translate(ox, oy)
                        canvas.scale(scale, scale)
                        canvas.drawRoundRect(
                            (l - ox) / scale, (top - oy) / scale, (r - ox) / scale, (b - oy) / scale,
                            radius / scale, radius / scale, imagePaint,
                        )
                        canvas.restore()
                    }
                    if (stroke > 0f) {
                        val h = stroke / 2
                        canvas.drawRoundRect(l + h, top + h, r - h, b - h, radius - h, radius - h, borderPaint)
                    }
                }
                if (!tiles.drawn) {
                    tiles.drawn = true
                    firstDraw()
                }
            },
    )
}
