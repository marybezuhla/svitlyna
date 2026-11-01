package ua.svitlyna.ui

import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.svitlyna.R
import ua.svitlyna.edit.FixDates
import ua.svitlyna.media.MediaRepository

/**
 * Svitlyna's "Fix date taken", in the settings: photos from messengers that lost their date get it
 * from their file name, written into the file after Android's permission (only the date tags).
 */
@Composable
fun FixDatesTool() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fixes by remember { mutableStateOf<List<FixDates.Fix>?>(null) }
    var working by remember { mutableStateOf(false) }
    suspend fun look() {
        fixes = withContext(Dispatchers.IO) {
            runCatching { FixDates.candidates(MediaRepository(context.contentResolver).loadAll()) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { look() }
    var pending by remember { mutableStateOf<List<FixDates.Fix>>(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val todo = pending
        pending = emptyList()
        if (result.resultCode != android.app.Activity.RESULT_OK || todo.isEmpty()) {
            working = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val done = FixDates.apply(context, todo)
            working = false
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.sv_fix_dates_done, done, done), Toast.LENGTH_LONG).show()
            look()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sv_fix_dates), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.sv_fix_dates_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = fixes
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                list == null || working -> CircularProgressIndicator()
                list.isEmpty() -> Text(stringResource(R.string.sv_fix_dates_none), style = MaterialTheme.typography.bodySmall)
                else -> OutlinedButton(onClick = {
                    // Android passes the list in one message of limited size: at most 500 at a time.
                    val todo = list.take(500)
                    runCatching { MediaStore.createWriteRequest(context.contentResolver, todo.map { it.item.uri }) }
                        .onSuccess { pi ->
                            pending = todo
                            working = true
                            ask.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                }) { Text(pluralStringResource(R.plurals.sv_fix_dates_button, minOf(list.size, 500), minOf(list.size, 500))) }
            }
        }
    }
}
