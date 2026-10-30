package com.example.galleryakio.editor

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material.icons.rounded.CropRotate
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Vignette
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val TOOL_CROP = 0
private const val TOOL_FILTERS = 1
private const val TOOL_TONE = 2
private const val TOOL_DECORATE = 3
private const val TOOL_MORE = 4
private val TOOLS = listOf(
    Icons.Rounded.CropRotate to "Transform",
    Icons.Rounded.Workspaces to "Filters",
    Icons.Rounded.WbSunny to "Tone",
    Icons.Rounded.EmojiEmotions to "Decorate",
    Icons.Rounded.GridView to "More",
)
private val TONE_PARAMS = Param.entries.toList()
private val GEOMETRY = listOf(
    Icons.Rounded.Straighten to "Straighten",
    Icons.Rounded.SwapVert to "Vertical skew",
    Icons.Rounded.SwapHoriz to "Horizontal skew",
)
private const val THUMB_SIDE = 160
private const val DECORATE_DRAW = 0
private const val DECORATE_STICKERS = 1
private const val DECORATE_TEXT = 2

/** What the GPU pass depends on, and with it the bitmap under the colour filter. */
private data class PixelKey(
    val turns: Int,
    val flipped: Boolean,
    val straighten: Float,
    val skewVertical: Float,
    val skewHorizontal: Float,
    val crop: CropFraction,
    val shadows: Float,
    val highlights: Float,
    val sharpen: Float,
    val vignette: Float,
    val spot: Map<SpotBand, SpotShift>,
    val face: FaceEffect,
)

private fun EditRecipe.pixelKey() = PixelKey(turns, flipped, straighten, skewVertical, skewHorizontal, crop, adjust.shadows, adjust.highlights, adjust.sharpen, adjust.vignette, spot, face)

internal fun Param.icon(): ImageVector = when (this) {
    Param.EXPOSURE -> Icons.Rounded.Exposure
    Param.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
    Param.CONTRAST -> Icons.Rounded.Contrast
    Param.HIGHLIGHTS -> Icons.Rounded.LightMode
    Param.SHADOWS -> Icons.Rounded.Nightlight
    Param.SATURATION -> Icons.Rounded.InvertColors
    Param.TEMPERATURE -> Icons.Rounded.Thermostat
    Param.TINT -> Icons.Rounded.Opacity
    Param.HUE -> Icons.Rounded.Palette
    Param.SHARPEN -> Icons.Rounded.Details
    Param.VIGNETTE -> Icons.Rounded.Vignette
}

private fun fitRect(imageWidth: Int, imageHeight: Int, box: Size): Rect {
    if (box.width <= 0f || box.height <= 0f || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = min(box.width / imageWidth, box.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    return Rect(Offset((box.width - w) / 2f, (box.height - h) / 2f), Size(w, h))
}

/**
 * Samsung Gallery's editor, in the app's glass. Cheap by construction:
 * - colour sliders swap the colour filter of one bitmap: one GPU redraw;
 * - straighten and skew are a canvas matrix over that bitmap;
 * - pixel effects render the small copy off the main thread, newest wins, last result stays up;
 * - the glass bends a copy of the picture without its colours, so moving a dial doesn't blur it
 *   again on every step.
 */
@Composable
internal fun ImageEditor(uri: Uri, displayName: String, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recipe by remember { mutableStateOf(EditRecipe()) }
    val history = remember { History(EditRecipe()) { recipe = it } }
    var tool by remember { mutableIntStateOf(TOOL_TONE) }
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

    // The turned picture for the crop tool, and the finished small copy for everything else.
    val oriented by produceState<Bitmap?>(null, proxy, recipe.turns, recipe.flipped) {
        val source = proxy ?: return@produceState
        val current = recipe
        value = withContext(Dispatchers.Default) { source.oriented(current) }
    }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var faces by remember { mutableStateOf<List<RectF>?>(null) }
    LaunchedEffect(proxy) {
        val source = proxy ?: return@LaunchedEffect
        var facesFor: PixelKey? = null
        snapshotFlow { recipe.pixelKey() }.collectLatest { key ->
            // Lets a dial being turned run ahead instead of rendering every step of it.
            if (recipe.hasPixelEffects) delay(30)
            val current = recipe
            rendered = withContext(Dispatchers.Default) {
                val base = source.oriented(current).transformed(current).cropped(current.crop)
                val sameFrame = facesFor?.let { it.turns == key.turns && it.flipped == key.flipped && it.crop == key.crop && it.straighten == key.straighten }
                if (!current.face.isZero && sameFrame != true) {
                    faces = runCatching { detectFaces(base) }.getOrDefault(emptyList())
                    facesFor = key
                }
                base.withPixelEffects(context, current, faces.orEmpty())
            }
        }
    }

    var draft by remember { mutableStateOf<StrokeMark?>(null) }
    val drawSettings = remember { DrawSettings() }
    var decorateMode by remember { mutableIntStateOf(DECORATE_DRAW) }
    var editingText by remember { mutableStateOf<Long?>(null) }
    var moreTool by remember { mutableStateOf<String?>(null) }
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    var cropRatioLabel by remember { mutableStateOf("Free") }

    val dirty = recipe != EditRecipe()
    DiscardGuard(dirty, onClose)

    val backdrop = rememberLayerBackdrop()
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pictureBounds by remember { mutableStateOf(Rect.Zero) }
    val filter = remember(recipe.preset, recipe.presetStrength, recipe.adjust) { ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(recipe.colorMatrix().array)) }

    fun save(format: ImageFormat, quality: Int) {
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

    CompositionLocalProvider(LocalEditorBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { root = it }) {
            // What the glass bends: changes only when the crop or the pixel effects do.
            val under = rendered
            val underImage = remember(under) { under?.asImageBitmap() }
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .drawBehind {
                        drawRect(Color.Black)
                        val image = underImage ?: return@drawBehind
                        val fit = fitRect(image.width, image.height, pictureBounds.size)
                        drawImage(
                            image,
                            dstOffset = IntOffset((pictureBounds.left + fit.left).toInt(), (pictureBounds.top + fit.top).toInt()),
                            dstSize = IntSize(fit.width.toInt(), fit.height.toInt()),
                        )
                    },
            )

            Column(Modifier.fillMaxSize()) {
                if (moreTool == null && editingText == null) {
                    EditorTopBar(
                        canUndo = recipe.let { history.canUndo },
                        canRedo = recipe.let { history.canRedo },
                        canRevert = dirty,
                        onUndo = history::undo,
                        onRedo = history::redo,
                        // A step like any other, so it can be undone: no need to ask first.
                        onRevert = {
                            history.commit(EditRecipe())
                            cropRatio = null
                            cropRatioLabel = "Free"
                        },
                        onSave = { save(ImageFormat.JPEG, 95) },
                        onSaveAs = { saving = true },
                    )
                } else {
                    Spacer(Modifier.height(16.dp))
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            root?.takeIf { it.isAttached }?.let { pictureBounds = it.localBoundingBoxOf(coordinates) }
                        },
                ) {
                    val source = proxy
                    val turned = oriented
                    when {
                        source == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                        tool == TOOL_CROP && turned != null -> {
                            CropCanvas(
                                picture = turned,
                                recipe = recipe,
                                ratio = cropRatio,
                                showGrid = true,
                                onCrop = { history.preview(recipe.copy(crop = it)) },
                                onCommit = { history.commit(recipe) },
                            )
                            CropActions(
                                ratioLabel = cropRatioLabel,
                                onFlip = { history.commit(recipe.flippedOver()) },
                                onTurn = {
                                    history.commit(recipe.turned())
                                    // A portrait ratio stays portrait on screen only if it turns too.
                                    cropRatio = null
                                    cropRatioLabel = "Free"
                                },
                                onRatio = { value, label ->
                                    cropRatio = value
                                    cropRatioLabel = label
                                    if (value != null) {
                                        history.commit(recipe.copy(crop = centeredCrop(value * turned.height / turned.width)))
                                    }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                            )
                        }
                        else -> {
                            val shown = if (comparing) source else rendered
                            PicturePreview(
                                bitmap = shown,
                                filter = if (comparing) null else filter,
                                marks = if (comparing) emptyList() else recipe.marks + listOfNotNull(draft),
                                modifier = Modifier.pointerInput(tool, decorateMode, moreTool) {
                                    if (tool == TOOL_DECORATE) return@pointerInput
                                    // Hold the picture to see it as it was.
                                    detectTapGestures(onPress = {
                                        comparing = true
                                        tryAwaitRelease()
                                        comparing = false
                                    })
                                },
                            )
                            val image = rendered
                            if (tool == TOOL_DECORATE && image != null) {
                                val box = pictureBounds.size
                                val fit = fitRect(image.width, image.height, box)
                                if (decorateMode == DECORATE_DRAW) {
                                    DrawLayer(
                                        picture = fit,
                                        settings = drawSettings,
                                        marks = recipe.marks,
                                        onDraft = { draft = it },
                                        onErase = { history.preview(recipe.copy(marks = it)) },
                                        onStroke = { history.commit(recipe.copy(marks = recipe.marks + it)) },
                                        onEraseDone = { history.commit(recipe) },
                                    )
                                } else {
                                    TextLayer(
                                        picture = fit,
                                        marks = recipe.marks,
                                        onChange = { history.preview(recipe.copy(marks = it)) },
                                        onCommit = { history.commit(recipe) },
                                        onTap = { if (decorateMode == DECORATE_TEXT) editingText = it.id },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                if (editingText == null) Box(Modifier.fillMaxWidth().height(if (moreTool == SPOT) 280.dp else if (moreTool == FACE) 232.dp else 196.dp), contentAlignment = Alignment.BottomCenter) {
                    when {
                        moreTool == SPOT -> SpotPanel(recipe, history::preview, onCancel = {
                            history.cancelPreview()
                            moreTool = null
                        }, onDone = {
                            history.commit(recipe)
                            moreTool = null
                        })
                        moreTool == FACE -> FacePanel(recipe, faces, history::preview, onCancel = {
                            history.cancelPreview()
                            moreTool = null
                        }, onDone = {
                            history.commit(recipe)
                            moreTool = null
                        })
                        tool == TOOL_CROP -> GeometryPanel(recipe, history::preview, history::commit)
                        tool == TOOL_FILTERS -> FilterPanel(thumb, recipe, history::preview, history::commit)
                        tool == TOOL_TONE -> TonePanel(recipe, thumb, history::preview, history::commit)
                        tool == TOOL_DECORATE -> DecoratePanel(
                            mode = decorateMode,
                            onMode = {
                                decorateMode = it
                                if (it == DECORATE_TEXT && recipe.marks.none { mark -> mark is TextMark && mark.text.isNotEmpty() && mark.size < 0.16f }) {
                                    val fresh = TextMark(id = System.nanoTime(), text = "")
                                    history.preview(recipe.copy(marks = recipe.marks + fresh))
                                    editingText = fresh.id
                                }
                            },
                            settings = drawSettings,
                            onAddText = {
                                val fresh = TextMark(id = System.nanoTime(), text = "")
                                history.preview(recipe.copy(marks = recipe.marks + fresh))
                                editingText = fresh.id
                            },
                            onSticker = { history.commit(recipe.copy(marks = recipe.marks + newSticker(it))) },
                        )
                        else -> MorePanel(onSpot = { moreTool = SPOT }, onFace = { moreTool = FACE })
                    }
                }
                if (moreTool == null && editingText == null) ToolBar(TOOLS, tool) { tool = it }
            }

            val editing = editingText?.let { id -> recipe.marks.firstOrNull { it is TextMark && it.id == id } as? TextMark }
            if (editing != null) {
                TextEditor(
                    mark = editing,
                    onChange = { changed -> history.preview(recipe.copy(marks = recipe.marks.map { if (it is TextMark && it.id == changed.id) changed else it })) },
                    onDone = {
                        editingText = null
                        history.commit(recipe.copy(marks = recipe.marks.filter { it !is TextMark || it.text.isNotBlank() }))
                    },
                )
            }
            if (saving) {
                ImageSaveDialog(onDismiss = { saving = false }) { format, quality ->
                    saving = false
                    save(format, quality)
                }
            }
            exportJob?.let { job -> ExportOverlay(null) { job.cancel() } }
        }
    }
}

private const val SPOT = "spot"
private const val FACE = "face"

@Composable
private fun PicturePreview(bitmap: Bitmap?, filter: ColorFilter?, marks: List<Mark>, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    // Its own layer: this changes on every step of a dial, and the glass mustn't be recorded with it.
    Canvas(modifier.graphicsLayer { }.fillMaxSize()) {
        val current = image ?: return@Canvas
        val fit = fitRect(current.width, current.height, size)
        drawImage(
            current,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            colorFilter = filter,
        )
        if (marks.isNotEmpty()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(fit.left, fit.top)
                native.clipRect(0f, 0f, fit.width, fit.height)
                marks.drawOn(native, fit.width, fit.height)
                native.restore()
            }
        }
    }
}

@Composable
private fun GeometryPanel(recipe: EditRecipe, onPreview: (EditRecipe) -> Unit, onCommit: (EditRecipe) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OptionPicker(
            GEOMETRY,
            changed = { index ->
                when (index) {
                    0 -> recipe.straighten != 0f
                    1 -> recipe.skewVertical != 0f
                    else -> recipe.skewHorizontal != 0f
                }
            },
            selected,
        ) { selected = it }
        Spacer(Modifier.height(4.dp))
        when (selected) {
            0 -> RulerDial(recipe.straighten / 45f, -1f, 1f, { onPreview(recipe.copy(straighten = it * 45f)) }, { onCommit(recipe.copy(straighten = it * 45f)) }, label = { "${(it * 45f).roundToInt()}°" })
            1 -> RulerDial(recipe.skewVertical, -1f, 1f, { onPreview(recipe.copy(skewVertical = it)) }, { onCommit(recipe.copy(skewVertical = it)) })
            else -> RulerDial(recipe.skewHorizontal, -1f, 1f, { onPreview(recipe.copy(skewHorizontal = it)) }, { onCommit(recipe.copy(skewHorizontal = it)) })
        }
    }
}

private val THUMB_SIZE = 72.dp

/** Small copies of the picture under each filter: colour filters over one bitmap, no rendering. */
@Composable
internal fun FilterPanel(thumb: Bitmap?, recipe: EditRecipe, onPreview: (EditRecipe) -> Unit, onCommit: (EditRecipe) -> Unit) {
    val painter = remember(thumb) { thumb?.asImageBitmap()?.let { BitmapPainter(it) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(PRESETS[recipe.preset].first, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        CenteredRow(PRESETS.size, recipe.preset, { onCommit(recipe.copy(preset = it, presetStrength = 1f)) }, THUMB_SIZE, 8.dp) { index, selected ->
            val filter = remember(index) { ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(PRESETS[index].second.colorMatrix().array)) }
            Box(
                Modifier
                    .size(THUMB_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.DarkGray)
                    .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(role = Role.RadioButton, onClickLabel = PRESETS[index].first) { onCommit(recipe.copy(preset = index, presetStrength = 1f)) },
            ) {
                if (painter != null) Image(painter, PRESETS[index].first, colorFilter = filter, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (recipe.preset != 0) {
            Spacer(Modifier.height(4.dp))
            RulerDial(
                recipe.presetStrength - 1f,
                -1f,
                0f,
                { onPreview(recipe.copy(presetStrength = it + 1f)) },
                { onCommit(recipe.copy(presetStrength = it + 1f)) },
                label = { "${((it + 1f) * 100).roundToInt()}%" },
            )
        }
    }
}

@Composable
private fun TonePanel(recipe: EditRecipe, thumb: Bitmap?, onPreview: (EditRecipe) -> Unit, onCommit: (EditRecipe) -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val param = TONE_PARAMS[selected]
    val options = remember { TONE_PARAMS.map { it.icon() to it.label } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OptionPicker(
            options,
            changed = { TONE_PARAMS[it].get(recipe.adjust) != 0f },
            selected,
            leading = {
                GlassTextButton("Auto", enabled = thumb != null) {
                    thumb?.let { onCommit(recipe.copy(adjust = autoAdjust(it, recipe.adjust))) }
                }
            },
        ) { selected = it }
        Spacer(Modifier.height(4.dp))
        RulerDial(
            param.get(recipe.adjust),
            param.min,
            1f,
            { onPreview(recipe.copy(adjust = param.set(recipe.adjust, it))) },
            { onCommit(recipe.copy(adjust = param.set(recipe.adjust, it))) },
        )
    }
}

/** Brings exposure and contrast towards a full, centred histogram. */
private fun autoAdjust(thumb: Bitmap, adjust: Adjustments): Adjustments {
    val pixels = IntArray(thumb.width * thumb.height)
    thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
    val histogram = IntArray(256)
    var sum = 0L
    pixels.forEach { p ->
        val luma = (((p shr 16) and 255) * 54 + ((p shr 8) and 255) * 183 + (p and 255) * 19) shr 8
        histogram[luma]++
        sum += luma
    }
    val mean = sum.toFloat() / pixels.size / 255f
    fun percentile(q: Float): Int {
        val target = (pixels.size * q).toInt()
        var seen = 0
        histogram.forEachIndexed { index, count ->
            seen += count
            if (seen >= target) return index
        }
        return 255
    }
    val low = percentile(0.02f)
    val high = percentile(0.98f)
    val spread = ((high - low).coerceAtLeast(1)) / 255f
    return adjust.copy(
        exposure = ((0.48f - mean) * 1.2f).coerceIn(-0.6f, 0.6f),
        contrast = ((0.9f - spread) * 0.5f).coerceIn(0f, 0.4f),
    )
}

@Composable
private fun DecoratePanel(
    mode: Int,
    onMode: (Int) -> Unit,
    settings: DrawSettings,
    onAddText: () -> Unit,
    onSticker: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        when (mode) {
            DECORATE_DRAW -> DrawPanel(settings)
            DECORATE_STICKERS -> StickerPanel(onSticker)
            else -> GlassTextButton("Add text", onClick = onAddText)
        }
        TextTabs(listOf("Draw", "Stickers", "Text"), mode, onMode)
    }
}

/** The extra tools, each a card as on Samsung. Only ones that really work on this phone are shown. */
@Composable
private fun MorePanel(onSpot: () -> Unit, onFace: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        MoreCard(Icons.Rounded.Face, "Face effects", onFace)
        MoreCard(Icons.Rounded.Colorize, "Spot color", onSpot)
    }
}

@Composable
private fun MoreCard(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 104.dp, height = 112.dp)
            .liquid(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

private val SPOT_OPTIONS = listOf(
    Icons.Rounded.Palette to "Hue",
    Icons.Rounded.InvertColors to "Saturation",
    Icons.Rounded.BrightnessMedium to "Lightness",
)

@Composable
private fun SpotPanel(recipe: EditRecipe, onPreview: (EditRecipe) -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
    var option by remember { mutableIntStateOf(0) }
    var band by remember { mutableStateOf(SpotBand.ALL) }
    val shift = recipe.spot[band] ?: SpotShift()
    fun set(value: Float) {
        val next = when (option) {
            0 -> shift.copy(hue = value)
            1 -> shift.copy(saturation = value)
            else -> shift.copy(lightness = value)
        }
        onPreview(recipe.copy(spot = recipe.spot + (band to next)))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OptionPicker(
            SPOT_OPTIONS,
            changed = { index ->
                when (index) {
                    0 -> shift.hue != 0f
                    1 -> shift.saturation != 0f
                    else -> shift.lightness != 0f
                }
            },
            option,
        ) { option = it }
        RulerDial(
            when (option) {
                0 -> shift.hue
                1 -> shift.saturation
                else -> shift.lightness
            },
            -1f,
            1f,
            ::set,
            ::set,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
            SpotBand.entries.forEach { item ->
                val chosen = item == band
                val changed = recipe.spot[item]?.isZero == false
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClickLabel = item.label) { band = item }, contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(if (chosen) 30.dp else 24.dp)
                            .clip(CircleShape)
                            .background(Color(item.swatch))
                            .border(if (chosen) 2.dp else 0.dp, if (chosen) Color.White else Color.Transparent, CircleShape),
                    )
                    if (changed) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).clip(CircleShape).background(Accent))
                }
            }
        }
        CancelDone(onCancel, onDone)
    }
}

private val FACE_OPTIONS = listOf(
    Icons.Rounded.WaterDrop to "Smoothness",
    Icons.Rounded.Contrast to "Skin tone",
)

@Composable
private fun FacePanel(recipe: EditRecipe, faces: List<RectF>?, onPreview: (EditRecipe) -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
    var option by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (faces != null && faces.isEmpty()) Text("No face found in this picture", color = Muted, fontSize = 13.sp)
        OptionPicker(
            FACE_OPTIONS,
            changed = { if (it == 0) recipe.face.smoothness != 0f else recipe.face.skinTone != 0f },
            option,
        ) { option = it }
        if (option == 0) {
            RulerDial(recipe.face.smoothness, 0f, 1f, { onPreview(recipe.copy(face = recipe.face.copy(smoothness = it))) }, { onPreview(recipe.copy(face = recipe.face.copy(smoothness = it))) })
        } else {
            RulerDial(recipe.face.skinTone, -1f, 1f, { onPreview(recipe.copy(face = recipe.face.copy(skinTone = it))) }, { onPreview(recipe.copy(face = recipe.face.copy(skinTone = it))) })
        }
        CancelDone(onCancel, onDone)
    }
}
