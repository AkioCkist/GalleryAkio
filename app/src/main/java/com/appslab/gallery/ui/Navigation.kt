package com.appslab.gallery.ui

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
import com.appslab.gallery.ui.components.LocalGlassDark
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appslab.gallery.ui.screens.AlbumDetailScreen
import com.appslab.gallery.ui.screens.FavoritesScreen
import com.appslab.gallery.ui.screens.MainTabsScreen
import com.appslab.gallery.ui.screens.PhotoDetailScreen
import com.appslab.gallery.ui.screens.SettingsScreen
import com.appslab.gallery.ui.screens.TrashScreen

/** Which list the photo viewer pages through. */
sealed interface MediaSource {
    data object All : MediaSource
    data object Favorites : MediaSource
    data object Videos : MediaSource
    /** Search results or a category: the list set in [GalleryViewModel.customList]. */
    data object Custom : MediaSource
    data class Album(val relativePath: String) : MediaSource

    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        Videos -> "videos"
        Custom -> "custom"
        is Album -> "album:$relativePath"
    }

    companion object {
        fun decode(s: String): MediaSource = when {
            s == "favorites" -> Favorites
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

    fun album(relativePath: String) = "album/${Uri.encode(relativePath)}"
    fun photo(id: Long, source: MediaSource) = "photoDetail/$id?source=${Uri.encode(source.encode())}"
}

private const val NAV_MS = 220

@Composable
fun GalleryNavHost(viewModel: GalleryViewModel, navController: NavHostController = rememberNavController()) {
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
            // The viewer is always dark, whatever the app theme: so are its menus and dialogs.
            CompositionLocalProvider(LocalGlassDark provides true) {
                PhotoDetailScreen(
                    viewModel = viewModel,
                    initialId = entry.arguments?.getLong("id") ?: 0L,
                    source = MediaSource.decode(entry.arguments?.getString("source") ?: "all"),
                    onBack = navController::popBackStack,
                    transition = transition,
                )
            }
        }
    }
}
