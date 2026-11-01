package ua.svitlyna.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.media.Images
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.TimelineEntry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

const val MIN_COLUMNS = 2
const val MAX_COLUMNS = 6

/**
 * The photo grid: day headings, pinch to change the number of columns, long press to select,
 * and a handle on the right edge to jump through years of photos quickly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    entries: List<TimelineEntry>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    selected: Set<Long>,
    onOpen: (index: Int) -> Unit,
    onToggle: (MediaItem) -> Unit,
    onToggleDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyGridState = rememberLazyGridState(),
) {
    val selecting = selected.isNotEmpty()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cellPx = with(density) { (maxWidth / columns).roundToPx() }
        // Thumbnails come in a few fixed sizes, so changing columns reuses what is already loaded.
        val thumbPx = when {
            cellPx <= 160 -> 160
            cellPx <= 256 -> 256
            cellPx <= 384 -> 384
            else -> 512
        }
        var zoom by remember { mutableFloatStateOf(1f) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(columns) {
                    // Two fingers: pinch out for bigger photos, pinch in for more on the screen.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        zoom = 1f
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                zoom *= event.calculateZoom()
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        when {
                            zoom > 1.25f && columns > MIN_COLUMNS -> onColumnsChange(columns - 1)
                            zoom < 0.8f && columns < MAX_COLUMNS -> onColumnsChange(columns + 1)
                        }
                    }
                },
        ) {
            items(
                count = entries.size,
                key = { entries[it].key },
                span = { if (entries[it] is TimelineEntry.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (entries[it] is TimelineEntry.Day) 0 else 1 },
            ) { i ->
                when (val e = entries[i]) {
                    is TimelineEntry.Day -> DayHeader(e, selecting, onToggleDay)
                    is TimelineEntry.Item -> Cell(
                        item = e.item,
                        px = thumbPx,
                        selected = e.item.id in selected,
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selecting) onToggle(e.item) else onOpen(e.index) },
                            onLongClick = { onToggle(e.item) },
                        ),
                    )
                }
            }
        }
        FastScroller(state, entries, Modifier.align(Alignment.CenterEnd).padding(contentPadding))
    }
}

@Composable
private fun DayHeader(day: TimelineEntry.Day, selecting: Boolean, onToggleDay: (LocalDate) -> Unit) {
    Text(
        text = dayLabel(day.date),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) onToggleDay(day.date) },
                onLongClick = { onToggleDay(day.date) },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    }
}

@Composable
fun Cell(item: MediaItem, px: Int, selected: Boolean, modifier: Modifier = Modifier, badge: String? = null) {
    Box(
        modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .semantics { contentDescription = item.name },
    ) {
        Thumbnail(item, px, Modifier.fillMaxSize())
        if (item.isVideo) {
            Text(
                text = duration(item.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary)
                    .background(Color(0x33000000)),
            )
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
fun Thumbnail(item: MediaItem, px: Int, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    var bitmap by remember(item.id, px) { mutableStateOf<Bitmap?>(Images.cached(item, px)) }
    LaunchedEffect(item.id, px) {
        if (bitmap == null) bitmap = Images.thumbnail(resolver, item, px)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (bitmap == null && item.isVideo) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.align(Alignment.Center))
        }
    }
}

fun duration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * A handle on the right edge: drag it to move through the whole gallery; shows the month while
 * dragging. Only the handle itself takes touches, so photos under the edge stay tappable.
 */
@Composable
private fun FastScroller(state: LazyGridState, entries: List<TimelineEntry>, modifier: Modifier) {
    if (entries.size < 200) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val fraction by remember(entries) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (entries.size - 1).coerceAtLeast(1) }
    }
    val handleHeight = 48.dp
    val handlePx = with(LocalDensity.current) { handleHeight.toPx() }
    BoxWithConstraints(modifier.fillMaxHeight().width(36.dp)) {
        val trackPx = (with(LocalDensity.current) { maxHeight.toPx() } - handlePx).coerceAtLeast(1f)
        val y = if (dragging) dragY.roundToInt() else (fraction * trackPx).roundToInt()
        Surface(
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .size(width = if (dragging) 28.dp else 18.dp, height = handleHeight)
                .pointerInput(entries, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragY = fraction * trackPx
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        dragY = (dragY + dy).coerceIn(0f, trackPx)
                        val target = (dragY / trackPx * (entries.size - 1)).roundToInt()
                        scope.launch { state.scrollToItem(target) }
                    }
                },
        ) {}
        if (dragging) {
            val date = dateNear(entries, state.firstVisibleItemIndex)
            if (date != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-(160.dp.roundToPx()), y) },
                ) {
                    Text(
                        date.format(DateTimeFormatter.ofPattern("LLLL yyyy")),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private fun dateNear(entries: List<TimelineEntry>, index: Int): LocalDate? {
    for (i in index.coerceIn(0, entries.lastIndex) downTo 0) {
        val e = entries[i]
        if (e is TimelineEntry.Day) return e.date
    }
    return null
}
