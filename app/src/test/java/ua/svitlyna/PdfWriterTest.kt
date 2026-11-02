package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.PdfWriter

class PdfWriterTest {
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun xrefPointsAtObjects() {
        val pdf = PdfWriter.bytes(listOf(PdfWriter.Page(fakeJpeg, 400, 300), PdfWriter.Page(fakeJpeg, 300, 400)))
        val s = String(pdf, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4"))
        assertTrue(s.trimEnd().endsWith("%%EOF"))
        val startxref = s.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(s.startsWith("xref", startxref))
        val rows = s.substring(startxref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(8, rows.size) // catalog, pages, and 3 objects per page
        for ((i, row) in rows.withIndex()) {
            val off = row.substring(0, 10).toInt()
            assertTrue("object ${i + 1}", s.startsWith("${i + 1} 0 obj", off))
        }
        assertTrue(s.contains("/Count 2"))
        assertTrue(s.contains("/Filter /DCTDecode /Length ${fakeJpeg.size}"))
        assertFalse(s.contains("/Author") || s.contains("/CreationDate") || s.contains("/Producer"))
    }

    @Test
    fun pictureFitsInsideMargins() {
        val s = String(PdfWriter.bytes(listOf(PdfWriter.Page(fakeJpeg, 1000, 100))), Charsets.ISO_8859_1)
        // Wide picture: full width between the margins.
        assertTrue(s.contains("q 547.28 0 0 54.73 24.00"))
    }
}
