package ua.svitlyna.ui

import android.content.ClipData
import android.content.Intent
import ua.svitlyna.media.MediaItem

/**
 * Svitlyna as the photo chooser for other apps ("attach a photo"). The whole gallery is shown, so
 * the app lock applies here too. The chosen photos are handed over for reading only; Android leaves
 * the location out for an app that has no access to it.
 */
class PickActivity : MainActivity() {
    override fun pickRequest(): PickRequest = PickRequest(
        types = types(intent),
        multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
        onPicked = ::deliver,
    )

    private fun deliver(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
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
        val type = intent.type
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
