package ua.svitlyna.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** One photo or video as the system media list knows it. Only what the grid and viewer need. */
data class MediaItem(
    val id: Long,
    val isVideo: Boolean,
    /** When it was taken (ms); falls back to the file's own date when the photo has none. */
    val dateMs: Long,
    val name: String,
    val mime: String,
    val bucketId: Long,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    /** For items in the trash: when the system will delete them for good (ms), else 0. */
    val expiresMs: Long = 0,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
        )
}

/** A folder (the system calls it a bucket) with how many items it has and the newest one as its cover. */
data class Folder(
    val id: Long,
    val name: String,
    val count: Int,
    val cover: MediaItem,
)
