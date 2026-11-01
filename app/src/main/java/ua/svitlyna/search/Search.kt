package ua.svitlyna.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import java.time.LocalDate
import java.time.ZoneId

/**
 * One search line, several sources: the text on photos (the index), file and folder names
 * («Telegram», «IMG_2024»), and dates in words («травень 2024»). Newest first.
 */
object Search {
    class Result(
        val items: List<MediaItem>,
        /** The keys looked for, to highlight the words on the photo. */
        val keys: List<String>,
        val byText: Int,
        val byName: Int,
        val dated: Boolean,
        /** Found by the text on them (the rest by name or date). */
        val textIds: Set<Long> = emptySet(),
    )

    val EMPTY = Result(emptyList(), emptyList(), 0, 0, false)

    suspend fun find(context: Context, query: String, items: List<MediaItem>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Result =
        withContext(Dispatchers.Default) {
            val parsed = DateWords.parse(query, today)
            val keys = parsed.rest.map(Words::key).filter { it.isNotEmpty() }.distinct()
            val range = parsed.range(zone)
            val inTime = if (range == null) items else items.filter { it.dateMs in range }
            if (keys.isEmpty()) {
                return@withContext if (range == null) EMPTY else Result(inTime, emptyList(), 0, 0, true)
            }
            val textIds = runCatching { withContext(Dispatchers.IO) { IndexDb.index(context).search(keys) } }.getOrDefault(emptySet())
            var byText = 0
            var byName = 0
            val found = inTime.filter { item ->
                when {
                    item.id in textIds -> { byText++; true }
                    nameMatches(item, keys) -> { byName++; true }
                    else -> false
                }
            }
            Result(found, keys, byText, byName, range != null, textIds)
        }

    /** Every key begins a word of the file's name or its folder's. */
    fun nameMatches(item: MediaItem, keys: List<String>): Boolean {
        val words = Words.keys(item.name.substringBeforeLast('.') + " " + item.bucketName)
        return keys.all { k -> words.any { it.startsWith(k) } }
    }
}
