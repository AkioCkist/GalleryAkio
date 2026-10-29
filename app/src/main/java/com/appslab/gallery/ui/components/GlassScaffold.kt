package com.appslab.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslab.gallery.ui.theme.CqMono
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Height of the floating header's controls. */
val GLASS_HEADER_SIZE = 44.dp

/**
 * Screen with a floating liquid-glass header (back button, title pill, [actions]) in place of
 * a TopAppBar. [content] fills the screen and scrolls under the header; it gets the top
 * padding that keeps its first item clear of it.
 */
@Composable
fun GlassScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.(Backdrop) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    // What the header blurs: the screen's content, recorded into a layer as it changes.
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    val dark = glassIsDark()
    val ink = glassContent(dark)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + GLASS_HEADER_SIZE + 16.dp
    Box(Modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(PaddingValues(top = top)) }
        // Content scrolls under the transparent status bar; fade it so the clock stays readable.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0.7f)))),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCircleButton(backdrop, dark, Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, size = GLASS_HEADER_SIZE)
            Column(
                Modifier
                    .height(GLASS_HEADER_SIZE)
                    .liquidGlass(backdrop, dark, lensHeight = 6.dp, lensAmount = 12.dp)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, fontFamily = CqMono, fontSize = 15.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = ink.copy(alpha = 0.6f), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            actions(backdrop)
        }
    }
}

/** A text action in a glass capsule, for headers (e.g. EMPTY in Trash). */
@Composable
fun GlassTextButton(backdrop: Backdrop, text: String, onClick: () -> Unit, color: Color? = null) {
    val dark = glassIsDark()
    Box(
        Modifier
            .height(GLASS_HEADER_SIZE)
            .liquidGlass(backdrop, dark, lensHeight = 6.dp, lensAmount = 12.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = CqMono, fontSize = 14.sp, color = color ?: glassContent(dark))
    }
}
