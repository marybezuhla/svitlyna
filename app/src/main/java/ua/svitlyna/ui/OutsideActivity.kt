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
        if (uri == null) {
            finish()
            return
        }
        val prefs = Prefs(this)
        setContent {
            SvitlynaTheme(amoled = prefs.amoled) {
                var item by remember { mutableStateOf<MediaItem?>(null) }
                var editing by remember { mutableStateOf(editOnly) }
                val scope = rememberCoroutineScope()
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
                                // The app that asked for the edit gets the new copy.
                                setResult(RESULT_OK, Intent().setData(saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
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
                            scope.launch {
                                // As from the gallery: without location when that is switched on and possible.
                                val clean = prefs.removeLocation && Share.canClean(one)
                                Share.prepare(this@OutsideActivity, listOf(one), clean).intent?.let { startActivity(it) }
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
