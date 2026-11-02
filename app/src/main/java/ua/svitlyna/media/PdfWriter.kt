package ua.svitlyna.media

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A small PDF writer: one picture per A4 page, centred with a margin, stored as the JPEG it already
 * is (so pages stay small and sharp). Nothing else goes into the file: no author, no dates, no
 * place — a PDF made from photos carries none of their metadata.
 */
class PdfWriter(private val out: OutputStream) {
    class Page(val jpeg: ByteArray, val widthPx: Int, val heightPx: Int)

    private val offsets = ArrayList<Long>()
    private var at = 0L

    private fun raw(b: ByteArray) { out.write(b); at += b.size }
    private fun text(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

    private fun obj(n: Int, body: () -> Unit) {
        while (offsets.size < n) offsets += 0L
        offsets[n - 1] = at
        text("$n 0 obj\n")
        body()
        text("\nendobj\n")
    }

    private val pageIds = ArrayList<Int>()
    private var nextId = 3

    /** Starts the file; then [add] each page (written at once, nothing kept), then [finish]. */
    fun begin() {
        text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        obj(1) { text("<< /Type /Catalog /Pages 2 0 R >>") }
    }

    /** Objects per page: the page, its picture, its contents. */
    fun add(p: Page) {
        val pageId = nextId
        val imgId = pageId + 1
        val contentId = pageId + 2
        nextId += 3
        val i = pageIds.size
        pageIds += pageId
        val (w, h) = A4
        val avW = w - 2 * MARGIN
        val avH = h - 2 * MARGIN
        val scale = minOf(avW / p.widthPx, avH / p.heightPx)
        val dw = p.widthPx * scale
        val dh = p.heightPx * scale
        val x = (w - dw) / 2
        val y = (h - dh) / 2
        obj(pageId) {
            text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(w)} ${fmt(h)}] " +
                "/Resources << /XObject << /Im$i $imgId 0 R >> >> /Contents $contentId 0 R >>")
        }
        obj(imgId) {
            text("<< /Type /XObject /Subtype /Image /Width ${p.widthPx} /Height ${p.heightPx} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n")
            raw(p.jpeg)
            text("\nendstream")
        }
        val content = "q ${fmt(dw)} 0 0 ${fmt(dh)} ${fmt(x)} ${fmt(y)} cm /Im$i Do Q".toByteArray(Charsets.ISO_8859_1)
        obj(contentId) {
            text("<< /Length ${content.size} >>\nstream\n")
            raw(content)
            text("\nendstream")
        }
    }

    val pages: Int get() = pageIds.size

    /** The page tree (now that all pages are known), the table of objects and the end. */
    fun finish() {
        require(pageIds.isNotEmpty())
        obj(2) { text("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>") }
        val xref = at
        text("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) text(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        text("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    fun write(pages: List<Page>) {
        begin()
        pages.forEach { add(it) }
        finish()
    }

    companion object {
        /** A4 in points (1/72 inch) and a margin of about 8 mm. */
        val A4 = 595.28 to 841.89
        const val MARGIN = 24.0

        fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

        fun bytes(pages: List<Page>): ByteArray = ByteArrayOutputStream().also { PdfWriter(it).write(pages) }.toByteArray()
    }
}
