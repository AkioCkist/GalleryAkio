package com.appslab.gallery.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
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
    data class Album(val relativePath: String) : MediaSource

    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        is Album -> "album:$relativePath"
    }

    companion object {
        fun decode(s: String): MediaSource = when {
            s == "favorites" -> Favorites
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

@Composable
fun GalleryNavHost(viewModel: GalleryViewModel, navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
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
            enterTransition = { fadeIn() },
            popExitTransition = { fadeOut() },
        ) { entry ->
            PhotoDetailScreen(
                viewModel = viewModel,
                initialId = entry.arguments?.getLong("id") ?: 0L,
                source = MediaSource.decode(entry.arguments?.getString("source") ?: "all"),
                onBack = navController::popBackStack,
            )
        }
    }
}
