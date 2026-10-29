package com.appslab.gallery.ui.screens

import com.appslab.gallery.ui.components.GlassScaffold
import com.appslab.gallery.ui.components.GlassTextButton
import com.appslab.gallery.ui.components.gridPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appslab.gallery.R
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.components.MediaGrid
import com.appslab.gallery.ui.components.SelectionBar
import com.appslab.gallery.ui.components.SelectionState
import com.appslab.gallery.ui.components.rememberConsentLauncher
import com.appslab.gallery.ui.theme.CqMono

/** System trash (Android 11+). Items are purged by the OS after 30 days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val items by viewModel.trash.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val selection = remember { SelectionState() }
    val consent = rememberConsentLauncher { approved -> if (approved) { selection.clear(); viewModel.loadTrash() } }

    // Reload whenever the library changes (restores and deletes both trigger it).
    LaunchedEffect(library.all) { viewModel.loadTrash() }

    GlassScaffold(
        title = "TRASH",
        onBack = onBack,
        actions = { backdrop ->
            if (items.isNotEmpty()) GlassTextButton(backdrop, "EMPTY", onClick = { consent(viewModel.deleteRequest(items)) }, color = Color(0xFFFF5A5A))
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                Build.VERSION.SDK_INT < 30 -> CenteredMessage("Trash needs Android 11+", "Deleted items are removed immediately.")
                items.isEmpty() -> CenteredMessage("Trash is empty", "Items you delete stay here for 30 days.")
                else -> MediaGrid(
                    items = items,
                    columns = settings.gridColumns,
                    showDateHeaders = false,
                    dateFormat = settings.dateFormat,
                    selection = selection,
                    onOpen = { selection.toggle(it.id) },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = gridPadding(padding.calculateTopPadding()),
                )
            }
            if (selection.active) {
                val selected = items.filter { it.id in selection.selected }
                SelectionBar(
                    count = selected.size,
                    onCancel = selection::clear,
                    onSelectAll = { selection.setAll(items.map { it.id }) },
                    onShare = null,
                    onDelete = { consent(viewModel.deleteRequest(selected)) },
                    extra = {
                        IconButton(onClick = { consent(viewModel.restoreRequest(selected)) }) {
                            Icon(painterResource(R.drawable.restore), "Restore", modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
    }
}
