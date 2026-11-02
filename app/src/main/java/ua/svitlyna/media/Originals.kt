package ua.svitlyna.media

import android.content.Context
import java.io.File

/**
 * Originals kept after "save over the original": in the app's private storage, 30 days and at most
 * 2 GB in all (whichever comes first), the oldest go first. Named "<photo number>_<time>.<ext>".
 */
object Originals {
    private const val DAYS = 30L
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "originals").apply { mkdirs() }

    /** Moves [backup] (the untouched original of photo [id]) into the kept originals. */
    fun keep(context: Context, id: Long, ext: String, backup: File): Boolean {
        val safeExt = ext.lowercase().filter { it.isLetterOrDigit() }.take(5).ifBlank { "img" }
        val target = File(dir(context), "${id}_${System.currentTimeMillis()}.$safeExt")
        val moved = backup.renameTo(target) || runCatching { backup.copyTo(target); backup.delete(); true }.getOrDefault(false)
        cleanup(context)
        return moved
    }

    fun cleanup(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        val old = System.currentTimeMillis() - DAYS * 86_400_000L
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (f.lastModified() < old || total > MAX_BYTES) {
                total -= f.length()
                f.delete()
            }
        }
    }
}
