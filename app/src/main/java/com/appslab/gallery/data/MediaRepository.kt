package com.appslab.gallery.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File

/** Reads photos and videos from MediaStore. All functions are main-safe. */
class MediaRepository(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Emits once immediately, then every time MediaStore reports a change. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * Loads every photo and video. Unlike the original build there is no LIMIT,
     * so libraries with more than 1000 photos / 500 videos show completely.
     */
    suspend fun loadAll(trashed: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            val photos = async { query(MediaType.PHOTO, trashed) }
            val videos = async { query(MediaType.VIDEO, trashed) }
            photos.await() + videos.await()
        }
    }

    private fun query(type: MediaType, trashed: Boolean): List<MediaItem> {
        val collection = collectionFor(type)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(if (type == MediaType.PHOTO) MediaStore.Images.Media.DATE_TAKEN else MediaStore.Video.Media.DATE_TAKEN)
            if (type == MediaType.VIDEO) add(MediaStore.Video.Media.DURATION)
            if (type == MediaType.PHOTO) add(MediaStore.Images.ImageColumns.ORIENTATION)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
            else @Suppress("DEPRECATION") add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()

        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            resolver.query(collection, projection, args, null)
        } else {
            if (trashed) return emptyList()
            resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }

        val result = ArrayList<MediaItem>(cursor?.count ?: 0)
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val widthCol = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val bucketCol = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val durationCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            val orientationCol = c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION)
            val relCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            @Suppress("DEPRECATION")
            val dataCol = if (Build.VERSION.SDK_INT < 29) c.getColumnIndex(MediaStore.MediaColumns.DATA) else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val added = c.getLong(addedCol) * 1000
                val taken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                val relativePath = when {
                    relCol >= 0 -> c.getString(relCol).orEmpty()
                    dataCol >= 0 -> relativePathFromData(c.getString(dataCol))
                    else -> ""
                }
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "Unknown",
                    type = type,
                    dateAdded = added,
                    dateTaken = if (taken > 0) taken else added,
                    dateModified = c.getLong(modifiedCol) * 1000,
                    size = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol) ?: if (type == MediaType.PHOTO) "image/*" else "video/*",
                    relativePath = relativePath,
                    bucketName = (if (bucketCol >= 0) c.getString(bucketCol) else null)
                        ?: relativePath.trimEnd('/').substringAfterLast('/').ifEmpty { "Unknown" },
                    // WIDTH/HEIGHT are the stored pixels; swap for photos rotated by EXIF.
                    width = if (rotated(c, orientationCol)) c.getIntOrZero(heightCol) else c.getIntOrZero(widthCol),
                    height = if (rotated(c, orientationCol)) c.getIntOrZero(widthCol) else c.getIntOrZero(heightCol),
                    duration = if (durationCol >= 0) c.getLong(durationCol) else null,
                    isTrashed = trashed,
                )
            }
        }
        return result
    }

    /** Groups items by folder. Pinned albums come first, in pin order. */
    fun buildAlbums(items: List<MediaItem>, pinned: List<String>): List<Album> {
        val byFolder = items.groupBy { it.relativePath }
        val albums = byFolder.map { (path, list) ->
            Album(
                relativePath = path,
                name = list.first().bucketName,
                itemCount = list.size,
                cover = list.maxByOrNull { it.dateTaken },
                isPinned = path in pinned,
            )
        }
        val pinIndex = pinned.withIndex().associate { it.value to it.index }
        return albums.sortedWith(
            compareBy<Album> { pinIndex[it.relativePath] ?: Int.MAX_VALUE }
                .thenBy { !it.name.equals("Camera", ignoreCase = true) }
                .thenByDescending { it.itemCount },
        )
    }

    /**
     * Moves items to the system trash (Android 11+) or deletes them (older).
     * Returns a [PendingIntent] the UI must launch for user consent, or null if done.
     */
    fun trashRequest(items: Collection<MediaItem>, trash: Boolean = true): PendingIntent? {
        if (items.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.createTrashRequest(resolver, items.map { it.uri }, trash)
        } else {
            items.forEach { runCatching { resolver.delete(it.uri, null, null) } }
            null
        }
    }

    /** Permanently deletes; returns a consent [PendingIntent] on Android 11+. */
    fun deleteRequest(items: Collection<MediaItem>): PendingIntent? {
        if (items.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.createDeleteRequest(resolver, items.map { it.uri })
        } else {
            items.forEach { runCatching { resolver.delete(it.uri, null, null) } }
            null
        }
    }

    /** Write access request needed before [rename] on Android 11+. */
    fun writeRequest(items: Collection<MediaItem>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(resolver, items.map { it.uri }) else null

    suspend fun rename(item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        runCatching { resolver.update(item.uri, values, null, null) > 0 }.getOrDefault(false)
    }

    /** Saves [bitmap] as a JPEG in Pictures/Gallery (a captured video frame). */
    suspend fun saveImage(bitmap: Bitmap, displayName: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Gallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = runCatching { resolver.insert(collectionFor(MediaType.PHOTO), values) }.getOrNull() ?: return@withContext false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
        } else if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        ok
    }

    private fun collectionFor(type: MediaType): Uri = when (type) {
        MediaType.PHOTO -> if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaType.VIDEO -> if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun rotated(c: Cursor, col: Int) = col >= 0 && (c.getInt(col) == 90 || c.getInt(col) == 270)

    private fun Cursor.getIntOrZero(col: Int) = if (col >= 0) getInt(col) else 0

    private fun relativePathFromData(data: String?): String {
        if (data.isNullOrEmpty()) return ""
        val parent = File(data).parent ?: return ""
        // "/storage/emulated/0/DCIM/Camera" -> "DCIM/Camera/"
        val marker = Regex("^/storage/[^/]+/(\\d+/)?")
        return parent.replace(marker, "").trimEnd('/') + "/"
    }
}
