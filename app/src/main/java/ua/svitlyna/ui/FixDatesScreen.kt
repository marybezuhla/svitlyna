package ua.svitlyna.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.edit.FixDates
import java.text.DateFormat
import java.util.Date

/** "Fix date taken": what will change ("was → will be"), chosen photos only, then Android asks. */
@Composable
fun FixDatesScreen(
    fixes: List<FixDates.Fix>,
    chosen: Set<Long>,
    onChosen: (Set<Long>) -> Unit,
    onFix: () -> Unit,
    busy: Boolean,
    contentPadding: PaddingValues,
) {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val dateOnly = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text(
            stringResource(R.string.fix_dates_explain),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        if (fixes.isEmpty()) {
            Text(stringResource(R.string.fix_dates_none), modifier = Modifier.padding(16.dp))
            return@Column
        }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChosen(fixes.map { it.item.id }.toSet()) }) { Text(stringResource(R.string.choose_all)) }
            TextButton(onClick = { onChosen(emptySet()) }) { Text(stringResource(R.string.choose_none)) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Button(enabled = chosen.isNotEmpty() && !busy, onClick = onFix) {
                Text(pluralStringResource(R.plurals.fix_dates_button, chosen.size, chosen.size))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(fixes, key = { it.item.id }) { fix ->
                val on = fix.item.id in chosen
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChosen(if (on) chosen - fix.item.id else chosen + fix.item.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = on, onCheckedChange = { onChosen(if (it) chosen + fix.item.id else chosen - fix.item.id) })
                    Thumbnail(fix.item, 160, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(fix.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.fix_dates_was, format.format(Date(fix.item.dateMs))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(
                                if (fix.date.hasTime) R.string.fix_dates_will else R.string.fix_dates_will_date_only,
                                if (fix.date.hasTime) format.format(Date(fix.newMs)) else dateOnly.format(Date(fix.newMs)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
