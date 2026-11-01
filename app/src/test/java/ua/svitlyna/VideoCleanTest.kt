package ua.svitlyna

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.VideoClean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** A small MP4 put together box by box: location gone, size and pictures unchanged. */
class VideoCleanTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        val size = body.size + 8
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private val place = "+50.4501+030.5234/".toByteArray()
    private val pictures = ByteArray(5000) { (it * 7).toByte() }
    private val video = box("ftyp", "isom".toByteArray(), ByteArray(4)) +
        box(
            "moov",
            box("mvhd", ByteArray(100) { 1 }),
            box("udta", box("©xyz", place)),
            box("trak", box("tkhd", ByteArray(84) { 2 }), box("meta", place)),
        ) +
        box("mdat", pictures)

    private fun clean(bytes: ByteArray): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        return VideoClean.clean(ByteArrayInputStream(bytes), out) to out.toByteArray()
    }

    private fun ByteArray.has(part: ByteArray) = (0..size - part.size).any { i -> part.indices.all { this[i + it] == part[it] } }

    @Test
    fun locationIsGoneEverythingElseInPlace() {
        val (ok, out) = clean(video)
        assertTrue(ok)
        // Same size: the video's tables still point at the right pictures.
        assertEquals(video.size, out.size)
        assertFalse(out.has(place))
        assertFalse(out.has("udta".toByteArray()))
        assertTrue(out.has("free".toByteArray()))
        // Pictures and sound byte for byte, at the same place.
        assertArrayEquals(video.copyOfRange(video.size - pictures.size, video.size), out.copyOfRange(out.size - pictures.size, out.size))
        assertTrue(out.has(ByteArray(84) { 2 }))
    }

    @Test
    fun notAVideoIsRefused() {
        assertFalse(clean("hello, this is text".toByteArray()).first)
    }

    @Test
    fun damagedBoxIsRefused() {
        val broken = video.copyOf().also { it[video.indexOfMoov() + 3] = 0x7F }
        assertFalse(clean(broken).first)
    }

    @Test
    fun aVideoWithADataTrackIsRefused() {
        // stsd: version/flags, one entry, then the entry: here GoPro GPS ("gpmd").
        fun withSamples(kind: String) = box("ftyp", "isom".toByteArray(), ByteArray(4)) +
            box("moov", box("trak", box("mdia", box("minf", box("stbl", box("stsd", ByteArray(4), byteArrayOf(0, 0, 0, 1), box(kind, ByteArray(8)))))))) +
            box("mdat", pictures)
        assertFalse(clean(withSamples("gpmd")).first)
        assertFalse(clean(withSamples("tx3g")).first)
        // An ordinary video track goes through.
        assertTrue(clean(withSamples("avc1")).first)
    }

    @Test
    fun anUnknownTrailerIsRefused() {
        assertFalse(clean(video + box("SEFT", place)).first)
    }

    private fun ByteArray.indexOfMoov(): Int = (0..size - 4).first { String(this, it, 4, Charsets.ISO_8859_1) == "moov" } - 4
}
