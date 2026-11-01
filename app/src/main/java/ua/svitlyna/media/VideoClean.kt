package ua.svitlyna.media

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A video copy without location and other notes (MP4, MOV, 3GP). The boxes holding them (`udta`,
 * `meta`, an XMP box) are not cut out but turned into empty `free` boxes OF THE SAME SIZE: the
 * video's tables point at its pictures by position in the file, and cutting would shift them and
 * spoil the video. Pictures and sound are copied byte for byte. Idea from Iris Gallery (Apache 2.0).
 *
 * Refused (the person is asked instead): a video with a data or subtitle track (action cameras,
 * drones and dash cams write the place into every frame there), and anything at the top level that
 * isn't a known MP4 box (a phone maker's own trailer could hold anything).
 */
object VideoClean {
    private const val MAX_MOOV = 64 * 1024 * 1024
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "mvex")
    private val NOTES = setOf("udta", "meta")
    private val TOP = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "udta", "meta", "moof", "mfra", "sidx", "styp", "pdin")
    private val FIRST = setOf("ftyp", "wide", "free", "skip", "mdat", "moov")
    /** Tracks that carry data instead of pictures or sound (GPS of GoPro, DJI, Sony, dash cams…). */
    private val DATA_TRACKS = setOf("meta", "text", "sbtl", "subt", "camm", "tmcd")

    /** XMP inside a video: uuid BE7ACFCB-97A9-42E8-9C71-999491E3AFAC. */
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte(),
    )

    /** False if it isn't a video of this kind or looks damaged (then nothing usable was written). */
    fun clean(input: InputStream, output: OutputStream, keepGoing: () -> Boolean = { true }): Boolean = runCatching {
        val src = DataInputStream(input.buffered(64 * 1024))
        val out = output.buffered(64 * 1024)
        var first = true
        while (true) {
            val head = ByteArray(8)
            val n = src.read(head)
            if (n <= 0) break
            if (n < 8) src.readFully(head, n, 8 - n)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            if (first && type !in FIRST) throw IOException("not an MP4")
            if (type !in TOP) throw IOException("unknown box at the top")
            first = false
            var size = u32(head, 0)
            var headSize = 8
            val big = ByteArray(8)
            if (size == 1L) {
                src.readFully(big)
                size = u64(big)
                headSize = 16
            }
            val payload = if (size == 0L) -1L else size - headSize
            if (size != 0L && payload < 0) throw IOException("bad box")
            when {
                type == "moov" -> {
                    if (payload < 0 || payload > MAX_MOOV) throw IOException("moov too large")
                    val body = ByteArray(payload.toInt())
                    src.readFully(body)
                    blank(body, 0, body.size)
                    out.write(head)
                    if (headSize == 16) out.write(big)
                    out.write(body)
                }
                type in NOTES || type == "uuid" -> {
                    if (payload < 0 || payload > MAX_MOOV) throw IOException("bad box")
                    val body = ByteArray(payload.toInt())
                    src.readFully(body)
                    val xmp = type == "uuid" && body.size >= 16 && body.copyOfRange(0, 16).contentEquals(XMP_UUID)
                    if (type in NOTES || xmp) {
                        "free".toByteArray(Charsets.ISO_8859_1).copyInto(head, 4)
                        body.fill(0)
                    }
                    out.write(head)
                    if (headSize == 16) out.write(big)
                    out.write(body)
                }
                else -> {
                    out.write(head)
                    if (headSize == 16) out.write(big)
                    copy(src, out, payload, keepGoing)
                }
            }
            if (size == 0L) break
        }
        out.flush()
        !first
    }.getOrDefault(false)

    /** Inside [buf] from [start] to [end]: note boxes become `free` and zeros, containers are walked. */
    fun blank(buf: ByteArray, start: Int, end: Int) {
        var at = start
        while (at + 8 <= end) {
            var size = u32(buf, at)
            var headSize = 8
            if (size == 1L) {
                if (at + 16 > end) throw IOException("bad box")
                size = u64(buf.copyOfRange(at + 8, at + 16))
                headSize = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < headSize || at + size > end) throw IOException("bad box")
            val type = String(buf, at + 4, 4, Charsets.ISO_8859_1)
            val bodyStart = at + headSize
            val boxEnd = (at + size).toInt()
            when {
                type in NOTES -> {
                    "free".toByteArray(Charsets.ISO_8859_1).copyInto(buf, at + 4)
                    buf.fill(0, bodyStart, boxEnd)
                }
                type == "uuid" && boxEnd - bodyStart >= 16 && buf.copyOfRange(bodyStart, bodyStart + 16).contentEquals(XMP_UUID) -> {
                    "free".toByteArray(Charsets.ISO_8859_1).copyInto(buf, at + 4)
                    buf.fill(0, bodyStart, boxEnd)
                }
                type == "hdlr" && boxEnd - bodyStart >= 12 &&
                    String(buf, bodyStart + 8, 4, Charsets.ISO_8859_1) in DATA_TRACKS -> throw IOException("data track")
                type in CONTAINERS -> blank(buf, bodyStart, boxEnd)
            }
            at = boxEnd
        }
    }

    private fun copy(src: InputStream, out: OutputStream, size: Long, keepGoing: () -> Boolean) {
        val b = ByteArray(64 * 1024)
        var left = size
        while (left != 0L) {
            if (!keepGoing()) throw IOException("stopped")
            val want = if (left < 0) b.size else minOf(left, b.size.toLong()).toInt()
            val r = src.read(b, 0, want)
            if (r < 0) {
                if (left < 0) return
                throw EOFException()
            }
            out.write(b, 0, r)
            if (left > 0) left -= r
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray): Long = (u32(b, 0) shl 32) or u32(b, 4)
}
