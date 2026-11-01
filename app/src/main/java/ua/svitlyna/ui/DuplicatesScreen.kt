package ua.svitlyna.ui

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.media.Duplicates

/** Exact duplicates: the one kept is marked; the extra copies can go to the trash in one go. */
@Composable
fun DuplicatesScreen(
    groups: List<Duplicates.Group>?,
    progress: Pair<Int, Int>?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTrashExtra: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.dupes_explain), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        when {
            progress != null -> {
                LinearProgressIndicator(
                    progress = { if (progress.second == 0) 0f else progress.first / progress.second.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.dupes_checking, progress.first, progress.second), Modifier.weight(1f))
                    TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                }
            }
            groups == null -> Button(onClick = onStart) { Text(stringResource(R.string.dupes_find)) }
            groups.isEmpty() -> Text(stringResource(R.string.dupes_none))
            else -> {
                val extra = groups.flatMap { it.extra }
                Text(
                    pluralStringResource(R.plurals.dupes_found, extra.size, extra.size, Formatter.formatShortFileSize(context, extra.sumOf { it.sizeBytes })),
                )
                Button(onClick = onTrashExtra, modifier = Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.dupes_trash)) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { it.keep.id }) { g ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (listOf(g.keep) + g.extra).forEachIndexed { i, item ->
                                Column {
                                    Thumbnail(item, 256, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                                    Text(
                                        stringResource(if (i == 0) R.string.dupes_keep else R.string.dupes_extra),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    Text(item.bucketName, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.size(width = 96.dp, height = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
