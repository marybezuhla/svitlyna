package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.CleanCopy
import java.io.ByteArrayOutputStream

class CleanCopyTest {
    private fun seg(out: ByteArrayOutputStream, marker: Int, body: ByteArray) {
        out.write(0xFF); out.write(marker)
        val n = body.size + 2
        out.write(n shr 8); out.write(n and 0xFF)
        out.write(body)
    }

    private fun jpeg(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0xFF); o.write(0xD8)
        seg(o, 0xE0, "JFIF\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1))
        seg(o, 0xE1, "Exif\u0000\u0000MM".toByteArray(Charsets.ISO_8859_1))
        seg(o, 0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta GPSLatitude=\"50.45\"/>".toByteArray(Charsets.ISO_8859_1))
        seg(o, 0xED, "Photoshop 3.0\u0000Kyiv".toByteArray(Charsets.ISO_8859_1))
        seg(o, 0xFE, "secret comment".toByteArray(Charsets.ISO_8859_1))
        seg(o, 0xDB, ByteArray(65) { 1 })
        seg(o, 0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        o.write(byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56))
        o.write(0xFF); o.write(0xD9)
        o.write("....ftypmp42....udta©xyz+50.45+030.52/".toByteArray(Charsets.UTF_8))
        return o.toByteArray()
    }

    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))

    @Test
    fun jpegKeepsPictureDropsTheRest() {
        val out = CleanCopy.jpeg(jpeg())
        assertNotNull(out)
        out!!
        for (s in listOf("50.45", "Kyiv", "secret", "xmpmeta", "ftyp", "©xyz")) assertFalse(s, has(out, s))
        assertTrue(has(out, "JFIF") && has(out, "Exif"))
        assertEquals(0xFF.toByte(), out[out.size - 2]); assertEquals(0xD9.toByte(), out[out.size - 1])
        // The picture data with its stuffing and restart marker is untouched.
        assertTrue(has(out, String(byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56), Charsets.ISO_8859_1)))
    }

    @Test
    fun brokenJpegIsRefused() {
        val b = jpeg()
        assertNull(CleanCopy.jpeg(b.copyOf(40)))
    }

    private fun chunk(o: ByteArrayOutputStream, type: String, data: ByteArray) {
        val n = data.size
        o.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        o.write(type.toByteArray(Charsets.ISO_8859_1)); o.write(data); o.write(ByteArray(4))
    }

    @Test
    fun pngDropsText() {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(o, "IHDR", ByteArray(13))
        chunk(o, "iTXt", "XML:com.adobe.xmp GPS 50.45".toByteArray(Charsets.ISO_8859_1))
        chunk(o, "tEXt", "Author Olena".toByteArray(Charsets.ISO_8859_1))
        chunk(o, "IDAT", ByteArray(10) { 7 })
        chunk(o, "IEND", ByteArray(0))
        o.write("trailer 50.45".toByteArray(Charsets.ISO_8859_1))
        val out = CleanCopy.png(o.toByteArray())!!
        assertFalse(has(out, "50.45")); assertFalse(has(out, "Olena"))
        assertTrue(has(out, "IHDR") && has(out, "IDAT") && has(out, "IEND"))
    }
}
