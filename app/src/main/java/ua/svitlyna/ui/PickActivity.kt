package ua.svitlyna.ui

import android.content.ClipData
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.media.MediaItem

/**
 * Svitlyna as the photo chooser for other apps ("attach a photo"). The whole gallery is shown, so
 * the app lock applies here too. The chosen photos are handed over for reading only: as cleaned
 * copies when "Send without location" is on, otherwise as they are (Android itself leaves the
 * location out for an app that has no access to it).
 */
class PickActivity : MainActivity() {
    override fun pickRequest(): PickRequest = PickRequest(
        types = types(intent),
        multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        onPicked = ::deliver,
    )

    private var delivering = false

    /**
     * With "Send without location" on, the other app gets cleaned copies (as when sharing); a file
     * that can't be cleaned is not handed over, and the person is told.
     */
    private fun deliver(items: List<MediaItem>) {
        if (items.isEmpty() || delivering) return
        delivering = true
        lifecycleScope.launch {
            val clean = Prefs(this@PickActivity).removeLocation
            val ok = if (clean) items.filter { ua.svitlyna.media.Share.canClean(it) } else items
            val left = items.size - ok.size
            val (uris, skipped) = ua.svitlyna.media.Share.uris(this@PickActivity, ok, clean)
            if (left + skipped > 0) {
                android.widget.Toast.makeText(
                    this@PickActivity,
                    resources.getQuantityString(R.plurals.share_skipped, left + skipped, left + skipped),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            delivering = false
            if (uris.isNotEmpty()) hand(uris)
        }
    }

    private fun hand(uris: List<android.net.Uri>) {
        val result = Intent().apply {
            data = uris.first()
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun types(intent: Intent): List<String> {
        val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty()
        // PICK may come with only an address: …/images/… or …/video/… says what is wanted.
        val path = intent.data?.path.orEmpty()
        val fromData = when {
            "/images" in path -> "image/*"
            "/video" in path -> "video/*"
            else -> null
        }
        val type = intent.type ?: fromData
        return (extra + listOfNotNull(type))
            .map { it.lowercase() }
            // "vnd.android.cursor.dir/image" is the old way of asking for any photo.
            .map { if (it.endsWith("/image")) "image/*" else if (it.endsWith("/video")) "video/*" else it }
            .ifEmpty { listOf("image/*", "video/*") }
    }
}

/** What another app asked for: which kinds of files ([types], with wildcards) and whether several. */
class PickRequest(val types: List<String>, val multiple: Boolean, val onPicked: (List<MediaItem>) -> Unit) {
    fun accepts(item: MediaItem): Boolean = types.any { t ->
        t == "*/*" || t == item.mime.lowercase() || (t.endsWith("/*") && item.mime.lowercase().startsWith(t.dropLast(1)))
    }

    val photos: Boolean get() = types.any { it == "*/*" || it.startsWith("image/") }
    val videos: Boolean get() = types.any { it == "*/*" || it.startsWith("video/") }
}
