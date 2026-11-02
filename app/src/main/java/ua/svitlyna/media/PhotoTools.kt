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

    /**
     * Copies of [items] with the longer side at most [maxSide] px (0: as is) in [format], next to
     * each original, with its date and metadata. Returns how many were saved.
     */
    suspend fun convert(
        context: Context,
        items: List<MediaItem>,
        format: SaveCopy.Format,
        maxSide: Int,
        quality: Int,
        progress: (Int) -> Unit,
    ): Int {
        var saved = 0
        for ((i, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            val picture = Images.full(context.contentResolver, item, if (maxSide > 0) maxSide else Int.MAX_VALUE)
            if (picture != null) {
                val suffix = if (maxSide > 0) "${maxSide}px" else format.ext
                if (SaveCopy.save(context, item, picture, suffix, format, quality) != null) saved++
                picture.recycle()
            }
            progress(i + 1)
        }
        return saved
    }

    /**
     * One PDF of [items] (one per A4 page, in the given order) in Documents/Svitlyna. Pages are
     * JPEG at most [maxSide] px; the file carries no metadata of the photos.
     */
    suspend fun pdf(context: Context, items: List<MediaItem>, maxSide: Int = 2000, progress: (Int) -> Unit): Uri? {
        val pages = ArrayList<PdfWriter.Page>()
        for ((i, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            val picture = Images.full(context.contentResolver, item, maxSide) ?: return null
            val jpeg = withContext(Dispatchers.Default) {
                // JPEG has no transparency: a transparent PNG is put on white.
                val flat = if (picture.hasAlpha()) {
                    Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888).also { b ->
                        android.graphics.Canvas(b).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
                    }
                } else picture
                ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also {
                    if (flat !== picture) flat.recycle()
                }
            }
            pages += PdfWriter.Page(jpeg, picture.width, picture.height)
            picture.recycle()
            progress(i + 1)
        }
        coroutineContext.ensureActive()
        return withContext(Dispatchers.IO) {
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
                resolver.openOutputStream(uri)!!.use { PdfWriter(it.buffered()).write(pages) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                uri?.let { runCatching { resolver.delete(it, null, null) } }
                null
            }
        }
    }
}
