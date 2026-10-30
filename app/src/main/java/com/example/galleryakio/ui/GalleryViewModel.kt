package com.example.galleryakio.ui

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.galleryakio.GalleryApplication
import com.example.galleryakio.data.Album
import com.example.galleryakio.data.GallerySettings
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.SortOption
import com.example.galleryakio.data.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.withIndex
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

    /** Only the settings that change what the library shows; grid columns, theme etc. don't re-sort it. */
    private data class LibraryOptions(
        val filters: Set<com.example.galleryakio.data.MediaFilterType>,
        val sortOption: SortOption,
        val sortOrder: SortOrder,
        val pinned: List<String>,
    )

    val library: StateFlow<LibraryState> = combine(
        raw,
        settings.map { LibraryOptions(it.mediaFilters, it.sortOption, it.sortOrder, it.pinnedAlbums) }.distinctUntilChanged(),
        randomSeed,
    ) { items, o, seed ->
        if (items == null) return@combine LibraryState(isLoading = true)
        val filtered = if (o.filters.isEmpty()) items
        else items.filter { item -> o.filters.any { it.matches(item) } }
        LibraryState(
            isLoading = false,
            all = items,
            visible = sort(filtered, o.sortOption, o.sortOrder, seed),
            albums = media.buildAlbums(items, o.pinned),
        )
    }
        // Sorting ~10k items must never run on the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    private var started = false

    /** Call once storage permission is granted. Reloads on every MediaStore change. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            // The first load runs at once; only later MediaStore bursts are debounced.
            media.changes().withIndex().debounce { if (it.index == 0) 0L else 300L }.collect { reload() }
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
