package com.example.galleryakio.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.galleryakio.editor.EditorScreen
import com.example.galleryakio.ui.components.LocalGlassDark
import com.example.galleryakio.ui.components.liquidGlass
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.galleryakio.ui.screens.AlbumDetailScreen
import com.example.galleryakio.ui.screens.FavoritesScreen
import com.example.galleryakio.ui.screens.MainTabsScreen
import com.example.galleryakio.ui.screens.PhotoDetailScreen
import com.example.galleryakio.ui.screens.SettingsScreen
import com.example.galleryakio.ui.screens.TrashScreen

/** Which list the photo viewer pages through. */
sealed interface MediaSource {
    data object All : MediaSource
    data object Favorites : MediaSource
    data object Videos : MediaSource
    /** Search results or a category: the list set in [GalleryViewModel.customList]. */
    data object Custom : MediaSource
    /** A file another app opened with this one: [GalleryViewModel.externalItem]. */
    data object External : MediaSource
    data class Album(val relativePath: String) : MediaSource

    fun encode(): String = when (this) {
        All -> "all"
        External -> "external"
        Favorites -> "favorites"
        Videos -> "videos"
        Custom -> "custom"
        is Album -> "album:$relativePath"
    }

    companion object {
        fun decode(s: String): MediaSource = when {
            s == "favorites" -> Favorites
            s == "external" -> External
            s == "custom" -> Custom
            s == "videos" -> Videos
            s.startsWith("album:") -> Album(s.removePrefix("album:"))
            else -> All
        }
    }
}

object Routes {
    const val MAIN = "main_tabs"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val FAVORITES = "favorite"
    const val ALBUM = "album/{path}"
    const val PHOTO = "photoDetail/{id}?source={source}"
    const val EDITOR = "editor/{id}"

    fun album(relativePath: String) = "album/${Uri.encode(relativePath)}"
    fun photo(id: Long, source: MediaSource) = "photoDetail/$id?source=${Uri.encode(source.encode())}"
    fun editor(id: Long) = "editor/$id"
}

private const val NAV_MS = 220
private const val KEY_FROM_EDITOR = "from_editor"

@Composable
fun GalleryNavHost(viewModel: GalleryViewModel, navController: NavHostController = rememberNavController()) {
    val opened = viewModel.externalItem
    LaunchedEffect(opened) {
        if (opened != null) navController.navigate(Routes.photo(opened.id, MediaSource.External))
    }
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        // Short tweens rather than the default springs, whose long tail made back feel laggy.
        enterTransition = { slideInHorizontally(tween(NAV_MS)) { it / 4 } + fadeIn(tween(NAV_MS)) },
        // The screen under the viewer stays as it is: fading it too only adds work to every frame.
        exitTransition = { if (targetState.destination.route == Routes.PHOTO) ExitTransition.None else fadeOut(tween(NAV_MS)) },
        popEnterTransition = { if (initialState.destination.route == Routes.PHOTO) EnterTransition.None else fadeIn(tween(NAV_MS)) },
        popExitTransition = { slideOutHorizontally(tween(NAV_MS)) { it / 4 } + fadeOut(tween(NAV_MS)) },
    ) {
        composable(Routes.MAIN) {
            MainTabsScreen(viewModel, navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel, onBack = navController::popBackStack)
        }
        composable(Routes.TRASH) {
            TrashScreen(viewModel, onBack = navController::popBackStack)
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(viewModel, navController)
        }
        composable(Routes.ALBUM, arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            AlbumDetailScreen(viewModel, navController, relativePath = path)
        }
        composable(
            Routes.PHOTO,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("source") { type = NavType.StringType; defaultValue = "all" },
            ),
            // The viewer animates itself (growing out of the tapped tile) off this transition.
            enterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) { entry ->
            // To and from the editor the viewer simply stays and fades: its own motion would fly
            // the picture back to its grid tile, which isn't where the user is going.
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            var fromEditor by remember { mutableStateOf(entry.savedStateHandle.remove<Boolean>(KEY_FROM_EDITOR) == true) }
            LaunchedEffect(transition.currentState, transition.targetState) {
                if (transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible) fromEditor = false
            }
            // The viewer is always dark, whatever the app theme: so are its menus and dialogs.
            CompositionLocalProvider(LocalGlassDark provides true) {
                PhotoDetailScreen(
                    viewModel = viewModel,
                    initialId = entry.arguments?.getLong("id") ?: 0L,
                    source = MediaSource.decode(entry.arguments?.getString("source") ?: "all"),
                    onBack = navController::popBackStack,
                    onEdit = { item ->
                        entry.savedStateHandle[KEY_FROM_EDITOR] = true
                        navController.navigate(Routes.editor(item.id))
                    },
                    transition = if (fromEditor || currentRoute == Routes.EDITOR) null else transition,
                )
            }
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val context = LocalContext.current
            val library by viewModel.library.collectAsStateWithLifecycle()
            val id = entry.arguments?.getLong("id") ?: 0L
            // Kept once found: saving adds to the library, which must not restart the editor.
            val item = remember(id) { library.all.firstOrNull { it.id == id } }
            if (item == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                EditorScreen(
                    uri = item.uri,
                    displayName = item.displayName,
                    isVideo = item.isVideo,
                    glass = { backdrop, shape -> liquidGlass(backdrop, darkMode = true, lensHeight = 6.dp, lensAmount = 12.dp, shape = shape) },
                    onClose = navController::popBackStack,
                    onSaved = {
                        Toast.makeText(context, if (item.isVideo) "Saved to Movies/Gallery" else "Saved to Pictures/Gallery", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
