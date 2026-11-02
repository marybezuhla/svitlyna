package ua.svitlyna.media

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The last step of a copy for sending: only what is needed to show the picture stays. ExifInterface
 * already removed the location from EXIF; this drops everything it doesn't touch.
 *
 * JPEG: kept are the picture's own segments, JFIF, EXIF (already cleaned), the colour profile and
 * Adobe's colour marker. Dropped are XMP (also the extended part with depth maps), IPTC (city,
 * author), comments, other application segments and anything after the end of the picture — the
 * video of a live photo (Google, Samsung) and its own notes.
 *
 * PNG: text chunks (tEXt, zTXt, iTXt — where XMP lives) and the time chunk are dropped.
 *
 * Returns false when the file isn't understood: then it is not sent.
 */
object CleanCopy {
    fun clean(file: File): Boolean {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        val out = when {
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> jpeg(bytes)
            bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> png(bytes)
            else -> bytes // WebP and others: as ExifInterface left them
        } ?: return false
        return runCatching { file.writeBytes(out); true }.getOrDefault(false)
    }

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun startsWith(b: ByteArray, at: Int, end: Int, prefix: String): Boolean {
        if (at + prefix.length > end) return false
        for (i in prefix.indices) if (b[at + i] != prefix[i].code.toByte()) return false
        return true
    }

    fun jpeg(b: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var at = 2
        while (at + 1 < b.size) {
            if (b[at] != 0xFF.toByte()) return null
            val marker = b[at + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { at++; continue } // fill byte
                marker == 0xD9 -> { out.write(0xFF); out.write(0xD9); return out.toByteArray() } // end: nothing after it
                marker in 0xD0..0xD7 || marker == 0x01 -> { out.write(b, at, 2); at += 2; continue }
            }
            if (at + 4 > b.size) return null
            val size = u16(b, at + 2)
            val end = at + 2 + size
            if (size < 2 || end > b.size) return null
            val body = at + 4
            val keep = when (marker) {
                0xE0 -> startsWith(b, body, end, "JFIF\u0000")
                0xE1 -> startsWith(b, body, end, "Exif\u0000\u0000")
                0xE2 -> startsWith(b, body, end, "ICC_PROFILE\u0000")
                0xEE -> startsWith(b, body, end, "Adobe")
                in 0xE3..0xEF, 0xFE -> false // other application segments, comments
                else -> true // tables, frame, restart interval, scan header…
            }
            if (keep) out.write(b, at, end - at)
            at = end
            if (marker == 0xDA) {
                // The picture data: up to the next marker that is not stuffing or a restart.
                var i = at
                while (i + 1 < b.size) {
                    if (b[i] == 0xFF.toByte()) {
                        val m = b[i + 1].toInt() and 0xFF
                        if (m != 0x00 && m !in 0xD0..0xD7 && m != 0xFF) break
                    }
                    i++
                }
                if (i + 1 >= b.size) return null // no end of picture
                out.write(b, at, i - at)
                at = i
            }
        }
        return null
    }

    fun png(b: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var at = 8
        while (at + 12 <= b.size) {
            val len = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
            val end = at + 12 + len
            if (len < 0 || end > b.size) return null
            val type = String(b, at + 4, 4, Charsets.ISO_8859_1)
            if (type !in setOf("tEXt", "zTXt", "iTXt", "tIME")) out.write(b, at, (end - at).toInt())
            at = end.toInt()
            if (type == "IEND") return out.toByteArray() // nothing after the end
        }
        return null
    }
}
