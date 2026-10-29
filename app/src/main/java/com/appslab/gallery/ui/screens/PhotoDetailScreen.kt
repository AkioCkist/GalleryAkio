package com.appslab.gallery.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appslab.gallery.R
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.MediaSource
import com.appslab.gallery.ui.components.VideoPlayer
import com.appslab.gallery.ui.components.ZoomableImage
import com.appslab.gallery.ui.components.formatDuration
import com.appslab.gallery.ui.components.formatSize
import com.appslab.gallery.ui.components.rememberConsentLauncher
import com.appslab.gallery.ui.components.shareItems
import com.appslab.gallery.ui.theme.CqMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhotoDetailScreen(viewModel: GalleryViewModel, initialId: Long, source: MediaSource, onBack: () -> Unit) {
    val context = LocalContext.current
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = remember(library, source, settings.albumSortOrder, settings.favoriteIds) {
        when (source) {
            MediaSource.All -> library.visible
            MediaSource.Favorites -> viewModel.favorites()
            MediaSource.Videos -> library.visible.filter { it.isVideo }
            MediaSource.Custom -> library.all.associateBy { it.id }.let { byId -> viewModel.customList.mapNotNull { byId[it.id] } }
            is MediaSource.Album -> viewModel.albumItems(source.relativePath, settings.albumSortOrder)
        }
    }
    if (items.isEmpty()) {
        if (!library.isLoading) LaunchedEffect(Unit) { onBack() }
        return
    }

    val startIndex = remember { items.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    val current = items.getOrNull(pager.currentPage.coerceIn(0, items.lastIndex)) ?: return

    var overlay by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    ImmersiveMode(fullscreen = !overlay)
    MaxBrightness(enabled = settings.maxBrightnessViewing)

    val trashConsent = rememberConsentLauncher { }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val isCurrent = page == pager.currentPage
            if (item.isVideo) {
                VideoPlayer(
                    item = item,
                    isCurrentPage = isCurrent,
                    rememberPosition = settings.rememberVideoPosition,
                    onControllerVisibility = { overlay = it },
                )
            } else {
                ZoomableImage(item, isCurrentPage = isCurrent, onTap = { overlay = !overlay })
            }
        }

        AnimatedVisibility(overlay, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomBar(
                item = current,
                isFavorite = current.id in settings.favoriteIds,
                onBack = onBack,
                onShare = { shareItems(context, listOf(current)) },
                onDelete = { confirmTrash = true },
                onDetails = { showInfo = true },
                onFavorite = { viewModel.settingsRepo.toggleFavorite(current.id) },
                onRename = { renaming = true },
            )
        }
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move to Trash?", fontFamily = CqMono) },
            text = { Text(current.displayName) },
            confirmButton = {
                TextButton(onClick = { confirmTrash = false; trashConsent(viewModel.trashRequest(listOf(current))) }) {
                    Text("Trash", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }
    if (showInfo) InfoSheet(current, onDismiss = { showInfo = false })
    if (renaming) RenameDialog(viewModel, current, onDismiss = { renaming = false })
}

/** Port of the original viewer bar: BACK, SHARE, DELETE and MORE as icon-over-label buttons. */
@Composable
private fun BottomBar(
    item: MediaItem,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(R.drawable.back2, "BACK", onBack)
        BarButton(R.drawable.share, "SHARE", onShare)
        BarButton(R.drawable.delete, "DELETE", onDelete)
        Box {
            BarButton(R.drawable.more2, "MORE") { menu = true }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                MoreItem(R.drawable.info, "DETAILS") { menu = false; onDetails() }
                MoreItem(if (isFavorite) R.drawable.fillfav2 else R.drawable.fav123, if (isFavorite) "UNFAVORITE" else "FAVORITE") {
                    menu = false; onFavorite()
                }
                if (!item.isVideo) {
                    MoreItem(R.drawable.wallpaper, "SET WALLPAPER") {
                        menu = false
                        context.startChooserSafely(attachIntent(item), "Set as:")
                    }
                    MoreItem(R.drawable.contact, "CONTACT PHOTO") {
                        menu = false
                        context.startChooserSafely(attachIntent(item), "Set as:")
                    }
                    MoreItem(R.drawable.lens, "GOOGLE LENS") {
                        menu = false
                        openGoogleLens(context, item)
                    }
                }
                MoreItem(R.drawable.rename, "RENAME") { menu = false; onRename() }
            }
        }
    }
}

@Composable
private fun BarButton(icon: Int, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), label, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(5.dp))
            Text(label, color = Color.White, fontFamily = CqMono, fontSize = 15.sp)
        }
    }
}

@Composable
private fun MoreItem(icon: Int, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(15.dp))
                Text(label, fontFamily = CqMono, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

private fun attachIntent(item: MediaItem) = Intent(Intent.ACTION_ATTACH_DATA).apply {
    setDataAndType(item.uri, item.mimeType)
    putExtra("mimeType", item.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Same fallback as the original openGoogleLens: open Lens, else its Play Store page. */
private fun openGoogleLens(context: Context, item: MediaItem) {
    val lens = Intent(Intent.ACTION_SEND).apply {
        setPackage("com.google.ar.lens")
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(lens)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Google Lens not installed. Opening Play Store...", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.ar.lens")))
        }
    }
}

private fun Context.startChooserSafely(intent: Intent, title: String) {
    try {
        startActivity(Intent.createChooser(intent, title))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app can handle this", Toast.LENGTH_SHORT).show()
    }
}

private data class ExifData(
    val camera: String? = null,
    val lens: String? = null,
    val aperture: String? = null,
    val exposure: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val flash: String? = null,
    val location: String? = null,
)

private fun readExif(context: Context, item: MediaItem): ExifData? = runCatching {
    context.contentResolver.openInputStream(item.uri)?.use { stream ->
        val exif = ExifInterface(stream)
        val latLong = exif.latLong
        ExifData(
            camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
                .joinToString(" ").ifBlank { null },
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/%.1f".format(it) },
            exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                if (it < 1) "1/${(1 / it).toInt()} s" else "%.1f s".format(it)
            },
            iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.1f mm".format(it) },
            flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 }?.let { if (it and 1 == 1) "Fired" else "Off" },
            location = latLong?.let { "%.5f, %.5f".format(it[0], it[1]) },
        )
    }
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exif by produceState<ExifData?>(null, item.id) {
        value = if (item.isVideo) null else withContext(Dispatchers.IO) { readExif(context, item) }
    }
    val dateFmt = remember { SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("DETAILS", fontFamily = CqMono, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            InfoRow("Name", item.displayName)
            InfoRow("Date", dateFmt.format(Date(item.dateTaken)))
            InfoRow("Size", formatSize(item.size))
            if (item.width > 0 && item.height > 0) {
                InfoRow("Resolution", "${item.width} × ${item.height} (%.1f MP)".format(item.width * item.height / 1_000_000f))
            }
            item.duration?.let { InfoRow("Duration", formatDuration(it)) }
            InfoRow("Type", item.mimeType)
            InfoRow("Path", item.relativePath.ifEmpty { item.bucketName })
            exif?.let { e ->
                e.camera?.let { InfoRow("Camera", it) }
                e.lens?.let { InfoRow("Lens", it) }
                val shot = listOfNotNull(e.aperture, e.exposure, e.iso, e.focalLength).joinToString("  ·  ")
                if (shot.isNotEmpty()) InfoRow("Settings", shot)
                e.flash?.let { InfoRow("Flash", it) }
                e.location?.let { InfoRow("Location", it) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label.uppercase(), fontFamily = CqMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val InvalidNameChars = Regex("[\\\\/:*?\"<>|]")

@Composable
private fun RenameDialog(viewModel: GalleryViewModel, item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ext = item.displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var name by remember { mutableStateOf(item.displayName.removeSuffix(ext)) }
    val error = when {
        name.isBlank() -> "File name cannot be empty"
        name.length > 200 -> "File name too long"
        InvalidNameChars.containsMatchIn(name) -> "Invalid characters in file name"
        else -> null
    }

    fun doRename() = scope.launch {
        val ok = viewModel.rename(item, name.trim() + ext)
        Toast.makeText(context, if (ok) "Renamed to: ${name.trim()}$ext" else "Rename failed", Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    val consent = rememberConsentLauncher { approved -> if (approved) doRename() else onDismiss() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RENAME", fontFamily = CqMono) },
        text = {
            Column {
                Text("ENTER NEW NAME FOR THE FILE:", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null,
                    suffix = { Text(ext) },
                    supportingText = { error?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Text("NOTE: THE FILE EXTENSION ($ext) IS KEPT", fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { consent(viewModel.writeRequest(listOf(item))) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImmersiveMode(fullscreen: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun MaxBrightness(enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val original = window.attributes.screenBrightness
        if (enabled) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = original }
        }
    }
}
