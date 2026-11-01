package ua.svitlyna

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.svitlyna.edit.MetaEdit
import ua.svitlyna.media.InPlace
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The check before a photo is written over: a "live photo" (a video after the end of the JPEG, as
 * Google and Samsung store it) keeps its video byte for byte; a copy where another tag changed is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InPlaceTest {
    private val video = ByteArray(100_000) { (it * 31 + 7).toByte() }

    private fun livePhoto(): File {
        val f = File.createTempFile("live", ".jpg")
        javaClass.classLoader!!.getResourceAsStream("plain.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        ExifInterface(f).apply {
            setAttribute(ExifInterface.TAG_MAKE, "Phone")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:05:12 14:03:21")
            setLatLong(48.0, 24.0)
            saveAttributes()
        }
        f.appendBytes(video)
        return f
    }

    private fun changedCopy(f: File, change: (ExifInterface) -> Unit): File {
        val copy = File.createTempFile("after", ".jpg")
        f.copyTo(copy, overwrite = true)
        ExifInterface(copy).also(change).saveAttributes()
        return copy
    }

    @Test
    fun dateAndPlaceChangeKeepsTheVideo() {
        val f = livePhoto()
        val c = MetaEdit.Change(taken = LocalDateTime.of(2024, 5, 1, 14, 30), removePlace = true)
        val after = changedCopy(f) { MetaEdit.write(it, c, ZoneId.of("Europe/Kyiv")) }
        assertTrue(InPlace.same(f, after, MetaEdit.changedTags(c)))
        // The video after the photo is there, unchanged.
        val bytes = after.readBytes()
        assertArrayEquals(video, bytes.copyOfRange(bytes.size - video.size, bytes.size))
        assertEquals("Phone", ExifInterface(after).getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun anotherTagChangedIsRefused() {
        val f = livePhoto()
        val c = MetaEdit.Change(taken = LocalDateTime.of(2024, 5, 1, 14, 30))
        val after = changedCopy(f) {
            MetaEdit.write(it, c)
            it.setAttribute(ExifInterface.TAG_MAKE, "Other")
        }
        assertFalse(InPlace.same(f, after, MetaEdit.changedTags(c)))
    }

    @Test
    fun aSmallPhotoCanBeChanged() {
        // Smaller than any fixed "end of file" window: only the part after EXIF is compared.
        val f = File.createTempFile("small", ".jpg")
        javaClass.classLoader!!.getResourceAsStream("plain.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        val c = MetaEdit.Change(place = 50.45 to 30.52)
        val after = changedCopy(f) { MetaEdit.write(it, c) }
        assertTrue(InPlace.same(f, after, MetaEdit.changedTags(c)))
    }

    @Test
    fun pngAndWebpToo() {
        for (name in listOf("plain.png", "plain.webp")) {
            val f = File.createTempFile("pic", "." + name.substringAfterLast('.'))
            javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            val c = MetaEdit.Change(taken = LocalDateTime.of(2024, 5, 1, 14, 30))
            val after = changedCopy(f) { MetaEdit.write(it, c) }
            assertNull(name, InPlace.difference(f, after, MetaEdit.changedTags(c)))
        }
    }

    @Test
    fun aCutVideoIsRefused() {
        val f = livePhoto()
        val after = File.createTempFile("cut", ".jpg")
        after.writeBytes(f.readBytes().copyOf(f.length().toInt() - 1000))
        assertFalse(InPlace.same(f, after, emptySet()))
    }
}
