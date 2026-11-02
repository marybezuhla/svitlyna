package ua.svitlyna.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.data.MediaImage
import ua.svitlyna.media.PhotoTools
import ua.svitlyna.media.SaveCopy

/** Which tool was chosen for which photos (from the selection menu). */
class PhotoToolsState {
    var convert by mutableStateOf<List<MediaImage>?>(null)
    var pdf by mutableStateOf<List<MediaImage>?>(null)
}

/**
 * The dialogs of the photo tools: choosing size and format (with the time it will take, shown
 * before starting), and progress with "Stop" while the copies or the PDF are made.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhotoToolsHost(state: PhotoToolsState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf(0L) }

    fun run(name: String, count: Int, block: suspend ((Int) -> Unit) -> Unit) {
        title = name
        done = 0
        total = count
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            try {
                block { done = it }
            } catch (e: CancellationException) {
                Toast.makeText(context, R.string.sv_tools_stopped, Toast.LENGTH_SHORT).show()
            } finally {
                job = null
            }
        }
    }

    state.convert?.let { items ->
        val photos = remember(items) { items.map { it.asItem() } }
        var format by remember { mutableStateOf(SaveCopy.Format.JPEG) }
        var side by remember { mutableIntStateOf(2048) }
        var quality by remember { mutableFloatStateOf(90f) }
        AlertDialog(
            onDismissRequest = { state.convert = null },
            title = { Text(stringResource(R.string.sv_convert_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sv_convert_format), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in SaveCopy.Format.entries) {
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.ext.uppercase()) })
                        }
                    }
                    Text(stringResource(R.string.sv_convert_size), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (s in listOf(0, 4096, 2048, 1280)) {
                            FilterChip(
                                selected = side == s,
                                onClick = { side = s },
                                label = { Text(if (s == 0) stringResource(R.string.sv_convert_as_is) else "$s px") },
                            )
                        }
                    }
                    if (format != SaveCopy.Format.PNG) {
                        Text(stringResource(R.string.sv_convert_quality, quality.toInt()), style = MaterialTheme.typography.labelLarge)
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 50f..100f, steps = 9)
                    }
                    Text(
                        stringResource(R.string.sv_convert_note) + " " +
                            stringResource(R.string.sv_tools_estimate, PhotoTools.estimateSeconds(photos)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    state.convert = null
                    val f = format; val s = side; val q = quality.toInt()
                    run(context.getString(R.string.sv_convert_title), photos.size) { progress ->
                        val saved = PhotoTools.convert(context, photos, f, s, q, progress)
                        Toast.makeText(context, context.resources.getQuantityString(R.plurals.sv_convert_done, saved, saved), Toast.LENGTH_LONG).show()
                    }
                }) { Text(pluralStringResource(R.plurals.sv_convert_start, photos.size, photos.size)) }
            },
            dismissButton = { TextButton(onClick = { state.convert = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    state.pdf?.let { items ->
        val photos = remember(items) { items.map { it.asItem() } }
        AlertDialog(
            onDismissRequest = { state.pdf = null },
            title = { Text(stringResource(R.string.sv_pdf_title)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.sv_pdf_explain, photos.size, photos.size) + " " +
                        stringResource(R.string.sv_tools_estimate, PhotoTools.estimateSeconds(photos)),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.pdf = null
                    run(context.getString(R.string.sv_pdf_title), photos.size) { progress ->
                        val uri = PhotoTools.pdf(context, photos, progress = progress)
                        Toast.makeText(context, if (uri != null) R.string.sv_pdf_saved else R.string.sv_pdf_failed, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.sv_pdf_make)) }
            },
            dismissButton = { TextButton(onClick = { state.pdf = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (job != null) {
        val left = if (done > 0) (((System.currentTimeMillis() - startedAt) / done) * (total - done) / 1000).toInt() else -1
        AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (left >= 0) stringResource(R.string.sv_tools_progress, done, total, left)
                        else stringResource(R.string.sv_tools_progress_start, done, total),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.sv_stop)) } },
        )
    }
}
