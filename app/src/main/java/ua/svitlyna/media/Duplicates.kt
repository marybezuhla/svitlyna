package ua.svitlyna.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Exact duplicates: files with the same bytes (the same photo saved twice, from two messengers…).
 * Only files of equal size are read and compared by their SHA-256, so it is quick; nothing is
 * written anywhere. Similar (not identical) shots come with "Cleaning up" later.
 */
object Duplicates {
    /** A set of identical files: [keep] stays, [extra] can go to the trash. */
    class Group(val keep: MediaItem, val extra: List<MediaItem>)

    suspend fun find(context: Context, items: List<MediaItem>, progress: (done: Int, total: Int) -> Unit): List<Group> =
        withContext(Dispatchers.IO) {
            val candidates = items.filter { it.sizeBytes > 0 }.groupBy { it.sizeBytes }.values.filter { it.size > 1 }
            val total = candidates.sumOf { it.size }
            var done = 0
            val groups = ArrayList<Group>()
            for (sameSize in candidates) {
                val byHash = HashMap<String, MutableList<MediaItem>>()
                for (item in sameSize) {
                    coroutineContext.ensureActive()
                    hash(context, item)?.let { byHash.getOrPut(it) { mutableListOf() } += item }
                    done++
                    progress(done, total)
                }
                for (same in byHash.values.filter { it.size > 1 }) {
                    // Keep the one that looks like the original: with a date inside, from the camera, the oldest.
                    val keep = same.sortedWith(
                        compareByDescending<MediaItem> { it.takenMs > 0 }
                            .thenByDescending { it.relativePath.startsWith("DCIM/Camera", true) }
                            .thenBy { it.id },
                    ).first()
                    groups += Group(keep, same - keep)
                }
            }
            groups.sortedByDescending { g -> g.extra.sumOf { it.sizeBytes } }
        }

    private suspend fun hash(context: Context, item: MediaItem): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(item.uri)!!.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
