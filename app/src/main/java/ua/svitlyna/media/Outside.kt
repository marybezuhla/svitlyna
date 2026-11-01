package ua.svitlyna.media

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video another app asked Svitlyna to show or edit (from a file manager, a messenger…). */
object Outside {
    suspend fun item(context: Context, uri: Uri, type: String?): MediaItem? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // One of the phone's own photos that Svitlyna can read anyway: everything about it is known,
        // and an edited copy goes next to it.
        // (Only the main media list: the system photo picker numbers things its own way.)
        if (uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull()?.startsWith("external") == true) {
            val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
            if (id > 0) runCatching { MediaRepository(resolver).loadOne(id) }.getOrNull()?.let { return@withContext it }
        }
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                }
            }
        }
        val mime = (type?.takeIf { '*' !in it } ?: runCatching { resolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()))
            ?.lowercase() ?: return@withContext null
        val isVideo = mime.startsWith("video/")
        if (!isVideo && !mime.startsWith("image/")) return@withContext null
        var width = 0
        var height = 0
        var taken = 0L
        if (!isVideo) {
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, o)
                    width = o.outWidth.coerceAtLeast(0)
                    height = o.outHeight.coerceAtLeast(0)
                }
            }
            runCatching { resolver.openInputStream(uri)?.use { taken = ExifInterface(it).dateTimeOriginal ?: 0L } }
        }
        // Readable at all? (A file without permission to read is not shown as an empty page.)
        val readable = runCatching { resolver.openInputStream(uri)?.use { it.read() }; true }.getOrDefault(false)
        if (!readable) return@withContext null
        MediaItem(
            id = -1L,
            isVideo = isVideo,
            dateMs = taken,
            name = name.ifBlank { if (isVideo) "video" else "photo" },
            mime = mime,
            bucketId = -1L,
            bucketName = "",
            width = width,
            height = height,
            sizeBytes = size,
            durationMs = 0,
            external = uri,
        )
    }
}
