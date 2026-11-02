package ua.svitlyna.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Tools for several photos at once: smaller copies in another format, and one PDF from many photos.
 * Originals are never changed. Long jobs report progress and stop when their scope is cancelled.
 */
object PhotoTools {
    /** About this long per megapixel on a mid-range phone (decode + encode), for the estimate shown before starting. */
    private const val MS_PER_MEGAPIXEL = 120L

    fun estimateSeconds(items: List<MediaItem>): Int {
        val mp = items.sumOf { (it.width.toLong() * it.height).coerceAtLeast(1_000_000) } / 1_000_000.0
        return ((mp * MS_PER_MEGAPIXEL) / 1000).toInt().coerceAtLeast(1)
    }

    /** "As is" stays below this many pixels (a 200 MP photo would not fit in memory twice). */
    private const val MAX_PIXELS = 50_000_000L

    class Done(val saved: Int, val skipped: Int)

    /**
     * Copies of [items] with the longer side at most [maxSide] px (0: as is) in [format], next to
     * each original, with its date and metadata.
     */
    suspend fun convert(
        context: Context,
        items: List<MediaItem>,
        format: SaveCopy.Format,
        maxSide: Int,
        quality: Int,
        progress: (Int) -> Unit,
    ): Done {
        var saved = 0
        var skipped = 0
        for ((i, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            val pixels = item.width.toLong() * item.height
            val side = when {
                maxSide > 0 -> maxSide
                pixels > MAX_PIXELS -> (maxOf(item.width, item.height) * Math.sqrt(MAX_PIXELS.toDouble() / pixels)).toInt()
                else -> Int.MAX_VALUE
            }
            val ok = runCatching {
                val picture = Images.full(context.contentResolver, item, side, software = true) ?: return@runCatching false
                try {
                    val suffix = if (maxSide > 0) "${maxSide}px" else format.ext
                    SaveCopy.save(context, item, picture, suffix, format, quality) != null
                } finally {
                    picture.recycle()
                }
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else false }
            if (ok) saved++ else skipped++
            progress(i + 1)
        }
        return Done(saved, skipped)
    }

    /**
     * One PDF of [items] (one per A4 page, in the given order) in Documents/Svitlyna, written page by
     * page (nothing piles up in memory). Pages are JPEG at most [maxSide] px; a photo that can't be
     * opened is left out; the file carries no metadata of the photos. Null if nothing was made.
     */
    suspend fun pdf(context: Context, items: List<MediaItem>, maxSide: Int = 2000, progress: (Int) -> Unit): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = "Svitlyna_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date()) + ".pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Svitlyna/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext null
            val written = resolver.openOutputStream(uri)!!.buffered().use { out ->
                val pdf = PdfWriter(out)
                pdf.begin()
                for ((i, item) in items.withIndex()) {
                    coroutineContext.ensureActive()
                    val page = runCatching { page(context, item, maxSide) }.getOrNull()
                    if (page != null) pdf.add(page)
                    progress(i + 1)
                }
                if (pdf.pages > 0) pdf.finish()
                pdf.pages
            }
            if (written == 0) throw IllegalStateException("no pages")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Throwable) {
            // Stopped or failed: no half-written PDF is left.
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private suspend fun page(context: Context, item: MediaItem, maxSide: Int): PdfWriter.Page? {
        val picture = Images.full(context.contentResolver, item, maxSide, software = true) ?: return null
        try {
            val jpeg = ByteArrayOutputStream().also { SaveCopy.onWhite(picture).let { flat ->
                flat.compress(Bitmap.CompressFormat.JPEG, 85, it)
                if (flat !== picture) flat.recycle()
            } }.toByteArray()
            return PdfWriter.Page(jpeg, picture.width, picture.height)
        } finally {
            picture.recycle()
        }
    }
}
