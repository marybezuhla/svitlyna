package ua.svitlyna.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
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
                )
            }
        }
        val item = items.getOrNull(pager.currentPage)
        if (chrome && item != null) {
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
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x88000000))) {
                androidx.compose.foundation.layout.Row(
                    Modifier.align(Alignment.Center).padding(bottom = 24.dp, top = 4.dp),
                ) {
                    IconButton(onClick = { onShare(item) }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.share), tint = Color.White)
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
    info?.let { InfoDialog(it) { info = null } }
}

@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenPx = with(density) { maxOf(maxWidth.toPx(), maxHeight.toPx()) }.toInt()
        // Twice the screen, so zooming stays sharp; capped to keep memory in check.
        val maxPx = (screenPx * 2).coerceIn(1080, 4096)
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
            onZoomChanged(s > 1.01f)
        }

        val content = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            setScale(1f, Offset.Zero)
                        } else {
                            val s = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            setScale(s, (center - tap) * (s - 1))
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
                        if (event.changes.size > 1 || scale > 1.01f) {
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
        if (shown != null) {
            Image(shown.asImageBitmap(), item.name, content, contentScale = ContentScale.Fit)
        } else {
            // The grid thumbnail while the full photo loads.
            Thumbnail(item, 512, content)
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
            setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
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
