package ua.svitlyna.media

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sharing without location. Photos in formats whose metadata can be rewritten (JPEG, PNG, WebP) are
 * copied into the app's private cache, the location is removed from the copy, and only the copy is
 * shared. Videos (MP4, MOV, 3GP) are copied with their notes boxes emptied ([VideoClean]). The
 * original is never changed. Other files (HEIC, GIF…) can't be cleaned yet: they are reported so
 * the person decides.
 */
object Share {
    private val CLEANABLE = setOf("image/jpeg", "image/png", "image/webp")
    private val CLEANABLE_VIDEO = setOf("video/mp4", "video/quicktime", "video/3gpp")

    fun canClean(item: MediaItem) = item.mime.lowercase() in (if (item.isVideo) CLEANABLE_VIDEO else CLEANABLE)

    /** The share intent, or null when nothing is left to share; [skipped] photos could not be cleaned. */
    class Prepared(val intent: Intent?, val skipped: Int)

    /**
     * With [removeLocation], cleanable photos are shared as cleaned copies, and a photo whose copy
     * could not be cleaned is left out rather than sent with its location. Files that can't be cleaned
     * at all go as they are: the caller has already asked about them.
     */
    suspend fun prepare(context: Context, items: List<MediaItem>, removeLocation: Boolean): Prepared =
        withContext(Dispatchers.IO) {
            cleanup(context)
            // Each share gets its own folder: the app receiving an earlier share may still be reading it.
            val dir = File(File(context.cacheDir, "share"), System.currentTimeMillis().toString())
            dir.mkdirs()
            var skipped = 0
            val uris = items.mapIndexedNotNull { i, item ->
                if (removeLocation && canClean(item)) {
                    cleanCopy(context, item, File(dir, "$i-${safeName(item)}")) ?: run { skipped++; null }
                } else {
                    item.uri
                }
            }
            if (uris.isEmpty()) return@withContext Prepared(null, skipped)
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = commonType(items)
            intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Prepared(Intent.createChooser(intent, null), skipped)
        }

    private fun cleanCopy(context: Context, item: MediaItem, target: File): Uri? = runCatching {
        if (item.isVideo) {
            // Not enough room for a copy of a big video: not sent with its location instead.
            check(context.cacheDir.usableSpace > item.sizeBytes + 50L * 1024 * 1024)
            val ok = context.contentResolver.openInputStream(item.uri)!!.use { input ->
                target.outputStream().use { VideoClean.clean(input, it) }
            }
            check(ok)
            return@runCatching FileProvider.getUriForFile(context, "${context.packageName}.share", target)
        }
        context.contentResolver.openInputStream(item.uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        val exif = ExifInterface(target)
        Metadata.removeLocation(exif)
        exif.saveAttributes()
        FileProvider.getUriForFile(context, "${context.packageName}.share", target)
    }.getOrElse {
        target.delete()
        null
    }

    private fun safeName(item: MediaItem): String =
        item.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").ifBlank { "photo" }.take(80)

    /** Removes shared copies older than an hour (by then the receiving app has long read them). */
    fun cleanup(context: Context) {
        val root = File(context.cacheDir, "share")
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        root.listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
    }

    private fun commonType(items: List<MediaItem>): String = when {
        items.all { !it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
}
