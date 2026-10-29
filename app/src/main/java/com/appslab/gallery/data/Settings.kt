package com.appslab.gallery.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.appslab.gallery.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GallerySettings(
    val theme: AppTheme = AppTheme.DEFAULT,
    val darkMode: Boolean = true,
    val gridColumns: Int = 3,
    val sortOption: SortOption = SortOption.DEFAULT,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    /** Empty means "show everything". */
    val mediaFilters: Set<MediaFilterType> = emptySet(),
    val dateFormat: DateFormatOption = DateFormatOption.FORMAT_FULL_DMY,
    val pinnedAlbums: List<String> = emptyList(),
    val favoriteIds: Set<Long> = emptySet(),
    val albumShowDates: Boolean = true,
    val albumSortOrder: SortOrder = SortOrder.DESCENDING,
    val exitPrivacy: Boolean = false,
    val appProtection: Boolean = false,
    val maxBrightnessViewing: Boolean = false,
    val rememberVideoPosition: Boolean = false,
    val ai: AiSettings = AiSettings(),
    val gridStyle: GridStyle = GridStyle.NOTHING,
    val gridSpacing: GridSpacing = GridSpacing.AUTO,
    val showDateHeaders: Boolean = true,
    val showVideoDuration: Boolean = true,
)

/** On-device AI search. Each part can be switched off to save battery and time. */
data class AiSettings(
    val enabled: Boolean = true,
    val faces: Boolean = true,
    val text: Boolean = true,
    val onlyWhileCharging: Boolean = false,
)

/**
 * Settings backed by the same "GalleryPrefs" file and keys as the original app,
 * so installing this build over the old one keeps the user's choices.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<GallerySettings> = _settings.asStateFlow()

    private fun read(): GallerySettings = GallerySettings(
        theme = runCatching { AppTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "DEFAULT") }
            .getOrDefault(AppTheme.DEFAULT),
        darkMode = prefs.getBoolean(KEY_DARK_MODE, true),
        gridColumns = prefs.getInt(KEY_GRID_COLUMNS, 3).coerceIn(1, 6),
        sortOption = SortOption.fromValue(prefs.getString(KEY_SORT_OPTION, null)),
        sortOrder = prefs.getString(KEY_SORT_ORDER, "descending").toSortOrder(),
        mediaFilters = prefs.getStringSet(KEY_MEDIA_FILTERS, null).orEmpty()
            .mapNotNull(MediaFilterType::fromStored).toSet()
            // The original stored every type when nothing was filtered.
            .let { if (it.size == MediaFilterType.entries.size) emptySet() else it },
        dateFormat = runCatching { DateFormatOption.valueOf(prefs.getString(KEY_DATE_FORMAT, null)!!) }
            .getOrDefault(DateFormatOption.FORMAT_FULL_DMY),
        pinnedAlbums = prefs.getString(KEY_PINNED_ALBUMS, "").orEmpty()
            .split(',').filter { it.isNotBlank() },
        favoriteIds = prefs.getStringSet(KEY_FAVORITE_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet(),
        albumShowDates = prefs.getBoolean(KEY_ALBUM_SHOW_DATES, true),
        albumSortOrder = prefs.getString(KEY_ALBUM_SORT_ORDER, "descending").toSortOrder(),
        exitPrivacy = prefs.getBoolean(KEY_EXIT_PRIVACY, false),
        appProtection = prefs.getBoolean(KEY_APP_PROTECTION, false),
        maxBrightnessViewing = prefs.getBoolean(KEY_MAX_BRIGHTNESS, false),
        rememberVideoPosition = prefs.getBoolean(KEY_REMEMBER_VIDEO_POSITION, false),
        ai = AiSettings(
            enabled = prefs.getBoolean(KEY_AI_ENABLED, true),
            faces = prefs.getBoolean(KEY_AI_FACES, true),
            text = prefs.getBoolean(KEY_AI_TEXT, true),
            onlyWhileCharging = prefs.getBoolean(KEY_AI_CHARGING, false),
        ),
        gridStyle = runCatching { GridStyle.valueOf(prefs.getString(KEY_GRID_STYLE, null)!!) }.getOrDefault(GridStyle.NOTHING),
        gridSpacing = runCatching { GridSpacing.valueOf(prefs.getString(KEY_GRID_SPACING, null)!!) }.getOrDefault(GridSpacing.AUTO),
        showDateHeaders = prefs.getBoolean(KEY_DATE_HEADERS, true),
        showVideoDuration = prefs.getBoolean(KEY_VIDEO_DURATION, true),
    )

    fun setGridStyle(style: GridStyle) = change({ it.copy(gridStyle = style) }) { putString(KEY_GRID_STYLE, style.name) }
    fun setGridSpacing(spacing: GridSpacing) = change({ it.copy(gridSpacing = spacing) }) { putString(KEY_GRID_SPACING, spacing.name) }
    fun setShowDateHeaders(on: Boolean) = change({ it.copy(showDateHeaders = on) }) { putBoolean(KEY_DATE_HEADERS, on) }
    fun setShowVideoDuration(on: Boolean) = change({ it.copy(showVideoDuration = on) }) { putBoolean(KEY_VIDEO_DURATION, on) }

    fun setAi(ai: AiSettings) = change({ it.copy(ai = ai) }) {
        putBoolean(KEY_AI_ENABLED, ai.enabled)
        putBoolean(KEY_AI_FACES, ai.faces)
        putBoolean(KEY_AI_TEXT, ai.text)
        putBoolean(KEY_AI_CHARGING, ai.onlyWhileCharging)
    }

    private inline fun change(transform: (GallerySettings) -> GallerySettings, crossinline write: SharedPreferences.Editor.(GallerySettings) -> Unit) {
        _settings.update(transform)
        val s = _settings.value
        prefs.edit { write(s) }
    }

    fun setTheme(theme: AppTheme) = change({ it.copy(theme = theme) }) { putString(KEY_THEME, it.theme.name) }
    fun setDarkMode(dark: Boolean) = change({ it.copy(darkMode = dark) }) { putBoolean(KEY_DARK_MODE, it.darkMode) }
    fun setGridColumns(columns: Int) =
        change({ it.copy(gridColumns = columns.coerceIn(1, 6)) }) { putInt(KEY_GRID_COLUMNS, it.gridColumns) }
    fun setSortOption(option: SortOption) =
        change({ it.copy(sortOption = option) }) { putString(KEY_SORT_OPTION, it.sortOption.value) }
    fun setSortOrder(order: SortOrder) =
        change({ it.copy(sortOrder = order) }) { putString(KEY_SORT_ORDER, order.toPref()) }
    fun setMediaFilters(filters: Set<MediaFilterType>) =
        change({ it.copy(mediaFilters = filters) }) { putStringSet(KEY_MEDIA_FILTERS, filters.map { f -> f.value }.toSet()) }
    fun setDateFormat(format: DateFormatOption) =
        change({ it.copy(dateFormat = format) }) { putString(KEY_DATE_FORMAT, format.name) }
    fun setAlbumShowDates(show: Boolean) =
        change({ it.copy(albumShowDates = show) }) { putBoolean(KEY_ALBUM_SHOW_DATES, show) }
    fun setAlbumSortOrder(order: SortOrder) =
        change({ it.copy(albumSortOrder = order) }) { putString(KEY_ALBUM_SORT_ORDER, order.toPref()) }
    fun setExitPrivacy(on: Boolean) = change({ it.copy(exitPrivacy = on) }) { putBoolean(KEY_EXIT_PRIVACY, on) }
    fun setAppProtection(on: Boolean) = change({ it.copy(appProtection = on) }) { putBoolean(KEY_APP_PROTECTION, on) }
    fun setMaxBrightnessViewing(on: Boolean) =
        change({ it.copy(maxBrightnessViewing = on) }) { putBoolean(KEY_MAX_BRIGHTNESS, on) }
    fun setRememberVideoPosition(on: Boolean) =
        change({ it.copy(rememberVideoPosition = on) }) { putBoolean(KEY_REMEMBER_VIDEO_POSITION, on) }

    fun togglePinned(relativePath: String) = change({
        val pinned = if (relativePath in it.pinnedAlbums) it.pinnedAlbums - relativePath else it.pinnedAlbums + relativePath
        it.copy(pinnedAlbums = pinned)
    }) { putString(KEY_PINNED_ALBUMS, it.pinnedAlbums.joinToString(",")) }

    /**
     * Puts every setting back to its default. The user's own data, favourites and
     * pinned albums, is kept: only preferences are reset.
     */
    fun resetToDefaults() {
        val keep = _settings.value
        prefs.edit {
            clear()
            putStringSet(KEY_FAVORITE_IDS, keep.favoriteIds.map(Long::toString).toSet())
            putString(KEY_PINNED_ALBUMS, keep.pinnedAlbums.joinToString(","))
        }
        _settings.value = read()
    }

    fun clearPinned() = change({ it.copy(pinnedAlbums = emptyList()) }) { putString(KEY_PINNED_ALBUMS, "") }

    fun toggleFavorite(id: Long) = change({
        it.copy(favoriteIds = if (id in it.favoriteIds) it.favoriteIds - id else it.favoriteIds + id)
    }) { putStringSet(KEY_FAVORITE_IDS, it.favoriteIds.map(Long::toString).toSet()) }

    private fun String?.toSortOrder() = if (this == "ascending") SortOrder.ASCENDING else SortOrder.DESCENDING
    private fun SortOrder.toPref() = if (this == SortOrder.ASCENDING) "ascending" else "descending"

    companion object {
        const val PREFS_NAME = "GalleryPrefs"
        private const val KEY_THEME = "app_theme"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_SORT_OPTION = "sort_option"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_MEDIA_FILTERS = "media_filters"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_PINNED_ALBUMS = "pinned_albums"
        private const val KEY_FAVORITE_IDS = "favorite_ids"
        private const val KEY_ALBUM_SHOW_DATES = "album_show_dates"
        private const val KEY_ALBUM_SORT_ORDER = "album_sort_order"
        private const val KEY_EXIT_PRIVACY = "exit_privacy"
        private const val KEY_APP_PROTECTION = "app_protection"
        private const val KEY_MAX_BRIGHTNESS = "max_brightness_viewing"
        private const val KEY_REMEMBER_VIDEO_POSITION = "remember_video_position"
        private const val KEY_GRID_STYLE = "grid_style"
        private const val KEY_GRID_SPACING = "grid_spacing"
        private const val KEY_DATE_HEADERS = "show_date_headers"
        private const val KEY_VIDEO_DURATION = "show_video_duration"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_FACES = "ai_faces"
        private const val KEY_AI_TEXT = "ai_text"
        private const val KEY_AI_CHARGING = "ai_only_charging"
    }
}
