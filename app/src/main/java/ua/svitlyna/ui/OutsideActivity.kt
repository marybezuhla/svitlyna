package ua.svitlyna.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.Outside
import ua.svitlyna.media.Share

/**
 * A photo or video opened from another app (a file manager, a messenger): shown on its own, with
 * sharing, details and editing into a copy. The gallery itself is not reachable from here, so the
 * app lock is not needed; nothing in the gallery is changed or removed.
 */
class OutsideActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri: Uri? = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val editOnly = intent.action == Intent.ACTION_EDIT
        // Svitlyna's own shared copies are not shown to whoever sends their address back.
        if (uri == null || uri.authority == "$packageName.share") {
            finish()
            return
        }
        // An address in the phone's media list could be sent by any app, even one with no access to
        // photos: Svitlyna reads it with its own access. So with the app lock on, it asks first,
        // and an edited copy of such a photo is never handed back to the app that asked.
        val fromGallery = uri.authority == android.provider.MediaStore.AUTHORITY
        val prefs = Prefs(this)
        setContent {
            SvitlynaTheme(amoled = prefs.amoled) {
                var item by remember { mutableStateOf<MediaItem?>(null) }
                var editing by remember { mutableStateOf(editOnly) }
                var unlocked by remember { mutableStateOf(!(fromGallery && AppLock.shouldLock(prefs.appLock))) }
                var askShare by remember { mutableStateOf<MediaItem?>(null) }
                val scope = rememberCoroutineScope()
                if (!unlocked) {
                    androidx.activity.compose.BackHandler { finish() }
                    val open = {
                        AppLock.ask(this@OutsideActivity, onNoScreenLock = { unlocked = true }) {
                            AppLock.unlocked = true
                            AppLock.leftAt = 0
                            unlocked = true
                        }
                    }
                    LaunchedEffect(Unit) { open() }
                    LockScreen(onUnlock = open)
                    return@SvitlynaTheme
                }
                fun send(one: MediaItem, clean: Boolean) {
                    scope.launch {
                        val prepared = Share.prepare(this@OutsideActivity, listOf(one), clean)
                        if (prepared.skipped > 0) {
                            Toast.makeText(this@OutsideActivity, resources.getQuantityString(R.plurals.share_skipped, 1, 1), Toast.LENGTH_LONG).show()
                        }
                        prepared.intent?.let { startActivity(it) }
                    }
                }
                askShare?.let { one ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { askShare = null },
                        title = { androidx.compose.material3.Text(stringResource(R.string.share_location_title)) },
                        text = { androidx.compose.material3.Text(resources.getQuantityString(R.plurals.share_location_text, 1, 1)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { askShare = null; send(one, clean = false) }) {
                                androidx.compose.material3.Text(stringResource(R.string.share_as_is))
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { askShare = null }) { androidx.compose.material3.Text(stringResource(R.string.cancel)) }
                        },
                    )
                }
                LaunchedEffect(uri) {
                    item = Outside.item(this@OutsideActivity, uri, intent.type)
                    if (item == null) {
                        Toast.makeText(this@OutsideActivity, R.string.outside_failed, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                val current = item
                when {
                    current == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                    editing && !current.isVideo -> EditorScreen(
                        current,
                        onClose = { if (editOnly) finish() else editing = false },
                        onSaved = { saved ->
                            if (editOnly) {
                                // The app that asked for the edit gets the new copy, unless it sent a
                                // gallery address (it might have had no right to that photo at all).
                                if (fromGallery) setResult(RESULT_OK) else {
                                    setResult(RESULT_OK, Intent().setData(saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }
                                finish()
                            } else {
                                Toast.makeText(this@OutsideActivity, R.string.edit_saved, Toast.LENGTH_LONG).show()
                                editing = false
                            }
                        },
                    )
                    else -> Viewer(
                        items = listOf(current),
                        startIndex = 0,
                        onClose = ::finish,
                        onShare = { one ->
                            // As from the gallery: without location when that is on; asked when it can't be removed.
                            when {
                                !prefs.removeLocation -> send(one, clean = false)
                                Share.canClean(one) -> send(one, clean = true)
                                else -> askShare = one
                            }
                        },
                        onTrash = {},
                        onEdit = { editing = true },
                        outside = true,
                    )
                }
            }
        }
    }
}
