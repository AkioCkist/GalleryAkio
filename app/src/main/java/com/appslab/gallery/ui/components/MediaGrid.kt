package com.appslab.gallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslab.gallery.R
import com.appslab.gallery.data.DateFormatOption
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

private sealed interface GridEntry {
    val key: String

    data class Header(val dateKey: String, val label: String, val ids: List<Long>) : GridEntry {
        override val key get() = "h:$dateKey"
    }

    data class Row(val items: List<MediaItem>) : GridEntry {
        override val key get() = "r:${items.first().id}"
    }
}

/**
 * Port of createPerfectGridListItems: one header per day, then rows of [columns]
 * items. The incoming order is kept, so callers sort first.
 */
private fun buildEntries(items: List<MediaItem>, columns: Int, showDates: Boolean, format: DateFormatOption): List<GridEntry> {
    if (!showDates) return items.chunked(columns).map(GridEntry::Row)
    val formatter = SimpleDateFormat(format.pattern.replace(" HH:mm", ""), Locale.getDefault())
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = keyFormat.format(Date())
    val yesterday = keyFormat.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)

    val out = ArrayList<GridEntry>()
    // The original grouped by DATE_ADDED, not the EXIF capture date.
    items.groupByTo(LinkedHashMap()) { keyFormat.format(Date(it.dateAdded)) }.forEach { (key, group) ->
        val label = when (key) {
            today -> "TODAY"
            yesterday -> "YESTERDAY"
            else -> formatter.format(Date(group.first().dateAdded))
        }
        out += GridEntry.Header(key, label.uppercase(Locale.ROOT), group.map { it.id })
        group.chunked(columns).forEach { out += GridEntry.Row(it) }
    }
    return out
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
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 100.dp),
) {
    val entries = remember(items, columns, showDateHeaders, dateFormat) {
        buildEntries(items, columns, showDateHeaders, dateFormat)
    }
    val cellPx = rememberCellSizePx(columns, LocalConfiguration.current.screenWidthDp)

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.key }, contentType = { if (it is GridEntry.Header) 0 else 1 }) { e ->
            when (e) {
                is GridEntry.Header -> DateHeader(
                    label = e.label,
                    darkMode = darkMode,
                    selecting = selection.active,
                    allSelected = e.ids.all { it in selection.selected },
                    onToggleAll = {
                        val all = e.ids.all { it in selection.selected }
                        selection.setAll(if (all) selection.selected - e.ids.toSet() else selection.selected + e.ids)
                    },
                )
                is GridEntry.Row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.items.forEachIndexed { index, item ->
                        // The last item of a short row stretches over the empty columns.
                        val span = if (index == e.items.lastIndex) columns - e.items.size + 1 else 1
                        MediaItemCard(
                            item = item,
                            cellSizePx = cellPx * span,
                            aspectRatio = span.toFloat(),
                            darkMode = darkMode,
                            isFavorite = item.id in favoriteIds,
                            selectionMode = selection.active,
                            selected = item.id in selection.selected,
                            onClick = { if (selection.active) selection.toggle(item.id) else onOpen(item) },
                            onLongClick = { selection.toggle(item.id) },
                            modifier = Modifier.weight(span.toFloat()),
                        )
                    }
                }
            }
        }
    }
}

/** Port of MediaItemCard: 18 dp rounded tile with a 1 dp border and press-scale. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaItemCard(
    item: MediaItem,
    cellSizePx: Int,
    aspectRatio: Float,
    darkMode: Boolean,
    isFavorite: Boolean,
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
            selected -> 0.97f
            else -> 1f
        },
        spring(dampingRatio = 0.5f, stiffness = 10000f),
        label = "card_scale",
    )
    val shape = RoundedCornerShape(18.dp)
    val tileColor = if (darkMode) Color(0xFF2A2A2A) else Color(0xFFEEEEEE)
    val borderColor = if (darkMode) Color(0xFF404040) else Color(0xFFBDBDBD)

    Box(
        modifier
            .aspectRatio(aspectRatio)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(tileColor)
            .border(1.dp, borderColor, shape)
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
