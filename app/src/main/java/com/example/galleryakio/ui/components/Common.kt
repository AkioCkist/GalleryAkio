package com.example.galleryakio.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import kotlin.math.abs
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.MediaThumb
import com.example.galleryakio.ui.theme.LocalCustomColors
import com.example.galleryakio.ui.theme.RobotoNumbers
import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatDuration(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * Square grid cell. Requests a thumbnail-sized decode so scrolling a large
 * library never decodes full-resolution bitmaps.
 */
@Composable
fun MediaThumbnail(
    item: MediaItem,
    cellSizePx: Int,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    // Decode at one of three fixed sizes, so pinching between nearby column counts
    // reuses the bitmaps already in memory instead of decoding every tile again.
    val bucket = thumbnailBucket(cellSizePx)
    val request = remember(item.uri, bucket) {
        // While this size decodes, show any other size already in memory, so a tile that
        // pinching moved into a new bucket never flashes empty.
        val cache = SingletonImageLoader.get(context).memoryCache
        val placeholder = if (cache == null || cache[MemoryCache.Key(thumbnailKey(item.id, bucket))] != null) null
        else THUMBNAIL_BUCKETS.sortedBy { abs(it - bucket) }
            .firstOrNull { it != bucket && cache[MemoryCache.Key(thumbnailKey(item.id, it))] != null }
            ?.let { thumbnailKey(item.id, it) }
        thumbnailRequest(context, item, bucket).placeholderMemoryCacheKey(placeholder).build()
    }
    Box(modifier.background(LocalCustomColors.current.shimmer)) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selectionMode) {
            if (selected) Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
            )
        }
    }
}

private val THUMBNAIL_BUCKETS = intArrayOf(256, 512, 1024)

fun thumbnailBucket(cellSizePx: Int): Int = when {
    cellSizePx <= 256 -> 256
    cellSizePx <= 512 -> 512
    else -> 1024
}

fun thumbnailKey(id: Long, bucket: Int) = "thumb:$id:$bucket"

/** The grid's thumbnail request for [item] at [bucket]; shared so every caller hits the same cache entry. */
fun thumbnailRequest(context: Context, item: MediaItem, bucket: Int): ImageRequest.Builder =
    ImageRequest.Builder(context)
        // MediaStore's cached thumbnails are made for grid sizes; past that, decode the file
        // so one- and two-column grids stay sharp. Videos always use MediaStore's frame.
        .data(
            when {
                item.isVideo -> MediaThumb(item.uri, bucket)
                bucket <= 512 -> MediaThumb(item.uri, bucket, minOf(item.width, item.height), item.dateModified, photo = true)
                else -> item.uri
            },
        )
        .size(Size(bucket, bucket))
        .memoryCacheKey(thumbnailKey(item.id, bucket))
        // A crossfade per tile means extra frames of work while flinging.
        .crossfade(false)

/** Thumbnail of [id] already in memory: [bucket] if present, else the nearest other size. */
fun cachedThumbnail(cache: MemoryCache, id: Long, bucket: Int): Bitmap? {
    cache[MemoryCache.Key(thumbnailKey(id, bucket))]?.let { return (it.image as? BitmapImage)?.bitmap }
    for (b in THUMBNAIL_BUCKETS.sortedBy { abs(it - bucket) }) {
        if (b == bucket) continue
        cache[MemoryCache.Key(thumbnailKey(id, b))]?.let { return (it.image as? BitmapImage)?.bitmap }
    }
    return null
}

/** Largest grid thumbnail of [id] already in memory, for the viewer to show while it decodes. */
fun cachedThumbnailKey(context: Context, id: Long): String? {
    val cache = SingletonImageLoader.get(context).memoryCache ?: return null
    return THUMBNAIL_BUCKETS.reversed().map { thumbnailKey(id, it) }.firstOrNull { cache[MemoryCache.Key(it)] != null }
}

@Composable
fun rememberCellSizePx(columns: Int, screenWidthDp: Int): Int {
    val density = LocalDensity.current
    return remember(columns, screenWidthDp) { with(density) { (screenWidthDp.dp / columns).roundToPx() } }
}

/** Launches MediaStore consent dialogs and reports whether the user approved. */
@Composable
fun rememberConsentLauncher(onResult: (approved: Boolean) -> Unit): (PendingIntent?) -> Unit {
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        callback.value(it.resultCode == Activity.RESULT_OK)
    }
    return remember(launcher) {
        { pi ->
            if (pi == null) callback.value(true)
            else launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }
}

fun shareItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent = if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items[0].mimeType
            putExtra(Intent.EXTRA_STREAM, items[0].uri)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
