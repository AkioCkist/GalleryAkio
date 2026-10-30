package com.example.galleryakio.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.galleryakio.ui.theme.CqMono
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/*
 * Menus, dialogs and sheets in liquid glass. Material's versions open in their own window,
 * which cannot see (and so cannot blur) the app behind it; these are drawn in the app's
 * window by [GlassOverlayHost] instead. The host records the app into its backdrop layer only
 * while one of them is open, so the rest of the time it costs nothing.
 */

private enum class OverlayKind { MENU, DIALOG, SHEET, BAR }

private class OverlayEntry(val kind: OverlayKind, val dark: Boolean) {
    var onDismiss: () -> Unit by mutableStateOf({})
    var anchor: Rect by mutableStateOf(Rect.Zero)
    var width: Dp by mutableStateOf(0.dp)
    var bottomPadding: Dp by mutableStateOf(0.dp)
    var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
}

private class OverlayHost {
    val entries = mutableStateListOf<OverlayEntry>()
}

private val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Forces dark (or light) glass below it, e.g. in the always-dark photo viewer. Null follows the theme. */
val LocalGlassDark = staticCompositionLocalOf<Boolean?> { null }

/** Text and icon colour on the glass panel being drawn. */
val LocalGlassContent = staticCompositionLocalOf { Color.White }

@Composable
fun glassIsDark(): Boolean = LocalGlassDark.current ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)

private fun panelTint(dark: Boolean) = if (dark) Color(0xFF161616).copy(alpha = 0.62f) else Color(0xFFF7F7F7).copy(alpha = 0.72f)

fun glassContent(dark: Boolean) = if (dark) Color.White else Color(0xFF151515)

/** Hosts glass overlays for everything in [content]; put it once near the root. */
@Composable
fun GlassOverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberSharedBackdrop(background)
    val active = host.entries.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (active) Modifier.sharedBackdrop(backdrop) else Modifier)) {
            CompositionLocalProvider(LocalOverlayHost provides host, content = content)
        }
        host.entries.forEachIndexed { i, entry ->
            key(entry) { OverlayLayer(entry, backdrop, topmost = i == host.entries.lastIndex) }
        }
    }
}

@Composable
private fun rememberOverlay(kind: OverlayKind, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit): OverlayEntry {
    val host = checkNotNull(LocalOverlayHost.current) { "Glass overlays need a GlassOverlayHost above them" }
    val dark = glassIsDark()
    val entry = remember { OverlayEntry(kind, dark) }
    entry.onDismiss = onDismiss
    entry.content = content
    DisposableEffect(entry) {
        host.entries += entry
        onDispose { host.entries -= entry }
    }
    return entry
}

@Composable
private fun OverlayLayer(entry: OverlayEntry, backdrop: Backdrop, topmost: Boolean) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(if (entry.kind == OverlayKind.MENU) 140 else 180)) }
    val dark = entry.dark
    if (entry.kind == OverlayKind.BAR) {
        // A floating bar: nothing to dismiss, and touches beside it reach the screen below.
        Box(
            Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 12.dp + entry.bottomPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            CompositionLocalProvider(LocalGlassContent provides glassContent(dark), LocalGlassDark provides dark) {
                Column(
                    Modifier
                        .graphicsLayer {
                            alpha = appear.value
                            translationY = (1f - appear.value) * 24.dp.toPx()
                        }
                        .liquidGlass(backdrop, dark, surface = panelTint(dark))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .height(52.dp)
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.Center,
                    content = entry.content,
                )
            }
        }
        return
    }
    BackHandler(enabled = topmost) { entry.onDismiss() }
    val shape = RoundedCornerShape(if (entry.kind == OverlayKind.MENU) 22.dp else 28.dp)
    val glass = Modifier.liquidGlass(
        backdrop, dark,
        lensHeight = 10.dp, lensAmount = 20.dp,
        shape = shape, blurRadius = 18.dp, surface = panelTint(dark),
    )
    // Taps on a panel must not fall through to the scrim, which dismisses.
    val swallow = Modifier.pointerInput(Unit) { detectTapGestures { } }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            // Dialogs and sheets dim what is behind them; menus leave it as it is.
            .then(if (entry.kind == OverlayKind.MENU) Modifier else Modifier.background(Color.Black.copy(alpha = 0.35f)))
            .pointerInput(entry) { detectTapGestures { entry.onDismiss() } },
    ) {
        CompositionLocalProvider(LocalGlassContent provides glassContent(dark), LocalGlassDark provides dark) {
            when (entry.kind) {
                OverlayKind.MENU -> MenuPlacement(entry.anchor) {
                    Column(
                        Modifier
                            .graphicsLayer {
                                val s = 0.9f + 0.1f * appear.value
                                scaleX = s
                                scaleY = s
                                transformOrigin = TransformOrigin(1f, 0f)
                            }
                            .then(glass)
                            .then(swallow)
                            .width(entry.width)
                            .padding(vertical = 6.dp),
                        content = entry.content,
                    )
                }
                OverlayKind.DIALOG -> Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .padding(horizontal = 24.dp)
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val s = 0.94f + 0.06f * appear.value
                                scaleX = s
                                scaleY = s
                            }
                            .then(glass)
                            .then(swallow)
                            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp),
                        content = entry.content,
                    )
                }
                OverlayKind.SHEET -> Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                            .graphicsLayer { translationY = (1f - appear.value) * 48.dp.toPx() }
                            .then(glass)
                            .then(swallow)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        content = entry.content,
                    )
                }
            }
        }
    }
}

/** Below the anchor with right edges aligned, or above it when there is no room below. */
@Composable
private fun MenuPlacement(anchor: Rect, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    val bottomInset = WindowInsets.systemBars.getBottom(density)
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val gap = 8.dp.roundToPx()
            val margin = 12.dp.roundToPx()
            val x = (anchor.right.roundToInt() - placeable.width)
                .coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
            val below = anchor.bottom.roundToInt() + gap
            val y = if (below + placeable.height <= constraints.maxHeight - bottomInset) below
            else (anchor.top.roundToInt() - gap - placeable.height).coerceAtLeast(topInset)
            placeable.place(x, y)
        }
    }
}

/**
 * Glass dropdown, the replacement for DropdownMenu: put it in the same Box as the button
 * that opens it. Only composed (and only recording the backdrop) while [expanded].
 */
@Composable
fun BoxScope.GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 220.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Spacer(Modifier.matchParentSize().onGloballyPositioned { anchor = it.boundsInWindow() })
    val where = anchor ?: return
    val entry = rememberOverlay(OverlayKind.MENU, onDismiss, content)
    entry.anchor = where
    entry.width = width
}

@Composable
fun GlassMenuItem(icon: Painter, title: String, subtitle: String? = null, tint: Color = LocalGlassContent.current, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontFamily = CqMono, fontSize = 15.sp, color = tint)
            if (subtitle != null) Text(subtitle, fontFamily = CqMono, fontSize = 11.sp, color = LocalGlassContent.current.copy(alpha = 0.6f))
        }
    }
}

/** Glass dialog: a title, any [content], and a row of [buttons] (see [GlassDialogButton]). */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    rememberOverlay(OverlayKind.DIALOG, onDismiss) {
        Text(title, fontFamily = CqMono, fontSize = 18.sp, color = LocalGlassContent.current)
        Spacer(Modifier.height(14.dp))
        content()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
    }
}

@Composable
fun GlassDialogButton(text: String, onClick: () -> Unit, color: Color = LocalGlassContent.current, enabled: Boolean = true) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text.uppercase(),
            fontFamily = CqMono,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) color else color.copy(alpha = 0.35f),
        )
    }
}

/**
 * A glass capsule floating at the bottom of the screen while it is composed (e.g. the
 * selection actions), [bottomPadding] above the navigation bar.
 */
@Composable
fun GlassBottomBar(bottomPadding: Dp = 0.dp, content: @Composable RowScope.() -> Unit) {
    val entry = rememberOverlay(OverlayKind.BAR, {}) { Row(verticalAlignment = Alignment.CenterVertically, content = content) }
    entry.bottomPadding = bottomPadding
}

/** Glass bottom sheet, the replacement for ModalBottomSheet. */
@Composable
fun GlassSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    rememberOverlay(OverlayKind.SHEET, onDismiss, content)
}

/** A choice row for dialogs: a round mark (filled when [selected]) and a label. */
@Composable
fun GlassChoiceRow(label: String, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    val content = LocalGlassContent.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) content else content.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(if (content.luminance() > 0.5f) Color.Black else Color.White))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = content, fontSize = 15.sp)
            if (description != null) Text(description, color = content.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

/** Single-line text field for glass dialogs. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    onDone: () -> Unit = {},
) {
    val content = LocalGlassContent.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(content.copy(alpha = 0.08f))
            .then(if (isError) Modifier.background(Color(0xFFFF5A5A).copy(alpha = 0.14f)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = content, fontSize = 15.sp),
            cursorBrush = SolidColor(content),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.weight(1f),
        )
        if (suffix != null) Text(suffix, color = content.copy(alpha = 0.55f), fontSize = 15.sp)
    }
}
