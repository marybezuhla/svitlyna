package ua.svitlyna.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.svitlyna.R
import ua.svitlyna.core.Prefs
import ua.svitlyna.media.MediaRepository
import ua.svitlyna.search.IndexJob
import ua.svitlyna.search.Indexer

/**
 * Svitlyna's search by text on photos, in the settings: on/off (off erases what was read), how far
 * the reading is, and "Read now" with a time estimate and Stop.
 */
@Composable
fun SearchSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.searchText) }
    var askErase by remember { mutableStateOf(false) }
    val progress by Indexer.progress.collectAsState()
    val scope = rememberCoroutineScope()
    suspend fun count() {
        val items = withContext(Dispatchers.IO) { runCatching { MediaRepository(context.contentResolver).loadAll() }.getOrDefault(emptyList()) }
        Indexer.refreshCount(context, items)
    }
    LaunchedEffect(progress.running) { if (!progress.running) count() }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.sv_search_text_setting), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.sv_search_text_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { want ->
                if (want) {
                    on = true
                    prefs.searchText = true
                    IndexJob.schedule(context)
                    scope.launch { count() }
                } else {
                    askErase = true
                }
            })
        }
        if (on) {
            if (progress.total > 0 && progress.done < progress.total) {
                LinearProgressIndicator(
                    progress = { progress.done / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        progress.total > 0 -> stringResource(R.string.sv_search_read, progress.done, progress.total)
                        progress.counted -> stringResource(R.string.sv_search_none_to_read)
                        else -> stringResource(R.string.sv_search_counting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    progress.running -> TextButton(onClick = { Indexer.stop() }) { Text(stringResource(R.string.sv_stop)) }
                    progress.total > progress.done -> TextButton(onClick = { Indexer.start(context) }) {
                        val minutes = (progress.secondsLeft + 59) / 60
                        Text(
                            if (minutes > 90) stringResource(R.string.sv_search_read_now_hours, (minutes + 59) / 60)
                            else stringResource(R.string.sv_search_read_now, minutes),
                        )
                    }
                }
            }
            if (progress.done < progress.total) {
                Text(
                    stringResource(if (progress.running) R.string.sv_search_while_open else R.string.sv_search_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (askErase) {
        AlertDialog(
            onDismissRequest = { askErase = false },
            text = { Text(stringResource(R.string.sv_search_off_question)) },
            confirmButton = {
                TextButton(onClick = {
                    askErase = false
                    on = false
                    prefs.searchText = false
                    IndexJob.schedule(context)
                    Indexer.eraseSoon(context)
                }) { Text(stringResource(R.string.sv_search_off_erase)) }
            },
            dismissButton = { TextButton(onClick = { askErase = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
