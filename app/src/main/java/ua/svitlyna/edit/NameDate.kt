package ua.svitlyna.edit

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The date a photo was taken, read from its file name, for photos whose file has no date inside
 * (messengers remove it). Covers the usual names:
 * - camera and screenshots: IMG_20240512_143021, PXL_20240512_143021123, Screenshot_20240512-143021
 * - WhatsApp: IMG-20240512-WA0001 (date only)
 * - Telegram: photo_2024-05-12_14-30-21
 * - Signal: signal-2024-05-12-143021
 * When the name has only a date, noon is used (shown as "date only").
 */
object NameDate {
    data class Found(val at: LocalDateTime, val hasTime: Boolean)

    private val COMPACT = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})""")
    private val DASHED = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})[_ -](\d{2})[-.:]?(\d{2})[-.:]?(\d{2})""")
    private val DATE_COMPACT = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""")
    private val DATE_DASHED = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)""")

    fun parse(name: String, latestYear: Int = LocalDate.now().year + 1): Found? {
        for (r in listOf(COMPACT, DASHED)) {
            r.find(name)?.let { m -> at(m.groupValues.drop(1), latestYear)?.let { return Found(it, true) } }
        }
        for (r in listOf(DATE_COMPACT, DATE_DASHED)) {
            r.find(name)?.let { m -> at(m.groupValues.drop(1) + listOf("12", "00", "00"), latestYear)?.let { return Found(it, false) } }
        }
        return null
    }

    private fun at(g: List<String>, latestYear: Int): LocalDateTime? = runCatching {
        val (y, mo, d, h, mi, s) = g.map { it.toInt() }
        if (y > latestYear) return null
        LocalDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi, s))
    }.getOrNull()

    private operator fun <T> List<T>.component6() = this[5]
}
