package com.example.galleryakio.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.example.galleryakio.data.AiSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.galleryakio.GalleryApplication
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.search.IndexProgress
import com.example.galleryakio.data.search.IndexedInfo
import com.example.galleryakio.data.search.SearchCategory
import com.example.galleryakio.data.search.normalizeForSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Face filter chips shown on the People view. */
enum class FaceFilter(val label: String) {
    ANY("ALL"), ONE("1 PERSON"), TWO("2 PEOPLE"), GROUP("GROUP 3+"), SMILING("SMILING");

    fun matches(info: IndexedInfo): Boolean = when (this) {
        ANY -> info.faces > 0
        ONE -> info.faces == 1
        TWO -> info.faces == 2
        GROUP -> info.faces >= 3
        SMILING -> info.smiling > 0
    }
}

data class CategorySummary(val category: SearchCategory, val items: List<MediaItem>, val avgConfidence: Float)

data class SearchResults(
    val query: String = "",
    /** File-name matches, available immediately. */
    val byName: List<MediaItem> = emptyList(),
    /** Label / recognised-text matches from the AI index, excluding [byName]. */
    val byContent: List<MediaItem> = emptyList(),
    val searching: Boolean = false,
) {
    val isEmpty get() = byName.isEmpty() && byContent.isEmpty()
}

/** Emits whether the device is plugged in, now and on every change. */
private fun chargingFlow(context: Context) = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            trySend(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(Intent.ACTION_BATTERY_CHANGED)
    }
    // ACTION_BATTERY_CHANGED is sticky, so registering delivers the current state at once.
    val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val gallery = app as GalleryApplication
    private val indexer = gallery.mediaIndexer

    val progress: StateFlow<IndexProgress> = indexer.progress
    val query = MutableStateFlow("")
    val faceFilter = MutableStateFlow(FaceFilter.ANY)

    private val library = MutableStateFlow<List<MediaItem>>(emptyList())
    val aiSettings: StateFlow<AiSettings> = gallery.settingsRepository.settings
        .map { it.ai }.stateIn(viewModelScope, SharingStarted.Eagerly, gallery.settingsRepository.settings.value.ai)

    init {
        viewModelScope.launch { indexer.load() }
        // Re-run (cancelling any pass in flight) whenever the library, the AI settings or
        // the charger state change. Work only starts once the Search tab is first opened.
        viewModelScope.launch {
            combine(library.filter { it.isNotEmpty() }, aiSettings, chargingFlow(app), indexer.generation) { items, ai, charging, _ ->
                Triple(items, ai, charging)
            }.collectLatest { (items, ai, charging) ->
                when {
                    !ai.enabled -> indexer.setWaitingForCharger(false)
                    ai.onlyWhileCharging && !charging -> indexer.setWaitingForCharger(true)
                    else -> {
                        indexer.setWaitingForCharger(false)
                        indexer.sync(items, ai)
                    }
                }
            }
        }
    }

    /** Called with the full library whenever it changes; re-indexes only what changed. */
    fun onLibrary(items: List<MediaItem>) {
        library.value = items
    }


    val categories: StateFlow<List<CategorySummary>> = combine(library, indexer.info) { items, info ->
        withContext(Dispatchers.Default) {
            SearchCategory.entries.map { cat ->
                val matched = items.filter { item -> info[item.id]?.let { cat.matches(it.labels, it.faces) } == true }
                CategorySummary(cat, matched, matched.mapNotNull { info[it.id]?.confidence }.average().toFloat().takeIf { !it.isNaN() } ?: 0f)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val people: StateFlow<List<MediaItem>> = combine(library, indexer.info, faceFilter) { items, info, filter ->
        withContext(Dispatchers.Default) {
            items.filter { item -> info[item.id]?.let(filter::matches) == true }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<SearchResults> = combine(
        query.map { it.trim() }.distinctUntilChanged().debounce(250),
        library,
        indexer.info.map { it.size }.distinctUntilChanged(),
    ) { q, items, _ -> q to items }
        .mapLatest { (q, items) ->
            if (q.isEmpty()) return@mapLatest SearchResults()
            withContext(Dispatchers.Default) {
                // Every word must appear in the file name (extension included) or the folder name.
                val words = normalizeForSearch(q).split(Regex("\\s+"))
                val byName = items.filter { item ->
                    val haystack = normalizeForSearch("${item.displayName} ${item.bucketName}")
                    words.all { it in haystack }
                }
                val nameIds = byName.mapTo(HashSet()) { it.id }
                val contentIds = if (!aiSettings.value.enabled) emptySet()
                else withContext(Dispatchers.IO) { indexer.index.search(q) }
                val byContent = items.filter { it.id in contentIds && it.id !in nameIds }
                SearchResults(q, byName, byContent)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())
}
