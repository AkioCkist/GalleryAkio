package com.appslab.gallery.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslab.gallery.R
import com.appslab.gallery.data.DateFormatOption
import com.appslab.gallery.data.GridStyle
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Grid look chosen in Settings, provided once at the top of the app. */
@Immutable
data class GridLayout(
    val style: GridStyle = GridStyle.NOTHING,
    val spacing: Int = GridStyle.NOTHING.defaultSpacing,
    val showDateHeaders: Boolean = true,
    val showVideoDuration: Boolean = true,
    val pinchToZoom: Boolean = true,
    val onColumnsChange: (Int) -> Unit = {},
)

val LocalGridLayout = staticCompositionLocalOf { GridLayout() }

/** Multi-select state shared by every grid screen. */
@Stable
class SelectionState {
    var selected by mutableStateOf<Set<Long>>(emptySet())
        private set
    val active get() = selected.isNotEmpty()

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun setAll(ids: Collection<Long>) {
        selected = ids.toSet()
    }

    fun clear() {
        selected = emptySet()
    }
}

private class DayGroup(val key: String, val label: String, val items: List<MediaItem>)

/** One group per day (by DATE_ADDED, like the original), keeping the incoming order. */
private fun groupByDay(items: List<MediaItem>, showDates: Boolean, format: DateFormatOption): List<DayGroup> {
    if (!showDates) return listOf(DayGroup("all", "", items))
    val formatter = SimpleDateFormat(format.pattern.replace(" HH:mm", ""), Locale.getDefault())
    // Local calendar day as a number: far cheaper than formatting a date string per item.
    val tz = java.util.TimeZone.getDefault()
    val dayOf = { millis: Long -> Math.floorDiv(millis + tz.getOffset(millis), DAY_MS) }
    val today = dayOf(System.currentTimeMillis())
    return items.groupByTo(LinkedHashMap()) { dayOf(it.dateAdded) }.map { (day, group) ->
        val label = when (day) {
            today -> "TODAY"
            today - 1 -> "YESTERDAY"
            else -> formatter.format(Date(group.first().dateAdded))
        }
        DayGroup(day.toString(), label.uppercase(Locale.ROOT), group)
    }
}

private sealed interface Entry {
    val key: String

    class Header(val group: DayGroup) : Entry {
        override val key get() = "h:${group.key}"
    }

    /**
     * A row of tiles. [weights] are relative widths; [height] null means square tiles.
     * [filler] is the empty weight left at the end of a short row.
     */
    class TileRow(val items: List<MediaItem>, val weights: List<Float>, val height: Dp?, val filler: Float) : Entry {
        override val key get() = "r:${items.first().id}"
    }

    class ListRow(val item: MediaItem) : Entry {
        override val key get() = "l:${item.id}"
    }
}

/** Width / height, clamped so panoramas and tall screenshots still get a usable tile. */
private fun MediaItem.ratio(): Float =
    if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2.4f) else 1f

private fun buildRows(group: DayGroup, style: GridStyle, columns: Int, widthDp: Float, spacing: Float): List<Entry> {
    val rows = ArrayList<Entry>()
    when (style) {
        GridStyle.LIST -> group.items.forEach { rows += Entry.ListRow(it) }
        GridStyle.MOSAIC -> {
            // Justified rows: fill each row to the full width at roughly the square-grid height.
            val target = (widthDp - spacing * (columns - 1)) / columns
            var current = ArrayList<MediaItem>()
            var sum = 0f
            for (item in group.items) {
                current += item
                sum += item.ratio()
                val rowWidthAtTarget = sum * target + spacing * (current.size - 1)
                if (rowWidthAtTarget >= widthDp) {
                    val h = (widthDp - spacing * (current.size - 1)) / sum
                    rows += Entry.TileRow(current, current.map { it.ratio() }, h.dp, 0f)
                    current = ArrayList()
                    sum = 0f
                }
            }
            if (current.isNotEmpty()) {
                val full = (widthDp - spacing * (current.size - 1)) / target
                if (sum >= full * 0.6f) {
                    // Nearly full: justify it too, so days with few photos don't leave ragged gaps.
                    val h = (widthDp - spacing * (current.size - 1)) / sum
                    rows += Entry.TileRow(current, current.map { it.ratio() }, h.dp, 0f)
                } else {
                    rows += Entry.TileRow(current, current.map { it.ratio() }, target.dp, (full - sum).coerceAtLeast(0f))
                }
            }
        }
        else -> group.items.chunked(columns).forEach { chunk ->
            val missing = columns - chunk.size
            if (style == GridStyle.NOTHING && missing > 0) {
                // The original stretched the last tile of a short row over the gap.
                rows += Entry.TileRow(chunk, List(chunk.size) { i -> if (i == chunk.lastIndex) missing + 1f else 1f }, null, 0f)
            } else {
                rows += Entry.TileRow(chunk, List(chunk.size) { 1f }, null, missing.toFloat())
            }
        }
    }
    return rows
}

/** Photos and headers in display order; the same list serves every column count. */
private fun morphNodes(groups: List<DayGroup>): List<MorphNode> = buildList {
    groups.forEach { g ->
        if (g.label.isNotEmpty()) add(MorphNode("h:${g.key}", null, g.label))
        g.items.forEach { add(MorphNode("m:${it.id}", it, null)) }
    }
}

/** Everything the morph geometry depends on; a pinch can reuse prepared geometry only if this matches. */
private data class MorphKey(
    val groups: List<DayGroup>,
    val style: GridStyle,
    val widthDp: Float,
    val spacingDp: Float,
    val density: Float,
    val padStartPx: Float,
    val padTopPx: Float,
    val headerHeightPx: Float,
)

/** Nodes and every column count's geometry, built off the main thread before a pinch needs them. */
private class MorphPrep(val key: MorphKey, val nodes: List<MorphNode>, val geometries: Array<MorphGeometry?>)

/**
 * Where every node sits for [columns], mirroring exactly what the LazyColumn lays out:
 * same rows, same spacing, same padding. All values in px. Nodes are numbered in
 * [morphNodes] order (header, then the day's photos), which row building preserves.
 */
private fun morphGeometry(k: MorphKey, nodeCount: Int, columns: Int): MorphGeometry {
    val rects = FloatArray(nodeCount * 4)
    val widthPx = k.widthDp * k.density
    val gap = k.spacingDp * k.density
    var y = k.padTopPx
    var index = 0
    fun put(l: Float, t: Float, r: Float, b: Float) {
        val o = index++ * 4
        rects[o] = l; rects[o + 1] = t; rects[o + 2] = r; rects[o + 3] = b
    }
    k.groups.forEach { g ->
        if (g.label.isNotEmpty()) {
            put(k.padStartPx, y, k.padStartPx + widthPx, y + k.headerHeightPx)
            y += k.headerHeightPx + gap
        }
        buildRows(g, k.style, columns, k.widthDp, k.spacingDp).forEach { e ->
            if (e !is Entry.TileRow) return@forEach
            val slots = e.items.size + if (e.filler > 0f) 1 else 0
            val total = e.weights.sum() + e.filler
            val available = widthPx - gap * (slots - 1)
            val rowHeight = e.height?.let { it.value * k.density } ?: (available / total)
            var x = k.padStartPx
            for (i in e.items.indices) {
                val w = available * e.weights[i] / total
                put(x, y, x + w, y + rowHeight)
                x += w + gap
            }
            y += rowHeight + gap
        }
    }
    return MorphGeometry(rects)
}

/**
 * Two-finger pinch reports a running scale and its centre. Runs on the Initial pass
 * so the list doesn't scroll while two fingers are down; single-finger scrolling is untouched.
 */
private fun Modifier.pinchGesture(
    enabled: Boolean,
    /**
     * Allowed scale, read once [onStart] has run. Clamping the running scale (not just the
     * result) means reversing direction at 1 or 6 columns responds at once, with no dead zone.
     */
    scaleRange: () -> ClosedFloatingPointRange<Float>,
    onStart: (centroid: Offset) -> Unit = {},
    onZoom: (scale: Float, centroid: Offset, timeMillis: Long) -> Unit,
    onEnd: (scale: Float, centroid: Offset) -> Unit,
): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var scale = 1f
            var range = 0f..Float.MAX_VALUE
            var centroid = Offset.Zero
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) {
                    centroid = event.calculateCentroid(useCurrent = true)
                    if (!pinching) {
                        onStart(centroid)
                        range = scaleRange()
                    }
                    pinching = true
                    scale = (scale * event.calculateZoom()).coerceIn(range)
                    onZoom(scale, centroid, event.changes.first().uptimeMillis)
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinching) onEnd(scale, centroid)
        }
    }

/** Lazy keys are media ids (Long) or "r:/l:id" strings; headers ("h:…") map to null. */
private fun keyToId(key: Any): Long? = when (key) {
    is Long -> key
    is String -> if (key.startsWith("h:")) null else key.substringAfter(':').toLongOrNull()
    else -> null
}

/**
 * Last few results, kept across compositions. Opening a photo removes the grid from
 * composition, so without this every return from the viewer regrouped and re-laid out the
 * whole library on the main thread before the first frame. Keys compare lists by identity.
 */
private class RecentCache<K, V>(private val capacity: Int = 4) {
    private val slots = ArrayDeque<Pair<K, V>>()

    fun get(matches: (K) -> Boolean, compute: () -> Pair<K, V>): V {
        val hit = slots.firstOrNull { matches(it.first) }
        if (hit != null) return hit.second
        val fresh = compute()
        slots.addFirst(fresh)
        if (slots.size > capacity) slots.removeLast()
        return fresh.second
    }
}

private class GroupKey(val items: List<MediaItem>, val showDates: Boolean, val format: DateFormatOption)
private data class EntryKey(val groups: List<DayGroup>, val style: GridStyle, val columns: Int, val widthDp: Float, val spacing: Int)

private val groupCache = RecentCache<GroupKey, List<DayGroup>>()
// Room for every column count, which a pinch prepares ahead of time.
private val entryCache = RecentCache<EntryKey, List<Entry>>(capacity = MAX_COLUMNS + 2)

private const val DAY_MS = 24L * 60 * 60 * 1000
const val MIN_COLUMNS = 1
const val MAX_COLUMNS = 6
/** The fast scroller only shows on lists at least this many screens long. */
private const val FAST_SCROLL_MIN_SCREENS = 4

/** First item to show for a fast-scroll fraction: the inverse of scrollFraction(). */
private fun fractionToIndex(f: Float, total: Int, visible: Int) =
    (f * (total - visible).coerceAtLeast(0)).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))

/** The grid's usual padding, starting [top] down (e.g. below a floating glass header). */
fun gridPadding(top: Dp) = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = 100.dp)
/** How far ahead (seconds) the release velocity is projected when choosing the column count. */
private const val FLING_PROJECTION = 0.12f
/** Columns per second; caps a wild flick so the settle never races past the next layout. */
private const val MAX_LEVEL_VELOCITY = 8f

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    showDateHeaders: Boolean,
    dateFormat: DateFormatOption,
    selection: SelectionState,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    favoriteIds: Set<Long> = emptySet(),
    darkMode: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 100.dp),
) {
    val layout = LocalGridLayout.current
    val style = layout.style
    val spacing = layout.spacing
    val showDates = showDateHeaders && layout.showDateHeaders
    // Edge-to-edge styles drop the side margin, as Google Photos does.
    val dir = LocalLayoutDirection.current
    val padding = if (style == GridStyle.CLASSIC || spacing == 0) PaddingValues(
        start = 0.dp, end = 0.dp,
        top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding(),
    ) else contentPadding
    val groups = remember(items, showDates, dateFormat) {
        groupCache.get({ it.items === items && it.showDates == showDates && it.format == dateFormat }) {
            GroupKey(items, showDates, dateFormat) to groupByDay(items, showDates, dateFormat)
        }
    }
    val onColumns by rememberUpdatedState(layout.onColumnsChange)
    var liveColumns by remember(columns) { mutableIntStateOf(columns) }
    val currentColumns by rememberUpdatedState(liveColumns)

    // Pinch zoom, Google Photos style: while fingers move, the existing grid is only
    // scaled on the GPU (graphicsLayer, no recomposition). On release the column count
    // changes once, the grid re-lays out, and the scale springs back to 1 from the
    // equivalent size, so the motion stays continuous.
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val listState = rememberLazyListState()
    val staggeredState = rememberLazyStaggeredGridState()
    /** Item under the fingers when the pinch ended, and its y on screen, to keep it in place. */
    var anchor by remember { mutableStateOf<Pair<Long, Float>?>(null) }
    val itemIdAt: (Float) -> Long? = { y ->
        // (media id, top, bottom) of each visible row or tile; headers have no id.
        val visible = if (layout.style == GridStyle.MASONRY) {
            staggeredState.layoutInfo.visibleItemsInfo.map { Triple(keyToId(it.key), it.offset.y, it.offset.y + it.size.height) }
        } else {
            listState.layoutInfo.visibleItemsInfo.map { Triple(keyToId(it.key), it.offset, it.offset + it.size) }
        }.filter { it.first != null }
        (visible.firstOrNull { y.toInt() in it.second..it.third } ?: visible.firstOrNull())?.first
    }

    // Samsung-style morph for row-based grids: while pinching, every photo glides between
    // the layouts for the two nearest column counts (see PinchMorph.kt). Masonry, whose
    // columns flow independently, falls back to scaling the grid on the GPU.
    val morphs = style != GridStyle.MASONRY && style != GridStyle.LIST
    var morph by remember { mutableStateOf<MorphState?>(null) }
    /** True once the morph layer has drawn; until then the real grid stays visible. */
    var morphDrawn by remember { mutableStateOf(false) }
    /** Builds a MorphState from the current layout; set inside BoxWithConstraints, where sizes are known. */
    val morphStarter = remember { arrayOfNulls<(Offset) -> MorphState?>(1) }
    /** Morph driven by the gesture in progress; null when this pinch doesn't own one. */
    val activeMorph = remember { arrayOfNulls<MorphState>(1) }
    var pinchStartColumns by remember { mutableIntStateOf(columns) }
    /** Column count the fingers are nearest to, for one haptic tick per step. */
    var lastTick by remember { mutableIntStateOf(columns) }
    val levelVelocity = remember { VelocityTracker1D(isDataDifferential = false) }
    val haptics = LocalHapticFeedback.current

    val gridModifier = modifier
        .testTag("media_grid")
        .onSizeChanged { gridSize = it }
        .pinchGesture(
            enabled = layout.pinchToZoom && style != GridStyle.LIST,
            scaleRange = { pinchStartColumns.toFloat() / MAX_COLUMNS..pinchStartColumns.toFloat() / MIN_COLUMNS },
            onStart = { c ->
                pinchStartColumns = currentColumns
                lastTick = currentColumns
                levelVelocity.resetTracking()
                // A pinch that lands while the previous one is still settling is ignored,
                // rather than yanking the photos to a new anchor mid-glide.
                activeMorph[0] = if (morphs && morph == null) morphStarter[0]?.invoke(c)?.also { morph = it } else null
            },
            onZoom = { scale, c, time ->
                // Spreading (scale > 1) means bigger photos, i.e. fewer columns.
                val level = pinchStartColumns / scale
                val nearest = level.roundToInt()
                if (nearest != lastTick) {
                    lastTick = nearest
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                val m = activeMorph[0]
                if (m != null) {
                    m.level = level
                    m.fingerY = c.y
                    levelVelocity.addDataPoint(time, level)
                } else if (!morphs) {
                    if (gridSize.width > 0) origin = TransformOrigin(c.x / gridSize.width, c.y / gridSize.height)
                    scope.launch { zoom.snapTo(scale) }
                }
            },
            onEnd = { scale, c ->
                val m = activeMorph[0]
                activeMorph[0] = null
                if (m != null) {
                    // Carry the fingers' speed into the settle, so lifting them doesn't stop
                    // the motion dead before it starts again.
                    val velocity = levelVelocity.calculateVelocity().coerceIn(-MAX_LEVEL_VELOCITY, MAX_LEVEL_VELOCITY)
                    val lo = floor(m.level).toInt().coerceAtLeast(MIN_COLUMNS)
                    val hi = ceil(m.level).toInt().coerceAtMost(MAX_COLUMNS)
                    // A quick flick finishes the step it is heading for; never skips one.
                    val target = (m.level + velocity * FLING_PROJECTION).roundToInt().coerceIn(lo, hi)
                    scope.launch {
                        Animatable(m.level).animateTo(
                            target.toFloat(),
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            initialVelocity = velocity,
                        ) { m.level = value.coerceIn(lo.toFloat(), hi.toFloat()) }
                        if (target != currentColumns) {
                            val g = m.geometry(target)
                            val id = m.nodes[m.anchor].item?.id
                            anchor = id?.let { it to (g.top(m.anchor) - m.scrollFor(target)) }
                            liveColumns = target
                            onColumns(target)
                            // Keep the morph on screen until the real grid has scrolled into place
                            // and drawn a frame, so the swap is invisible.
                            snapshotFlow { anchor }.first { it == null }
                            withFrameNanos {}
                            withFrameNanos {}
                        }
                        morph = null
                        morphDrawn = false
                    }
                    return@pinchGesture
                }
                if (morphs) return@pinchGesture
                val from = currentColumns
                val target = (from / scale).roundToInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                scope.launch {
                    if (target == from) {
                        zoom.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f))
                    } else {
                        anchor = itemIdAt(c.y)?.let { it to c.y }
                        // Tiles are now width/target; start at the size the user pinched to.
                        zoom.snapTo(scale * target / from)
                        liveColumns = target
                        onColumns(target)
                        zoom.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 400f))
                    }
                }
            },
        )
    val topPaddingPx = with(LocalDensity.current) { padding.calculateTopPadding().toPx() }
    val zoomLayer = Modifier.graphicsLayer {
        scaleX = zoom.value
        scaleY = zoom.value
        transformOrigin = origin
    }

    val tile: @Composable (MediaItem, Modifier, Int) -> Unit = { item, mod, sizePx ->
        MediaItemCard(
            item = item,
            cellSizePx = sizePx,
            style = style,
            darkMode = darkMode,
            isFavorite = item.id in favoriteIds,
            showDuration = layout.showVideoDuration,
            selectionMode = selection.active,
            selected = item.id in selection.selected,
            onClick = { if (selection.active) selection.toggle(item.id) else onOpen(item) },
            onLongClick = { selection.toggle(item.id) },
            modifier = mod,
        )
    }
    /** Measured date-header height, so the morph lines up with the list pixel for pixel. */
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val header: @Composable (DayGroup) -> Unit = { g ->
        val ids = remember(g) { g.items.map { it.id } }
        DateHeader(
            modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
            label = g.label,
            darkMode = darkMode,
            selecting = selection.active,
            allSelected = ids.all { it in selection.selected },
            onToggleAll = {
                val all = ids.all { it in selection.selected }
                selection.setAll(if (all) selection.selected - ids.toSet() else selection.selected + ids)
            },
        )
    }
    val cellPx = rememberCellSizePx(liveColumns, LocalConfiguration.current.screenWidthDp)

    if (style == GridStyle.MASONRY) {
        LaunchedEffect(liveColumns) {
            val (id, y) = anchor ?: return@LaunchedEffect
            anchor = null
            var index = 0
            run {
                groups.forEach { g ->
                    if (g.label.isNotEmpty()) index++
                    val i = g.items.indexOfFirst { it.id == id }
                    if (i >= 0) { index += i; return@run }
                    index += g.items.size
                }
            }
            staggeredState.scrollToItem(index)
            staggeredState.scrollBy(-(y - topPaddingPx))
        }
        // Date of every lazy item (headers and tiles), for the fast scroller's bubble.
        val itemLabels = remember(groups) {
            buildList { groups.forEach { g -> if (g.label.isNotEmpty()) add(g.label); repeat(g.items.size) { add(g.label) } } }
        }
        Box {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(liveColumns),
            state = staggeredState,
            modifier = gridModifier.then(zoomLayer),
            contentPadding = padding,
            verticalItemSpacing = spacing.dp,
            horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            groups.forEach { g ->
                if (g.label.isNotEmpty()) item(key = "h:${g.key}", span = StaggeredGridItemSpan.FullLine) { header(g) }
                items(g.items, key = { it.id }) { item ->
                    tile(item, Modifier.fillMaxWidth().aspectRatio(item.ratio()), cellPx)
                }
            }
        }
        FastScroller(
            progress = { staggeredState.scrollFraction() },
            scrolling = { staggeredState.isScrollInProgress },
            enabled = { staggeredState.layoutInfo.let { it.totalItemsCount > it.visibleItemsInfo.size * FAST_SCROLL_MIN_SCREENS } },
            onDrag = { f -> staggeredState.requestScrollToItem(fractionToIndex(f, staggeredState.layoutInfo.totalItemsCount, staggeredState.layoutInfo.visibleItemsInfo.size)) },
            label = { f -> itemLabels.getOrNull(fractionToIndex(f, itemLabels.size, staggeredState.layoutInfo.visibleItemsInfo.size))?.ifEmpty { null } },
            topInset = padding.calculateTopPadding(),
            bottomInset = padding.calculateBottomPadding(),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        }
        return
    }

    BoxWithConstraints(gridModifier) {
        val widthDp = maxWidth.value - padding.calculateStartPadding(dir).value - padding.calculateEndPadding(dir).value
        val entries = remember(groups, style, liveColumns, widthDp, spacing) {
            val key = EntryKey(groups, style, liveColumns, widthDp, spacing)
            entryCache.get({ it.groups === groups && it == key }) {
                key to groups.flatMap { g ->
                    (if (g.label.isNotEmpty()) listOf<Entry>(Entry.Header(g)) else emptyList()) +
                        buildRows(g, style, liveColumns, widthDp, spacing.toFloat())
                }
            }
        }
        val density = LocalDensity.current
        val padStartPx = with(density) { padding.calculateStartPadding(dir).toPx() }
        val defaultHeaderPx = with(density) { 52.dp.toPx() }
        val morphKey = MorphKey(
            groups, style, widthDp, spacing.toFloat(), density.density, padStartPx, topPaddingPx,
            if (headerHeightPx > 0) headerHeightPx.toFloat() else defaultHeaderPx,
        )
        val morphPrep = remember { arrayOfNulls<MorphPrep>(1) }
        if (morphs) LaunchedEffect(morphKey) {
            // Lay out every column count ahead of time, off the main thread, so neither the
            // start of a pinch nor crossing into a new column count stalls a frame.
            val (prep, rows) = withContext(Dispatchers.Default) {
                val nodes = morphNodes(groups)
                val geometries = Array(MAX_COLUMNS + 1) { c -> if (c < MIN_COLUMNS) null else morphGeometry(morphKey, nodes.size, c) }
                val rows = (MIN_COLUMNS..MAX_COLUMNS).associateWith { c ->
                    groups.flatMap { g ->
                        (if (g.label.isNotEmpty()) listOf<Entry>(Entry.Header(g)) else emptyList()) +
                            buildRows(g, style, c, widthDp, spacing.toFloat())
                    }
                }
                MorphPrep(morphKey, nodes, geometries) to rows
            }
            morphPrep[0] = prep
            // The grid's rows for each count too, so the re-layout when a pinch ends is cheaper.
            rows.forEach { (c, list) ->
                val key = EntryKey(groups, style, c, widthDp, spacing)
                entryCache.get({ it.groups === groups && it == key }) { key to list }
            }
        }
        morphStarter[0] = start@{ c ->
            val prep = morphPrep[0]?.takeIf { it.key == morphKey }
            val nodes = prep?.nodes ?: morphNodes(groups)
            if (nodes.isEmpty()) return@start null
            val geometries = prep?.geometries?.copyOf() ?: arrayOfNulls(MAX_COLUMNS + 1)
            val geometryFor = { cols: Int -> morphGeometry(morphKey, nodes.size, cols) }
            val g0 = geometries[liveColumns] ?: geometryFor(liveColumns).also { geometries[liveColumns] = it }
            val visible = listState.layoutInfo.visibleItemsInfo
            // Current scroll in content px, from any visible row.
            val row = visible.firstOrNull { (it.key as? String)?.startsWith("r:") == true } ?: return@start null
            val rowId = (row.key as String).removePrefix("r:").toLongOrNull() ?: return@start null
            val rowNode = nodes.indexOfFirst { it.item?.id == rowId }.takeIf { it >= 0 } ?: return@start null
            val scroll0 = g0.top(rowNode) - row.offset
            // The photo under the fingers (or the nearest one in that row).
            val cy = c.y + scroll0
            var a = g0.firstBelow(cy, nodes.size)
            var best = a
            var bestDist = Float.MAX_VALUE
            while (a < nodes.size && g0.top(a) <= cy + g0.height(a)) {
                if (nodes[a].item != null) {
                    val dx = when {
                        c.x < g0.left(a) -> g0.left(a) - c.x
                        c.x > g0.right(a) -> c.x - g0.right(a)
                        else -> 0f
                    }
                    val dy = if (cy in g0.top(a)..g0.bottom(a)) 0f else minOf(kotlin.math.abs(cy - g0.top(a)), kotlin.math.abs(cy - g0.bottom(a)))
                    if (dx + dy < bestDist) { bestDist = dx + dy; best = a }
                }
                a++
            }
            if (nodes[best].item == null) best = (best + 1).coerceAtMost(nodes.lastIndex)
            val fraction = ((cy - g0.top(best)) / g0.height(best).coerceAtLeast(1f)).coerceIn(0f, 1f)
            MorphState(nodes, geometries, geometryFor, best, fraction, liveColumns.toFloat(), c.y)
        }
        // After a pinch re-layout, scroll so the anchored photo sits exactly where the
        // morph left it (anchor.second = its top on screen).
        LaunchedEffect(entries) {
            val (id, y) = anchor ?: return@LaunchedEffect
            val index = entries.indexOfFirst { e ->
                when (e) {
                    is Entry.TileRow -> e.items.any { it.id == id }
                    is Entry.ListRow -> e.item.id == id
                    is Entry.Header -> false
                }
            }
            if (index >= 0) {
                listState.scrollToItem(index)
                val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat() ?: topPaddingPx
                listState.scrollBy(offset - y)
            }
            anchor = null
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(zoomLayer)
                // Hidden (but still laid out) while the morph layer stands in for it.
                .graphicsLayer { alpha = if (morph != null && morphDrawn) 0f else 1f },
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(if (style == GridStyle.LIST) 2.dp else spacing.dp),
        ) {
            items(entries, key = { it.key }, contentType = { it::class }) { e ->
                when (e) {
                    is Entry.Header -> header(e.group)
                    is Entry.ListRow -> ListItemRow(
                        item = e.item,
                        isFavorite = e.item.id in favoriteIds,
                        selectionMode = selection.active,
                        selected = e.item.id in selection.selected,
                        onClick = { if (selection.active) selection.toggle(e.item.id) else onOpen(e.item) },
                        onLongClick = { selection.toggle(e.item.id) },
                    )
                    is Entry.TileRow -> Row(
                        Modifier.fillMaxWidth().let { if (e.height != null) it.height(e.height) else it },
                        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
                    ) {
                        e.items.forEachIndexed { i, item ->
                            val w = e.weights[i]
                            val sizePx = if (e.height != null) with(density) { e.height.roundToPx() } else (cellPx * w).roundToInt()
                            tile(
                                item,
                                Modifier.weight(w).let { if (e.height != null) it.fillMaxHeight() else it.aspectRatio(w) },
                                sizePx,
                            )
                        }
                        if (e.filler > 0f) Spacer(Modifier.weight(e.filler))
                    }
                }
            }
        }
        val entryLabels = remember(entries) {
            var current = ""
            entries.map { e -> (if (e is Entry.Header) e.group.label else current).also { current = it } }
        }
        FastScroller(
            progress = { listState.scrollFraction() },
            scrolling = { listState.isScrollInProgress },
            enabled = { morph == null && listState.layoutInfo.let { it.totalItemsCount > it.visibleItemsInfo.size * FAST_SCROLL_MIN_SCREENS } },
            onDrag = { f -> listState.requestScrollToItem(fractionToIndex(f, listState.layoutInfo.totalItemsCount, listState.layoutInfo.visibleItemsInfo.size)) },
            label = { f -> entryLabels.getOrNull(fractionToIndex(f, entryLabels.size, listState.layoutInfo.visibleItemsInfo.size))?.ifEmpty { null } },
            topInset = padding.calculateTopPadding(),
            bottomInset = padding.calculateBottomPadding(),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        morph?.let { m ->
            // Frozen for the whole pinch: the column count changes under the morph at the end,
            // and re-requesting every thumbnail then would blank the tiles for a frame.
            val morphThumbPx = remember(m) { cellPx }
            MorphLayer(
                state = m,
                style = style,
                darkMode = darkMode,
                thumbnailPx = morphThumbPx,
                onFirstDraw = { morphDrawn = true },
            )
        }
    }
}

/** One tile. Shape and border come from the [GridStyle]; NOTHING keeps the original's press-scale. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaItemCard(
    item: MediaItem,
    cellSizePx: Int,
    style: GridStyle,
    darkMode: Boolean,
    isFavorite: Boolean,
    showDuration: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.95f
            selected -> if (style == GridStyle.CLASSIC) 0.9f else 0.97f
            else -> 1f
        },
        spring(dampingRatio = 0.5f, stiffness = 10000f),
        label = "card_scale",
    )
    val shape = RoundedCornerShape(style.cornerRadius.dp)
    val tileColor = if (darkMode) Color(0xFF2A2A2A) else Color(0xFFEEEEEE)
    val borderColor = if (darkMode) Color(0xFF404040) else Color(0xFFBDBDBD)

    Box(
        modifier
            .tileBounds(item.id)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (TileBounds.hiddenId == item.id) 0f else 1f
            }
            .clip(shape)
            .background(tileColor)
            .let { if (style.border) it.border(1.dp, borderColor, shape) else it }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        // The card already clips to the shape; a second clip here was a second rounded clip
        // per tile for the GPU on every frame of scrolling.
        MediaThumbnail(item, cellSizePx, Modifier.fillMaxSize())
        if (isFavorite) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp),
            ) {
                Icon(painterResource(R.drawable.fillfav2), "Favorite", tint = Color(0xFFFF4444), modifier = Modifier.size(14.dp))
            }
        }
        if (showDuration && item.isVideo) DurationBadge(item, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        if (selectionMode && selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f), shape))
            Icon(
                painterResource(R.drawable.circlechek), "Selected",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
            )
        } else if (selectionMode) {
            Icon(
                painterResource(R.drawable.circlechekoutline), null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
            )
        }
    }
}

@Composable
private fun DurationBadge(item: MediaItem, modifier: Modifier = Modifier) {
    Text(
        item.duration?.let(::formatDuration) ?: "",
        color = Color.White,
        fontFamily = CqMono,
        fontSize = 12.sp,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Aves-style list row: thumbnail, file name, date and size. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListItemRow(
    item: MediaItem,
    isFavorite: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val colors = LocalCustomColors.current
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.selectedItem.copy(alpha = 0.2f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) {
            MediaThumbnail(item, px, Modifier.fillMaxSize())
            if (item.isVideo) DurationBadge(item, Modifier.align(Alignment.BottomEnd).padding(3.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(item.displayName, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${dateFmt.format(Date(item.dateTaken))}  ·  ${formatSize(item.size)}",
                color = colors.unselectedItem, fontSize = 12.sp, maxLines = 1,
            )
            if (item.width > 0) Text("${item.width} × ${item.height}", color = colors.unselectedItem, fontSize = 12.sp)
        }
        if (isFavorite) Icon(painterResource(R.drawable.fillfav2), "Favorite", tint = Color(0xFFFF4444), modifier = Modifier.size(16.dp))
        if (selectionMode) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painterResource(if (selected) R.drawable.circlechek else R.drawable.circlechekoutline), null,
                tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Port of DateHeaderWithCheckbox: centred dot-matrix date, select-all circle while selecting. */
@Composable
private fun DateHeader(
    label: String,
    darkMode: Boolean,
    selecting: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (darkMode) Color.White else Color(0xFF212121),
            fontSize = 20.sp,
            fontFamily = CqMono,
            textAlign = TextAlign.Center,
        )
        if (selecting) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (allSelected) R.drawable.circlechek else R.drawable.circlechekoutline),
                    if (allSelected) "Selected" else "Select day",
                    tint = if (darkMode) Color.White else Color(0xFF212121),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Floating glass capsule shown while items are selected, [bottomPadding] above the
 * navigation bar (e.g. clear of the main tabs' own bar).
 */
@Composable
fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    bottomPadding: Dp = 0.dp,
    extra: @Composable () -> Unit = {},
) {
    GlassBottomBar(bottomPadding) {
        val ink = LocalGlassContent.current
        Box(
            Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onCancel).padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text("CANCEL", fontFamily = CqMono, fontSize = 13.sp, color = ink) }
        Text("$count SELECTED", fontFamily = CqMono, fontSize = 13.sp, color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(end = 6.dp))
        SelectionIcon(painterResource(R.drawable.squrechek), "Select all", onSelectAll)
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ink) { extra() }
        if (onShare != null) SelectionIcon(painterResource(R.drawable.share), "Share", onShare)
        SelectionIcon(painterResource(R.drawable.delete), "Move to trash", onDelete)
    }
}

@Composable
private fun SelectionIcon(painter: androidx.compose.ui.graphics.painter.Painter, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, description, tint = LocalGlassContent.current, modifier = Modifier.size(20.dp))
    }
}
