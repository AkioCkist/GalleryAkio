package com.example.galleryakio.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

internal val Accent = Color(0xFFFFD21E)
internal val Muted = Color.White.copy(alpha = 0.55f)
/** Comfortable finger targets, whatever a control looks like. */
private val TOUCH_TARGET = 48.dp
private val GLASS_BUTTON = 44.dp
private val OPTION_SIZE = 60.dp
private val OPTION_GAP = 14.dp

/** The app's liquid glass, passed in: it lives in :app, and only needs a backdrop and a shape. */
fun interface EditorGlass {
    fun Modifier.glass(backdrop: Backdrop, shape: Shape): Modifier
}

internal val LocalEditorGlass = compositionLocalOf<EditorGlass> { error("No EditorGlass") }
internal val LocalEditorBackdrop = compositionLocalOf<Backdrop> { error("No backdrop") }

/**
 * Remembered, and in a layer of its own: a new modifier, or the parent's layer being recorded again
 * because a dial or the picture beside it changed, redoes the glass's blur (20 ms a frame for this
 * screen). Left alone over the still backdrop, it stays cached.
 */
@Composable
internal fun Modifier.liquid(shape: Shape = CircleShape): Modifier {
    val glass = LocalEditorGlass.current
    val backdrop = LocalEditorBackdrop.current
    return then(remember(glass, backdrop, shape) { with(glass) { Modifier.graphicsLayer { }.glass(backdrop, shape) } })
}

/**
 * Editor for one photo or video, laid out like Samsung Gallery's. Nothing here is composed or loaded
 * until this screen is opened.
 *
 * @param onSaved a new file was written (the original is never touched)
 */
@Composable
fun EditorScreen(
    uri: Uri,
    displayName: String,
    isVideo: Boolean,
    glass: EditorGlass,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
        CompositionLocalProvider(LocalEditorGlass provides glass) {
            Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (isVideo) VideoEditor(uri, displayName, onClose, onSaved)
                else ImageEditor(uri, displayName, onClose, onSaved)
            }
        }
    }
}

/** Asks before throwing edits away, on system back too. */
@Composable
internal fun DiscardGuard(dirty: Boolean, onClose: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { if (dirty) asking = true else onClose() }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Discard edits?") },
            confirmButton = { TextButton(onClick = { asking = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
internal fun GlassIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(GLASS_BUTTON)
            .liquid()
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun GlassTextButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(GLASS_BUTTON)
            .liquid(RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Undo and redo on the left, the file actions on the right, as Samsung has them. */
@Composable
internal fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canRevert: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    /** Format and quality, for pictures. */
    onSaveAs: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassIcon(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        GlassIcon(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = canRedo, onClick = onRedo)
        Spacer(Modifier.weight(1f))
        GlassTextButton("Revert", enabled = canRevert, onClick = onRevert)
        GlassTextButton("Save", enabled = canRevert, onClick = onSave)
        if (onSaveAs != null) {
            Box {
                GlassIcon(Icons.Rounded.MoreVert, "More options", enabled = canRevert) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Save as…") }, onClick = { menu = false; onSaveAs() })
                }
            }
        }
    }
}

/** The five tools, in one capsule like the viewer's bottom bar. */
@Composable
internal fun ToolBar(tools: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(56.dp).liquid(RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tools.forEachIndexed { index, (icon, label) ->
                Box(
                    Modifier
                        .size(width = 56.dp, height = 56.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Tab, onClickLabel = label) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, label, tint = if (index == selected) Accent else Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** A row that keeps its choice in the middle; swiping it chooses as well as tapping does. */
@Composable
internal fun CenteredRow(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    itemWidth: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    item: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val choose by rememberUpdatedState(onSelect)
    val currentCount by rememberUpdatedState(count)
    LaunchedEffect(selected) {
        if (!state.isScrollInProgress) state.animateScrollToItem(selected)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (scrolling, _) ->
                if (scrolling) return@collect
                val info = state.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val nearest = info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) } ?: return@collect
                if (nearest.index in 0 until currentCount) choose(nearest.index)
            }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = (maxWidth - itemWidth) / 2
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(gap),
            flingBehavior = rememberSnapFlingBehavior(state),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count) { index -> item(index, index == selected) }
        }
    }
}

/** One choice of what a dial changes: white when chosen, with a dot when it has been changed. */
@Composable
internal fun OptionCircle(icon: ImageVector, label: String, selected: Boolean, changed: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(OPTION_SIZE)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (selected) Modifier.clip(CircleShape).background(Color.White) else Modifier.liquid())
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        if (changed) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(10.dp).clip(CircleShape).background(Accent))
        }
    }
}

@Composable
internal fun OptionPicker(
    options: List<Pair<ImageVector, String>>,
    changed: (Int) -> Boolean,
    selected: Int,
    /** In the label's row, where it can't cover a circle being scrolled. */
    leading: (@Composable () -> Unit)? = null,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(GLASS_BUTTON).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(options[selected].second, color = Color.White, fontSize = 13.sp)
            if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        }
        Spacer(Modifier.height(4.dp))
        CenteredRow(options.size, selected, onSelect, OPTION_SIZE, OPTION_GAP) { index, isSelected ->
            OptionCircle(options[index].first, options[index].second, isSelected, changed(index)) { onSelect(index) }
        }
    }
}

/**
 * Samsung's ruler: the marks slide under a fixed line, with the number while it moves. Drags are
 * relative, so it can't jump on touch, and a double tap goes back to zero.
 */
@Composable
internal fun RulerDial(
    value: Float,
    min: Float,
    max: Float,
    onValue: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    label: (Float) -> String = { (it * 100).roundToInt().toString() },
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val current by rememberUpdatedState(value)
    val update by rememberUpdatedState(onValue)
    val commit by rememberUpdatedState(onCommit)
    var dragging by remember { mutableStateOf(false) }
    val pxPerUnit = with(density) { 150.dp.toPx() }
    val tickGap = with(density) { 8.dp.toPx() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (dragging || value != 0f) label(value) else " ", color = Accent, fontSize = 13.sp, modifier = Modifier.graphicsLayer { })
        Canvas(
            Modifier
                .graphicsLayer { }
                .fillMaxWidth()
                .height(TOUCH_TARGET)
                .pointerInput(min, max) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        update(0f)
                        commit(0f)
                    })
                }
                .pointerInput(min, max) {
                    var position = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            position = current
                            dragging = true
                        },
                        onDragEnd = {
                            dragging = false
                            commit(current)
                        },
                        onDragCancel = {
                            dragging = false
                            commit(current)
                        },
                    ) { change, delta ->
                        change.consume()
                        val before = position
                        position = (position - delta / pxPerUnit).coerceIn(min, max)
                        // Zero is easy to find: it's felt, and it holds the dial for a moment.
                        val crossed = before != 0f && (before < 0f) != (position < 0f) || (before != 0f && position == 0f)
                        if (crossed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val shown = if (abs(position) < 0.015f) 0f else position
                        update(shown)
                    }
                },
        ) {
            val center = size.width / 2f
            val mid = size.height / 2f
            val valueOffset = value * pxPerUnit
            val first = ((min * pxPerUnit) / tickGap).roundToInt()
            val last = ((max * pxPerUnit) / tickGap).roundToInt()
            for (i in first..last) {
                val x = center + i * tickGap - valueOffset
                if (x < -tickGap || x > size.width + tickGap) continue
                val edgeFade = (1f - abs(x - center) / center).coerceIn(0f, 1f)
                val major = i % 5 == 0
                val half = if (major) 9.dp.toPx() else 6.dp.toPx()
                drawLine(
                    Color.White.copy(alpha = edgeFade * (if (major) 0.9f else 0.5f)),
                    Offset(x, mid - half),
                    Offset(x, mid + half),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawLine(Accent, Offset(center, mid - 14.dp.toPx()), Offset(center, mid + 14.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Glass capsule choice, e.g. Draw / Stickers / Text. */
@Composable
internal fun TextTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.height(TOUCH_TARGET).clickable(role = Role.Tab) { onSelect(index) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (index == selected) Accent else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Blocks the screen while exporting, with the way out. [progress] null: no idea how far yet. */
@Composable
internal fun ExportOverlay(progress: Float?, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).roundToInt()} %", color = Color.White, fontSize = 14.sp)
            } else {
                CircularProgressIndicator(color = Accent)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        }
    }
}

@Composable
internal fun ImageSaveDialog(onDismiss: () -> Unit, onSave: (ImageFormat, Int) -> Unit) {
    var format by remember { mutableStateOf(ImageFormat.JPEG) }
    var quality by remember { mutableFloatStateOf(95f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as new file") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { Chip(it.label, it == format) { format = it } }
                }
                if (format.hasQuality) {
                    Spacer(Modifier.height(12.dp))
                    Text("Quality ${quality.roundToInt()}", fontSize = 13.sp)
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(format, quality.roundToInt()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp)
    }
}

/** Cancel and Done of a tool that takes over the panel: nothing it did stays without Done. */
@Composable
internal fun CancelDone(onCancel: () -> Unit, onDone: () -> Unit, doneLabel: String = "Done") {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = onCancel), contentAlignment = Alignment.Center) {
            Text("Cancel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = onDone), contentAlignment = Alignment.Center) {
            Text(doneLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
