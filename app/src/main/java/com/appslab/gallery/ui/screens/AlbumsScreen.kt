package com.appslab.gallery.ui.screens

import com.appslab.gallery.ui.components.GlassScaffold
import com.appslab.gallery.ui.components.GlassCircleButton
import com.appslab.gallery.ui.components.GlassMenu
import com.appslab.gallery.ui.components.GlassMenuItem
import com.appslab.gallery.ui.components.GLASS_HEADER_SIZE
import com.appslab.gallery.ui.components.glassIsDark
import com.appslab.gallery.ui.components.gridPadding
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.appslab.gallery.R
import com.appslab.gallery.data.Album
import com.appslab.gallery.data.SortOrder
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.MediaSource
import com.appslab.gallery.ui.Routes
import com.appslab.gallery.ui.components.MediaGrid
import com.appslab.gallery.ui.components.MediaThumbnail
import com.appslab.gallery.ui.components.SelectionState
import com.appslab.gallery.ui.components.rememberCellSizePx
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors

@Composable
fun AlbumsScreen(viewModel: GalleryViewModel, navController: NavController) {
    val library by viewModel.library.collectAsStateWithLifecycle()

    val headerPad = LocalHeaderPadding.current
    when {
        library.isLoading -> CenteredMessage(loading = true, title = "Loading albums...", modifier = Modifier.padding(top = headerPad))
        library.albums.isEmpty() -> CenteredMessage(
            title = "No albums found",
            subtitle = "Try adding some photos or videos to your device.",
            modifier = Modifier.padding(top = headerPad),
        )
        // Port of AlbumsScreenContent: rows of two square cards, 21 dp side margins.
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 21.dp),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + headerPad,
                bottom = 100.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(library.albums.chunked(2), key = { row -> row.first().relativePath }) { row ->
                Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    row.forEach { album ->
                        AlbumCard(
                            album = album,
                            onClick = { navController.navigate(Routes.album(album.relativePath)) },
                            onLongClick = { viewModel.settingsRepo.togglePinned(album.relativePath) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Port of AnimatedAlbumCard: 24 dp rounded square, name and count over a bottom gradient. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val cellPx = rememberCellSizePx(2, LocalConfiguration.current.screenWidthDp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = 10000f), label = "album_scale")
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LocalCustomColors.current.cardBackground),
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (album.cover != null) {
                MediaThumbnail(album.cover, cellPx, Modifier.fillMaxSize())
            } else {
                Text(
                    "No thumbnail", color = Color.White.copy(alpha = 0.6f), fontFamily = CqMono,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 12.dp, bottom = 15.dp)) {
                Text(
                    album.name.uppercase(),
                    color = Color.White, fontSize = 18.sp, fontFamily = CqMono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album.itemCount.toString(),
                    color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontFamily = CqMono,
                )
            }
            if (album.isPinned) {
                Icon(
                    painterResource(R.drawable.pinfill), "Pinned",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(viewModel: GalleryViewModel, navController: NavController, relativePath: String) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val album = library.albums.firstOrNull { it.relativePath == relativePath }
    val items = remember(library.all, relativePath, settings.albumSortOrder) {
        viewModel.albumItems(relativePath, settings.albumSortOrder)
    }
    val selection = remember { SelectionState() }
    var menu by remember { mutableStateOf(false) }

    GlassScaffold(
        title = (album?.name ?: "Album").uppercase(),
        subtitle = "${items.size} items",
        onBack = { navController.popBackStack() },
        actions = { backdrop ->
            Box {
                GlassCircleButton(backdrop, glassIsDark(), Icons.Filled.MoreVert, "More options", onClick = { menu = true }, size = GLASS_HEADER_SIZE)
                GlassMenu(expanded = menu, onDismiss = { menu = false }) {
                    val newest = settings.albumSortOrder == SortOrder.DESCENDING
                    GlassMenuItem(painterResource(if (newest) R.drawable.order2 else R.drawable.order1), if (newest) "OLDEST FIRST" else "NEWEST FIRST") {
                        menu = false
                        viewModel.settingsRepo.setAlbumSortOrder(if (newest) SortOrder.ASCENDING else SortOrder.DESCENDING)
                    }
                    GlassMenuItem(painterResource(R.drawable.date), if (settings.albumShowDates) "HIDE DATES" else "SHOW DATES") {
                        menu = false
                        viewModel.settingsRepo.setAlbumShowDates(!settings.albumShowDates)
                    }
                    GlassMenuItem(painterResource(R.drawable.pinfill), if (album?.isPinned == true) "UNPIN ALBUM" else "PIN ALBUM") {
                        menu = false
                        viewModel.settingsRepo.togglePinned(relativePath)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (items.isEmpty() && !library.isLoading) {
                CenteredMessage("No items in this album")
            } else {
                MediaGrid(
                    items = items,
                    columns = settings.gridColumns,
                    showDateHeaders = settings.albumShowDates,
                    dateFormat = settings.dateFormat,
                    selection = selection,
                    onOpen = { navController.navigate(Routes.photo(it.id, MediaSource.Album(relativePath))) },
                    modifier = Modifier.fillMaxSize(),
                    favoriteIds = settings.favoriteIds,
                    darkMode = settings.darkMode,
                    contentPadding = gridPadding(padding.calculateTopPadding()),
                )
            }
            SelectionActions(viewModel, selection, items, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(viewModel: GalleryViewModel, navController: NavController) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val items = remember(library.all, settings.favoriteIds) { viewModel.favorites() }
    val selection = remember { SelectionState() }

    GlassScaffold(title = "FAVORITES", onBack = { navController.popBackStack() }) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (items.isEmpty()) {
                CenteredMessage("No favorites yet", "Tap the heart while viewing a photo.")
            } else {
                MediaGrid(
                    items = items,
                    columns = settings.gridColumns,
                    showDateHeaders = false,
                    dateFormat = settings.dateFormat,
                    selection = selection,
                    onOpen = { navController.navigate(Routes.photo(it.id, MediaSource.Favorites)) },
                    modifier = Modifier.fillMaxSize(),
                    favoriteIds = settings.favoriteIds,
                    darkMode = settings.darkMode,
                    contentPadding = gridPadding(padding.calculateTopPadding()),
                )
            }
            SelectionActions(viewModel, selection, items, Modifier.align(Alignment.BottomCenter))
        }
    }
}
