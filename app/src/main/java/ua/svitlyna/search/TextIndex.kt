package ua.svitlyna.search

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The text found on photos, in a full-text index (SQLite FTS4). Each photo is one row: its id, a
 * stamp (to notice a changed file), whether it has text, and only the search keys ([Words.keys]),
 * not the text itself (the viewer reads the photo again to show it). The database itself is
 * encrypted ([IndexDb]).
 */
class TextIndex(private val db: SupportSQLiteDatabase) {
    companion object {
        const val VERSION = 2

        fun create(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS photo (id INTEGER PRIMARY KEY, stamp INTEGER NOT NULL, has_text INTEGER NOT NULL)")
            // Prefix indexes for 2 and 3 letters: a short beginning doesn't make SQLite scan every word.
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS words USING fts4(keys, prefix=\"2,3\")")
        }

        /** A new layout: what was read is read again (it is only a cache of the photos). */
        fun recreate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS photo")
            db.execSQL("DROP TABLE IF EXISTS words")
            create(db)
        }

        /**
         * The FTS query for typed keys: every key must be a word of the photo; the last one may be
         * just the beginning of a word ([lastIsBeginning]: it is still being typed). Keys of one
         * letter look for nothing (they would match half the words).
         */
        fun match(keys: List<String>, lastIsBeginning: Boolean = true): String? {
            val safe = keys.map { k -> k.filter { it in 'a'..'z' || it in '0'..'9' } }.filter { it.length >= 2 || it.all(Char::isDigit) && it.isNotEmpty() }.distinct()
            if (safe.isEmpty()) return null
            return safe.mapIndexed { i, k -> if (lastIsBeginning && i == safe.lastIndex) "$k*" else k }.joinToString(" ")
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
                db.execSQL("INSERT INTO words (docid, keys) VALUES (?, ?)", arrayOf<Any>(id, keys.joinToString(" ")))
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

    /** Photos whose text has all of [keys] (see [match]). */
    fun search(keys: List<String>, lastIsBeginning: Boolean = true): Set<Long> {
        val q = match(keys, lastIsBeginning) ?: return emptySet()
        val out = HashSet<Long>()
        db.query("SELECT docid FROM words WHERE words MATCH ?", arrayOf<Any>(q)).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    fun clear() {
        db.execSQL("DELETE FROM photo")
        db.execSQL("DELETE FROM words")
    }
}
