package ua.svitlyna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ua.svitlyna.ocr.PhotoText
import ua.svitlyna.ocr.PhotoTextReader
import ua.svitlyna.ocr.TextRecognizer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import ua.svitlyna.R
import ua.svitlyna.media.Images
import ua.svitlyna.media.MediaItem
import java.text.DateFormat
import java.util.Date

/** Full-screen photos and videos, swiped left and right. Tap hides the buttons; pinch or double tap zooms. */
@Composable
fun Viewer(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (MediaItem) -> Unit,
    onTrash: (MediaItem) -> Unit,
) {
    BackHandler(onBack = onClose)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MediaItem?>(null) }

    // Text on the photo: read on request, for the photo on screen only.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var textFor by remember { mutableStateOf<Long?>(null) }
    var reading by remember { mutableStateOf<Job?>(null) }
    var photoText by remember { mutableStateOf<PhotoText?>(null) }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val textMode = textFor != null
    DisposableEffect(Unit) {
        val recognizer = TextRecognizer.get(context)
        recognizer.hold()
        // The models' memory (about 60 MB) is freed when the viewer closes.
        onDispose { CoroutineScope(Dispatchers.Default).launch { recognizer.letGo() } }
    }

    fun closeText() {
        reading?.cancel()
        reading = null
        textFor = null
        photoText = null
        chosen = emptySet()
    }

    fun readText(item: MediaItem) {
        closeText()
        notice = null
        textFor = item.id
        reading = scope.launch {
            val read = PhotoTextReader.read(context, item)
            reading = null
            if (read == null || read.words.isEmpty()) {
                notice = R.string.no_text_found
                textFor = null
            } else {
                photoText = read
                // Everything is chosen at first: the most common wish is "all of it".
                chosen = read.words.indices.toSet()
            }
        }
    }
    // Another photo swiped in (or the list changed under it): its text is not the one shown.
    LaunchedEffect(items.getOrNull(pager.currentPage)?.id) {
        if (textFor != null && items.getOrNull(pager.currentPage)?.id != textFor) closeText()
        notice = null
    }
    BackHandler(enabled = textMode) { closeText() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed && !textMode,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val current = pager.currentPage == page
            if (item.isVideo) {
                VideoPage(item, current, onTap = { chrome = !chrome })
            } else {
                ZoomableImage(
                    item = item,
                    onTap = { chrome = !chrome },
                    onZoomChanged = { if (current) zoomed = it },
                    text = photoText.takeIf { textFor == item.id },
                    chosen = chosen,
                    onChosen = { chosen = it },
                )
            }
        }
        val item = items.getOrNull(pager.currentPage)
        if (item != null && textMode) {
            TextBar(
                reading = reading != null,
                text = photoText,
                chosen = chosen,
                onChosen = { chosen = it },
                onClose = ::closeText,
                onCopied = { notice = R.string.copied },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (chrome && item != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .statusBarsPadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateMs)),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .navigationBarsPadding(),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.align(Alignment.Center).padding(vertical = 4.dp),
                ) {
                    IconButton(onClick = { onShare(item) }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.share), tint = Color.White)
                    }
                    if (!item.isVideo) {
                        TextButton(onClick = { readText(item) }) {
                            Text(stringResource(R.string.text_on_photo), color = Color.White)
                        }
                    }
                    IconButton(onClick = { info = item }) {
                        Icon(Icons.Filled.Info, stringResource(R.string.details), tint = Color.White)
                    }
                    IconButton(onClick = { onTrash(item) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.move_to_trash), tint = Color.White)
                    }
                }
            }
        }
    }
    notice?.let { res ->
        LaunchedEffect(res) {
            kotlinx.coroutines.delay(2500)
            notice = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(res),
                color = Color.White,
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(16.dp),
            )
        }
    }
    info?.let { InfoDialog(it) { info = null } }
}

/** The bar while choosing text: what is chosen, all or none, copy, share, done. */
@Composable
private fun TextBar(
    reading: Boolean,
    text: PhotoText?,
    chosen: Set<Int>,
    onChosen: (Set<Int>) -> Unit,
    onClose: () -> Unit,
    onCopied: () -> Unit,
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
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.reading_text),
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.stop), color = Color.White) }
            }
            return@Column
        }
        Text(
            pluralStringResource(R.plurals.text_chosen, text.words.size, chosen.size, text.words.size) + " · " +
                stringResource(R.string.text_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChosen(text.words.indices.toSet()) }) { Text(stringResource(R.string.choose_all), color = Color.White) }
            TextButton(onClick = { onChosen(emptySet()) }) { Text(stringResource(R.string.choose_none), color = Color.White) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.text_on_photo), text.text(chosen)))
                // Android 13+ confirms copying itself.
                if (Build.VERSION.SDK_INT < 33) onCopied()
            }) {
                Text(stringResource(R.string.copy), color = Color.White)
            }
            IconButton(enabled = chosen.isNotEmpty(), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.text(chosen))
                context.startActivity(Intent.createChooser(send, null))
            }) {
                Icon(Icons.Filled.Share, stringResource(R.string.share), tint = Color.White)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close), tint = Color.White) }
        }
    }
}

/**
 * The recognised words drawn over the photo: chosen ones filled, others outlined. Tap a word to
 * choose it or not; drag over words to choose (or un-choose) them all the way.
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
        // A little slack around each word: words are small targets for a finger.
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
                // Only a finger that starts on a word chooses: a tap on a word turns it on or off,
                // a drag chooses every word between the first and the one under the finger (in
                // reading order). A finger on empty space is left to the photo (pan, double tap),
                // and a second finger hands the gesture to zooming and undoes the drag.
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
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                        }
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
            if (i in chosen) {
                drawRect(accent.copy(alpha = 0.35f), topLeft, box)
            }
            drawRect(accent.copy(alpha = 0.9f), topLeft, box, style = Stroke(width = 1.5f))
        }
    }
}

@Composable
private fun ZoomableImage(
    item: MediaItem,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    text: PhotoText?,
    chosen: Set<Int>,
    onChosen: (Set<Int>) -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenPx = with(density) { maxOf(maxWidth.toPx(), maxHeight.toPx()) }.toInt()
        // One and a half screens: sharp enough for zooming, light on memory. Region-by-region
        // decoding for very large photos comes later.
        val maxPx = (screenPx * 3 / 2).coerceIn(1080, 4096)
        val tap by rememberUpdatedState(onTap)
        val zoomChanged by rememberUpdatedState(onZoomChanged)
        var full by remember(item.id) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(item.id) { full = Images.full(resolver, item, maxPx) }

        var scale by remember(item.id) { mutableFloatStateOf(1f) }
        var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = size.width * (s - 1) / 2
            val maxY = size.height * (s - 1) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        fun setScale(s: Float, o: Offset) {
            scale = s
            offset = if (s <= 1f) Offset.Zero else clamp(o, s)
            zoomChanged(s > 1.01f)
        }

        val content = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            setScale(1f, Offset.Zero)
                        } else {
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            setScale(s, (center - at) * (s - 1))
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                // Two fingers zoom; one finger moves the photo only when it is zoomed in,
                // otherwise the swipe goes to the next photo.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // A finger choosing words (already taken by the text layer) doesn't move the photo.
                        val taken = event.changes.size == 1 && event.changes.any { it.isConsumed }
                        if (!taken && (event.changes.size > 1 || scale > 1.01f)) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            setScale((scale * z).coerceIn(1f, 8f), offset + p)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }

        val shown = full
        Box(content) {
            if (shown != null) {
                Image(shown.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                // The grid thumbnail while the full photo loads.
                Thumbnail(item, 512, Modifier.fillMaxSize())
            }
            if (text != null && shown != null) {
                TextOverlay(text, chosen, onChosen, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, current: Boolean, onTap: () -> Unit) {
    var playing by remember(item.id) { mutableStateOf(false) }
    // Swiping away stops the video and frees the player.
    LaunchedEffect(current) { if (!current) playing = false }
    Box(Modifier.fillMaxSize()) {
        if (playing) {
            VideoPlayer(item)
        } else {
            Thumbnail(
                item,
                512,
                Modifier
                    .fillMaxSize()
                    .pointerInput(item.id) { detectTapGestures(onTap = { onTap() }) },
            )
            IconButton(
                onClick = { playing = true },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color(0x88000000), CircleShape),
            ) {
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun VideoPlayer(item: MediaItem) {
    val context = LocalContext.current
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            // Pauses other players (music) while this video plays, and pauses itself for a call.
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Home button or another app: no sound in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(item.name) },
        text = {
            Column {
                Text(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(item.dateMs)))
                if (item.width > 0 && item.height > 0) Text("${item.width} × ${item.height}")
                Text(android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes))
                if (item.isVideo) Text(duration(item.durationMs))
                Text(stringResource(R.string.folder_named, item.bucketName))
                Text(item.mime)
            }
        },
    )
}
