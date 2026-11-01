package ua.svitlyna.ui

import android.content.Context
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.core.Prefs
import ua.svitlyna.data.MediaImage
import ua.svitlyna.data.isScreenshot

/**
 * Temporary screenshots (off by default): screenshots older than the chosen number of days are
 * offered for the trash, all at once, with Android's own confirmation; nothing goes without a tap.
 * "Keep" in the viewer takes a screenshot out of the offer. The state is shared by the banner and
 * the viewer, so a tap in one shows in the other at once.
 */
object TempShots {
    private var loaded = false
    var enabled by mutableStateOf(false)
        private set
    var days by mutableStateOf(30)
        private set
    var kept by mutableStateOf(emptySet<Long>())
        private set
    var laterUntil by mutableStateOf(0L)
        private set

    fun load(context: Context) {
        if (loaded) return
        val p = Prefs(context)
        enabled = p.tempScreenshots
        days = p.tempDays
        kept = p.keptScreenshots
        laterUntil = p.tempLaterUntil
        loaded = true
    }

    fun setEnabled(context: Context, on: Boolean) { load(context); enabled = on; Prefs(context).tempScreenshots = on }
    fun setDays(context: Context, d: Int) { load(context); days = d; Prefs(context).tempDays = d }

    fun toggleKept(context: Context, id: Long) {
        load(context)
        kept = if (id in kept) kept - id else kept + id
        Prefs(context).keptScreenshots = kept
    }

    /** Asked again in a week, not at every start. */
    fun later(context: Context) {
        laterUntil = System.currentTimeMillis() + 7 * 86_400_000L
        Prefs(context).tempLaterUntil = laterUntil
    }

    // "Keep" marks are a few numbers each; they are never pruned from a partial list (a filtered
    // or still-loading one would wipe them and offer kept screenshots again).

    /** Screenshots (not screen recordings, not favourites) older than the chosen days. */
    fun old(images: List<MediaImage>, favorites: Set<Long>, now: Long = System.currentTimeMillis()): List<MediaImage> {
        if (!enabled) return emptyList()
        val limit = now - days * 86_400_000L
        return images.filter {
            it.id > 0 && !it.isVideo && it.isScreenshot && it.dateTaken in 1 until limit && it.id !in kept && it.id !in favorites
        }
    }

    /** For the viewer: null when this is not a screenshot or the option is off. */
    fun isKept(context: Context, item: MediaImage): Boolean? {
        load(context)
        return if (enabled && !item.isVideo && item.isScreenshot && item.id > 0) item.id in kept else null
    }
}

@Composable
fun BoxScope.TempScreenshotsBanner(
    images: List<MediaImage>,
    favorites: Set<Long>,
    visible: Boolean,
    padding: PaddingValues,
    onTrash: (List<MediaImage>) -> Unit,
) {
    val context = LocalContext.current
    remember { TempShots.load(context); true }
    val enabled = TempShots.enabled
    val days = TempShots.days
    val kept = TempShots.kept
    val old = remember(images, favorites, enabled, days, kept) { TempShots.old(images, favorites) }
    var hidden by remember { mutableStateOf(false) }
    if (!visible || hidden || old.isEmpty() || System.currentTimeMillis() < TempShots.laterUntil) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(padding).padding(12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.sv_old_screenshots, old.size, old.size, days), modifier = Modifier.weight(1f))
            TextButton(onClick = { hidden = true; TempShots.later(context) }) { Text(stringResource(R.string.sv_later)) }
            TextButton(onClick = { hidden = true; onTrash(old) }) { Text(stringResource(R.string.sv_to_trash)) }
        }
    }
}
