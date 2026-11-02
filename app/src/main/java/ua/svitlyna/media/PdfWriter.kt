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

    /** Writes all [pages]; objects: 1 catalog, 2 page tree, then per page: page, image, contents. */
    fun write(pages: List<Page>) {
        require(pages.isNotEmpty())
        text("%PDF-1.4\n%âãÏÓ\n")
        val pageIds = pages.indices.map { 3 + it * 3 }
        obj(1) { text("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { text("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>") }
        for ((i, p) in pages.withIndex()) {
            val pageId = pageIds[i]
            val imgId = pageId + 1
            val contentId = pageId + 2
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
        val xref = at
        text("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) text(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        text("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    companion object {
        /** A4 in points (1/72 inch) and a margin of about 8 mm. */
        val A4 = 595.28 to 841.89
        const val MARGIN = 24.0

        fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

        fun bytes(pages: List<Page>): ByteArray = ByteArrayOutputStream().also { PdfWriter(it).write(pages) }.toByteArray()
    }
}
