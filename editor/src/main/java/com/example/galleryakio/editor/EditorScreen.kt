package com.example.galleryakio.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.t8rin.cropper.ImageCropper
import com.t8rin.cropper.model.AspectRatio
import com.t8rin.cropper.model.OutlineType
import com.t8rin.cropper.model.RectCropShape
import com.t8rin.cropper.settings.CropDefaults
import com.t8rin.cropper.settings.CropOutlineProperty
import com.t8rin.cropper.settings.CropType
import kotlin.math.roundToInt

internal val EditorBackground = Color(0xFF0B0B0B)
internal val ToolBackground = Color(0xFF161616)
private val Accent = Color.White
private val Muted = Color.White.copy(alpha = 0.55f)

/**
 * Editor for one photo or video. Nothing here is composed or loaded until this screen is opened.
 *
 * Bars are solid on purpose: glass over a picture being changed would blur again on every step.
 *
 * @param onSaved a new file was written (the original is never touched)
 */
@Composable
fun EditorScreen(uri: Uri, displayName: String, isVideo: Boolean, onClose: () -> Unit, onSaved: (Uri) -> Unit) {
    Box(Modifier.fillMaxSize().background(EditorBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (isVideo) VideoEditor(uri, displayName, onClose, onSaved)
        else ImageEditor(uri, displayName, onClose, onSaved)
    }
}

/** Asks before throwing edits away, on the close button and on system back alike. */
@Composable
internal fun discardGuard(dirty: Boolean, onClose: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val close = { if (dirty) asking = true else onClose() }
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Discard edits?") },
            confirmButton = { TextButton(onClick = { asking = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep editing") } },
        )
    }
    return close
}

@Composable
internal fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    /** Shows the unedited picture while held. */
    onCompare: ((Boolean) -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Rounded.Close, "Close", onClick = onClose)
        Spacer(Modifier.weight(1f))
        if (onCompare != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            onCompare(true)
                            tryAwaitRelease()
                            onCompare(false)
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Compare, "Hold to compare", tint = Accent, modifier = Modifier.size(22.dp))
            }
        }
        BarIcon(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        BarIcon(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = canRedo, onClick = onRedo)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSave) { Text("Save") }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun BarIcon(icon: ImageVector, description: String, enabled: Boolean = true, tint: Color = Accent, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, description, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ToolTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(ToolBackground).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).height(48.dp).clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (index == selected) Accent else Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Color.Black else Accent, fontSize = 13.sp)
    }
}

/**
 * The sliders. [onPreview] runs while dragging and must stay cheap; [onCommit] on letting go makes
 * the undo step.
 */
@Composable
internal fun AdjustPanel(
    adjust: Adjustments,
    params: List<Param>,
    onPreview: (Adjustments) -> Unit,
    onCommit: (Adjustments) -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    val param = params[selected.coerceIn(params.indices)]
    val value = param.get(adjust)
    Column(Modifier.fillMaxWidth().background(ToolBackground).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = { onPreview(param.set(adjust, it)) },
                onValueChangeFinished = { onCommit(adjust) },
                valueRange = param.min..1f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                (value * 100).roundToInt().toString(),
                color = Accent,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(36.dp).clickable { onCommit(param.set(adjust, 0f)) },
            )
        }
        ChipRow {
            params.forEachIndexed { index, item ->
                val changed = item.get(adjust) != 0f
                Chip(if (changed) "${item.label} •" else item.label, index == selected) { selected = index }
            }
        }
    }
}

/** Presets as small copies of the picture: colour filters over one small bitmap, no rendering. */
@Composable
internal fun PresetPanel(thumb: Bitmap?, current: Adjustments, onPick: (Adjustments) -> Unit) {
    val image = remember(thumb) { thumb?.asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().background(ToolBackground).horizontalScroll(rememberScrollState()).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PRESETS.forEach { (name, preset) ->
            Column(Modifier.width(72.dp).clickable { onPick(preset) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray)) {
                    if (image != null) {
                        androidx.compose.foundation.Image(
                            BitmapPainter(image),
                            name,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(preset.composeMatrix()),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(name, color = if (preset == current) Accent else Muted, fontSize = 12.sp)
            }
        }
    }
}

internal fun Adjustments.composeMatrix() = androidx.compose.ui.graphics.ColorMatrix(colorMatrix().array)

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private val CROP_RATIOS = listOf(
    "Free" to AspectRatio.Original,
    "1:1" to AspectRatio(1f),
    "4:3" to AspectRatio(4f / 3f),
    "3:4" to AspectRatio(3f / 4f),
    "16:9" to AspectRatio(16f / 9f),
    "9:16" to AspectRatio(9f / 16f),
)

/** ImageToolbox's cropper over the turned picture, reporting a crop instead of cropping pixels. */
@Composable
internal fun CropPanel(
    picture: Bitmap,
    onTurn: () -> Unit,
    onFlip: () -> Unit,
    onApply: (CropFraction) -> Unit,
    onCancel: () -> Unit,
) {
    var ratio by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    val image = remember(picture) { picture.asImageBitmap() }
    val outline = remember { CropOutlineProperty(OutlineType.Rect, RectCropShape(0, "Rect")) }
    val properties = remember(ratio, outline) {
        val chosen = CROP_RATIOS[ratio].second
        CropDefaults.properties(
            cropType = CropType.Dynamic,
            cropOutlineProperty = outline,
            aspectRatio = chosen,
            fixedAspectRatio = chosen != AspectRatio.Original,
            overlayRatio = 1f,
        )
    }
    val style = remember { CropDefaults.style(backgroundColor = Color.Black.copy(alpha = 0.6f)) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ImageCropper(
                modifier = Modifier.fillMaxSize(),
                imageBitmap = image,
                cropStyle = style,
                cropProperties = properties,
                crop = applying,
                onCropStart = {},
                onZoomChange = {},
                onCropSuccess = {},
                onCropFraction = { l, t, r, b ->
                    applying = false
                    onApply(CropFraction(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f)))
                },
            )
        }
        Column(Modifier.fillMaxWidth().background(ToolBackground).padding(vertical = 4.dp)) {
            ChipRow { CROP_RATIOS.forEachIndexed { index, (label, _) -> Chip(label, index == ratio) { ratio = index } } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.Rounded.Close, "Cancel crop", onClick = onCancel)
                Spacer(Modifier.weight(1f))
                BarIcon(Icons.Rounded.Rotate90DegreesCw, "Turn", onClick = onTurn)
                BarIcon(Icons.Rounded.Flip, "Flip", onClick = onFlip)
                Spacer(Modifier.weight(1f))
                BarIcon(Icons.Rounded.Check, "Apply crop") { applying = true }
            }
        }
    }
}

/** Blocks the screen while exporting, with the way out. [progress] null: no idea how far yet. */
@Composable
internal fun ExportOverlay(progress: Float?, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).roundToInt()} %", color = Accent, fontSize = 14.sp)
            } else {
                CircularProgressIndicator(color = Accent)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = Accent) }
        }
    }
}

@Composable
internal fun ImageSaveDialog(onDismiss: () -> Unit, onSave: (ImageFormat, Int) -> Unit) {
    var format by remember { mutableStateOf(ImageFormat.JPEG) }
    var quality by remember { mutableFloatStateOf(95f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as new file") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { Chip(it.label, it == format) { format = it } }
                }
                if (format.hasQuality) {
                    Spacer(Modifier.height(12.dp))
                    Text("Quality ${quality.roundToInt()}", fontSize = 13.sp)
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(format, quality.roundToInt()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
