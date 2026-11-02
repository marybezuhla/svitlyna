package ua.svitlyna.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves an edited picture as a NEW file next to the original, following the metadata rules:
 * - the copy belongs to the same moment: its date taken is the original's, so it sits beside the
 *   original in the timeline instead of jumping to the top;
 * - all of the original's metadata is copied (location included, read with "media location" access);
 * - the original is never changed or removed.
 */
object SaveCopy {
    /** Returns the new file's address, or null if it couldn't be saved (nothing half-written is left). */
    /** The picture formats a copy can be saved in. */
    enum class Format(val ext: String, val mime: String, val compress: Bitmap.CompressFormat) {
        JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
        WEBP("webp", "image/webp", Bitmap.CompressFormat.WEBP_LOSSY),
        PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    }

    suspend fun save(
        context: Context,
        original: MediaItem,
        picture: Bitmap,
        suffix: String,
        format: Format? = null,
        quality: Int = 95,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Without a chosen format: PNG stays PNG (screenshots), anything else becomes JPEG.
        val f = format ?: if (original.mime.equals("image/png", ignoreCase = true)) Format.PNG else Format.JPEG
        val png = f == Format.PNG
        val ext = f.ext
        val mime = f.mime
        val dir = File(context.cacheDir, "edit").apply { mkdirs() }
        val temp = File.createTempFile("copy", ".$ext", dir)
        var uri: Uri? = null
        try {
            temp.outputStream().use { out ->
                // Lossless for PNG (screenshots); high quality for photos.
                check(picture.compress(f.compress, if (png) 100 else quality.coerceIn(30, 100), out))
            }
            copyMetadata(context, original, temp)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, copyName(original.name, suffix, ext))
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(context, original) ?: "Pictures/Svitlyna/")
                // A file from another app may have no date at all: then Android dates the copy itself.
                if (original.dateMs > 0) put(MediaStore.MediaColumns.DATE_TAKEN, original.dateMs)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: IllegalArgumentException) {
                // Android keeps photos only under DCIM/ and Pictures/: an original elsewhere
                // (Download/, another app's folder) gets its copy in Pictures/Svitlyna.
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Svitlyna/")
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } ?: return@withContext null
            resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            // The system re-reads the file when it stops being pending: the date taken is set again after.
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (original.dateMs > 0) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, original.dateMs) }, null, null)
            }
            uri
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        } finally {
            temp.delete()
        }
    }

    private fun copyMetadata(context: Context, original: MediaItem, target: File) {
        val source = runCatching {
            // With "media location" access the original's location comes too; without it Android
            // removes it from what the app reads, and the copy has none.
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(original.uri))!!.use { ExifInterface(it) }
        }.recoverCatching {
            context.contentResolver.openInputStream(original.uri)!!.use { ExifInterface(it) }
        }.getOrNull()
        val exif = ExifInterface(target)
        Metadata.copy(source, exif, original.dateMs)
        exif.saveAttributes()
    }

    /** A file from another app has no folder in the gallery: its copy goes to Pictures/Svitlyna. */
    private fun relativePath(context: Context, item: MediaItem): String? = if (item.external != null) null else runCatching {
        context.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** "Screenshot_2026.png" + "no-bars" → "Screenshot_2026_no-bars.png". */
    fun copyName(name: String, suffix: String, ext: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "photo" }
        return "${base}_$suffix.$ext"
    }
}
