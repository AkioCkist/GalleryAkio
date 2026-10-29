package com.appslab.gallery.ui

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appslab.gallery.GalleryApplication
import com.appslab.gallery.data.Album
import com.appslab.gallery.data.GallerySettings
import com.appslab.gallery.data.MediaItem
import com.appslab.gallery.data.SortOption
import com.appslab.gallery.data.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

data class LibraryState(
    val isLoading: Boolean = true,
    /** Every item, newest first, before user filters. */
    val all: List<MediaItem> = emptyList(),
    /** [all] after media-type filters and sort settings: what the Photos tab shows. */
    val visible: List<MediaItem> = emptyList(),
    val albums: List<Album> = emptyList(),
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val gallery = app as GalleryApplication
    private val media = gallery.mediaRepository
    val settingsRepo = gallery.settingsRepository
    val settings: StateFlow<GallerySettings> = settingsRepo.settings

    private val raw = MutableStateFlow<List<MediaItem>?>(null)
    private val _trash = MutableStateFlow<List<MediaItem>>(emptyList())
    val trash: StateFlow<List<MediaItem>> = _trash.asStateFlow()

    /** Shuffle seed for [SortOption.RANDOM]; changes only on explicit reshuffle. */
    private val randomSeed = MutableStateFlow(Random.nextLong())

    val library: StateFlow<LibraryState> = combine(raw, settings, randomSeed) { items, s, seed ->
        if (items == null) return@combine LibraryState(isLoading = true)
        val filtered = if (s.mediaFilters.isEmpty()) items
        else items.filter { item -> s.mediaFilters.any { it.matches(item) } }
        LibraryState(
            isLoading = false,
            all = items,
            visible = sort(filtered, s.sortOption, s.sortOrder, seed),
            albums = media.buildAlbums(items, s.pinnedAlbums),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    private var started = false

    /** Call once storage permission is granted. Reloads on every MediaStore change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            media.changes().debounce(300).collect { reload() }
        }
    }

    suspend fun reload() {
        raw.value = media.loadAll()
    }

    fun loadTrash() = viewModelScope.launch { _trash.value = media.loadAll(trashed = true) }

    fun item(id: Long): MediaItem? = library.value.all.firstOrNull { it.id == id }

    fun albumItems(relativePath: String, order: SortOrder): List<MediaItem> =
        library.value.all.filter { it.relativePath == relativePath }
            .let { list -> if (order == SortOrder.ASCENDING) list.sortedBy { it.dateTaken } else list.sortedByDescending { it.dateTaken } }

    fun favorites(): List<MediaItem> {
        val ids = settings.value.favoriteIds
        return library.value.all.filter { it.id in ids }
    }

    /** List the photo viewer pages through for [MediaSource.Custom] (search results, categories). */
    var customList: List<MediaItem> = emptyList()

    fun reshuffle() {
        randomSeed.value = Random.nextLong()
    }

    // Each returns a consent PendingIntent to launch, or null when finished already.
    fun trashRequest(items: Collection<MediaItem>): PendingIntent? = media.trashRequest(items)
    fun restoreRequest(items: Collection<MediaItem>): PendingIntent? = media.trashRequest(items, trash = false)
    fun deleteRequest(items: Collection<MediaItem>): PendingIntent? = media.deleteRequest(items)
    fun writeRequest(items: Collection<MediaItem>): PendingIntent? = media.writeRequest(items)
    suspend fun rename(item: MediaItem, newName: String) = media.rename(item, newName)

    /**
     * Same as the original createPerfectGridListItemsWithSortOrder: days are ordered by
     * DATE_ADDED (newest or oldest first), and [option] only reorders items inside each day.
     */
    private fun sort(items: List<MediaItem>, option: SortOption, order: SortOrder, seed: Long): List<MediaItem> {
        val byDate = if (order == SortOrder.ASCENDING) items.sortedBy { it.dateAdded } else items.sortedByDescending { it.dateAdded }
        if (option == SortOption.DEFAULT) return byDate
        val random = Random(seed)
        val tz = java.util.TimeZone.getDefault()
        val dayKey = { item: MediaItem -> (item.dateAdded + tz.getOffset(item.dateAdded)) / DAY_MS }
        return byDate.groupBy(dayKey).values.flatMap { day ->
            when (option) {
                SortOption.NAME -> day.sortedBy { it.displayName.lowercase() }
                SortOption.SIZE -> day.sortedByDescending { it.size }
                SortOption.DATE_MODIFIED -> day.sortedByDescending { it.dateModified }
                SortOption.RANDOM -> day.shuffled(random)
                SortOption.DEFAULT -> day
            }
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
