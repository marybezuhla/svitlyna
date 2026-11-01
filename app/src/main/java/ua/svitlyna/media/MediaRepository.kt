package ua.svitlyna.media

import android.content.ContentResolver
import android.database.Cursor
import android.os.Bundle
import android.provider.MediaStore

/**
 * Reads photos and videos from the system media list (MediaStore). The system keeps this list
 * up to date itself, so nothing walks folders on the disk: a gallery of 100 000 items is one query.
 */
class MediaRepository(private val resolver: ContentResolver) {

    /** Everything the app may see, newest first. */
    fun loadAll(): List<MediaItem> = query(trashed = false)

    /** Items in the system trash, newest first. */
    fun loadTrash(): List<MediaItem> = query(trashed = true)

    /** One item by its id, or null if it is gone (or the app can't see it). */
    fun loadOne(id: Long): MediaItem? = query(trashed = false, id = id).firstOrNull()

    private fun query(trashed: Boolean, id: Long? = null): List<MediaItem> {
        val args = Bundle().apply {
            val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                if (id != null) "$types AND ${MediaStore.MediaColumns._ID} = ?" else types,
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                listOfNotNull(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    id?.toString(),
                ).toTypedArray(),
            )
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val out = ArrayList<MediaItem>()
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), PROJECTION, args, null)
            ?.use { c -> while (c.moveToNext()) out += c.toItem() }
        out.sortWith(NEWEST_FIRST)
        return out
    }

    private fun Cursor.toItem(): MediaItem {
        val taken = getLongOrZero(MediaStore.MediaColumns.DATE_TAKEN)
        val modifiedSec = getLongOrZero(MediaStore.MediaColumns.DATE_MODIFIED)
        val addedSec = getLongOrZero(MediaStore.MediaColumns.DATE_ADDED)
        return MediaItem(
            id = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
            isVideo = getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)) ==
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            dateMs = bestDate(taken, modifiedSec, addedSec),
            name = getStringOrEmpty(MediaStore.MediaColumns.DISPLAY_NAME),
            mime = getStringOrEmpty(MediaStore.MediaColumns.MIME_TYPE),
            bucketId = getLongOrZero(MediaStore.MediaColumns.BUCKET_ID),
            bucketName = getStringOrEmpty(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            width = getLongOrZero(MediaStore.MediaColumns.WIDTH).toInt(),
            height = getLongOrZero(MediaStore.MediaColumns.HEIGHT).toInt(),
            sizeBytes = getLongOrZero(MediaStore.MediaColumns.SIZE),
            durationMs = getLongOrZero(MediaStore.MediaColumns.DURATION),
            expiresMs = getLongOrZero(MediaStore.MediaColumns.DATE_EXPIRES) * 1000L,
            relativePath = getStringOrEmpty(MediaStore.MediaColumns.RELATIVE_PATH),
            takenMs = taken,
            isFavorite = getLongOrZero(MediaStore.MediaColumns.IS_FAVORITE) == 1L,
        )
    }

    private fun Cursor.getLongOrZero(column: String): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun Cursor.getStringOrEmpty(column: String): String {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) "" else getString(i) ?: ""
    }

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_FAVORITE,
        )

        val NEWEST_FIRST: Comparator<MediaItem> =
            compareByDescending<MediaItem> { it.dateMs }.thenByDescending { it.id }

        /**
         * The date a photo belongs to in the timeline: when it was taken; if the file has no such date
         * (common for photos from messengers), when the file was last changed, then when it was added.
         */
        fun bestDate(takenMs: Long, modifiedSec: Long, addedSec: Long): Long = when {
            takenMs > 0 -> takenMs
            modifiedSec > 0 -> modifiedSec * 1000L
            else -> addedSec * 1000L
        }

        /** Folders, the one with the newest item first. */
        fun folders(items: List<MediaItem>): List<Folder> =
            items.groupBy { it.bucketId }
                .map { (id, list) -> Folder(id, list.first().bucketName, list.size, list.first()) }
                .sortedWith(compareByDescending<Folder> { it.cover.dateMs }.thenBy { it.name })
    }
}
