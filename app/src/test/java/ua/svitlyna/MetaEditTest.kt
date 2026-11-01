package ua.svitlyna

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.svitlyna.edit.MetaEdit
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Changing the date or place in the file: save → read back → compare; nothing else may change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetaEditTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1"/></rdf:RDF></x:xmpmeta>"""

    private fun photo(name: String): File {
        val f = File.createTempFile("edit", "." + name.substringAfterLast('.'))
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Phone")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:05:12 14:03:21")
            setAttribute(ExifInterface.TAG_XMP, xmp)
            setLatLong(48.0, 24.0)
            saveAttributes()
        }
        return f
    }

    private fun change(f: File, c: MetaEdit.Change) {
        ExifInterface(f).apply { MetaEdit.write(this, c, zone); saveAttributes() }
    }

    @Test
    fun dateAndPlaceAreWrittenAndTheRestStays() {
        for (name in listOf("plain.jpg", "plain.png", "plain.webp")) {
            val f = photo(name)
            change(f, MetaEdit.Change(taken = LocalDateTime.of(2024, 5, 1, 14, 30), place = 50.4501 to 30.5234))
            val back = ExifInterface(f)
            assertEquals(name, "2024:05:01 14:30:00", back.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals(name, "+03:00", back.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            val ll = back.latLong!!
            assertEquals(name, 50.4501, ll[0], 1e-4)
            assertEquals(name, 30.5234, ll[1], 1e-4)
            assertEquals(name, "Phone", back.getAttribute(ExifInterface.TAG_MAKE))
            // The live photo's description stays: without it the video inside would be lost to galleries.
            assertEquals(name, xmp, back.getAttribute(ExifInterface.TAG_XMP))
            f.delete()
        }
    }

    @Test
    fun placeCanBeRemovedAndDateStays() {
        val f = photo("plain.jpg")
        change(f, MetaEdit.Change(removePlace = true))
        val back = ExifInterface(f)
        assertNull(back.latLong)
        assertEquals("2019:05:12 14:03:21", back.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        f.delete()
    }

    @Test
    fun typedTextIsUnderstood() {
        assertEquals(LocalDateTime.of(2024, 5, 1, 14, 30), MetaEdit.parseDate(" 2024-05-01 14:30 "))
        assertNull(MetaEdit.parseDate("2024-13-01 14:30"))
        assertEquals(50.4501 to 30.5234, MetaEdit.parsePlace("50.4501, 30.5234"))
        assertEquals(-33.9 to 151.2, MetaEdit.parsePlace("-33.9 151.2"))
        assertNull(MetaEdit.parsePlace("95, 30"))
        assertNull(MetaEdit.parsePlace("Київ"))
    }
}
