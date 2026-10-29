package com.appslab.gallery.ui.screens

import com.appslab.gallery.ui.components.GlassMenu
import com.appslab.gallery.ui.components.GlassMenuItem
import androidx.compose.foundation.background
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.appslab.gallery.ui.components.GlassCircleButton
import com.appslab.gallery.ui.components.GlassNavBar
import com.appslab.gallery.ui.components.NAV_BAR_HEIGHT
import com.appslab.gallery.ui.components.NAV_BAR_MARGIN
import com.appslab.gallery.ui.components.NavTab
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.graphics.Brush
import com.appslab.gallery.ui.theme.CqMono
import com.appslab.gallery.ui.theme.LocalCustomColors
import kotlinx.coroutines.launch

private val Tabs = listOf(
    NavTab("PHOTOS", Icons.Rounded.Photo),
    NavTab("VIDEOS", Icons.Rounded.PlayCircle),
    NavTab("ALBUMS", Icons.Rounded.PhotoLibrary),
    NavTab("SEARCH", Icons.Rounded.Search),
)
private const val TAB_PHOTOS = 0
private const val TAB_VIDEOS = 1
private const val TAB_ALBUMS = 2

/** Space the tab pages leave at the top, below the status bar. */
val LocalHeaderPadding = staticCompositionLocalOf<Dp> { 0.dp }

/** Space the floating nav bar takes at the bottom, above the system navigation bar. */
val LocalBottomBarPadding = staticCompositionLocalOf<Dp> { 0.dp }

@Composable
fun MainTabsScreen(viewModel: GalleryViewModel, navController: NavController) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pager = rememberPagerState { Tabs.size }
    val scope = rememberCoroutineScope()
    val background = MaterialTheme.colorScheme.background
    // What the glass bar shows through: the tab pages, recorded into a layer each frame.
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

    // The bar slides away while the user scrolls down through photos, and comes back when
    // they scroll up, tap empty space or change tab.
    var navVisible by remember { mutableStateOf(true) }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) navVisible = false else if (available.y > 6f) navVisible = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pager.currentPage) { navVisible = true }
    val hidden by animateFloatAsState(
        if (navVisible) 0f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "nav_hidden",
    )
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.getBottom(density)

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            // A tap nothing else used (a photo or button consumes its own) is a tap on empty space.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val up = waitForUpOrCancellation(PointerEventPass.Final)
                    if (up != null && !up.isConsumed) navVisible = true
                }
            },
    ) {
        CompositionLocalProvider(
            LocalHeaderPadding provides 4.dp,
            LocalBottomBarPadding provides NAV_BAR_HEIGHT + NAV_BAR_MARGIN,
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().nestedScroll(hideOnScroll).layerBackdrop(backdrop),
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
        // Content scrolls under the transparent status bar; fade it so the clock stays readable.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0.7f)))),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = NAV_BAR_MARGIN)
                // Slid on the GPU, fully below the screen edge when hidden.
                .graphicsLayer {
                    translationY = hidden * (size.height + NAV_BAR_MARGIN.toPx() + navInset)
                    alpha = 1f - hidden * 0.5f
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassNavBar(
                backdrop = backdrop,
                tabs = Tabs,
                position = { pager.currentPage + pager.currentPageOffsetFraction },
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                darkMode = settings.darkMode,
            )
            ThreeDotMenu(viewModel, navController, pager.currentPage, backdrop, settings.darkMode)
        }
    }
}

@Composable
private fun ThreeDotMenu(viewModel: GalleryViewModel, navController: NavController, tab: Int, backdrop: Backdrop, darkMode: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Box {
        GlassCircleButton(backdrop, darkMode, Icons.Rounded.MoreVert, "More options", onClick = { expanded = true })
        GlassMenu(expanded = expanded, onDismiss = { expanded = false }) {
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
    GlassMenuItem(painterResource(icon), title, subtitle, onClick = onClick)
}
