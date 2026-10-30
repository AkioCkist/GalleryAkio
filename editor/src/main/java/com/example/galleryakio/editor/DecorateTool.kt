package com.example.galleryakio.editor

import android.text.Layout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot

private val PEN_COLORS = listOf(Color.White, Color.Black, Color(0xFFE53935), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFEC407A))
private val STICKERS = listOf("😀", "😂", "🥰", "😎", "🤩", "😭", "😡", "🥳", "😴", "🤔", "👍", "👎", "👏", "🙏", "💪", "✌️", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💔", "⭐", "✨", "🔥", "💯", "🎉", "🎂", "🎁", "🌸", "🌹", "🌈", "☀️", "🌙", "⚡", "☁️", "🐱", "🐶", "🐰", "🦋", "🍀", "🍕", "☕", "🎵")
private const val STICKER_SIZE = 0.16f
private const val MIN_TEXT = 0.02f
private const val MAX_TEXT = 0.5f

internal enum class DrawTool(val label: String) { PEN("Pen"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), ERASER("Eraser") }

internal class DrawSettings {
    var tool by mutableStateOf(DrawTool.PEN)
    var color by mutableStateOf(Color.White)
    var width by mutableStateOf(0.008f)
}

/** Draws under a finger. [picture] is where the picture sits in this layer. */
@Composable
internal fun DrawLayer(
    picture: Rect,
    settings: DrawSettings,
    marks: List<Mark>,
    onDraft: (StrokeMark?) -> Unit,
    onErase: (List<Mark>) -> Unit,
    onStroke: (StrokeMark) -> Unit,
    onEraseDone: () -> Unit,
) {
    val area by rememberUpdatedState(picture)
    val latestMarks by rememberUpdatedState(marks)
    Box(
        Modifier.fillMaxSize().pointerInput(settings) {
            var points = ArrayList<Float>()
            fun add(x: Float, y: Float) {
                points.add(((x - area.left) / area.width).coerceIn(0f, 1f))
                points.add(((y - area.top) / area.height).coerceIn(0f, 1f))
            }
            fun stroke() = StrokeMark(
                points.toList(),
                settings.color.toArgb(),
                settings.width,
                when (settings.tool) {
                    DrawTool.MARKER -> PenKind.MARKER
                    DrawTool.HIGHLIGHTER -> PenKind.HIGHLIGHTER
                    else -> PenKind.PEN
                },
            )
            fun erase(x: Float, y: Float) {
                val fx = (x - area.left) / area.width
                val fy = (y - area.top) / area.height
                val kept = latestMarks.filter { !(it is StrokeMark && it.isNear(fx, fy, 0.03f)) }
                if (kept.size != latestMarks.size) onErase(kept)
            }
            detectDragGestures(
                onDragStart = { at ->
                    if (settings.tool == DrawTool.ERASER) {
                        erase(at.x, at.y)
                    } else {
                        points = ArrayList()
                        add(at.x, at.y)
                        onDraft(stroke())
                    }
                },
                onDragEnd = {
                    if (settings.tool == DrawTool.ERASER) onEraseDone()
                    else {
                        onStroke(stroke())
                        onDraft(null)
                    }
                },
                onDragCancel = { onDraft(null) },
            ) { change, _ ->
                change.consume()
                if (settings.tool == DrawTool.ERASER) {
                    erase(change.position.x, change.position.y)
                } else {
                    add(change.position.x, change.position.y)
                    onDraft(stroke())
                }
            }
        },
    )
}

private fun StrokeMark.isNear(x: Float, y: Float, reach: Float): Boolean {
    var i = 0
    while (i + 1 < points.size) {
        if (hypot(points[i] - x, points[i + 1] - y) < reach) return true
        i += 2
    }
    return false
}

/** Texts and stickers: drag to move, pinch to resize, tap to edit. */
@Composable
internal fun TextLayer(
    picture: Rect,
    marks: List<Mark>,
    onChange: (List<Mark>) -> Unit,
    onCommit: () -> Unit,
    onTap: (TextMark) -> Unit,
) {
    val area by rememberUpdatedState(picture)
    val latest by rememberUpdatedState(marks)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val fx = (down.position.x - area.left) / area.width
                val fy = (down.position.y - area.top) / area.height
                // The one on top, with some room around it for a finger.
                val hit = latest.filterIsInstance<TextMark>().lastOrNull { mark ->
                    val box = mark.bounds(area.width, area.height)
                    val slopX = 24.dp.toPx() / area.width
                    val slopY = 24.dp.toPx() / area.height
                    fx > box.left - slopX && fx < box.right + slopX && fy > box.top - slopY && fy < box.bottom + slopY
                } ?: return@awaitEachGesture
                down.consume()
                var moved = false
                var total = 0f
                var current = hit
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    total += abs(pan.x) + abs(pan.y)
                    if (total > viewConfiguration.touchSlop || zoom != 1f) moved = true
                    if (moved) {
                        current = current.copy(
                            x = (current.x + pan.x / area.width).coerceIn(0f, 1f),
                            y = (current.y + pan.y / area.height).coerceIn(0f, 1f),
                            size = (current.size * zoom).coerceIn(MIN_TEXT, MAX_TEXT),
                        )
                        change(latest.map { if (it is TextMark && it.id == hit.id) current else it })
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (moved) commit() else tap(hit)
            }
        },
    )
}

/** Tools for drawing, in the capsule Samsung has. */
@Composable
internal fun DrawPanel(settings: DrawSettings) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.height(52.dp).liquid(RoundedCornerShape(50)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawTool.entries.forEach { tool ->
                val icon = when (tool) {
                    DrawTool.PEN -> Icons.Rounded.Edit
                    DrawTool.MARKER -> Icons.Rounded.BorderColor
                    DrawTool.HIGHLIGHTER -> Icons.Rounded.Brush
                    DrawTool.ERASER -> Icons.Rounded.AutoFixOff
                }
                val chosen = settings.tool == tool
                Box(
                    Modifier
                        .size(48.dp)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(if (chosen) Color.White.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable(role = Role.RadioButton, onClickLabel = tool.label) { settings.tool = tool },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, tool.label, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (settings.tool != DrawTool.ERASER) {
            ColorRow(settings.color) { settings.color = it }
        } else {
            Text("Touch a line to remove it", color = Muted, fontSize = 13.sp, modifier = Modifier.height(40.dp).padding(top = 10.dp))
        }
    }
}

@Composable
internal fun ColorRow(selected: Color, onPick: (Color) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        PEN_COLORS.forEach { color ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.RadioButton) { onPick(color) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (color == selected) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(if (color == selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickerPanel(onPick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        STICKERS.forEach { emoji ->
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Add sticker") { onPick(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 28.sp)
            }
        }
    }
}

internal fun newSticker(emoji: String) = TextMark(id = System.nanoTime(), text = emoji, size = STICKER_SIZE)

/**
 * Writing a text, over the picture, with the keyboard up. Changes are shown live; [onDone] with an
 * empty text removes it.
 */
@Composable
internal fun TextEditor(mark: TextMark, onChange: (TextMark) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).height(48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onDone).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = Color.White, fontSize = 16.sp) }

        // How big, on the left as on Samsung.
        SizeBar(
            value = (mark.size - MIN_TEXT) / (MAX_TEXT - MIN_TEXT),
            onValue = { onChange(mark.copy(size = MIN_TEXT + it * (MAX_TEXT - MIN_TEXT))) },
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 56.dp),
        )

        BasicTextField(
            value = mark.text,
            onValueChange = { onChange(mark.copy(text = it)) },
            textStyle = TextStyle(
                color = Color(mark.color),
                fontSize = (mark.size * 300f).coerceIn(14f, 64f).sp,
                fontFamily = FontFamily(Typeface(mark.style.typeface)),
                textAlign = when (mark.align) {
                    Layout.Alignment.ALIGN_NORMAL -> TextAlign.Start
                    Layout.Alignment.ALIGN_OPPOSITE -> TextAlign.End
                    else -> TextAlign.Center
                },
            ),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, top = 64.dp)
                .focusRequester(focus),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .liquid(RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val alignIcon = when (mark.align) {
                    Layout.Alignment.ALIGN_NORMAL -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                    Layout.Alignment.ALIGN_OPPOSITE -> Icons.AutoMirrored.Rounded.FormatAlignRight
                    else -> Icons.Rounded.FormatAlignCenter
                }
                PlainIcon(alignIcon, "Alignment") {
                    onChange(
                        mark.copy(
                            align = when (mark.align) {
                                Layout.Alignment.ALIGN_CENTER -> Layout.Alignment.ALIGN_NORMAL
                                Layout.Alignment.ALIGN_NORMAL -> Layout.Alignment.ALIGN_OPPOSITE
                                else -> Layout.Alignment.ALIGN_CENTER
                            },
                        ),
                    )
                }
                PlainIcon(Icons.Rounded.TextFields, if (mark.background) "Remove background" else "Add background", tint = if (mark.background) Accent else Color.White) {
                    onChange(mark.copy(background = !mark.background))
                }
            }
            ColorRow(Color(mark.color)) { onChange(mark.copy(color = it.toArgb())) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextStyleKind.entries.forEach { style ->
                    val chosen = style == mark.style
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (chosen) Color.White else Color.Transparent)
                            .clickable(role = Role.RadioButton, onClickLabel = style.name) { onChange(mark.copy(style = style)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = if (chosen) Color.Black else Color.White, fontSize = 18.sp, fontFamily = FontFamily(Typeface(style.typeface)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SizeBar(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(value)
    val set by rememberUpdatedState(onValue)
    Box(
        modifier
            .size(width = 40.dp, height = 170.dp)
            .liquid(RoundedCornerShape(50))
            .pointerInput(Unit) {
                var position = 0f
                detectVerticalDragGestures(onDragStart = { position = current }) { change, delta ->
                    change.consume()
                    position = (position - delta / size.height).coerceIn(0f, 1f)
                    set(position)
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = (10 + (1f - value) * 130).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
