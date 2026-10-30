package com.example.galleryakio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.galleryakio.ui.theme.CqMono
import androidx.compose.foundation.layout.BoxScope
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.abs

/** Height of the floating bar; screens under it pad their content by this plus [NAV_BAR_MARGIN]. */
val NAV_BAR_HEIGHT = 56.dp
val NAV_BAR_MARGIN = 12.dp
private val TAB_WIDTH = 64.dp
private val INSET = 4.dp
private val CapsuleShape = RoundedCornerShape(50)

data class NavTab(val label: String, val icon: ImageVector)

/** Glass colours: the tint drawn over the blurred backdrop, and the content on top. */
private class GlassColors(val surface: Color, val content: Color, val indicator: Color)

private fun glassColors(darkMode: Boolean) =
    if (darkMode) GlassColors(Color(0xFF121212).copy(alpha = 0.45f), Color.White, Color.White.copy(alpha = 0.14f))
    else GlassColors(Color(0xFFFAFAFA).copy(alpha = 0.5f), Color(0xFF151515), Color.Black.copy(alpha = 0.08f))

/**
 * Makes [content] the source of [backdrop] while [enabled], i.e. while some glass over it is
 * showing. A [SharedBackdrop] costs little (the content is still recorded only once), but
 * nothing at all is better while no glass uses it. [enabled] is read here, so flipping it
 * recomposes only this Box.
 */
@Composable
fun BackdropSource(
    backdrop: SharedBackdrop,
    enabled: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.then(if (enabled()) Modifier.sharedBackdrop(backdrop) else Modifier), content = content)
}

/** Liquid glass surface: the content behind is blurred, saturated and bent at the edges. */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    darkMode: Boolean,
    lensHeight: Dp = 8.dp,
    lensAmount: Dp = 16.dp,
    shape: Shape = CapsuleShape,
    blurRadius: Dp = 4.dp,
    /** Tint over the blur; panels with text use a denser one than the default. */
    surface: Color = glassColors(darkMode).surface,
): Modifier {
    if (EXP and 1 != 0) return this.background(surface, shape)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurRadius.toPx())
            lens(lensHeight.toPx(), lensAmount.toPx())
        },
        onDrawSurface = { drawRect(surface) },
    )
}

/**
 * Compact floating tab bar in liquid glass. [position] is the selected page including the
 * swipe in progress (e.g. pager page + offset fraction); it is read only while drawing, so
 * the indicator follows a swipe without recomposing the bar.
 */
@Composable
fun GlassNavBar(
    backdrop: Backdrop,
    tabs: List<NavTab>,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = glassColors(darkMode)
    Box(
        modifier
            .liquidGlass(backdrop, darkMode)
            .height(NAV_BAR_HEIGHT)
            .padding(INSET),
    ) {
        // Selection capsule, slid on the GPU.
        Box(
            Modifier
                .graphicsLayer { translationX = position() * TAB_WIDTH.toPx() }
                .width(TAB_WIDTH)
                .fillMaxHeight()
                .background(colors.indicator, CapsuleShape),
        )
        Row {
            tabs.forEachIndexed { i, tab ->
                TabItem(tab, i, position, colors.content, onClick = { onSelect(i) })
            }
        }
    }
}

@Composable
private fun TabItem(tab: NavTab, index: Int, position: () -> Float, content: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .width(TAB_WIDTH)
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            // Fades between dimmed and full as the indicator passes over it.
            .graphicsLayer { alpha = 0.55f + 0.45f * (1f - abs(position() - index)).coerceIn(0f, 1f) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, tint = content, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(tab.label, color = content, fontFamily = CqMono, fontSize = 11.sp, maxLines = 1)
    }
}

/** Round glass button matching the bar, e.g. for the overflow menu beside it. */
@Composable
fun GlassCircleButton(
    backdrop: Backdrop,
    darkMode: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = NAV_BAR_HEIGHT,
) {
    Box(
        Modifier
            .size(size)
            .liquidGlass(backdrop, darkMode)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = glassColors(darkMode).content, modifier = Modifier.size(22.dp))
    }
}

// TEMP perf experiment
val EXP: Int = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.gallery.exp") as String
}.getOrNull()?.toIntOrNull() ?: 0
