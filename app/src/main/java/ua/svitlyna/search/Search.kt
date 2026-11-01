package ua.svitlyna.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
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
            val all = parsed.rest.map(Words::key).filter { it.isNotEmpty() }
            // One letter or digit looks for nothing (it would match half the gallery).
            val keys = all.filter { it.length >= 2 }.distinct()
            // The last word is still being typed unless a space follows it: only it is a beginning,
            // and only if it is the one that was kept.
            val typing = query.isNotEmpty() && !query.last().isWhitespace() && all.lastOrNull()?.length?.let { it >= 2 } == true
            val range = parsed.range(zone)
            val inTime = if (range == null) items else items.filter { it.dateMs in range }
            if (keys.isEmpty()) {
                return@withContext if (range == null) EMPTY else Result(inTime, emptyList(), 0, 0, true)
            }
            val textIds = if (!ua.svitlyna.ui.Prefs(context).searchText) emptySet() else
                runCatching { withContext(Dispatchers.IO) { IndexDb.index(context).search(keys, typing) } }.getOrDefault(emptySet())
            val names = buildMutex.withLock { names(items) }
            var byText = 0
            var byName = 0
            val found = ArrayList<MediaItem>()
            for ((i, item) in inTime.withIndex()) {
                if (i % 2000 == 0) ensureActive()
                when {
                    item.id in textIds -> { byText++; found += item }
                    nameMatches(names[item.id].orEmpty(), keys, typing) -> { byName++; found += item }
                }
            }
            Result(found, keys, byText, byName, range != null, textIds)
        }

    /** Keys of each item's file and folder names, made once per gallery list (one making at a time). */
    @Volatile
    private var cache: Pair<List<MediaItem>, Map<Long, List<String>>>? = null
    private val buildMutex = kotlinx.coroutines.sync.Mutex()

    /** Search closed: the names' keys are let go. */
    fun forget() {
        cache = null
    }

    private suspend fun names(items: List<MediaItem>): Map<Long, List<String>> {
        cache?.let { (list, map) -> if (list === items) return map }
        val folders = HashMap<Long, List<String>>()
        val map = HashMap<Long, List<String>>(items.size * 2)
        for ((i, item) in items.withIndex()) {
            if (i % 2000 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val folder = folders.getOrPut(item.bucketId) { Words.keys(item.bucketName) }
            map[item.id] = (Words.keys(item.name.substringBeforeLast('.')) + folder).distinct()
        }
        cache = items to map
        return map
    }

    /** Every key is a word of the names (the last one may be a beginning while being typed). */
    fun nameMatches(words: List<String>, keys: List<String>, typing: Boolean = true): Boolean =
        keys.withIndex().all { (i, k) ->
            if (typing && i == keys.lastIndex) words.any { it.startsWith(k) } else k in words
        }
}
