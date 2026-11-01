package ua.svitlyna.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sharing without location. Photos in formats whose metadata can be rewritten (JPEG, PNG, WebP) are
 * copied into the app's private cache, the location is removed from the copy, and only the copy is
 * shared. The original is never changed. Other files (HEIC, videos, GIF…) can't be cleaned yet:
 * they are reported so the person decides.
 */
object Share {
    private val CLEANABLE = setOf("image/jpeg", "image/png", "image/webp")

    private val LOCATION_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_VERSION_ID,
        // XMP can repeat the location (and other programs' notes); the shared copy goes without it.
        ExifInterface.TAG_XMP,
    )

    fun canClean(item: MediaItem) = !item.isVideo && item.mime.lowercase() in CLEANABLE

    /** The share intent, or null when nothing is left to share; [skipped] photos could not be cleaned. */
    class Prepared(val intent: Intent?, val skipped: Int)

    /**
     * With [removeLocation], cleanable photos are shared as cleaned copies, and a photo whose copy
     * could not be cleaned is left out rather than sent with its location. Files that can't be cleaned
     * at all go as they are: the caller has already asked about them.
     */
    suspend fun prepare(context: Context, items: List<MediaItem>, removeLocation: Boolean): Prepared =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share")
            // Copies from an earlier share are no longer needed once a new one starts.
            dir.deleteRecursively()
            dir.mkdirs()
            var skipped = 0
            val uris = items.mapIndexedNotNull { i, item ->
                if (removeLocation && canClean(item)) {
                    cleanCopy(context, item, File(dir, "$i-${safeName(item)}")) ?: run { skipped++; null }
                } else {
                    item.uri
                }
            }
            if (uris.isEmpty()) return@withContext Prepared(null, skipped)
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = commonType(items)
            intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prepared(Intent.createChooser(intent, null), skipped)
        }

    private fun cleanCopy(context: Context, item: MediaItem, target: File): Uri? = runCatching {
        context.contentResolver.openInputStream(item.uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        val exif = ExifInterface(target)
        LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        FileProvider.getUriForFile(context, "${context.packageName}.share", target)
    }.getOrElse {
        target.delete()
        null
    }

    private fun safeName(item: MediaItem): String =
        item.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "photo" }.take(80)

    private fun commonType(items: List<MediaItem>): String = when {
        items.all { !it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
}
