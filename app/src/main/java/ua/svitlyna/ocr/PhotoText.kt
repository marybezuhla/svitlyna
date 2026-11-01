package ua.svitlyna.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * The text on one photo, with where each word is. Positions are in a picture of [width] × [height]
 * (the photo scaled down, the right way up), so the viewer can draw them over the photo it shows.
 */
class PhotoText(val width: Int, val height: Int, val lines: List<OcrLine>) {
    /** Every word with its line, in reading order. */
    val words: List<Pair<OcrLine, OcrWord>> = lines.flatMap { l -> l.words.map { l to it } }

    /** The chosen words as text: words of one line with spaces, lines with line breaks. */
    fun text(chosen: Set<Int>): String {
        val byGroup = LinkedHashMap<Int, MutableList<String>>()
        words.forEachIndexed { i, (line, word) ->
            if (i in chosen) byGroup.getOrPut(line.group) { mutableListOf() } += word.text
        }
        return joinOcrLines(byGroup.values.map { it.joinToString(" ") })
    }
}

object PhotoTextReader {
    /** Long side for reading: enough for small print, as in Pysanka. */
    private const val MAX_SIDE = 2400

    /** A picture this much taller than wide (a long screenshot) is read in parts, so its text stays legible. */
    private const val TALL = 2.5f
    private const val TILE_RATIO = 2
    private const val MAX_TILES = 12

    /** Parts overlap by this much (in the file's pixels), so a line on the seam is read whole in one of them. */
    private const val OVERLAP = 96

    /**
     * Reads all the text on [item], or null when there is none or the photo can't be read (deleted,
     * access taken away, an unusual file, not enough memory): never a crash. Runs off the main thread;
     * cancelling stops between lines.
     */
    suspend fun read(context: Context, item: MediaItem): PhotoText? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            val recognizer = TextRecognizer.get(context)
            // Parts are cut from the file as stored, so only a picture with no rotation goes that way.
            if (w > 0 && h > w * TALL && upright(resolver, item)) {
                readTall(resolver, item, w, h, recognizer)
            } else {
                readWhole(resolver, item, recognizer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun upright(resolver: ContentResolver, item: MediaItem): Boolean {
        val orientation = resolver.openInputStream(item.uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } ?: ExifInterface.ORIENTATION_UNDEFINED
        return orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
    }

    private suspend fun readWhole(resolver: ContentResolver, item: MediaItem, recognizer: TextRecognizer): PhotoText? {
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                val longer = max(info.size.width, info.size.height)
                if (longer > MAX_SIDE) {
                    val s = MAX_SIDE.toFloat() / longer
                    decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
                // The recogniser reads the pixels, which a picture kept on the graphics chip doesn't allow.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        return try {
            PhotoText(bitmap.width, bitmap.height, recognizer.recognize(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    /** A long screenshot: parts of twice its width, top to bottom (screenshots have no rotation to apply). */
    private suspend fun readTall(
        resolver: ContentResolver,
        item: MediaItem,
        w: Int,
        h: Int,
        recognizer: TextRecognizer,
    ): PhotoText? {
        var sample = 1
        while (w / (sample * 2) >= 1200) sample *= 2
        val input = resolver.openInputStream(item.uri) ?: return null
        return input.use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream) ?: return null
            try {
                val tile = w * TILE_RATIO
                val lines = ArrayList<OcrLine>()
                var top = 0
                var n = 0
                var groupBase = 0
                while (top < h && n < MAX_TILES) {
                    coroutineContext.ensureActive()
                    val bottom = min(h, top + tile)
                    val part = decoder.decodeRegion(
                        Rect(0, top, w, bottom),
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    // Each part keeps only the lines whose middle is in its own share of the overlaps.
                    val ownFrom = if (top == 0) 0 else top + OVERLAP / 2
                    val ownTo = if (bottom >= h) h else bottom - OVERLAP / 2
                    if (part != null) {
                        try {
                            val dy = top / sample
                            val read = recognizer.recognize(part).filter { l ->
                                val middle = top + l.box.centerY() * sample
                                middle in ownFrom until ownTo.coerceAtLeast(ownFrom + 1)
                            }
                            read.forEach { l ->
                                lines += l.copy(
                                    box = Rect(l.box).apply { offset(0, dy) },
                                    words = l.words.map { it.copy(box = Rect(it.box).apply { offset(0, dy) }) },
                                    group = l.group + groupBase,
                                )
                            }
                            groupBase += (read.maxOfOrNull { it.group } ?: -1) + 1
                        } finally {
                            part.recycle()
                        }
                    }
                    if (bottom >= h) break
                    top += tile - OVERLAP
                    n++
                }
                // The parts read cover at most MAX_TILES × the tile height; the rest of a very long
                // screenshot is not read (the viewer shows the same scaled picture).
                PhotoText(w / sample, h / sample, lines)
            } finally {
                decoder.recycle()
            }
        }
    }
}
