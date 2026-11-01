package ua.svitlyna.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.check.Prepare
import ua.svitlyna.check.Preset
import ua.svitlyna.check.Sensitive
import ua.svitlyna.media.Images
import ua.svitlyna.media.MediaItem
import ua.svitlyna.ocr.PhotoTextReader
import kotlin.math.abs

private enum class CheckResult { CHECKED, PARTIAL, FAILED, STOPPED }

/** One personal detail found on the photo: what it is and where (fractions of the photo). */
private class Finding(val kind: Sensitive.Kind, val area: RectF)

/**
 * "Before sending": checks the photo for personal details (location, card and phone numbers,
 * e-mail, IBAN, car plates, addresses), blacks them out (all at first; tap to keep one), lets the
 * person black out any other area with a finger, and cuts the photo to a network's size. What is
 * sent is a fresh copy with no metadata at all; the original is not touched.
 */
@Composable
fun PrepareScreen(item: MediaItem, onClose: () -> Unit, onSendAsIs: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picture by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var checking by remember(item.id) { mutableStateOf(true) }
    var result by remember(item.id) { mutableStateOf(CheckResult.CHECKED) }
    var findings by remember(item.id) { mutableStateOf(emptyList<Finding>()) }
    var off by remember(item.id) { mutableStateOf(emptySet<Int>()) }
    var manual by remember(item.id) { mutableStateOf(emptyList<RectF>()) }
    var location by remember(item.id) { mutableStateOf(false) }
    var preset by remember(item.id) { mutableStateOf(Preset.ORIGINAL) }
    var sending by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<android.content.Intent?>(null) }
    var checkJob by remember(item.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(item.id) {
        picture = Images.full(context.contentResolver, item, 2048)
        location = Prepare.hasLocation(context, item)
    }
    LaunchedEffect(item.id) {
        checkJob = coroutineContext[kotlinx.coroutines.Job]
        // At most a minute for one photo; "Stop" ends it sooner.
        val text = kotlinx.coroutines.withTimeoutOrNull(60_000) { PhotoTextReader.read(context, item) }
        result = when {
            text == null -> CheckResult.FAILED
            !text.complete -> CheckResult.PARTIAL
            else -> CheckResult.CHECKED
        }
        findings = text?.sensitive()?.map { (kind, words) ->
            val boxes = words.map { text.words[it].second.box }
            RectF(
                boxes.minOf { it.left } / text.width.toFloat(),
                boxes.minOf { it.top } / text.height.toFloat(),
                boxes.maxOf { it.right } / text.width.toFloat(),
                boxes.maxOf { it.bottom } / text.height.toFloat(),
            ).let { Finding(kind, it) }
        }.orEmpty()
        checking = false
    }

    fun stopChecking() {
        checkJob?.cancel()
        checking = false
        result = CheckResult.STOPPED
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.sv_back), tint = Color.White)
            }
            Text(stringResource(R.string.sv_prepare_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = picture
            if (shown == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                Image(shown.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                BlackOutLayer(
                    imageW = shown.width,
                    imageH = shown.height,
                    findings = findings,
                    off = off,
                    manual = manual,
                    keep = preset.keep(shown.width, shown.height),
                    onToggle = { i -> off = if (i in off) off - i else off + i },
                    onAdd = { manual = manual + it },
                    onRemove = { i -> manual = manual.filterIndexed { k, _ -> k != i } },
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(if (location) R.string.sv_prepare_location_removed else R.string.sv_prepare_no_metadata),
                style = MaterialTheme.typography.bodySmall,
            )
            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.sv_prepare_checking),
                        Modifier.padding(start = 8.dp).weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = ::stopChecking) { Text(stringResource(R.string.sv_stop)) }
                }
            } else if (result == CheckResult.FAILED || result == CheckResult.STOPPED) {
                Text(stringResource(R.string.sv_prepare_not_checked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (findings.isEmpty()) {
                Text(stringResource(R.string.sv_prepare_nothing_found), style = MaterialTheme.typography.bodySmall)
            } else {
                val hidden = findings.indices.count { it !in off }
                Text(
                    pluralStringResource(R.plurals.sv_prepare_found, findings.size, findings.size, kindsText(findings.map { it.kind })) +
                        " " + pluralStringResource(R.plurals.sv_prepare_hidden, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (result == CheckResult.PARTIAL) {
                Text(stringResource(R.string.sv_prepare_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.sv_prepare_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in Preset.entries) {
                    FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(stringResource(presetName(p))) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (manual.isNotEmpty()) {
                    TextButton(onClick = { manual = emptyList() }) { Text(stringResource(R.string.sv_prepare_clear_manual)) }
                } else {
                    // The photo as it is (with the "several photos" location setting), without this screen.
                    TextButton(onClick = onSendAsIs) { Text(stringResource(R.string.sv_prepare_as_is)) }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                // Sent when ready, but not over the lock screen: then right after unlocking.
                val locked = LocalLocked.current
                LaunchedEffect(ready, locked) {
                    val i = ready
                    if (i != null && !locked) {
                        ready = null
                        context.startActivity(i)
                        onClose()
                    }
                }
                Button(
                    // Not while checking: what is found must be blacked out before anything is sent.
                    enabled = picture != null && !sending && !checking,
                    onClick = {
                        sending = true
                        scope.launch {
                            val areas = findings.filterIndexed { i, _ -> i !in off }.map { it.area } + manual
                            val intent = Prepare.share(context, item, preset, areas)
                            sending = false
                            if (intent != null) ready = intent
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sv_share))
                }
            }
        }
    }
}

@Composable
private fun kindsText(kinds: List<Sensitive.Kind>): String =
    kinds.distinct().map { stringResource(kindName(it)) }.joinToString(", ")

private fun kindName(kind: Sensitive.Kind) = when (kind) {
    Sensitive.Kind.CARD -> R.string.sv_kind_card
    Sensitive.Kind.PHONE -> R.string.sv_kind_phone
    Sensitive.Kind.EMAIL -> R.string.sv_kind_email
    Sensitive.Kind.IBAN -> R.string.sv_kind_iban
    Sensitive.Kind.PLATE -> R.string.sv_kind_plate
    Sensitive.Kind.ADDRESS -> R.string.sv_kind_address
}

private fun presetName(p: Preset) = when (p) {
    Preset.ORIGINAL -> R.string.sv_preset_original
    Preset.POST -> R.string.sv_preset_post
    Preset.SQUARE -> R.string.sv_preset_square
    Preset.STORY -> R.string.sv_preset_story
    Preset.TELEGRAM -> R.string.sv_preset_telegram
}

/**
 * Drawn over the photo (fit to the screen): blacked-out areas solid, found details that are kept
 * outlined, the part a network will cut off dimmed. Tap a found detail to black it out or keep it;
 * tap a hand-drawn area to remove it; drag anywhere else to black out a new area.
 */
@Composable
private fun BlackOutLayer(
    imageW: Int,
    imageH: Int,
    findings: List<Finding>,
    off: Set<Int>,
    manual: List<RectF>,
    keep: RectF,
    onToggle: (Int) -> Unit,
    onAdd: (RectF) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val f by rememberUpdatedState(findings)
    val m by rememberUpdatedState(manual)
    val toggle by rememberUpdatedState(onToggle)
    val add by rememberUpdatedState(onAdd)
    val remove by rememberUpdatedState(onRemove)
    val accent = MaterialTheme.colorScheme.error

    fun scale(): Triple<Float, Float, Float> {
        val s = minOf(size.width / imageW.toFloat(), size.height / imageH.toFloat())
        return Triple(s, (size.width - imageW * s) / 2f, (size.height - imageH * s) / 2f)
    }

    fun toPhoto(p: Offset): Offset {
        val (s, dx, dy) = scale()
        return Offset(((p.x - dx) / s / imageW).coerceIn(0f, 1f), ((p.y - dy) / s / imageH).coerceIn(0f, 1f))
    }

    fun RectF.hit(p: Offset, slack: Float = 0.01f) =
        p.x >= left - slack && p.x <= right + slack && p.y >= top - slack && p.y <= bottom + slack

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(imageW, imageH) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = toPhoto(down.position)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        if (!change.pressed) break
                        if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) {
                            change.consume()
                            drawing = start to toPhoto(change.position)
                        }
                    }
                    val d = drawing
                    drawing = null
                    if (moved && d != null) {
                        val r = RectF(minOf(d.first.x, d.second.x), minOf(d.first.y, d.second.y), maxOf(d.first.x, d.second.x), maxOf(d.first.y, d.second.y))
                        if (abs(r.width()) > 0.01f && abs(r.height()) > 0.005f) add(r)
                    } else {
                        // An area the person drew is on top: a tap removes it first.
                        val mi = m.indexOfLast { it.hit(start, 0f) }
                        if (mi >= 0) {
                            remove(mi)
                        } else {
                            val fi = f.indexOfFirst { it.area.hit(start) }
                            if (fi >= 0) toggle(fi)
                        }
                    }
                }
            },
    ) {
        val (s, dx, dy) = scale()
        fun rect(r: RectF): Pair<Offset, Size> =
            Offset(dx + r.left * imageW * s, dy + r.top * imageH * s) to Size(r.width() * imageW * s, r.height() * imageH * s)

        val dark = Color(0xFF181818)
        findings.forEachIndexed { i, fnd ->
            val (o, sz) = rect(fnd.area)
            if (i in off) drawRect(accent, o, sz, style = Stroke(width = 3f)) else drawRect(dark, o, sz)
        }
        manual.forEach { r -> rect(r).let { (o, sz) -> drawRect(dark, o, sz) } }
        drawing?.let { (a, b) ->
            val r = RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
            rect(r).let { (o, sz) -> drawRect(dark.copy(alpha = 0.7f), o, sz) }
        }
        // The part a network's size cuts off: dimmed.
        val dim = Color(0xAA000000)
        val (ko, ks) = rect(keep)
        val (fo, fs) = rect(RectF(0f, 0f, 1f, 1f))
        if (ko.y > fo.y) drawRect(dim, fo, Size(fs.width, ko.y - fo.y))
        if (ko.y + ks.height < fo.y + fs.height) drawRect(dim, Offset(fo.x, ko.y + ks.height), Size(fs.width, fo.y + fs.height - ko.y - ks.height))
        if (ko.x > fo.x) drawRect(dim, fo, Size(ko.x - fo.x, fs.height))
        if (ko.x + ks.width < fo.x + fs.width) drawRect(dim, Offset(ko.x + ks.width, fo.y), Size(fo.x + fs.width - ko.x - ks.width, fs.height))
    }
}
