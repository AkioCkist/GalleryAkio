package com.example.galleryakio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxScope
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs

/** Height of the floating bar; screens under it pad their content by this plus [NAV_BAR_MARGIN]. */
val NAV_BAR_HEIGHT = 44.dp
val NAV_BAR_MARGIN = 12.dp
private val TAB_WIDTH = 48.dp
private val INSET = 3.dp
private val CapsuleShape = RoundedCornerShape(50)

data class NavTab(val label: String, val icon: ImageVector)

/** Glass colours: the tint drawn over the blurred backdrop, and the content on top. */
private class GlassColors(val surface: Color, val content: Color)

private fun glassColors(darkMode: Boolean) =
    if (darkMode) GlassColors(Color(0xFF121212).copy(alpha = 0.45f), Color.White)
    else GlassColors(Color(0xFFFAFAFA).copy(alpha = 0.5f), Color(0xFF151515))

/**
 * Records [content] into [backdrop] only while [enabled], i.e. while some glass over it is
 * showing. A recorded backdrop costs a second draw of the content on every frame it changes
 * (every frame of a playing video), whether or not any glass is visible to use it.
 * [enabled] is read here, so flipping it recomposes only this Box.
 */
@Composable
fun BackdropSource(
    backdrop: LayerBackdrop,
    enabled: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.then(if (enabled()) Modifier.layerBackdrop(backdrop) else Modifier), content = content)
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
 * The bar's glass, tuned like the fast-scroll drop: nearly clear, so the photos show through
 * bent at the rim, with a specular edge, a faint inner shade and a soft shadow. A thin tint
 * keeps the icons readable over bright photos.
 */
private fun Modifier.navGlass(backdrop: Backdrop, darkMode: Boolean): Modifier {
    val surface = if (darkMode) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.28f)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { CapsuleShape },
        effects = {
            vibrancy()
            blur(2.dp.toPx())
            lens(
                refractionHeight = size.minDimension * 0.42f,
                refractionAmount = size.minDimension * 0.9f,
                depthEffect = true,
                chromaticAberration = true,
            )
        },
        highlight = { Highlight(width = 1.dp, alpha = 0.9f, style = HighlightStyle.Default(angle = 60f, falloff = 1.5f)) },
        shadow = { Shadow(radius = 12.dp, offset = DpOffset(0.dp, 4.dp), color = Color.Black.copy(alpha = 0.25f)) },
        innerShadow = { InnerShadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.08f)) },
        onDrawSurface = { drawRect(surface) },
    )
}

private val IndicatorFill = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.14f)))
private val IndicatorRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.18f)))

/**
 * Compact floating icon-only tab bar in liquid glass. [position] is the selected page
 * including the swipe in progress (e.g. pager page + offset fraction); it is read only while
 * drawing, so the indicator follows a swipe without recomposing the bar.
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
            .navGlass(backdrop, darkMode)
            .height(NAV_BAR_HEIGHT)
            .padding(INSET),
    ) {
        // Selection capsule, a lighter pane on the glass with its own rim, slid on the GPU.
        Box(
            Modifier
                .graphicsLayer { translationX = position() * TAB_WIDTH.toPx() }
                .width(TAB_WIDTH)
                .fillMaxHeight()
                .background(IndicatorFill, CapsuleShape)
                .border(0.75.dp, IndicatorRim, CapsuleShape),
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
    Box(
        Modifier
            .width(TAB_WIDTH)
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            // Fades between dimmed and full as the indicator passes over it.
            .graphicsLayer { alpha = 0.6f + 0.4f * (1f - abs(position() - index)).coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(tab.icon, tab.label, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** Round button in the bar's glass, for the overflow menu beside it. */
@Composable
fun GlassNavButton(backdrop: Backdrop, darkMode: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(NAV_BAR_HEIGHT)
            .navGlass(backdrop, darkMode)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = glassColors(darkMode).content, modifier = Modifier.size(22.dp))
    }
}

/** Round glass button, e.g. for the actions in a floating header. */
@Composable
fun GlassCircleButton(
    backdrop: Backdrop,
    darkMode: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp,
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
