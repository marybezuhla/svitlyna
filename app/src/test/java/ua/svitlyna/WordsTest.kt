package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.search.Words

class WordsTest {
    private fun same(vararg words: String) {
        val keys = words.map(Words::key).toSet()
        assertEquals(words.joinToString(), 1, keys.size)
    }

    @Test
    fun wordFormsMeet() {
        same("Шевченко", "Шевченка", "Шевченку", "Шевченком", "Шевченкові", "ШЕВЧЕНКО", "Shevchenko")
        same("Київ", "Kyiv", "КИЇВ")
        same("пам'ять", "памʼять", "пам’ять", "память")
        same("гривня", "гривні", "гривню", "гривнею")
    }

    @Test
    fun aBeginningFinds() {
        val keys = listOf(Words.key("Шевч"))
        assertTrue(Words.matches("Шевченка", keys))
        assertTrue(Words.matches("Шевченківська", keys))
        assertFalse(Words.matches("Франко", keys))
    }

    @Test
    fun splittingKeepsApostrophesAndNumbers() {
        assertEquals(listOf("пам'ять", "2024", "рік"), Words.split("Пам'ять, 2024 — рік!"))
        assertEquals("380501234567", Words.key("380501234567"))
    }

    @Test
    fun shortWordsKeepTheirEnding() {
        // "она" and "оно" stay different: too short to cut.
        assertEquals("ia", Words.key("я"))
        assertEquals("kit", Words.key("кіт"))
    }
}
