package ua.svitlyna.media

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

/**
 * Changing a few tags of the person's own photo, safely:
 * 1. the original is copied into the app's cache (read as the original, so the place is real);
 * 2. the tags are changed in the copy;
 * 3. the copy is checked: every other tag the same, and everything after the metadata block
 *    (the picture, a live photo's video) byte for byte the same;
 * 4. only then the photo is written over. If that fails half way, the photo is put back as it was;
 *    if even that fails, the untouched original is saved as a new photo, so nothing is lost.
 */
object InPlace {
    enum class Result { DONE, REFUSED, BROKEN_SAVED }

    fun rewrite(context: Context, uri: Uri, changed: Set<String>, change: (ExifInterface) -> Unit): Result {
        // Only photos of the phone's media list are changed in place.
        if (uri.authority != MediaStore.AUTHORITY) return Result.REFUSED
        val resolver = context.contentResolver
        val original = MediaStore.setRequireOriginal(uri)
        val dir = File(context.cacheDir, "inplace").apply { mkdirs() }
        val before = File.createTempFile("before", ".img", dir)
        val after = File.createTempFile("after", ".img", dir)
        try {
            // Throws without "media location" access: then nothing is changed rather than writing zeros.
            resolver.openInputStream(original)!!.use { input -> before.outputStream().use { input.copyTo(it) } }
            before.copyTo(after, overwrite = true)
            ExifInterface(after).also(change).saveAttributes()
            if (!same(before, after, changed)) return Result.REFUSED
        } catch (e: Exception) {
            before.delete()
            after.delete()
            return Result.REFUSED
        }
        try {
            if (write(context, original, after)) return Result.DONE
            // Half written: the photo goes back to exactly what it was.
            if (write(context, original, before)) return Result.REFUSED
            saveAside(context, uri, before)
            return Result.BROKEN_SAVED
        } finally {
            before.delete()
            after.delete()
        }
    }

    /** Every tag outside [changed] is the same, and everything after the metadata block is identical. */
    fun same(before: File, after: File, changed: Set<String>): Boolean = difference(before, after, changed) == null

    /** What differs besides [changed] (for the check and for tests), or null when nothing does. */
    fun difference(before: File, after: File, changed: Set<String>): String? {
        val a = ExifInterface(before)
        val b = ExifInterface(after)
        for (tag in Metadata.COPYABLE_TAGS + listOf(ExifInterface.TAG_ORIENTATION)) {
            if (tag in changed) continue
            // ExifInterface fills an empty "DateTime" when the date taken is written: that is all it adds.
            if (tag == ExifInterface.TAG_DATETIME && a.getAttribute(tag) == null && ExifInterface.TAG_DATETIME_ORIGINAL in changed) continue
            if (a.getAttribute(tag) != b.getAttribute(tag)) return "tag $tag: ${a.getAttribute(tag)} → ${b.getAttribute(tag)}"
        }
        val partsA = kept(before)
        val partsB = kept(after)
        if (partsA != null && partsB != null) {
            return if (sameBytes(before, partsA, after, partsB)) null else "bytes: $partsA (${before.length()}) vs $partsB (${after.length()})"
        }
        // WebP and other formats: the picture must still open, at the same size.
        val oa = bounds(before)
        val ob = bounds(after)
        return if (oa.first > 0 && oa == ob) null else "size $oa vs $ob"
    }

    private fun bounds(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    /**
     * The parts of the file that must not change: a JPEG without its start marker and its EXIF
     * segment (every other segment, the picture and anything after it — a live photo's video); a PNG
     * without its signature and eXIf chunk. Null for other formats.
     */
    fun kept(f: File): List<LongRange>? = RandomAccessFile(f, "r").use { r ->
        val len = r.length()
        if (len < 12) return null
        val head = ByteArray(8).also { r.readFully(it) }
        val out = ArrayList<LongRange>()
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
            var at = 2L
            while (at + 4 <= len) {
                r.seek(at)
                if (r.readUnsignedByte() != 0xFF) return null
                val marker = r.readUnsignedByte()
                if (marker == 0xDA || marker == 0xD9) break // the picture data begins: the rest stays as it is
                val size = r.readUnsignedShort()
                val end = at + 2 + size
                var exif = false
                if (marker == 0xE1 && size >= 8) {
                    val id = ByteArray(6).also { r.readFully(it) }
                    exif = String(id, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
                }
                if (!exif) out += at until end
                at = end
            }
            if (at < len) out += at until len
            return out
        }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (head.contentEquals(png)) {
            var at = 8L
            while (at + 12 <= len) {
                r.seek(at)
                val size = r.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { r.readFully(it) }.toString(Charsets.ISO_8859_1)
                val end = at + 12 + size
                if (type != "eXIf") out += at until minOf(end, len)
                at = end
            }
            if (at < len) out += at until len
            return out
        }
        return null
    }

    /** The bytes of [ra] in [a] and of [rb] in [b], each read one after the other, are the same (by SHA-256). */
    private fun sameBytes(a: File, ra: List<LongRange>, b: File, rb: List<LongRange>): Boolean {
        if (ra.sumOf { it.last - it.first + 1 } != rb.sumOf { it.last - it.first + 1 }) return false
        return digest(a, ra).contentEquals(digest(b, rb))
    }

    private fun digest(f: File, ranges: List<LongRange>): ByteArray = java.security.MessageDigest.getInstance("SHA-256").let { md ->
        RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(64 * 1024)
            for (range in ranges) {
                r.seek(range.first)
                var left = range.last - range.first + 1
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    r.readFully(buf, 0, n)
                    md.update(buf, 0, n)
                    left -= n
                }
            }
        }
        md.digest()
    }

    private fun write(context: Context, uri: Uri, from: File): Boolean = runCatching {
        // "wt": the old content is cut off, so no stale bytes stay at the end.
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> from.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    /** The untouched original, saved as a new photo in Pictures/Svitlyna when writing it back failed. */
    private fun saveAside(context: Context, uri: Uri, original: File) {
        runCatching {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "photo.jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, SaveCopy.copyName(name, "original", name.substringAfterLast('.', "jpg")))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Svitlyna/")
            }
            val copy = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(copy)!!.use { out -> original.inputStream().use { it.copyTo(out) } }
        }
    }
}
