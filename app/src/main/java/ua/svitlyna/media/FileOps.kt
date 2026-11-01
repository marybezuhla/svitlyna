package ua.svitlyna.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moving, copying and renaming files (folder management from Iris Gallery). Moving and renaming
 * change the person's files, so they run only after Android's own write permission; a copy is a
 * new file, byte for byte the same (so every bit of metadata stays), with the original's date.
 */
object FileOps {
    /** "Pictures/Trips/" for a new folder called "Trips"; Android keeps photos and videos only under DCIM/ and Pictures/. */
    fun newFolderPath(name: String): String = "Pictures/" + name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "Svitlyna" } + "/"

    /** After write permission: moves each item into [relativePath]. Returns how many moved. */
    suspend fun move(context: Context, items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        items.count { item ->
            runCatching {
                context.contentResolver.update(
                    item.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) },
                    null,
                    null,
                ) > 0
            }.getOrDefault(false)
        }
    }

    /** Copies each item into [relativePath] (no permission needed: the copies are new files). */
    suspend fun copy(context: Context, items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        var done = 0
        for (item in items) {
            var uri: Uri? = null
            try {
                val collection = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val path = relativePath
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.DATE_TAKEN, item.dateMs)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = context.contentResolver.insert(collection, values) ?: continue
                // Byte for byte, read with "media location" access so the location comes too.
                val source = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) }
                    .getOrNull() ?: context.contentResolver.openInputStream(item.uri)
                source!!.use { input -> context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, item.dateMs) }, null, null)
                done++
            } catch (e: CancellationException) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                throw e
            } catch (e: Exception) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
        done
    }

    /** After write permission: a new name, the same extension. */
    suspend fun rename(context: Context, item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val ext = item.name.substringAfterLast('.', "")
        val base = newName.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").removeSuffix(".$ext")
        if (base.isBlank()) return@withContext false
        val name = if (ext.isNotEmpty()) "$base.$ext" else base
        runCatching {
            context.contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0
        }.getOrDefault(false)
    }
}
