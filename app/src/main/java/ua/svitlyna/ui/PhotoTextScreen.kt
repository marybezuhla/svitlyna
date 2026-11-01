package ua.svitlyna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.data.MediaImage
import ua.svitlyna.media.MediaItem
import ua.svitlyna.ocr.PhotoText
import ua.svitlyna.ocr.PhotoTextReader
import ua.svitlyna.ocr.TextRecognizer

/** Svitlyna's model of a photo for its own tools (text, checks before sending), from an Iris item. */
fun MediaImage.asItem(): MediaItem = MediaItem(
    id = id,
    isVideo = isVideo,
    dateMs = dateTaken,
    name = name,
    mime = mimeType,
    bucketId = bucketId,
    bucketName = bucketName,
    width = width,
    height = height,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    takenMs = dateTaken,
    // A photo of the phone's media list is addressed by its number (copies go next to it); anything
    // else (a vault photo, decrypted through the vault) through its own address.
    external = if (uri.authority == android.provider.MediaStore.AUTHORITY && id > 0) null else uri,
)

/**
 * Text on a photo (Svitlyna): recognised on the phone, drawn over the photo; words are chosen by
 * tapping or running a finger over them, then copied or shared. [highlight]: search keys whose
 * words are chosen at first (from search); otherwise everything is.
 */
@Composable
fun PhotoTextScreen(image: MediaImage, highlight: List<String> = emptyList(), onClose: () -> Unit) {
    val context = LocalContext.current
    var text by remember(image.id) { mutableStateOf<PhotoText?>(null) }
    var chosen by remember(image.id) { mutableStateOf(emptySet<Int>()) }
    var reading by remember(image.id) { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    DisposableEffect(Unit) {
        val recognizer = TextRecognizer.get(context)
        recognizer.hold()
        // The models' memory (about 60 MB) is freed when this screen closes.
        onDispose { CoroutineScope(Dispatchers.Default).launch { recognizer.letGo() } }
    }
    LaunchedEffect(image.id) {
        val read = PhotoTextReader.read(context, image.asItem())
        reading = false
        if (read == null || read.words.isEmpty()) {
            Toast.makeText(context, R.string.sv_no_text_found, Toast.LENGTH_SHORT).show()
            onClose()
        } else {
            text = read
            val found = if (highlight.isEmpty()) emptySet() else
                read.words.indices.filterTo(HashSet()) { ua.svitlyna.search.Words.matches(read.words[it].second.text, highlight) }
            chosen = found.ifEmpty { read.words.indices.toSet() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        text?.let { TextOverlay(it, chosen, { c -> chosen = c }, Modifier.fillMaxSize()) }
        Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close), tint = Color.White) }
        }
        TextBar(reading, text, chosen, { chosen = it }, onClose, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TextBar(
    reading: Boolean,
    text: PhotoText?,
    chosen: Set<Int>,
    onChosen: (Set<Int>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (reading || text == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Text(stringResource(R.string.sv_reading_text), color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                TextButton(onClick = onClose) { Text(stringResource(R.string.sv_stop), color = Color.White) }
            }
            return@Column
        }
        Text(
            pluralStringResource(R.plurals.sv_text_chosen, text.words.size, chosen.size, text.words.size) + " · " +
                stringResource(R.string.sv_text_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChosen(text.words.indices.toSet()) }) { Text(stringResource(R.string.sv_choose_all), color = Color.White) }
            TextButton(onClick = { onChosen(emptySet()) }) { Text(stringResource(R.string.sv_choose_none), color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.sv_text_on_photo), text.text(chosen)))
                // Android 13+ confirms copying itself.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.sv_copy, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.sv_copy), color = Color.White) }
            IconButton(enabled = chosen.isNotEmpty(), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.text(chosen))
                context.startActivity(Intent.createChooser(send, null))
            }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share), tint = Color.White) }
        }
    }
}

/**
 * The recognised words drawn over the photo: chosen ones filled, others outlined. A tap on a word
 * chooses it or not; a drag chooses every word between the first and the one under the finger.
 */
@Composable
private fun TextOverlay(text: PhotoText, chosen: Set<Int>, onChosen: (Set<Int>) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(chosen)
    val choose by rememberUpdatedState(onChosen)
    val accent = MaterialTheme.colorScheme.primary
    var size by remember { mutableStateOf(IntSize.Zero) }

    // The photo is drawn "fit": scaled to the screen and centred. Words are placed the same way.
    fun mapping(): Triple<Float, Float, Float> {
        val s = minOf(size.width / text.width.toFloat(), size.height / text.height.toFloat())
        return Triple(s, (size.width - text.width * s) / 2f, (size.height - text.height * s) / 2f)
    }

    fun hit(p: Offset): Int? {
        val (s, dx, dy) = mapping()
        val x = (p.x - dx) / s
        val y = (p.y - dy) / s
        val pad = 6 / s
        return text.words.indices.firstOrNull { i ->
            val r = text.words[i].second.box
            x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = hit(down.position) ?: return@awaitEachGesture
                    val before = current
                    val adding = start !in before
                    var dragging = false
                    var last: Int? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            if (dragging) choose(before)
                            return@awaitEachGesture
                        }
                        val change = event.changes.first()
                        if (!change.pressed) {
                            if (!dragging) {
                                change.consume()
                                choose(if (adding) before + start else before - start)
                            }
                            return@awaitEachGesture
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            change.consume()
                            val at = hit(change.position) ?: continue
                            if (at == last) continue
                            last = at
                            val range = (minOf(start, at)..maxOf(start, at)).toSet()
                            choose(if (adding) before + range else before - range)
                        }
                    }
                }
            },
    ) {
        val (s, dx, dy) = mapping()
        text.words.forEachIndexed { i, (_, word) ->
            val r = word.box
            val topLeft = Offset(dx + r.left * s - 2, dy + r.top * s - 2)
            val box = androidx.compose.ui.geometry.Size(r.width() * s + 4, r.height() * s + 4)
            if (i in chosen) drawRect(accent.copy(alpha = 0.35f), topLeft, box)
            drawRect(accent.copy(alpha = 0.9f), topLeft, box, style = Stroke(width = 1.5f))
        }
    }
}
