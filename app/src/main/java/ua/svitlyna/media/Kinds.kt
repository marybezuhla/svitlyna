package ua.svitlyna.media

import android.content.Context
import androidx.exifinterface.media.ExifInterface

/** What kind of picture an item is, for the timeline filters (rules adapted from Iris Gallery). */
enum class Filter { ALL, PHOTOS, VIDEOS, SCREENSHOTS, GIF, RAW, PANORAMAS }

private val RAW_EXT = listOf(".dng", ".cr2", ".cr3", ".nef", ".arw", ".orf", ".raf", ".rw2", ".pef", ".srw")

val MediaItem.isGif: Boolean get() = mime.equals("image/gif", ignoreCase = true) || name.endsWith(".gif", ignoreCase = true)

val MediaItem.isRaw: Boolean
    get() = !isVideo && (mime.contains("raw", ignoreCase = true) || mime.contains("dng", ignoreCase = true) ||
        RAW_EXT.any { name.endsWith(it, ignoreCase = true) })

/** Much wider than tall (or taller than wide), or named as a panorama by the camera. */
val MediaItem.isPanorama: Boolean
    get() = !isVideo && !isScreenshot && (name.startsWith("PANO", ignoreCase = true) ||
        (width > 0 && height > 0 && maxOf(width, height) >= 2.2f * minOf(width, height)))

fun MediaItem.matches(filter: Filter): Boolean = when (filter) {
    Filter.ALL -> true
    Filter.PHOTOS -> !isVideo
    Filter.VIDEOS -> isVideo
    Filter.SCREENSHOTS -> isScreenshot
    Filter.GIF -> isGif
    Filter.RAW -> isRaw
    Filter.PANORAMAS -> isPanorama
}

/**
 * Live photos. Google, Samsung (since 2021) and other cameras put a short video inside the photo and
 * say so in its XMP; the player plays it straight from the photo (nothing is copied out). An iPhone
 * Live Photo is a photo and a video with the same name, side by side.
 */
object LivePhoto {
    private val MARKERS = listOf("MicroVideo", "MotionPhoto=\"1\"", "MotionPhoto>1<", "Camera:MotionPhoto", "GCamera:MotionPhoto")

    fun hasEmbeddedVideo(context: Context, item: MediaItem): Boolean {
        if (item.isVideo || item.isGif || item.isScreenshot) return false
        if (item.name.startsWith("MVIMG_", true) || item.name.contains("_MP.", true)) return true
        return runCatching {
            context.contentResolver.openInputStream(item.uri)!!.use { ExifInterface(it) }.getAttribute(ExifInterface.TAG_XMP)
        }.getOrNull()?.let { xmp -> MARKERS.any { xmp.contains(it) } } == true
    }

    /** The iPhone-style pair: a video in the same folder with the same name. */
    fun pairedVideo(item: MediaItem, all: List<MediaItem>): MediaItem? {
        if (item.isVideo) return null
        val stem = item.name.substringBeforeLast('.')
        if (stem.isBlank()) return null
        return all.firstOrNull { it.isVideo && it.bucketId == item.bucketId && it.name.substringBeforeLast('.').equals(stem, true) }
    }
}
