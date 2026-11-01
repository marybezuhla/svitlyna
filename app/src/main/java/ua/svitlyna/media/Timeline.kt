package ua.svitlyna.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A row of the timeline grid: either a day heading or one photo. */
sealed interface TimelineEntry {
    val key: String

    data class Day(val date: LocalDate, val count: Int) : TimelineEntry {
        override val key: String get() = "d$date"
    }

    data class Item(val item: MediaItem, val index: Int) : TimelineEntry {
        override val key: String get() = "i${item.id}"
    }
}

object Timeline {
    /**
     * Groups items (already newest first) by the day they were taken, in the phone's time zone.
     * [TimelineEntry.Item.index] is the position in [items], so the viewer can open at the same photo.
     */
    fun byDay(items: List<MediaItem>, zone: ZoneId = ZoneId.systemDefault()): List<TimelineEntry> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<TimelineEntry>(items.size + items.size / 8 + 1)
        var i = 0
        while (i < items.size) {
            val day = dayOf(items[i].dateMs, zone)
            var j = i
            while (j < items.size && dayOf(items[j].dateMs, zone) == day) j++
            out += TimelineEntry.Day(day, j - i)
            for (k in i until j) out += TimelineEntry.Item(items[k], k)
            i = j
        }
        return out
    }

    fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
