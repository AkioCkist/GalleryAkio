package com.appslab.gallery.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslab.gallery.ui.theme.CqMono

private val HeaderEasing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

/** Height of the header below the status bar: tab row (70) + top padding (12) + indicator (4). */
val TabHeaderHeight: Dp = 86.dp

/**
 * Port of the original AutoHideTabHeader: a cyclic carousel showing the previous,
 * current and next tab, with the current one large and centred, a glowing
 * indicator underneath and the ⋮ menu on the right. Slides up when [visible] is false.
 */
@Composable
fun TabHeader(
    tabs: List<String>,
    selected: Int,
    visible: Boolean,
    darkMode: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    val offset by animateFloatAsState(if (visible) 0f else -(TabHeaderHeight.value + 40f), tween(350, easing = HeaderEasing), label = "header_offset")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250, easing = LinearEasing), label = "header_alpha")
    val scale by animateFloatAsState(if (visible) 1f else 0.98f, tween(350, easing = HeaderEasing), label = "header_scale")
    val background = (if (darkMode) Color.Black else Color.White).copy(alpha = 0.98f)

    Column(
        modifier
            .offset(y = offset.dp)
            .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
            .background(background)
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            SlidingTabRow(
                tabs = tabs,
                selected = selected,
                darkMode = darkMode,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.Center),
            )
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { menu() }
        }
        Box(
            Modifier.fillMaxWidth().height(4.dp).offset(y = (-12).dp),
            contentAlignment = Alignment.Center,
        ) {
            TabIndicator(darkMode)
        }
    }
}

@Composable
private fun SlidingTabRow(
    tabs: List<String>,
    selected: Int,
    darkMode: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.width(300.dp).height(70.dp), contentAlignment = Alignment.Center) {
        val n = tabs.size
        val visibleTabs = listOf((selected - 1 + n) % n, selected, (selected + 1) % n)
        val positions = listOf(-100f, 0f, 100f)
        visibleTabs.forEachIndexed { slot, index ->
            val x by animateFloatAsState(positions[slot], spring(dampingRatio = 0.5f, stiffness = 1500f), label = "tab_position_${index}_$slot")
            Box(
                Modifier
                    .offset(x = x.dp)
                    .size(width = 140.dp, height = 65.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedTabText(tabs[index], isSelected = index == selected, darkMode = darkMode)
            }
        }
    }
}

@Composable
private fun AnimatedTabText(text: String, isSelected: Boolean, darkMode: Boolean) {
    val size by animateFloatAsState(if (isSelected) 37f else 20f, spring(dampingRatio = 0.5f, stiffness = 10000f), label = "font_size_$text")
    val color = when {
        isSelected -> if (darkMode) Color.White else Color(0xFF212121)
        darkMode -> Color(0xFF808080)
        else -> Color(0xFF7F7F7F)
    }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        color = color,
        fontSize = size.sp,
        fontFamily = CqMono,
        letterSpacing = (if (isSelected) 0.3 else 0.5).sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
    )
}

/** Short pill under the centred tab, with a soft pulsing glow (indicator_pulse). */
@Composable
private fun TabIndicator(darkMode: Boolean) {
    val width by animateDpAsState(16.dp, spring(dampingRatio = 0.5f, stiffness = 1500f), label = "indicator_width")
    val height by animateDpAsState(3.dp, spring(dampingRatio = 0.5f, stiffness = 1500f), label = "indicator_height")
    val color = if (darkMode) Color.White else Color.Black.copy(alpha = 0.6f)
    val pulse by rememberInfiniteTransition(label = "indicator_pulse").animateFloat(
        0.5f, 0.95f, infiniteRepeatable(tween(1200, easing = HeaderEasing), RepeatMode.Reverse), label = "shimmer_alpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(width * 2.2f, height * 2.2f).background(color.copy(alpha = pulse * 0.15f), RoundedCornerShape(height * 1.1f)))
        Box(Modifier.size(width * 1.6f, height * 1.6f).background(color.copy(alpha = pulse * 0.25f), RoundedCornerShape(height * 0.8f)))
        Box(
            Modifier
                .size(width, height)
                .shadow(6.dp, RoundedCornerShape(height / 2), clip = false, ambientColor = color, spotColor = color)
                .background(
                    Brush.horizontalGradient(listOf(color.copy(alpha = 0.8f), color, color.copy(alpha = 0.8f))),
                    RoundedCornerShape(height / 2),
                ),
        )
        Box(Modifier.size(width * 0.6f, height * 0.4f).background(Color.White.copy(alpha = pulse * 0.4f), RoundedCornerShape(height)))
    }
}
