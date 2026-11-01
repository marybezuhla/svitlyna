package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.svitlyna.media.MediaItem
import ua.svitlyna.ui.MemoriesWidget
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MemoriesTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private fun photo(id: Long, at: LocalDateTime, path: String = "DCIM/Camera/", taken: Boolean = true): MediaItem {
        val ms = at.atZone(zone).toInstant().toEpochMilli()
        return MediaItem(id, false, ms, "IMG_$id.jpg", "image/jpeg", 1, "Camera", 100, 100, 1000, 0, relativePath = path, takenMs = if (taken) ms else 0)
    }

    @Test
    fun onlyThisDayInEarlierYears() {
        val today = LocalDate.of(2026, 11, 1)
        val items = listOf(
            photo(1, LocalDateTime.of(2026, 11, 1, 10, 0)), // today itself
            photo(2, LocalDateTime.of(2025, 10, 31, 23, 0)), // another day
            photo(3, LocalDateTime.of(2023, 11, 1, 9, 0), path = "Pictures/Screenshots/"), // a screenshot
            photo(4, LocalDateTime.of(2022, 11, 1, 9, 0), taken = false), // no date of its own
            photo(5, LocalDateTime.of(2021, 11, 1, 18, 30)),
        )
        assertEquals(5L, MemoriesWidget.pick(items, today, zone)?.id)
        assertNull(MemoriesWidget.pick(items.take(4), today, zone))
        // A folder hidden from the timeline, or one outside the camera's: not on the home screen.
        assertNull(MemoriesWidget.pick(items, today, zone, hidden = setOf(1L)))
        assertNull(MemoriesWidget.pick(listOf(photo(6, LocalDateTime.of(2021, 11, 1, 9, 0), path = "Pictures/Telegram/")), today, zone))
    }

    @Test
    fun rangesCoverWholeDaysAndSkipMissingLeapDays() {
        val r = MemoriesWidget.ranges(LocalDate.of(2026, 11, 1), zone)
        assertEquals(50, r.size)
        val first = r.first()
        assertEquals(LocalDate.of(2025, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli(), first.first)
        assertEquals(LocalDate.of(2025, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli() - 1, first.last)
        // 29 February exists only every 4 years.
        assertEquals(12, MemoriesWidget.ranges(LocalDate.of(2024, 2, 29), zone).size)
    }

    @Test
    fun theSameAllDayAnotherTomorrow() {
        val items = (1L..3L).map { photo(it, LocalDateTime.of(2020 + it.toInt(), 6, 15, 12, 0)) } +
            (4L..6L).map { photo(it, LocalDateTime.of(2020 + it.toInt() - 3, 6, 16, 12, 0)) }
        val a = MemoriesWidget.pick(items, LocalDate.of(2026, 6, 15), zone)
        assertEquals(a, MemoriesWidget.pick(items, LocalDate.of(2026, 6, 15), zone))
        assertEquals(true, (a?.id ?: 0) in 1L..3L)
    }
}
