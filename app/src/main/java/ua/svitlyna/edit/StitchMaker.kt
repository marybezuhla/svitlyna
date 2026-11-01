package ua.svitlyna.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.SaveCopy
import kotlin.coroutines.coroutineContext

/** Makes one long screenshot from several, and saves it next to the first one (originals stay). */
object StitchMaker {
    /** Taller than this would be too heavy for the phone's memory (about 90 MB of pixels). */
    const val MAX_HEIGHT = 20_000

    sealed interface Result {
        data class Saved(val uri: Uri) : Result
        /** No overlap found between two neighbouring screenshots: they can be stacked instead. */
        data object NoOverlap : Result
        data object TooTall : Result
        data object Failed : Result
    }

    /** [items] in the order they were taken. With [stack], simply one under the other. */
    suspend fun make(context: Context, items: List<MediaItem>, stack: Boolean): Result = withContext(Dispatchers.IO) {
        try {
            // In the order taken; the same second: by the order Android numbered them.
            val sorted = items.sortedWith(compareBy<MediaItem>({ it.dateMs }, { it.id }))
            val heights = sorted.map { it.height }
            val pieces = if (stack) {
                Stitch.stacked(heights)
            } else {
                val rows = sorted.map { item ->
                    coroutineContext.ensureActive()
                    decode(context, item)?.let { bmp -> rowsOf(bmp).also { bmp.recycle() } } ?: return@withContext Result.Failed
                }
                val joins = (0 until rows.size - 1).map { i -> Stitch.find(rows[i], rows[i + 1]) ?: return@withContext Result.NoOverlap }
                Stitch.pieces(rows[0].height, joins)
            }
            val width = sorted.maxOf { it.width }
            val total = pieces.sumOf { it.to - it.from }
            if (total > MAX_HEIGHT) return@withContext Result.TooTall

            val out = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            var y = 0
            // One screenshot in memory at a time.
            for ((index, group) in pieces.groupBy { it.index }) {
                coroutineContext.ensureActive()
                val bmp = decode(context, sorted[index]) ?: run { out.recycle(); return@withContext Result.Failed }
                for (p in group) {
                    val to = p.to.coerceAtMost(bmp.height)
                    if (to <= p.from) continue
                    canvas.drawBitmap(bmp, Rect(0, p.from, bmp.width, to), Rect(0, y, bmp.width, y + to - p.from), null)
                    y += to - p.from
                }
                bmp.recycle()
            }
            // Stopped while drawing: nothing is saved.
            coroutineContext.ensureActive()
            val uri = SaveCopy.save(context, sorted.first(), out, "long")
            out.recycle()
            if (uri != null) Result.Saved(uri) else Result.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Failed
        }
    }

    private fun decode(context: Context, item: MediaItem): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    /** Row signatures: grey values at [Stitch.SAMPLES] evenly spaced columns of every row. */
    private fun rowsOf(bmp: Bitmap): Stitch.Rows {
        val w = bmp.width
        val h = bmp.height
        val data = IntArray(h * Stitch.SAMPLES)
        val line = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(line, 0, w, 0, y, w, 1)
            for (k in 0 until Stitch.SAMPLES) {
                val c = line[(k * 2 + 1) * w / (Stitch.SAMPLES * 2)]
                data[y * Stitch.SAMPLES + k] = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
            }
        }
        return Stitch.Rows(h, data)
    }
}
