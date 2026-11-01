package ua.svitlyna.edit

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlyna.media.Metadata
import ua.svitlyna.media.MediaItem
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Changing the date taken or the place of a photo, in the file itself (after Android's permission).
 * Only the tags asked for change: pixels and every other tag (camera, XMP of a live photo…) stay.
 */
object MetaEdit {
    val WRITABLE = setOf("image/jpeg", "image/png", "image/webp")

    /** [taken]: the new date taken (null: unchanged). [place]: new latitude, longitude; [removePlace]: none. */
    data class Change(val taken: LocalDateTime? = null, val place: Pair<Double, Double>? = null, val removePlace: Boolean = false)

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val TYPED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun formatDate(at: LocalDateTime): String = TYPED.format(at)

    /** "2024-05-01 14:30" → the moment; null if it isn't a real date. */
    fun parseDate(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text.trim(), TYPED) }.getOrNull()

    /** "50.4501, 30.5234" (as maps apps copy it) → latitude, longitude; null if not a real place. */
    fun parsePlace(text: String): Pair<Double, Double>? {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    /** Writes [change] into [exif] (the caller saves). Location tags only, never XMP: a live photo's video is described there. */
    fun write(exif: ExifInterface, change: Change, zone: ZoneId = ZoneId.systemDefault()) {
        change.taken?.let { at ->
            val zoned = at.atZone(zone)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(zoned))
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, zoned.offset.id.let { if (it == "Z") "+00:00" else it })
        }
        if (change.removePlace || change.place != null) {
            Metadata.LOCATION_TAGS.filter { it != ExifInterface.TAG_XMP }.forEach { exif.setAttribute(it, null) }
        }
        change.place?.let { (lat, lon) -> exif.setLatLong(lat, lon) }
    }

    /** After Android's write permission. False if the file couldn't be changed (it stays as it was). */
    suspend fun apply(context: Context, item: MediaItem, change: Change): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Opened as the original: without "media location" access Android would hand over zeros
            // for the place, and saving would write them in. Then nothing is changed.
            context.contentResolver.openFileDescriptor(MediaStore.setRequireOriginal(item.uri), "rw")!!.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                write(exif, change)
                exif.saveAttributes()
            }
            change.taken?.let { at ->
                val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                runCatching {
                    context.contentResolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, ms) }, null, null)
                }
            }
            true
        }.getOrDefault(false)
    }
}
