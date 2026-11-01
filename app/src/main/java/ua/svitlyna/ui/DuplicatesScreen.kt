package ua.svitlyna.ui

import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedButton
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
import ua.svitlyna.media.MediaItem

/**
 * Exact duplicates and similar photos. The person decides: a tap marks a photo for the trash or
 * takes the mark off. Exact copies come marked (all but the suggested original); similar ones don't.
 */
@Composable
fun DuplicatesScreen(
    groups: List<Duplicates.Group>?,
    progress: Pair<Int, Int>?,
    photoCount: Int,
    marked: Set<Long>,
    onMark: (MediaItem) -> Unit,
    onStart: (similar: Boolean) -> Unit,
    onStop: () -> Unit,
    onTrashMarked: () -> Unit,
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
            else -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStart(false) }) { Text(stringResource(R.string.dupes_find)) }
                    val minutes = (Duplicates.similarSeconds(photoCount) + 59) / 60
                    OutlinedButton(onClick = { onStart(true) }) { Text(stringResource(R.string.dupes_find_similar, minutes)) }
                }
                when {
                    groups == null -> {}
                    groups.isEmpty() -> Text(stringResource(R.string.dupes_none), Modifier.padding(top = 12.dp))
                    else -> {
                        val all = groups.flatMap { it.items }.filter { it.id in marked }.distinctBy { it.id }
                        Text(stringResource(R.string.dupes_tap), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        Button(enabled = all.isNotEmpty(), onClick = onTrashMarked, modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(pluralStringResource(R.plurals.dupes_trash_marked, all.size, all.size, Formatter.formatShortFileSize(context, all.sumOf { it.sizeBytes })))
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(groups, key = { it.best.id }) { g ->
                                Column {
                                    Text(
                                        stringResource(if (g.exact) R.string.dupes_same else R.string.dupes_similar),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    // Lazy: only the previews on screen are loaded, however large the group.
                                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(g.items, key = { it.id }) { item -> Tile(item, item.id in marked, item === g.best) { onMark(item) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(item: MediaItem, toTrash: Boolean, best: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            Thumbnail(
                item,
                256,
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (toTrash) Modifier.border(3.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp)) else Modifier),
            )
        }
        Text(
            stringResource(
                when {
                    toTrash -> R.string.dupes_to_trash
                    best -> R.string.dupes_best
                    else -> R.string.dupes_keep
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (toTrash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            "${item.width}×${item.height} · " + Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.size(width = 96.dp, height = 16.dp),
        )
        Text(item.bucketName, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.size(width = 96.dp, height = 16.dp))
    }
}
