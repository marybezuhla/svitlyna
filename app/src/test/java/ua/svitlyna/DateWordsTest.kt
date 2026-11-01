package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.svitlyna.search.DateWords
import java.time.LocalDate

class DateWordsTest {
    private val today = LocalDate.of(2026, 11, 1) // a Sunday

    private fun check(q: String, from: LocalDate?, to: LocalDate?, rest: List<String> = emptyList()) {
        val p = DateWords.parse(q, today)
        assertEquals(q, from, p.from)
        assertEquals(q, to, p.to)
        assertEquals(q, rest, p.rest)
    }

    @Test
    fun monthsAndYears() {
        check("травень 2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31))
        check("у травні 2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31), listOf("у"))
        check("May 2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31))
        check("2023", LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))
        // A month still ahead this year means last year's.
        check("грудень", LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31))
        check("жовтня", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    }

    @Test
    fun days() {
        check("1 травня 2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1))
        check("24 серпня", LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 24))
        check("вчора", LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31))
        check("сьогодні", today, today)
    }

    @Test
    fun relative() {
        check("минулого тижня", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))
        check("цього місяця", LocalDate.of(2026, 11, 1), today)
        check("минулого року", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
        check("last month", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
    }

    @Test
    fun numbers() {
        check("01.05.2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1))
        check("чек 2024-05-01", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1), listOf("чек"))
        check("1.5.24", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 1))
        check("1.5.99", LocalDate.of(1999, 5, 1), LocalDate.of(1999, 5, 1))
        // Not about time: stays a word to look for.
        assertEquals(listOf("last", "supper"), DateWords.parse("Last Supper", today).rest)
        assertEquals(listOf("літа"), DateWords.parse("минулого літа", today).rest)
    }

    @Test
    fun otherWordsStayForTheText() {
        check("чек травень 2024", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31), listOf("чек"))
        // Not months: grass, birch, a sickle.
        val p = DateWords.parse("трава береза серп", today)
        assertNull(p.from)
        assertEquals(listOf("трава", "береза", "серп"), p.rest)
        // A number alone is not a day.
        assertEquals(listOf("12"), DateWords.parse("12", today).rest)
    }
}
