package com.example.galleryakio.data.search

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Size
import com.example.galleryakio.data.AiSettings
import com.example.galleryakio.data.MediaItem
import com.example.galleryakio.data.search.SearchIndex.Companion.FLAG_FACES
import com.example.galleryakio.data.search.SearchIndex.Companion.FLAG_LABELS
import com.example.galleryakio.data.search.SearchIndex.Companion.FLAG_TEXT
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class IndexProgress(
    val done: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    /** True while "only while charging" holds the work back. */
    val waitingForCharger: Boolean = false,
) {
    val fraction get() = if (total == 0) 1f else done.toFloat() / total
}

/**
 * Runs ML Kit image labeling, face detection and Latin text recognition over the
 * photo library, on-device, and keeps the results in [SearchIndex].
 *
 * Optimisations over a straight loop:
 * - [WORKERS] photos in flight at once, and the three detectors run concurrently per photo;
 * - decoding and model work run on low-priority background threads so scrolling stays smooth;
 * - incremental: only new, edited, or photos missing a now-enabled analysis are processed;
 * - ML Kit clients are created only for the parts the user has switched on.
 */
class MediaIndexer(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    val index = SearchIndex(context)

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(LABEL_THRESHOLD).build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build(),
        )
    }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Background-priority pool: the indexer never competes with the UI thread for CPU. */
    private val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "media-indexer")
    }.asCoroutineDispatcher()

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()

    private val _info = MutableStateFlow<Map<Long, IndexedInfo>>(emptyMap())
    /** Results for every analysed photo, updated as batches finish. */
    val info: StateFlow<Map<Long, IndexedInfo>> = _info.asStateFlow()

    private val mutex = Mutex()

    suspend fun load() = withContext(Dispatchers.IO) {
        _info.value = index.loadAll()
    }

    fun setWaitingForCharger(waiting: Boolean) = _progress.update { it.copy(waitingForCharger = waiting, running = false) }

    /** Brings the index in line with [items] for the analyses enabled in [ai]. Runs one pass at a time. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun sync(items: List<MediaItem>, ai: AiSettings) = mutex.withLock {
        val wanted = FLAG_LABELS or (if (ai.faces) FLAG_FACES else 0) or (if (ai.text) FLAG_TEXT else 0)
        val todo = withContext(Dispatchers.IO) {
            val photos = items.filter { !it.isVideo && !it.isSvg }
            val known = index.analyzedVersions()
            index.remove(known.keys - photos.mapTo(HashSet()) { it.id })
            val pending = photos.filter { p ->
                val k = known[p.id]
                k == null || k.first != p.dateModified || (k.second and wanted) != wanted
            }
            _progress.value = IndexProgress(photos.size - pending.size, photos.size, running = pending.isNotEmpty())
            if (pending.isEmpty()) _info.value = index.loadAll()
            // Newest first so recent photos become searchable quickly.
            pending.sortedByDescending { it.dateAdded }
        }
        if (todo.isEmpty()) return@withLock

        val queue = Channel<MediaItem>(Channel.UNLIMITED).apply { todo.forEach(::trySend); close() }
        val results = Channel<Analysis>(Channel.UNLIMITED)
        val active = AtomicInteger(WORKERS)
        try {
            coroutineScope {
                repeat(WORKERS) {
                    launch(dispatcher) {
                        for (item in queue) {
                            analyze(item, wanted)?.let { results.send(it) }
                            _progress.update { it.copy(done = it.done + 1) }
                        }
                        if (active.decrementAndGet() == 0) results.close()
                    }
                }
                // Single writer: batches go to SQLite and the in-memory map together.
                launch(Dispatchers.IO) {
                    val batch = ArrayList<Analysis>(BATCH)
                    for (r in results) {
                        batch += r
                        if (batch.size >= BATCH) flush(batch)
                    }
                    flush(batch)
                }
            }
        } finally {
            _progress.update { it.copy(running = false) }
        }
    }

    private val _generation = MutableStateFlow(0)
    /** Bumped by [clear], so whoever drives [sync] knows to start over. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    suspend fun clear() {
        withContext(Dispatchers.IO) { index.clear() }
        _info.value = emptyMap()
        _progress.value = IndexProgress()
        _generation.update { it + 1 }
    }

    fun indexedCount(): Int = _info.value.size

    private fun flush(batch: MutableList<Analysis>) {
        if (batch.isEmpty()) return
        index.save(batch)
        _info.update { map ->
            map + batch.associate { a ->
                a.mediaId to IndexedInfo(
                    labels = a.labels.map { it.first }.toSet(),
                    faces = a.faces,
                    smiling = a.smiling,
                    confidence = a.labels.maxOfOrNull { it.second } ?: 0f,
                )
            }
        }
        batch.clear()
    }

    private suspend fun analyze(item: MediaItem, flags: Int): Analysis? {
        // Text needs more pixels than labels and faces; decode once at the size the largest task needs.
        val bitmap = decode(item, if (flags and FLAG_TEXT != 0) TEXT_SIDE else LABEL_SIDE) ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            coroutineScope {
                val labels = async { runCatching { labeler.process(image).await() }.getOrDefault(emptyList()) }
                val faces = async {
                    if (flags and FLAG_FACES == 0) emptyList()
                    else runCatching { faceDetector.process(image).await() }.getOrDefault(emptyList())
                }
                val text = async {
                    if (flags and FLAG_TEXT == 0) ""
                    else runCatching { textRecognizer.process(image).await().text }.getOrDefault("")
                }
                val faceList = faces.await()
                Analysis(
                    mediaId = item.id,
                    dateModified = item.dateModified,
                    labels = labels.await().map { it.text.lowercase() to it.confidence }.sortedByDescending { it.second },
                    faces = faceList.size,
                    smiling = faceList.count { (it.smilingProbability ?: 0f) > 0.7f },
                    text = text.await().replace('\n', ' ').take(MAX_TEXT),
                    flags = flags,
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Analysis failed for ${item.id}", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes at most [maxSide] px on the long side, straight from MediaStore's thumbnail cache when possible. */
    private fun decode(item: MediaItem, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.loadThumbnail(item.uri, Size(maxSide, maxSide), null)
        } else if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                val scale = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            resolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "MediaIndexer"
        const val LABEL_THRESHOLD = 0.6f
        /** ML Kit's labeler works on 224 px internally and faces need ~100 px; 512 is plenty. */
        const val LABEL_SIDE = 512
        const val TEXT_SIDE = 960
        const val WORKERS = 3
        const val BATCH = 25
        const val MAX_TEXT = 2000
    }
}
