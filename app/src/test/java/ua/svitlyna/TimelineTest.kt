package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.MediaRepository
import ua.svitlyna.media.Timeline
import ua.svitlyna.media.TimelineEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TimelineTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")

    private fun item(id: Long, ms: Long, bucket: Long = 1, name: String = "f$bucket") = MediaItem(
        id = id, isVideo = false, dateMs = ms, name = "$id.jpg", mime = "image/jpeg",
        bucketId = bucket, bucketName = name, width = 1, height = 1, sizeBytes = 1, durationMs = 0,
    )

    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, kyiv).toInstant().toEpochMilli()

    @Test
    fun groupsByDayInLocalTime() {
        // 00:30 in Kyiv is still the previous day in UTC: it must count as the Kyiv day.
        val items = listOf(item(3, at(2026, 11, 1, 0) + 30 * 60_000), item(2, at(2026, 10, 31, 23)), item(1, at(2026, 10, 31, 8)))
        val entries = Timeline.byDay(items, kyiv)
        assertEquals(5, entries.size)
        assertEquals(TimelineEntry.Day(LocalDate.of(2026, 11, 1), 1), entries[0])
        assertEquals(TimelineEntry.Day(LocalDate.of(2026, 10, 31), 2), entries[2])
        // Each photo keeps its place in the full list, so the viewer opens on the same photo.
        assertEquals(listOf(0, 1, 2), entries.filterIsInstance<TimelineEntry.Item>().map { it.index })
    }

    @Test
    fun emptyGalleryHasNoEntries() {
        assertEquals(emptyList<TimelineEntry>(), Timeline.byDay(emptyList(), kyiv))
    }

    @Test
    fun dateTakenWinsThenFileDates() {
        assertEquals(5_000L, MediaRepository.bestDate(takenMs = 5_000, modifiedSec = 9, addedSec = 7))
        // No date taken (typical for photos from messengers): the file's own date, not "now".
        assertEquals(9_000L, MediaRepository.bestDate(takenMs = 0, modifiedSec = 9, addedSec = 7))
        assertEquals(7_000L, MediaRepository.bestDate(takenMs = 0, modifiedSec = 0, addedSec = 7))
    }

    @Test
    fun newestFirstThenById() {
        val sorted = listOf(item(1, 100), item(3, 200), item(2, 200)).sortedWith(MediaRepository.NEWEST_FIRST)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun foldersCountAndUseNewestAsCover() {
        val items = listOf(item(4, 400, bucket = 2, name = "Camera"), item(3, 300, bucket = 1, name = "Telegram"), item(2, 200, bucket = 2, name = "Camera"))
        val folders = MediaRepository.folders(items)
        assertEquals(listOf("Camera", "Telegram"), folders.map { it.name })
        assertEquals(2, folders[0].count)
        assertEquals(4L, folders[0].cover.id)
    }
}
