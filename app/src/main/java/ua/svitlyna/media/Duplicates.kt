package ua.svitlyna.media

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Exact duplicates: files with the same bytes (the same photo saved twice, from two messengers…).
 * Only files of equal size are read and compared by their SHA-256, so it is quick.
 *
 * Similar photos (a series of shots, a copy made smaller by a messenger): each photo's small preview
 * is reduced to a 64-bit "difference hash"; photos of the same shape whose hashes differ in at most
 * [SIMILAR_BITS] bits are grouped. The way of comparing (bands, so not every pair is compared) comes
 * from Iris Gallery (Apache 2.0). Nothing is written anywhere; the person chooses what goes to the trash.
 */
object Duplicates {
    const val SIMILAR_BITS = 5

    /** Files that are the same ([exact]) or look alike; [best] is the suggested one to keep. */
    class Group(val items: List<MediaItem>, val exact: Boolean, val best: MediaItem)

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
                    groups += Group(listOf(keep) + (same - keep), exact = true, best = keep)
                }
            }
            groups.sortedByDescending { g -> g.items.filter { it !== g.best }.sumOf { it.sizeBytes } }
        }

    /** About how long [findSimilar] takes for [count] photos (a small preview each), in seconds. */
    fun similarSeconds(count: Int): Int = (count / 150).coerceAtLeast(1)

    suspend fun findSimilar(context: Context, items: List<MediaItem>, progress: (done: Int, total: Int) -> Unit): List<Group> =
        withContext(Dispatchers.IO) {
            // Screenshots of one chat look alike without being the same: they are left out.
            val photos = items.filter { !it.isVideo && !it.isScreenshot && it.width > 0 && it.height > 0 }
            val prints = ArrayList<Pair<MediaItem, Long>>(photos.size)
            photos.forEachIndexed { i, item ->
                coroutineContext.ensureActive()
                fingerprint(context, item)?.let { prints += item to it }
                if (i % 20 == 0 || i == photos.lastIndex) progress(i + 1, photos.size)
            }
            Similar.group(prints.map { it.second }, prints.map { it.first.width.toFloat() / it.first.height }).map { idx ->
                val members = idx.map { prints[it].first }
                // The largest picture, then the one with its own date: most likely the original.
                val best = members.sortedWith(
                    compareByDescending<MediaItem> { it.width.toLong() * it.height }.thenByDescending { it.takenMs > 0 }.thenBy { it.id },
                ).first()
                Group(listOf(best) + (members - best), exact = false, best = best)
            }.sortedByDescending { it.items.size }
        }

    /** 9×8 grey preview → 64 bits: is each pixel brighter than its right neighbour. */
    private fun fingerprint(context: Context, item: MediaItem): Long? = runCatching {
        val small = context.contentResolver.loadThumbnail(item.uri, android.util.Size(64, 64), null)
        val px = android.graphics.Bitmap.createScaledBitmap(small, 9, 8, true)
        val grey = IntArray(72) { i ->
            val c = px.getPixel(i % 9, i / 9)
            (c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114
        }
        if (px !== small) px.recycle()
        small.recycle()
        Similar.dHash(grey)
    }.getOrNull()

    private suspend fun hash(context: Context, item: MediaItem): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        // The original bytes: without this Android may blank the location, and photos that differ only
        // in where they were taken would look the same.
        (runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) }.getOrNull()
            ?: context.contentResolver.openInputStream(item.uri))!!.use { input ->
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
