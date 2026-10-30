package com.example.galleryakio.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class VideoEdit(
    val recipe: EditRecipe = EditRecipe(),
    /** Null: to the end. */
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val speed: Float = 1f,
    val muted: Boolean = false,
)

/** Transforms [source] (decoding and encoding in hardware where it can) to a cache file, then moves it to Movies/Gallery. */
@OptIn(UnstableApi::class)
internal suspend fun exportVideo(
    context: Context,
    source: Uri,
    displayName: String,
    edit: VideoEdit,
    onProgress: (Float) -> Unit,
): Uri {
    val output = File(context.cacheDir, "video_edit_${System.nanoTime()}.mp4")
    try {
        transform(context, source, output, edit, onProgress)
        return withContext(Dispatchers.IO) { publish(context, output, displayName) }
    } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { output.delete() }
    }
}

@OptIn(UnstableApi::class)
private suspend fun transform(context: Context, source: Uri, output: File, edit: VideoEdit, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
    val item = MediaItem.Builder()
        .setUri(source)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(edit.startMs)
                .apply { edit.endMs?.let { setEndPositionMs(it) } }
                .build(),
        )
        .build()
    val edited = EditedMediaItem.Builder(item)
        .setRemoveAudio(edit.muted)
        .setEffects(Effects(emptyList(), videoEffects(edit.recipe)))
        .apply { if (edit.speed != 1f) setSpeed(ConstantSpeed(edit.speed)) }
        .build()
    // In the video's own gamma, not the linear default: that's where the preview's colour filter
    // works, and the same matrix would otherwise come out differently.
    val processor = DefaultVideoFrameProcessor.Factory.Builder()
        .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL)
        .build()
    val done = CompletableDeferred<Unit>()
    val transformer = Transformer.Builder(context)
        .setVideoFrameProcessorFactory(processor)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                done.complete(Unit)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                done.completeExceptionally(exportException)
            }
        })
        .build()
    transformer.start(edited, output.absolutePath)
    val poller = launch {
        val holder = ProgressHolder()
        while (isActive) {
            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
            delay(200)
        }
    }
    try {
        done.await()
    } finally {
        poller.cancel()
        // Does nothing once it has finished.
        transformer.cancel()
    }
}

@OptIn(UnstableApi::class)
private fun videoEffects(recipe: EditRecipe): List<Effect> = buildList {
    // Media3 turns counter-clockwise. A separate step, so the flip comes after as in the recipe.
    if (recipe.turns != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(360f - 90f * recipe.turns).build())
    if (recipe.flipped) add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
    if (!recipe.crop.isFull) {
        val c = recipe.crop
        add(Crop(c.left * 2f - 1f, c.right * 2f - 1f, 1f - c.bottom * 2f, 1f - c.top * 2f))
    }
    val matrix = recipe.adjust.colorMatrix().array
    if (!matrix.contentEquals(android.graphics.ColorMatrix().array)) {
        add(
            RgbMatrix { _, _ ->
                // Rows with 0..255 offsets to columns with 0..1 ones.
                floatArrayOf(
                    matrix[0], matrix[5], matrix[10], 0f,
                    matrix[1], matrix[6], matrix[11], 0f,
                    matrix[2], matrix[7], matrix[12], 0f,
                    matrix[4] / 255f, matrix[9] / 255f, matrix[14] / 255f, 1f,
                )
            },
        )
    }
}

@OptIn(UnstableApi::class)
private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long) = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
}

private fun publish(context: Context, file: File, displayName: String): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "${displayName.substringBeforeLast('.')}_edit.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Gallery")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create the file")
    try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Couldn't write the file")
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        return uri
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

internal fun Throwable.exportMessage(): String? = when (this) {
    is CancellationException -> null
    else -> message ?: "Export failed"
}
