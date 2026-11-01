package ua.svitlyna.search

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The text found on photos, in a full-text index (SQLite FTS4). Each photo is one row: its id, a
 * stamp (to notice a changed file), whether it has text, the search keys ([Words.keys]) and the text
 * as read (to show and to highlight). The database itself is encrypted ([IndexDb]).
 */
class TextIndex(private val db: SupportSQLiteDatabase) {
    companion object {
        fun create(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS photo (id INTEGER PRIMARY KEY, stamp INTEGER NOT NULL, has_text INTEGER NOT NULL)")
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS words USING fts4(keys, body, notindexed=body)")
        }

        /** The FTS query for typed keys: every key must begin a word of the photo. */
        fun match(keys: List<String>): String? {
            val safe = keys.map { k -> k.filter { it in 'a'..'z' || it in '0'..'9' } }.filter { it.isNotEmpty() }.distinct()
            return if (safe.isEmpty()) null else safe.joinToString(" ") { "$it*" }
        }
    }

    /** Ids already read, with their stamps. */
    fun stamps(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        db.query("SELECT id, stamp FROM photo").use { c -> while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) }
        return out
    }

    /** Ids of photos with text on them. */
    fun withText(): Set<Long> {
        val out = HashSet<Long>()
        db.query("SELECT id FROM photo WHERE has_text = 1").use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun put(id: Long, stamp: Long, text: String) {
        val keys = Words.keys(text)
        db.beginTransaction()
        try {
            db.insert("photo", CONFLICT_REPLACE, ContentValues().apply {
                put("id", id)
                put("stamp", stamp)
                put("has_text", if (keys.isEmpty()) 0 else 1)
            })
            db.execSQL("DELETE FROM words WHERE docid = ?", arrayOf<Any>(id))
            if (keys.isNotEmpty()) {
                db.execSQL("INSERT INTO words (docid, keys, body) VALUES (?, ?, ?)", arrayOf<Any>(id, keys.joinToString(" "), text))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL("DELETE FROM photo WHERE id = ?", arrayOf<Any>(id))
                db.execSQL("DELETE FROM words WHERE docid = ?", arrayOf<Any>(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Photos whose text has all of [keys] (word beginnings). */
    fun search(keys: List<String>): Set<Long> {
        val q = match(keys) ?: return emptySet()
        val out = HashSet<Long>()
        db.query("SELECT docid FROM words WHERE words MATCH ?", arrayOf<Any>(q)).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    /** The text read on one photo, or null. */
    fun text(id: Long): String? =
        db.query("SELECT body FROM words WHERE docid = ?", arrayOf<Any>(id)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun clear() {
        db.execSQL("DELETE FROM photo")
        db.execSQL("DELETE FROM words")
    }
}
