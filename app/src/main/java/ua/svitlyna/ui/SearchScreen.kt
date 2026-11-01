package ua.svitlyna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.search.Indexer
import ua.svitlyna.search.Search

/**
 * One line to search: text on photos, names of files and folders, dates in words. Below it, how far
 * the reading of photos is, with "Read now" (time estimate, Stop) and the switch to turn it off.
 */
@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    result: Search.Result,
    onOpen: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var on by remember { mutableStateOf(prefs.searchText) }
    var askErase by remember { mutableStateOf(false) }
    val progress by Indexer.progress.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        Indexer.refreshCount(context)
    }
    // When a reading pass ends (here or in the background), the count is made exact again.
    LaunchedEffect(progress.running) {
        if (!progress.running) Indexer.refreshCount(context)
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(focus),
        )
        if (on) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (progress.total > 0 && progress.done < progress.total) {
                    LinearProgressIndicator(
                        progress = { progress.done / progress.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress.total == 0) stringResource(R.string.search_counting)
                        else stringResource(R.string.search_read, progress.done, progress.total),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        progress.running -> TextButton(onClick = { Indexer.stop() }) { Text(stringResource(R.string.stop)) }
                        progress.total > progress.done -> TextButton(onClick = { Indexer.start(context) }) {
                            Text(stringResource(R.string.search_read_now, (progress.secondsLeft + 59) / 60))
                        }
                    }
                }
                if (progress.done < progress.total && !progress.running) {
                    Text(stringResource(R.string.search_background), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (query.isNotBlank()) {
            Text(
                summary(result),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(result.items, key = { _, it -> it.id }) { i, item ->
                    Thumbnail(item, 256, Modifier.aspectRatio(1f).clickable { onOpen(i) })
                }
            }
        } else {
            Column(Modifier.padding(16.dp).weight(1f)) {
                Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodySmall)
            }
        }
        // Reading text on photos: on (default) or off; off erases what was read.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.search_text_setting), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.search_text_explain), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = { want ->
                if (want) {
                    on = true
                    prefs.searchText = true
                    ua.svitlyna.search.IndexJob.schedule(context)
                } else {
                    askErase = true
                }
            })
        }
    }
    if (askErase && !LocalLocked.current) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askErase = false },
            text = { Text(stringResource(R.string.search_off_question)) },
            confirmButton = {
                TextButton(onClick = {
                    askErase = false
                    on = false
                    prefs.searchText = false
                    ua.svitlyna.search.IndexJob.schedule(context)
                    Indexer.erase(context)
                }) { Text(stringResource(R.string.search_off_erase)) }
            },
            dismissButton = { TextButton(onClick = { askErase = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun summary(r: Search.Result): String {
    val parts = ArrayList<String>()
    if (r.byText > 0) parts += stringResource(R.string.search_by_text, r.byText)
    if (r.byName > 0) parts += stringResource(R.string.search_by_name, r.byName)
    if (r.dated && r.keys.isEmpty()) parts += stringResource(R.string.search_by_date, r.items.size)
    return if (parts.isEmpty()) stringResource(R.string.search_nothing) else parts.joinToString(" · ")
}
