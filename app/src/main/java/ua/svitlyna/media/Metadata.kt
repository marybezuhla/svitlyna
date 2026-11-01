package ua.svitlyna.media

import androidx.exifinterface.media.ExifInterface
import java.lang.reflect.Modifier

/**
 * Metadata rules shared by everything that writes a photo: a copy keeps ALL the original's metadata
 * (not a hand-picked list), except what describes the old pixels; sharing removes location.
 */
object Metadata {
    /** Tags that describe the picture's pixels or its embedded preview: the copy has new ones. */
    private val PIXEL_TAGS = setOf(
        ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_BITS_PER_SAMPLE, ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION, ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
        ExifInterface.TAG_Y_CB_CR_POSITIONING, ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_ROWS_PER_STRIP, ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION, ExifInterface.TAG_NEW_SUBFILE_TYPE,
        ExifInterface.TAG_SUBFILE_TYPE, ExifInterface.TAG_DEFAULT_CROP_SIZE,
        // A changed picture is a different picture: it must not claim the original's unique id.
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )

    /** Every tag ExifInterface knows, minus the pixel ones: found by reflection, so new tags are never forgotten. */
    val COPYABLE_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
            .mapNotNull { it.get(null) as? String }
            .filter { it !in PIXEL_TAGS }
            .distinct()
    }

    val LOCATION_TAGS = listOf(
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
        // XMP can repeat the location (and other programs' notes); a shared copy goes without it.
        ExifInterface.TAG_XMP,
    )

    /**
     * Copies every copyable tag that [from] has into [to] (the caller saves [to]). The copy is marked
     * upright, because its pixels were already turned the right way up when it was made.
     *
     * If the original has no date taken inside the file (PNG screenshots don't), the copy gets
     * [takenMs] written in: otherwise a later rescan would date the copy "now" and it would jump to
     * the top of the timeline.
     */
    fun copy(from: ExifInterface?, to: ExifInterface, takenMs: Long) {
        if (from != null) {
            for (tag in COPYABLE_TAGS) {
                val value = from.getAttribute(tag) ?: continue
                // XMP describing a video inside the photo or an Ultra HDR gain map would be wrong in
                // a copy that has neither: such XMP is left out.
                if (tag == ExifInterface.TAG_XMP && XMP_NOT_IN_COPY.any { value.contains(it) }) continue
                to.setAttribute(tag, value)
            }
        }
        if (to.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null && takenMs > 0) {
            val at = java.time.Instant.ofEpochMilli(takenMs).atZone(java.time.ZoneId.systemDefault())
            to.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(at))
            to.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, at.offset.id.let { if (it == "Z") "+00:00" else it })
        }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
    }

    private val EXIF_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    private val XMP_NOT_IN_COPY = listOf("MicroVideo", "MotionPhoto", "hdrgm:", "GContainer", "Container:Directory")

    fun removeLocation(exif: ExifInterface) {
        LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
    }
}
