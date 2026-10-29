package com.appslab.gallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = keyFormat.format(Date())
    val yesterday = keyFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
    return items.groupByTo(LinkedHashMap()) { keyFormat.format(Date(it.dateAdded)) }.map { (key, group) ->
        val label = when (key) {
            today -> "TODAY"
            yesterday -> "YESTERDAY"
            else -> formatter.format(Date(group.first().dateAdded))
        }
        DayGroup(key, label.uppercase(Locale.ROOT), group)
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

/** Two-finger pinch changes the column count, like Google Photos. Single-finger scrolling is untouched. */
private fun Modifier.pinchColumns(enabled: Boolean, columns: Int, onChange: (Int) -> Unit): Modifier =
    if (!enabled) this else pointerInput(columns) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var done = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1 && !done) {
                    zoom *= event.calculateZoom()
                    val next = when {
                        zoom > 1.3f -> columns - 1
                        zoom < 0.75f -> columns + 1
                        else -> columns
                    }.coerceIn(2, 5)
                    if (next != columns) {
                        onChange(next)
                        done = true
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

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
    val groups = remember(items, showDates, dateFormat) { groupByDay(items, showDates, dateFormat) }
    val onColumns by rememberUpdatedState(layout.onColumnsChange)
    val gridModifier = modifier.pinchColumns(layout.pinchToZoom, columns) { onColumns(it) }

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
    val header: @Composable (DayGroup) -> Unit = { g ->
        val ids = remember(g) { g.items.map { it.id } }
        DateHeader(
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
    val cellPx = rememberCellSizePx(columns, LocalConfiguration.current.screenWidthDp)

    if (style == GridStyle.MASONRY) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            modifier = gridModifier,
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
        return
    }

    BoxWithConstraints(gridModifier) {
        val widthDp = maxWidth.value - padding.calculateStartPadding(dir).value - padding.calculateEndPadding(dir).value
        val entries = remember(groups, style, columns, widthDp, spacing) {
            groups.flatMap { g ->
                (if (g.label.isNotEmpty()) listOf<Entry>(Entry.Header(g)) else emptyList()) +
                    buildRows(g, style, columns, widthDp, spacing.toFloat())
            }
        }
        val density = LocalDensity.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
        MediaThumbnail(item, cellSizePx, Modifier.fillMaxSize().clip(shape))
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
private fun DateHeader(label: String, darkMode: Boolean, selecting: Boolean, allSelected: Boolean, onToggleAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
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

/** Bottom bar shown while items are selected. */
@Composable
fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth(),
        color = LocalCustomColors.current.cardBackground,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("CANCEL", fontFamily = CqMono) }
            Text(
                "SELECTED $count", fontFamily = CqMono, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) { Icon(painterResource(R.drawable.squrechek), "Select all") }
            extra()
            if (onShare != null) IconButton(onClick = onShare) { Icon(painterResource(R.drawable.share), "Share") }
            IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.delete), "Move to trash") }
        }
    }
}
