package ua.svitlyna.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DuplicateGroup(
    val items: List<MediaImage>,
    val exact: Boolean,
) {
    val reclaimableBytes: Long get() = items.drop(1).sumOf { it.sizeBytes }
}

class DuplicateDetector(private val context: Context) {
    suspend fun scan(
        media: List<MediaImage>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val photos = media.filter { !it.isVideo && it.sizeBytes > 0 }
        val exactGroups = mutableListOf<DuplicateGroup>()
        val exactIds = mutableSetOf<Long>()

        // Hash only plausible exact candidates. This avoids reading every original.
        photos.groupBy { Triple(it.sizeBytes, it.width, it.height) }.values
            .filter { it.size > 1 }
            .forEach { candidates ->
                candidates.mapNotNull { item -> checksum(item)?.let { it to item } }
                    .groupBy({ it.first }, { it.second }).values
                    .filter { it.size > 1 }
                    .forEach { group ->
                        exactGroups += DuplicateGroup(group.sortedByDescending { it.dateTaken }, exact = true)
                        exactIds += group.map { it.id }
                    }
            }

        val remaining = photos.filterNot { it.id in exactIds }
        val fingerprints = ArrayList<Pair<MediaImage, Long>>(remaining.size)
        remaining.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            fingerprint(item)?.let { fingerprints += item to it }
            onProgress(index + 1, remaining.size)
        }

        // Svitlyna: photos are grouped around the first of each group (not chained, so look-alike
        // series don't grow into one huge group), screenshots are left out (screens of one chat look
        // alike without being the same). See media/Similar.kt.
        val candidates = fingerprints.filterNot { it.first.isScreenshot }
        val similarGroups = ua.svitlyna.media.Similar.group(
            candidates.map { it.second },
            candidates.map { it.first.width.toFloat() / it.first.height.coerceAtLeast(1) },
        ).map { indexes ->
            DuplicateGroup(indexes.map { candidates[it].first }
                .sortedWith(compareByDescending<MediaImage> { it.width.toLong() * it.height }.thenByDescending { it.dateTaken }), exact = false)
        }
        (exactGroups + similarGroups).sortedByDescending { it.reclaimableBytes }
    }

    private fun checksum(item: MediaImage): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        // Read as the original: otherwise Android may blank the place, and photos that differ only
        // in where they were taken would look the same.
        (runCatching { context.contentResolver.openInputStream(android.provider.MediaStore.setRequireOriginal(item.uri)) }.getOrNull()
            ?: context.contentResolver.openInputStream(item.uri))?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun fingerprint(item: MediaImage): Long? = runCatching {
        val bitmap = context.contentResolver.loadThumbnail(item.uri, Size(9, 8), null)
        val scaled = if (bitmap.width == 9 && bitmap.height == 8) bitmap
            else Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val left = scaled.getPixel(x, y)
            val right = scaled.getPixel(x + 1, y)
            fun luma(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114)
            if (luma(left) > luma(right)) hash = hash or (1L shl bit)
            bit++
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        hash
    }.getOrNull()
}
