package ua.svitlyna

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.svitlyna.search.TextIndex
import ua.svitlyna.search.Words

/** The index's queries on plain SQLite (the phone uses the same queries on the encrypted one). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextIndexTest {
    private fun index(): TextIndex {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = TextIndex.create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return TextIndex(FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase)
    }

    private fun find(i: TextIndex, q: String) = i.search(Words.split(q).map(Words::key))

    @Test
    fun putSearchChangeRemove() {
        val i = index()
        i.put(1, 10, "Тарас Шевченко. Кобзар")
        i.put(2, 20, "ЧЕК №123 АТБ Маркет Сума 245,90 грн")
        i.put(3, 30, "")
        assertEquals(setOf(1L), find(i, "шевченка"))
        assertEquals(setOf(1L), find(i, "Shevchenko kobzar"))
        assertEquals(setOf(2L), find(i, "чек атб"))
        assertEquals(emptySet<Long>(), find(i, "чек кобзар"))
        assertEquals(setOf(1L, 2L), i.withText())
        assertEquals(mapOf(1L to 10L, 2L to 20L, 3L to 30L), i.stamps())
        // Read again after a change: the old words are gone.
        i.put(1, 11, "Леся Українка")
        assertEquals(emptySet<Long>(), find(i, "шевченко"))
        assertEquals(setOf(1L), find(i, "українки"))
        i.remove(listOf(1L))
        assertEquals(emptySet<Long>(), find(i, "українка"))
        assertNull(i.text(1))
        i.clear()
        assertEquals(emptyMap<Long, Long>(), i.stamps())
    }

    @Test
    fun oddInputIsSafe() {
        val i = index()
        i.put(1, 1, "\"OR\" * NEAR ( ) - :")
        // Quotes, stars and query words typed by the person don't break the query: the words
        // "or" and "near" are simply looked for (and are on this photo).
        assertEquals(setOf(1L), find(i, "\" OR * ( NEAR"))
        assertEquals(emptySet<Long>(), find(i, "\" AND * ( NOT"))
        assertEquals(null, TextIndex.match(emptyList()))
    }
}
