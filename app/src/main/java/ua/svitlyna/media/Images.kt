package ua.svitlyna.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Small pictures for the grid come from the system's own thumbnails (already made and kept by
 * Android), so nothing is decoded twice and nothing extra is written to the disk. Only a memory
 * cache is kept: no thumbnail of any photo ever lands in the app's files.
 */
object Images {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    /** At most two full photos decode at once, so a fast swipe through many photos can't run out of memory. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fullIo = Dispatchers.IO.limitedParallelism(2)

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun cached(item: MediaItem, px: Int): Bitmap? = cache.get(key(item, px))

    private fun key(item: MediaItem, px: Int) = "${item.id}:${item.isVideo}:$px"

    /** A square-ish thumbnail at least [px] on the longer side, or null if the system can't make one. */
    suspend fun thumbnail(resolver: ContentResolver, item: MediaItem, px: Int): Bitmap? {
        cache.get(key(item, px))?.let { return it }
        return withContext(io) {
            val signal = CancellationSignal()
            val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
                cont.invokeOnCancellation { signal.cancel() }
                val result = runCatching { resolver.loadThumbnail(item.uri, Size(px, px), signal) }.getOrNull()
                cont.resume(result)
            }
            bmp?.also { cache.put(key(item, px), it) }
        }
    }

    /**
     * The full photo for the viewer, scaled so its longer side is at most [maxPx]. ImageDecoder turns
     * the photo the right way up by its EXIF orientation.
     */
    /** [software]: pixels the app reads or draws itself (tools); otherwise Android may keep it on the GPU. */
    suspend fun full(resolver: ContentResolver, item: MediaItem, maxPx: Int, software: Boolean = false): Bitmap? = withContext(fullIo) {
        // A photo swiped past while waiting for its turn is not decoded at all.
        ensureActive()
        runCatching {
            val source = ImageDecoder.createSource(resolver, item.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                var longer = maxOf(w, h)
                // Svitlyna: for the tools, never more than 50 MP, whatever the gallery's list says.
                if (software && w.toLong() * h > 50_000_000L) {
                    val cap = (longer * kotlin.math.sqrt(50_000_000.0 / (w.toLong() * h))).toInt()
                    if (cap < maxPx) {
                        val scale = cap.toFloat() / longer
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                        return@decodeBitmap
                    }
                }
                if (longer > maxPx) {
                    val scale = maxPx.toFloat() / longer
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull()
    }
}
