package com.appslab.gallery.data.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** What ML Kit found in one photo. */
data class Analysis(
    val mediaId: Long,
    val dateModified: Long,
    val labels: List<Pair<String, Float>>,
    val faces: Int,
    val smiling: Int,
    val text: String,
    /** Which analyses ran, as [SearchIndex.FLAG_LABELS] | [SearchIndex.FLAG_FACES] | [SearchIndex.FLAG_TEXT]. */
    val flags: Int,
)

/** Per-photo summary kept in memory for categories and face filters. */
data class IndexedInfo(val labels: Set<String>, val faces: Int, val smiling: Int, val confidence: Float)

/**
 * SQLite store for the on-device analysis. A normal table keeps the raw results;
 * an FTS4 table holds normalised labels and recognised text for keyword search.
 */
class SearchIndex(context: Context) : SQLiteOpenHelper(context.applicationContext, "search_index.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE media (
                id INTEGER PRIMARY KEY,
                date_modified INTEGER NOT NULL,
                labels TEXT NOT NULL,
                faces INTEGER NOT NULL,
                smiling INTEGER NOT NULL,
                text TEXT NOT NULL,
                flags INTEGER NOT NULL
            )""",
        )
        // docid = media id. Content is pre-normalised (lowercase, no diacritics).
        db.execSQL("CREATE VIRTUAL TABLE media_fts USING fts4(labels, text)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS media")
        db.execSQL("DROP TABLE IF EXISTS media_fts")
        onCreate(db)
    }

    /** id → (date_modified, flags) of everything analysed so far. */
    fun analyzedVersions(): Map<Long, Pair<Long, Int>> = readableDatabase
        .rawQuery("SELECT id, date_modified, flags FROM media", null).use { c ->
            buildMap(c.count) { while (c.moveToNext()) put(c.getLong(0), c.getLong(1) to c.getInt(2)) }
        }

    fun loadAll(): Map<Long, IndexedInfo> = readableDatabase
        .rawQuery("SELECT id, labels, faces, smiling FROM media", null).use { c ->
            buildMap(c.count) {
                while (c.moveToNext()) {
                    val parsed = parseLabels(c.getString(1))
                    put(
                        c.getLong(0),
                        IndexedInfo(parsed.keys, c.getInt(2), c.getInt(3), parsed.values.maxOrNull() ?: 0f),
                    )
                }
            }
        }

    fun save(results: List<Analysis>) {
        if (results.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in results) {
                db.insertWithOnConflict("media", null, ContentValues().apply {
                    put("id", r.mediaId)
                    put("date_modified", r.dateModified)
                    put("labels", r.labels.joinToString(";") { "${it.first}:${"%.3f".format(java.util.Locale.US, it.second)}" })
                    put("faces", r.faces)
                    put("smiling", r.smiling)
                    put("text", r.text)
                    put("flags", r.flags)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("media_fts", "docid = ?", arrayOf(r.mediaId.toString()))
                db.insert("media_fts", null, ContentValues().apply {
                    put("docid", r.mediaId)
                    put("labels", normalizeForSearch(r.labels.joinToString(" ") { it.first }))
                    put("text", normalizeForSearch(r.text))
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("media", "id = ?", arrayOf(id.toString()))
                db.delete("media_fts", "docid = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM media")
        writableDatabase.execSQL("DELETE FROM media_fts")
    }

    /**
     * Ids whose labels or recognised text match every word of [query] (prefix match),
     * or, for Vietnamese words, any of their English label synonyms.
     */
    fun search(query: String): Set<Long> {
        val normalized = normalizeForSearch(query).trim()
        if (normalized.isEmpty()) return emptySet()
        // One MATCH per alternative, unioned: mixing OR with implicit AND in a single
        // FTS4 query would bind the OR to just the last word.
        val queries = (listOf(normalized) + VietnameseSynonyms[normalized].orEmpty())
            .map(::toFtsQuery).filter { it.isNotBlank() }.distinct()
        val db = readableDatabase
        return buildSet {
            for (q in queries) {
                db.rawQuery("SELECT docid FROM media_fts WHERE media_fts MATCH ?", arrayOf(q)).use { c ->
                    while (c.moveToNext()) add(c.getLong(0))
                }
            }
        }
    }

    /** "hot dog" → "hot* dog*": every word must appear, as a prefix. */
    private fun toFtsQuery(text: String): String =
        text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.joinToString(" ") { "$it*" }

    companion object {
        const val FLAG_LABELS = 1
        const val FLAG_FACES = 2
        const val FLAG_TEXT = 4
    }

    private fun parseLabels(raw: String): Map<String, Float> =
        raw.split(';').filter { it.isNotEmpty() }.associate {
            val i = it.lastIndexOf(':')
            if (i < 0) it to 0f else it.substring(0, i) to (it.substring(i + 1).toFloatOrNull() ?: 0f)
        }
}
