package ua.svitlyna

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.svitlyna.media.Metadata
import ua.svitlyna.media.SaveCopy
import ua.svitlyna.core.Bars
import java.io.File

/**
 * The metadata rules, checked by writing real files: save → read back → compare.
 * Location sits both in EXIF and in XMP, as phones write it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataTest {
    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="50,27.0N"/></rdf:RDF></x:xmpmeta>"""

    private fun sample(name: String): File {
        val ext = name.substringAfterLast('.')
        val f = File.createTempFile("meta", ".$ext")
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun original(name: String): File {
        val f = sample(name)
        val e = ExifInterface(f)
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:05:12 14:03:21")
        e.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+03:00")
        e.setAttribute(ExifInterface.TAG_MAKE, "Phone")
        e.setAttribute(ExifInterface.TAG_MODEL, "Model 7")
        e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Київ, весна")
        e.setLatLong(50.45, 30.52)
        e.setAttribute(ExifInterface.TAG_XMP, xmp)
        e.saveAttributes()
        return f
    }

    @Test
    fun copyKeepsEverythingForEachFormat() {
        for (name in listOf("plain.jpg", "plain.png", "plain.webp")) {
            val from = ExifInterface(original(name))
            val target = sample(name)
            val to = ExifInterface(target)
            Metadata.copy(from, to, takenMs = 0)
            to.saveAttributes()

            val back = ExifInterface(target)
            for (tag in listOf(
                ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
            )) {
                assertEquals("$name: $tag", from.getAttribute(tag), back.getAttribute(tag))
            }
            assertTrue("$name: XMP kept", back.getAttribute(ExifInterface.TAG_XMP)?.contains("GPSLatitude") == true)
            assertEquals("$name: upright", ExifInterface.ORIENTATION_NORMAL, back.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        }
    }

    @Test
    fun copyWithoutDateInsideGetsTheOriginalsDate() {
        // A PNG screenshot has no date taken in the file: the copy gets it written in.
        val target = sample("plain.png")
        val to = ExifInterface(target)
        val taken = java.time.ZonedDateTime.of(2024, 3, 9, 18, 5, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        Metadata.copy(from = null, to = to, takenMs = taken)
        to.saveAttributes()
        assertEquals("2024:03:09 18:05:00", ExifInterface(target).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    @Test
    fun removingLocationRemovesItFromExifAndXmp() {
        for (name in listOf("plain.jpg", "plain.png", "plain.webp")) {
            val f = original(name)
            val e = ExifInterface(f)
            Metadata.removeLocation(e)
            e.saveAttributes()
            val back = ExifInterface(f)
            assertNull("$name: latitude", back.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull("$name: longitude", back.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            assertFalse("$name: XMP", back.getAttribute(ExifInterface.TAG_XMP)?.contains("GPS") == true)
            // Everything else stays.
            assertEquals("$name: date", "2019:05:12 14:03:21", back.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        }
    }

    @Test
    fun copyableTagsFoundByReflectionButNotPixelOnes() {
        assertTrue(ExifInterface.TAG_DATETIME_ORIGINAL in Metadata.COPYABLE_TAGS)
        assertTrue(ExifInterface.TAG_GPS_LATITUDE in Metadata.COPYABLE_TAGS)
        assertTrue(ExifInterface.TAG_XMP in Metadata.COPYABLE_TAGS)
        assertFalse(ExifInterface.TAG_IMAGE_WIDTH in Metadata.COPYABLE_TAGS)
        assertFalse(ExifInterface.TAG_ORIENTATION in Metadata.COPYABLE_TAGS)
    }

    @Test
    fun copyName() {
        assertEquals("Screenshot_2026_no-bars.png", SaveCopy.copyName("Screenshot_2026.png", "no-bars", "png"))
        assertEquals("photo_no-bars.jpg", SaveCopy.copyName("", "no-bars", "jpg"))
    }

    @Test
    fun barsCropOnlyFullPortraitScreenshots() {
        val bars = Bars(screenW = 1080, screenH = 2400, top = 110, bottom = 63)
        assertEquals(110 until 2337, bars.keep(1080, 2400, withNavigation = true))
        assertEquals(110 until 2400, bars.keep(1080, 2400, withNavigation = false))
        assertNull("landscape", bars.keep(2400, 1080, true))
        assertNull("already cropped", bars.keep(1080, 2227, true))
        assertNull("another phone", bars.keep(1440, 3200, true))
        assertNull("not measured", Bars(0, 0, 0, 0).keep(1080, 2400, true))
    }
}
