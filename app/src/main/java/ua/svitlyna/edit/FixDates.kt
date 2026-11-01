package ua.svitlyna.edit

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Fix date taken": photos with no date inside the file (typical for messengers) get the date from
 * their name written into the file (EXIF DateTimeOriginal and its time zone). Only the date tags are
 * added: pixels and every other tag stay as they were. Android asks the person first.
 */
object FixDates {
    /** Formats whose metadata can be written in place. */
    private val WRITABLE = setOf("image/jpeg", "image/png", "image/webp")

    data class Fix(val item: MediaItem, val date: NameDate.Found) {
        val newMs: Long get() = date.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Photos without a date inside whose name has one, and that differ from where they now stand. */
    fun candidates(items: List<MediaItem>): List<Fix> = items.mapNotNull { item ->
        if (item.isVideo || item.takenMs > 0 || item.mime.lowercase() !in WRITABLE) return@mapNotNull null
        val date = NameDate.parse(item.name) ?: return@mapNotNull null
        val fix = Fix(item, date)
        // A name with only the date, and the photo already on that day: nothing to move.
        if (!date.hasTime) {
            val zone = ZoneId.systemDefault()
            val now = java.time.Instant.ofEpochMilli(item.dateMs).atZone(zone).toLocalDate()
            if (now == date.at.toLocalDate()) return@mapNotNull null
        }
        fix.takeIf { kotlin.math.abs(it.newMs - item.dateMs) > 60_000 }
    }

    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Writes the dates (after Android's write permission was given). Returns how many were fixed. */
    suspend fun apply(context: Context, fixes: List<Fix>): Int = withContext(Dispatchers.IO) {
        var done = 0
        for (fix in fixes) {
            try {
                val zoned = fix.date.at.atZone(ZoneId.systemDefault())
                // Opened as the original, so the location is real: without "media location" access
                // Android would hand over zeros, and saving would write them into the photo. Then this
                // photo is skipped instead.
                // A checked copy first, then the photo (see InPlace): only the two date tags change.
                val ok = ua.svitlyna.media.InPlace.rewrite(
                    context,
                    fix.item.uri,
                    setOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                ) { exif ->
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(zoned))
                    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, zoned.offset.id.let { if (it == "Z") "+00:00" else it })
                }
                if (!ok) continue
                // The system re-reads the file; the date is also set in the media list right away.
                runCatching {
                    context.contentResolver.update(
                        fix.item.uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, fix.newMs) },
                        null,
                        null,
                    )
                }
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This one stays as it was; the others go on.
            }
        }
        done
    }
}
