package ua.svitlyna.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

/**
 * Changing a few tags of the person's own photo, safely:
 * 1. the original is copied into the app's cache (read as the original, so the place is real);
 * 2. the tags are changed in the copy;
 * 3. the copy is checked: every other tag the same, the end of the file (pictures, the video of a
 *    live photo) byte for byte the same;
 * 4. only then the photo is written over, and if that fails half way, it is put back as it was.
 */
object InPlace {
    private const val TAIL = 64 * 1024

    fun rewrite(context: Context, uri: Uri, changed: Set<String>, change: (ExifInterface) -> Unit): Boolean {
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
            if (!same(before, after, changed)) return false
            if (write(context, original, after)) return true
            // Half written: the photo goes back to exactly what it was.
            write(context, original, before)
            return false
        } catch (e: Exception) {
            return false
        } finally {
            before.delete()
            after.delete()
        }
    }

    /** Every tag outside [changed] is the same, and the files end with the same bytes. */
    fun same(before: File, after: File, changed: Set<String>): Boolean {
        val a = ExifInterface(before)
        val b = ExifInterface(after)
        for (tag in Metadata.COPYABLE_TAGS + listOf(ExifInterface.TAG_ORIENTATION)) {
            if (tag in changed) continue
            if (a.getAttribute(tag) != b.getAttribute(tag)) return false
        }
        // WebP keeps its EXIF near the end of the file: there only the tags are compared.
        if (isWebp(before)) return true
        val n = minOf(TAIL.toLong(), before.length(), after.length()).toInt()
        return tail(before, n).contentEquals(tail(after, n))
    }

    private fun isWebp(f: File): Boolean = RandomAccessFile(f, "r").use { r ->
        if (r.length() < 12) return false
        val head = ByteArray(12).also { r.readFully(it) }
        String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"
    }

    private fun tail(f: File, n: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
        ByteArray(n).also { r.seek(r.length() - n); r.readFully(it) }
    }

    private fun write(context: Context, uri: Uri, from: File): Boolean = runCatching {
        // "wt": the old content is cut off, so no stale bytes stay at the end.
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> from.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)
}
