package com.appslab.gallery.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo

/**
 * Grid tiles currently laid out, by media id, so the viewer can grow out of the tapped tile
 * and shrink back into the one for the photo it ends on. Tiles only register on attach and
 * unregister on detach; their position is read only while a transition runs.
 */
object TileBounds {
    private val nodes = HashMap<Long, MutableList<TileBoundsNode>>()

    /**
     * Media id whose tile is hidden while the viewer's photo flies out of or back into it,
     * so the photo never shows twice. Read only in the tiles' layer blocks (draw phase).
     */
    var hiddenId by mutableLongStateOf(NO_ID)

    const val NO_ID = Long.MIN_VALUE

    internal fun add(node: TileBoundsNode) {
        nodes.getOrPut(node.id) { ArrayList(1) } += node
    }

    internal fun remove(node: TileBoundsNode) {
        val list = nodes[node.id] ?: return
        list -= node
        if (list.isEmpty()) nodes.remove(node.id)
    }

    /**
     * Full (unclipped) window rect of the on-screen tile for [id], or null if none is visible.
     * Several grids can hold the same photo (the pager keeps neighbouring tabs composed); only
     * one of them is on screen.
     */
    fun find(id: Long): Rect? {
        for (node in nodes[id].orEmpty()) {
            if (!node.isAttached) continue
            val coords = node.requireLayoutCoordinates()
            if (!coords.isAttached || coords.boundsInWindow().isEmpty) continue
            return Rect(coords.positionInWindow(), coords.size.let { Size(it.width.toFloat(), it.height.toFloat()) })
        }
        return null
    }
}

internal class TileBoundsNode(var id: Long) : Modifier.Node() {
    override fun onAttach() = TileBounds.add(this)
    override fun onDetach() = TileBounds.remove(this)
}

private data class TileBoundsElement(val id: Long) : ModifierNodeElement<TileBoundsNode>() {
    override fun create() = TileBoundsNode(id)

    override fun update(node: TileBoundsNode) {
        if (node.id == id) return
        TileBounds.remove(node)
        node.id = id
        TileBounds.add(node)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tileBounds"
        properties["id"] = id
    }
}

/** Registers this tile with [TileBounds] as the on-screen spot of media [id]. */
fun Modifier.tileBounds(id: Long): Modifier = this then TileBoundsElement(id)

/**
 * The open/close motion of the viewer: one thumbnail drawn from the grid tile's rect (cropped,
 * rounded like the tile) to the photo's fitted rect on screen. It is a single bitmap in one
 * draw call, so a frame of it costs next to nothing; the real viewer is only shown under it.
 *
 * [progress] is 0 at the tile and 1 full screen. [tile] returns the tile's window rect, or
 * null when it isn't on screen, in which case the photo just shrinks a little and fades.
 */
@Composable
fun ViewerTransitionLayer(
    bitmap: Bitmap,
    progress: () -> Float,
    tile: () -> Rect?,
    tileCornerPx: Float,
    modifier: Modifier = Modifier,
) {
    val paint = remember(bitmap) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
    }
    val origin = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Spacer(
        modifier
            .fillMaxSize()
            .onPlaced { origin[0] = it }
            .drawBehind {
                val p = progress()
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                // Where the photo sits once open: fitted and centred, as the viewer shows it.
                val fit = minOf(size.width / bw, size.height / bh)
                val full = Rect(
                    Offset((size.width - bw * fit) / 2, (size.height - bh * fit) / 2),
                    Size(bw * fit, bh * fit),
                )
                val windowTile = tile()
                val start = windowTile?.translate(-(origin[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero))
                // Clip: the tile's rect and corners at 0, the whole photo at 1.
                val clip: Rect
                val image: Rect
                val corner: Float
                var alpha = 1f
                if (start != null) {
                    clip = lerp(start, full, p)
                    corner = tileCornerPx * (1 - p)
                    // The photo as the tile crops it (ContentScale.Crop), growing into the fitted one.
                    val crop = maxOf(start.width / bw, start.height / bh)
                    val cropped = Rect(
                        Offset(start.center.x - bw * crop / 2, start.center.y - bh * crop / 2),
                        Size(bw * crop, bh * crop),
                    )
                    image = lerp(cropped, full, p)
                } else {
                    val shrink = 0.9f + 0.1f * p
                    val half = Offset(full.width * shrink / 2, full.height * shrink / 2)
                    clip = Rect(full.center - half, full.center + half)
                    image = clip
                    corner = 0f
                    alpha = p
                }
                if (clip.width <= 0f || clip.height <= 0f) return@drawBehind
                // Move the canvas rather than the shader: changing a shader's local matrix
                // rebuilds its native shader on every frame.
                val scale = image.width / bw
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save()
                canvas.translate(image.left, image.top)
                canvas.scale(scale, scale)
                canvas.drawRoundRect(
                    (clip.left - image.left) / scale, (clip.top - image.top) / scale,
                    (clip.right - image.left) / scale, (clip.bottom - image.top) / scale,
                    corner / scale, corner / scale, paint,
                )
                canvas.restore()
            },
    )
}
