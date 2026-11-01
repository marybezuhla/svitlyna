package ua.svitlyna.ocr

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
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

    /** Reads all the text on [item]. Runs off the main thread; cancelling stops between parts. */
    suspend fun read(context: Context, item: MediaItem): PhotoText? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        val recognizer = TextRecognizer.get(context)
        if (w > 0 && h > w * TALL) readTall(resolver, item, w, h, recognizer) else readWhole(resolver, item, recognizer)
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
                    val part = decoder.decodeRegion(
                        Rect(0, top, w, min(h, top + tile)),
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    if (part != null) {
                        try {
                            val dy = top / sample
                            val read = recognizer.recognize(part)
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
                    top += tile
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
