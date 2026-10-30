package com.example.galleryakio.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOOL_ADJUST = 0
private const val TOOL_PRESETS = 1
private const val TOOL_CROP = 2
private val TOOLS = listOf("Adjust", "Filters", "Crop")
private val IMAGE_PARAMS = Param.entries.toList()
private const val THUMB_SIDE = 160

/** What the pixel-effect pass depends on: colours alone never trigger it. */
private data class PixelKey(val turns: Int, val flipped: Boolean, val crop: CropFraction, val shadows: Float, val highlights: Float, val sharpen: Float, val vignette: Float)

private fun EditRecipe.pixelKey() = PixelKey(turns, flipped, crop, adjust.shadows, adjust.highlights, adjust.sharpen, adjust.vignette)

/**
 * Colour sliders only swap the colour filter of the same bitmap, so they cost one redraw on the
 * GPU. Crops and pixel effects render the small copy on a background thread; a newer value cancels
 * the render under way and the last result stays on screen meanwhile.
 */
@Composable
internal fun ImageEditor(uri: Uri, displayName: String, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recipe by remember { mutableStateOf(EditRecipe()) }
    // Reads recipe so canUndo / canRedo update with it.
    val history = remember { History(EditRecipe()) { recipe = it } }
    var tool by remember { mutableIntStateOf(TOOL_ADJUST) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    val proxy by produceState<Bitmap?>(null, uri) {
        value = runCatching { withContext(Dispatchers.IO) { loadBitmap(context, uri, PROXY_SIDE) } }.getOrElse { error ->
            if (error is CancellationException) throw error
            Toast.makeText(context, "Couldn't open this image", Toast.LENGTH_SHORT).show()
            onClose()
            null
        }
    }
    val thumb by produceState<Bitmap?>(null, proxy) {
        val source = proxy ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val scale = THUMB_SIDE.toFloat() / minOf(source.width, source.height)
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(proxy) {
        val source = proxy ?: return@LaunchedEffect
        snapshotFlow { recipe.pixelKey() }.collectLatest {
            // Lets a slider being dragged run ahead instead of rendering every step of it.
            if (recipe.adjust.hasPixelEffects) delay(30)
            val current = recipe
            rendered = withContext(Dispatchers.Default) {
                source.oriented(current).cropped(current.crop).withPixelEffects(context, current.adjust)
            }
        }
    }

    val close = discardGuard(recipe != EditRecipe(), onClose)
    Column(Modifier.fillMaxSize()) {
        if (tool != TOOL_CROP) {
            EditorTopBar(
                canUndo = recipe.let { history.canUndo },
                canRedo = recipe.let { history.canRedo },
                onClose = close,
                onUndo = history::undo,
                onRedo = history::redo,
                onSave = { saving = true },
                onCompare = { comparing = it },
            )
        }
        val source = proxy
        if (source == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            return@Column
        }
        if (tool == TOOL_CROP) {
            val picture = remember(source, recipe.turns, recipe.flipped) { source.oriented(recipe) }
            CropPanel(
                picture = picture,
                onTurn = { history.commit(recipe.turned().copy(crop = CropFraction())) },
                onFlip = { history.commit(recipe.flippedOver().copy(crop = CropFraction())) },
                onApply = {
                    history.commit(recipe.copy(crop = it))
                    tool = TOOL_ADJUST
                },
                onCancel = { tool = TOOL_ADJUST },
            )
            return@Column
        }
        val shown = if (comparing) source else rendered
        val image = remember(shown) { shown?.asImageBitmap() }
        val filter = remember(recipe.adjust) { ColorFilter.colorMatrix(recipe.adjust.composeMatrix()) }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(
                    BitmapPainter(image),
                    null,
                    colorFilter = if (comparing) null else filter,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        when (tool) {
            TOOL_ADJUST -> AdjustPanel(recipe.adjust, IMAGE_PARAMS, { history.preview(recipe.copy(adjust = it)) }, { history.commit(recipe.copy(adjust = it)) })
            TOOL_PRESETS -> PresetPanel(thumb, recipe.adjust) { history.commit(recipe.copy(adjust = it)) }
        }
        ToolTabs(TOOLS, tool) { tool = it }
    }

    if (saving) {
        ImageSaveDialog(onDismiss = { saving = false }) { format, quality ->
            saving = false
            exportJob = scope.launch {
                try {
                    onSaved(exportImage(context, uri, displayName, recipe, format, quality))
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Toast.makeText(context, error.exportMessage(), Toast.LENGTH_LONG).show()
                } finally {
                    exportJob = null
                }
            }
        }
    }
    exportJob?.let { job -> ExportOverlay(null) { job.cancel() } }
}
