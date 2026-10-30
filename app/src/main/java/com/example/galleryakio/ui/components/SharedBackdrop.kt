package com.example.galleryakio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.Backdrop

/**
 * What glass shows through, without drawing it twice. The library's LayerBackdrop draws its
 * content to the screen and then records it again into its layer, so every frame the content
 * changes (each frame of a scroll) records the whole grid twice, and once more for each
 * backdrop it sits in. Here the content is recorded once into [layer], the screen draws that
 * layer, and glass draws the same layer again: a reference to an already recorded node.
 *
 * [background] is drawn under the content only where glass shows it, for content that
 * leaves gaps the screen fills from further back.
 */
@Stable
class SharedBackdrop internal constructor(
    internal val layer: GraphicsLayer,
    private val background: Color,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)

    /** [layerBlock] (a transform on the glass itself) is not undone: scale glass outside it instead. */
    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val glass = coordinates?.takeIf { it.isAttached } ?: return
        val source = this@SharedBackdrop.coordinates?.takeIf { it.isAttached } ?: return
        val offset = try {
            source.localPositionOf(glass)
        } catch (_: Exception) {
            glass.positionInWindow() - source.positionInWindow()
        }
        translate(-offset.x, -offset.y) {
            if (background.alpha > 0f) drawRect(background, size = layer.size.toSize())
            drawLayer(layer)
        }
    }
}

@Composable
fun rememberSharedBackdrop(background: Color = Color.Transparent): SharedBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer, background) { SharedBackdrop(layer, background) }
}

/** Makes this node's content the source of [backdrop]: recorded once, drawn on screen from the layer. */
fun Modifier.sharedBackdrop(backdrop: SharedBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.coordinates = it }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }
