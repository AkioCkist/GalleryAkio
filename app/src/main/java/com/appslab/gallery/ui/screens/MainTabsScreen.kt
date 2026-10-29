package com.appslab.gallery.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.appslab.gallery.R
import com.appslab.gallery.data.SortOrder
import com.appslab.gallery.ui.GalleryViewModel
import com.appslab.gallery.ui.Routes
import com.appslab.gallery.ui.components.TabHeader
import com.appslab.gallery.ui.components.TabHeaderHeight
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors
import kotlinx.coroutines.launch

private val Tabs = listOf("PHOTOS", "VIDEOS", "ALBUMS", "SEARCH")
private const val TAB_PHOTOS = 0
private const val TAB_VIDEOS = 1
private const val TAB_ALBUMS = 2

/** Space the tab pages leave at the top so content starts under the overlaid header. */
val LocalHeaderPadding = staticCompositionLocalOf<Dp> { 0.dp }

@Composable
fun MainTabsScreen(viewModel: GalleryViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pager = rememberPagerState { Tabs.size }
    val scope = rememberCoroutineScope()
    var headerVisible by remember { mutableStateOf(true) }
    var lastToggle by remember { mutableStateOf(0L) }

    // Same rule as TabBarVisibilityState: ignore flips closer than 100 ms apart.
    fun setVisible(v: Boolean) {
        val now = System.currentTimeMillis()
        if (v != headerVisible && now - lastToggle > 100) {
            headerVisible = v
            lastToggle = now
        }
    }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) setVisible(false) else if (available.y > 6f) setVisible(true)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pager.currentPage) { headerVisible = true }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalHeaderPadding provides TabHeaderHeight) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().nestedScroll(hideOnScroll),
                // Only the visible tab stays composed: each tab is a full grid, and keeping a
                // second one alive doubled the work of coming back from the viewer.
                beyondViewportPageCount = 0,
            ) { page ->
                when (page) {
                    TAB_PHOTOS -> PhotosScreen(viewModel, navController)
                    TAB_VIDEOS -> VideosScreen(viewModel, navController)
                    TAB_ALBUMS -> AlbumsScreen(viewModel, navController)
                    else -> SearchScreen(viewModel, navController)
                }
            }
        }
        TabHeader(
            tabs = Tabs,
            selected = pager.currentPage,
            visible = headerVisible,
            darkMode = settings.darkMode,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ThreeDotMenu(viewModel, navController, pager.currentPage, iconColor = if (settings.darkMode) Color.White else Color(0xFF212121))
        }
    }
}

@Composable
private fun ThreeDotMenu(viewModel: GalleryViewModel, navController: NavController, tab: Int, iconColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(20.dp)
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.more2), "More options", tint = iconColor, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = shape,
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
            modifier = Modifier.width(200.dp).shadow(16.dp, shape).clip(shape),
        ) {
            if (tab == TAB_PHOTOS || tab == TAB_VIDEOS) {
                val newest = settings.sortOrder == SortOrder.DESCENDING
                MenuEntry(
                    icon = if (newest) R.drawable.order1 else R.drawable.order2,
                    title = "SORT ORDER",
                    subtitle = if (newest) "NEWEST FIRST" else "OLDEST FIRST",
                ) {
                    viewModel.settingsRepo.setSortOrder(if (newest) SortOrder.ASCENDING else SortOrder.DESCENDING)
                    expanded = false
                }
            }
            if (tab == TAB_ALBUMS && settings.pinnedAlbums.isNotEmpty()) {
                MenuEntry(R.drawable.pinfill, "CLEAR PINS") { viewModel.settingsRepo.clearPinned(); expanded = false }
            }
            MenuEntry(R.drawable.fav123, "FAVORITES") { expanded = false; navController.navigate(Routes.FAVORITES) }
            MenuEntry(R.drawable.del123, "TRASH") { expanded = false; navController.navigate(Routes.TRASH) }
            MenuEntry(R.drawable.setting17, "SETTINGS") { expanded = false; navController.navigate(Routes.SETTINGS) }
        }
    }
}

@Composable
private fun MenuEntry(icon: Int, title: String, subtitle: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, fontFamily = CqMono, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle != null) {
                        Text(subtitle, fontFamily = CqMono, fontSize = 12.sp, color = LocalCustomColors.current.unselectedItem)
                    }
                }
            }
        },
        onClick = onClick,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
