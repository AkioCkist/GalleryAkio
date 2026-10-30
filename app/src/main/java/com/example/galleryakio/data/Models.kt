package com.example.galleryakio.data

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class MediaType { PHOTO, VIDEO }

@Immutable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val type: MediaType,
    /** Epoch millis. */
    val dateAdded: Long,
    /** Epoch millis; falls back to [dateAdded] when the file has no capture date. */
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val mimeType: String,
    val relativePath: String,
    val bucketName: String,
    val width: Int = 0,
    val height: Int = 0,
    /** Video duration in millis, null for photos. */
    val duration: Long? = null,
    val isTrashed: Boolean = false,
) {
    val isVideo get() = type == MediaType.VIDEO
    val isGif get() = mimeType == "image/gif" || displayName.endsWith(".gif", ignoreCase = true)
    val isSvg get() = mimeType == "image/svg+xml" || displayName.endsWith(".svg", ignoreCase = true)
}

@Immutable
data class Album(
    /** Album key: the folder's relative path, e.g. "DCIM/Camera/". */
    val relativePath: String,
    val name: String,
    val itemCount: Int,
    val cover: MediaItem?,
    val isPinned: Boolean = false,
)

/** [value] is what the original app stored under the "sort_option" preference. */
enum class SortOption(val label: String, val description: String, val value: String) {
    DEFAULT("Date Taken (Default)", "Sort by date taken, newest first", "date_taken"),
    NAME("Name", "Sort alphabetically by file name", "name"),
    SIZE("Size", "Sort by file size, largest first", "size"),
    DATE_MODIFIED("Date Modified", "Sort by last modified date", "date_modified"),
    RANDOM("Random", "Randomize order for discovery", "random");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}

enum class SortOrder { ASCENDING, DESCENDING }

/**
 * Grid styles, modelled on popular gallery apps.
 * [defaultSpacing], [cornerRadius] and [border] describe each style's look.
 */
enum class GridStyle(
    val label: String,
    val description: String,
    val defaultSpacing: Int,
    val cornerRadius: Int,
    val border: Boolean,
) {
    NOTHING("Nothing", "Original: rounded tiles with a thin border", 4, 18, true),
    CLASSIC("Classic", "Edge-to-edge squares, like Google Photos and Samsung Gallery", 2, 0, false),
    ROUNDED("Rounded", "Soft rounded squares without borders, like One UI", 4, 12, false),
    MOSAIC("Mosaic", "Rows that keep each photo's shape, like Apple Photos and Aves", 2, 6, false),
    MASONRY("Masonry", "Columns of uncropped photos, Pinterest style", 4, 12, false),
    LIST("List", "One row per item with name, date and size, like Aves", 0, 10, false);

    val isSquare get() = this == NOTHING || this == CLASSIC || this == ROUNDED
}

/** Gap between tiles. AUTO uses the style's own spacing. */
enum class GridSpacing(val label: String, val dp: Int?) {
    AUTO("Auto", null), NONE("None", 0), SMALL("Small", 2), MEDIUM("Medium", 4), LARGE("Large", 8);

    fun resolve(style: GridStyle) = dp ?: style.defaultSpacing
}

/** [value] is what the original app stored under the "media_filters" preference. */
enum class MediaFilterType(
    val label: String,
    val value: String,
    val extensions: List<String>,
    val mimeTypes: List<String>,
) {
    IMAGES(
        "Images", "images",
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif"),
        listOf("image/jpeg", "image/png", "image/webp", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif"),
    ),
    VIDEOS(
        "Videos", "videos",
        listOf(".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp"),
        listOf("video/mp4", "video/avi", "video/x-matroska", "video/quicktime", "video/x-ms-wmv", "video/x-flv", "video/webm", "video/3gpp"),
    ),
    GIF("GIFs", "gif", listOf(".gif"), listOf("image/gif")),
    RAW(
        "RAW", "raw",
        listOf(".raw", ".dng", ".cr2", ".nef", ".arw", ".orf", ".rw2", ".pef", ".srw"),
        listOf(
            "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw",
            "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-pentax-pef", "image/x-samsung-srw", "image/raw",
        ),
    ),
    SVG("SVG", "svg", listOf(".svg"), listOf("image/svg+xml"));

    fun matches(item: MediaItem): Boolean =
        item.mimeType.lowercase() in mimeTypes ||
            extensions.any { item.displayName.endsWith(it, ignoreCase = true) }

    companion object {
        fun fromStored(s: String) = entries.firstOrNull { it.value == s || it.name == s }
    }
}

enum class DateFormatOption(val example: String, val pattern: String) {
    FORMAT_DOT_DMY("16.02.2021", "dd.MM.yyyy"),
    FORMAT_SLASH_DMY("16/02/2021", "dd/MM/yyyy"),
    FORMAT_SLASH_MDY("02/16/2021", "MM/dd/yyyy"),
    FORMAT_DASH_DMY("16-02-2021", "dd-MM-yyyy"),
    FORMAT_DASH_MDY("02-16-2021", "MM-dd-yyyy"),
    FORMAT_DOT_YMD("2021.02.16", "yyyy.MM.dd"),
    FORMAT_FULL_DMY("16 February 2021", "dd MMMM yyyy"),
    FORMAT_FULL_MDY("February 16 2021", "MMMM dd yyyy"),
    FORMAT_TIME_DMY("16.02.2021 14:30", "dd.MM.yyyy HH:mm"),
    FORMAT_TIME_FULL("16 February 2021 14:30", "dd MMMM yyyy HH:mm"),
}
