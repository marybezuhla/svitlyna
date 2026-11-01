package ua.svitlyna.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.edit.MetaEdit
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.PhotoDetails
import java.time.Instant
import java.time.ZoneId

/** Date taken and place of one photo, typed in; written into the file after Android's permission. */
@Composable
fun MetaEditDialog(item: MediaItem, details: PhotoDetails?, onDismiss: () -> Unit, onSave: (MetaEdit.Change) -> Unit) {
    val startDate = remember(item.id) {
        MetaEdit.formatDate(Instant.ofEpochMilli(item.dateMs).atZone(ZoneId.systemDefault()).toLocalDateTime())
    }
    val startPlace = remember(item.id) {
        if (details?.lat != null && details.lon != null) "%.5f, %.5f".format(java.util.Locale.ROOT, details.lat, details.lon) else ""
    }
    var date by remember(item.id) { mutableStateOf(startDate) }
    var place by remember(item.id) { mutableStateOf(startPlace) }
    var remove by remember(item.id) { mutableStateOf(false) }
    val dateOk = MetaEdit.parseDate(date) != null
    val placeOk = remove || place.isBlank() || MetaEdit.parsePlace(place) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meta_edit)) },
        text = {
            Column {
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    singleLine = true,
                    isError = !dateOk,
                    label = { Text(stringResource(R.string.meta_date)) },
                    supportingText = { Text("2024-05-01 14:30") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    singleLine = true,
                    enabled = !remove,
                    isError = !placeOk,
                    label = { Text(stringResource(R.string.meta_place)) },
                    supportingText = { Text(stringResource(R.string.meta_place_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (startPlace.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = remove, onCheckedChange = { remove = it })
                        Text(stringResource(R.string.meta_remove_place))
                    }
                }
                Text(stringResource(R.string.meta_explain), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = dateOk && placeOk,
                onClick = {
                    val change = MetaEdit.Change(
                        taken = MetaEdit.parseDate(date).takeIf { date.trim() != startDate },
                        place = if (remove || place.trim() == startPlace) null else MetaEdit.parsePlace(place),
                        removePlace = remove || (place.isBlank() && startPlace.isNotEmpty()),
                    )
                    if (change == MetaEdit.Change()) onDismiss() else onSave(change)
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
