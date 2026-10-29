package com.appslab.gallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.appslab.gallery.data.MediaItem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.MediaSource
import com.appslab.gallery.ui.Routes
import com.appslab.gallery.ui.components.MediaGrid
import com.appslab.gallery.ui.components.SelectionBar
import com.appslab.gallery.ui.components.SelectionState
import com.appslab.gallery.ui.components.rememberConsentLauncher
import com.appslab.gallery.ui.components.shareItems
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors

@Composable
fun PhotosScreen(viewModel: GalleryViewModel, navController: NavController) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selection = remember { SelectionState() }

    when {
        library.isLoading -> CenteredMessage(loading = true, title = "Loading...", modifier = Modifier.padding(top = LocalHeaderPadding.current))
        library.visible.isEmpty() -> CenteredMessage(
            title = "No photos or videos found",
            subtitle = "Try adding some to your device.",
            modifier = Modifier.padding(top = LocalHeaderPadding.current),
        )
        else -> Box(Modifier.fillMaxSize()) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalHeaderPadding.current
            MediaGrid(
                items = library.visible,
                columns = settings.gridColumns,
                showDateHeaders = true,
                dateFormat = settings.dateFormat,
                selection = selection,
                onOpen = { navController.navigate(Routes.photo(it.id, MediaSource.All)) },
                modifier = Modifier.fillMaxSize(),
                favoriteIds = settings.favoriteIds,
                darkMode = settings.darkMode,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = 100.dp),
            )
            SelectionActions(
                viewModel, selection, library.visible,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Same grid as Photos, videos only, with a duration badge on each tile. */
@Composable
fun VideosScreen(viewModel: GalleryViewModel, navController: NavController) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selection = remember { SelectionState() }
    val videos = remember(library.visible) { library.visible.filter { it.isVideo } }

    when {
        library.isLoading -> CenteredMessage(loading = true, title = "Loading...", modifier = Modifier.padding(top = LocalHeaderPadding.current))
        videos.isEmpty() -> CenteredMessage(
            title = "No videos found",
            subtitle = "Videos you record or download will appear here.",
            modifier = Modifier.padding(top = LocalHeaderPadding.current),
        )
        else -> Box(Modifier.fillMaxSize()) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalHeaderPadding.current
            MediaGrid(
                items = videos,
                columns = settings.gridColumns,
                showDateHeaders = true,
                dateFormat = settings.dateFormat,
                selection = selection,
                onOpen = { navController.navigate(Routes.photo(it.id, MediaSource.Videos)) },
                modifier = Modifier.fillMaxSize(),
                favoriteIds = settings.favoriteIds,
                darkMode = settings.darkMode,
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = top, bottom = 100.dp),
            )
            SelectionActions(viewModel, selection, videos, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Selection bar plus trash confirmation, shared by Photos, album and favorites grids. */
@Composable
fun SelectionActions(
    viewModel: GalleryViewModel,
    selection: SelectionState,
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
) {
    if (!selection.active) return
    val context = LocalContext.current
    var confirmTrash by remember { mutableStateOf(false) }
    val consent = rememberConsentLauncher { approved -> if (approved) selection.clear() }
    val selectedItems = items.filter { it.id in selection.selected }

    BackHandler { selection.clear() }

    SelectionBar(
        count = selection.selected.size,
        onCancel = selection::clear,
        onSelectAll = { selection.setAll(items.map { it.id }) },
        onShare = { shareItems(context, selectedItems) },
        onDelete = { confirmTrash = true },
        modifier = modifier.navigationBarsPadding(),
    )

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move to Trash?", fontFamily = CqMono) },
            text = { Text("Move ${selectedItems.size} item(s) to the trash. They can be restored for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    consent(viewModel.trashRequest(selectedItems))
                }) { Text("Trash", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun CenteredMessage(title: String, subtitle: String? = null, loading: Boolean = false, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
            }
            Text(title, fontFamily = CqMono, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(subtitle, fontSize = 13.sp, color = LocalCustomColors.current.unselectedItem)
            }
        }
    }
}
