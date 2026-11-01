package ua.svitlyna.media

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the photo says about itself: camera, lens, exposure, description, author, location. */
data class PhotoDetails(
    val camera: String?,
    val lens: String?,
    val exposure: String?,
    val description: String?,
    val author: String?,
    val lat: Double?,
    val lon: Double?,
) {
    companion object {
        suspend fun read(context: Context, item: MediaItem): PhotoDetails? = withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext null
            val exif = runCatching {
                // With "media location" access the location is read too.
                context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri))!!.use { ExifInterface(it) }
            }.recoverCatching {
                context.contentResolver.openInputStream(item.uri)!!.use { ExifInterface(it) }
            }.getOrNull() ?: return@withContext null
            fun tag(t: String) = exif.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() }
            val make = tag(ExifInterface.TAG_MAKE)
            val model = tag(ExifInterface.TAG_MODEL)
            val camera = listOfNotNull(make, model?.removePrefix(make ?: "")?.trim()).joinToString(" ").ifBlank { null }
            val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "ƒ/%.1f".format(it) }
            val time = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                if (it >= 1) "%.1f s".format(it) else "1/${(1 / it).toInt()} s"
            }
            val iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" }
            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.1f mm".format(it) }
            val exposure = listOfNotNull(fNumber, time, iso, focal).joinToString(" · ").ifBlank { null }
            val latLong = exif.latLong
            PhotoDetails(
                camera = camera,
                lens = tag(ExifInterface.TAG_LENS_MODEL),
                exposure = exposure,
                description = tag(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: tag(ExifInterface.TAG_USER_COMMENT),
                author = tag(ExifInterface.TAG_ARTIST),
                lat = latLong?.get(0),
                lon = latLong?.get(1),
            )
        }
    }
}
