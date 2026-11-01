package ua.svitlyna.search

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Dates said in words, in Ukrainian or English: «травень 2024», «1 травня», «2023», «вчора»,
 * «минулого тижня», «цього року», "may 2024", "last week". What is not about a date is left as the
 * words to look for on photos («чек травень 2024» → a date and «чек»).
 */
object DateWords {
    /** The time asked for ([from] to [to], whole days, [to] included) and the other words. */
    data class Parsed(val from: LocalDate?, val to: LocalDate?, val rest: List<String>) {
        fun range(zone: ZoneId): LongRange? {
            if (from == null || to == null) return null
            return from.atStartOfDay(zone).toInstant().toEpochMilli()..(to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
        }
    }

    /** Month names in the forms people write them (not «трава» or «береза»!), January first. */
    private val MONTHS = listOf(
        "січень січня січні january jan",
        "лютий лютого лютому february feb",
        "березень березня березні march mar",
        "квітень квітня квітні april apr",
        "травень травня травні may",
        "червень червня червні june jun",
        "липень липня липні july jul",
        "серпень серпня серпні august aug",
        "вересень вересня вересні september sept sep",
        "жовтень жовтня жовтні october oct",
        "листопад листопада листопаді november nov",
        "грудень грудня грудні december dec",
    ).map { it.split(' ').toSet() }

    private fun month(word: String): Int? {
        val i = MONTHS.indexOfFirst { word in it }
        return if (i >= 0) i + 1 else null
    }

    private val TODAY = setOf("сьогодні", "today")
    private val YESTERDAY = setOf("вчора", "учора", "yesterday")
    private val LAST = setOf("минулого", "минулий", "минулому", "минулої", "last", "previous")
    private val THIS = setOf("цього", "цей", "цьому", "цієї", "this")
    private fun isWeek(w: String) = w.startsWith("тижн") || w.startsWith("тиждень") || w == "week"
    private fun isMonth(w: String) = w.startsWith("місяц") || w.startsWith("місяць") || w == "month"
    private fun isYear(w: String) = w == "рік" || w == "року" || w == "році" || w == "year"

    fun parse(query: String, today: LocalDate): Parsed {
        val words = Words.split(query).map { it.lowercase() }
        val used = BooleanArray(words.size)
        var from: LocalDate? = null
        var to: LocalDate? = null
        fun set(a: LocalDate, b: LocalDate) {
            from = a
            to = b
        }
        var year: Int? = null
        var monthNo: Int? = null
        var day: Int? = null
        for ((i, w) in words.withIndex()) {
            when {
                w in TODAY -> { set(today, today); used[i] = true }
                w in YESTERDAY -> { val d = today.minusDays(1); set(d, d); used[i] = true }
                (w in LAST || w in THIS) && i + 1 < words.size -> {
                    val next = words[i + 1]
                    val last = w in LAST
                    when {
                        isWeek(next) -> {
                            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(if (last) 1 else 0)
                            set(monday, if (last) monday.plusDays(6) else today)
                        }
                        isMonth(next) -> {
                            val first = today.withDayOfMonth(1).minusMonths(if (last) 1 else 0)
                            set(first, if (last) first.plusMonths(1).minusDays(1) else today)
                        }
                        isYear(next) -> {
                            val first = today.withDayOfYear(1).minusYears(if (last) 1 else 0)
                            set(first, if (last) first.plusYears(1).minusDays(1) else today)
                        }
                        else -> continue
                    }
                    used[i] = true
                    used[i + 1] = true
                }
                w.length == 4 && w.all { it.isDigit() } && w.toInt() in 1900..today.year -> { year = w.toInt(); used[i] = true }
                month(w) != null -> { monthNo = month(w); used[i] = true }
                w.length <= 2 && w.all { it.isDigit() } && w.toInt() in 1..31 &&
                    (words.getOrNull(i + 1)?.let { month(it) } != null || words.getOrNull(i - 1)?.let { month(it) } != null) -> {
                    day = w.toInt()
                    used[i] = true
                }
            }
        }
        val m = monthNo
        val y = year
        when {
            m != null && day != null -> {
                // «1 травня» without a year: this year's, or last year's if that day is still ahead.
                val yy = y ?: if (runCatching { LocalDate.of(today.year, m, day!!) }.getOrNull()?.isAfter(today) == true) today.year - 1 else today.year
                runCatching { LocalDate.of(yy, m, day!!) }.getOrNull()?.let { set(it, it) }
            }
            m != null -> {
                val yy = y ?: if (m > today.monthValue) today.year - 1 else today.year
                val first = LocalDate.of(yy, m, 1)
                set(first, first.plusMonths(1).minusDays(1))
            }
            y != null -> set(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
        }
        // A month name that turned out not to be needed for a date is still a word to look for.
        val rest = words.filterIndexed { i, _ -> !used[i] }
        return Parsed(from, to, rest)
    }
}
