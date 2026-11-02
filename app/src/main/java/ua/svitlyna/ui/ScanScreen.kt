package ua.svitlyna.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.svitlyna.R
import ua.svitlyna.media.DocScan
import ua.svitlyna.media.Images
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.SaveCopy

/**
 * Scanning a document from a photo: the person moves the four corners onto the page's corners; the
 * page is made straight and, if chosen, clean black-and-white. Saved as a copy next to the photo.
 */
@Composable
fun ScanScreen(item: MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var clean by remember { mutableStateOf(true) }
    // Corners as fractions of the picture: start a little inside its edges.
    var corners by remember { mutableStateOf(floatArrayOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f)) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    BackHandler(onBack = onClose)
    LaunchedEffect(item.id) {
        picture = Images.full(context.contentResolver, item, 2600, software = true)
        if (picture == null) failed = true
    }
    val grab = with(LocalDensity.current) { 40.dp.toPx() }

    // Nothing reaches the viewer underneath.
    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Text(
            stringResource(R.string.sv_scan_hint),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
            val pic = picture
            when {
                failed -> Text(stringResource(R.string.sv_scan_failed), color = Color.White)
                pic == null -> CircularProgressIndicator()
                else -> {
                    // Where the picture sits inside the box (fitted, centred).
                    val scale = minOf(box.width / pic.width.toFloat(), box.height / pic.height.toFloat())
                    val dw = pic.width * scale
                    val dh = pic.height * scale
                    val left = (box.width - dw) / 2
                    val top = (box.height - dh) / 2
                    fun toScreen(i: Int) = Offset(left + corners[2 * i] * dw, top + corners[2 * i + 1] * dh)
                    Image(pic.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    var dragging by remember { mutableStateOf(-1) }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(pic, box) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    dragging = (0 until 4).minByOrNull { (toScreen(it) - at).getDistance() }
                                        ?.takeIf { (toScreen(it) - at).getDistance() < grab * 2 } ?: -1
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 },
                            ) { change, _ ->
                                val i = dragging
                                if (i >= 0) {
                                    change.consume()
                                    val c = corners.copyOf()
                                    c[2 * i] = ((change.position.x - left) / dw).coerceIn(0f, 1f)
                                    c[2 * i + 1] = ((change.position.y - top) / dh).coerceIn(0f, 1f)
                                    corners = c
                                }
                            }
                        },
                    ) {
                        // The outline of the page that will be saved (corners in order).
                        val pts = (0 until 4).map { toScreen(it) }
                        val o = DocScan.ordered(FloatArray(8) { k -> if (k % 2 == 0) pts[k / 2].x else pts[k / 2].y })
                        val p = Path().apply {
                            moveTo(o[0], o[1])
                            for (i in 1..3) lineTo(o[2 * i], o[2 * i + 1])
                            close()
                        }
                        drawPath(p, Color(0x5533B5E5))
                        drawPath(p, Color(0xFF33B5E5), style = Stroke(width = 3.dp.toPx()))
                        for (i in 0 until 4) {
                            drawCircle(Color.White, radius = 12.dp.toPx(), center = toScreen(i))
                            drawCircle(Color(0xFF33B5E5), radius = 9.dp.toPx(), center = toScreen(i))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = clean, role = Role.Switch, onValueChange = { clean = it }).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sv_scan_clean), color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = clean, onCheckedChange = null)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            // Without moving anything (also with TalkBack): the whole picture is the page.
            TextButton(onClick = { corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f) }) {
                Text(stringResource(R.string.sv_scan_whole), color = Color.White)
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel), color = Color.White) }
            Button(enabled = picture != null && !saving, onClick = {
                val pic = picture ?: return@Button
                saving = true
                scope.launch {
                    val uri = withContext(Dispatchers.Default) {
                        val px = FloatArray(8) { i -> corners[i] * if (i % 2 == 0) pic.width else pic.height }
                        runCatching {
                            val page = DocScan.straighten(pic, px)
                            val result = if (clean) DocScan.clean(page).also { page.recycle() } else page
                            SaveCopy.save(context, item, result, "scan", SaveCopy.Format.JPEG, 92).also { result.recycle() }
                        }.getOrNull()
                    }
                    saving = false
                    Toast.makeText(context, if (uri != null) R.string.sv_scan_saved else R.string.sv_scan_failed, Toast.LENGTH_LONG).show()
                    if (uri != null) onClose()
                }
            }) {
                if (saving) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sv_scan_save))
            }
        }
    }
}
