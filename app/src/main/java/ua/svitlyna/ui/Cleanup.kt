package ua.svitlyna.ui

import android.content.Context
import android.os.CancellationSignal
import android.text.format.Formatter
import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ua.svitlyna.R
import ua.svitlyna.data.MediaImage
import ua.svitlyna.data.isGif
import ua.svitlyna.data.isScreenshot
import ua.svitlyna.media.Sharpness
import java.io.File
import kotlin.coroutines.coroutineContext

/** The groups of "Free up space". Nothing is removed here: the person selects and uses the trash. */
enum class CleanupKind { BIG_VIDEOS, OLD_SHOTS, MESSENGERS, BLURRY }

object Cleanup {
    /** 100 MB as the phone shows sizes (decimal). */
    const val BIG_VIDEO = 100_000_000L
    private const val OLD_SHOT_DAYS = 30
    private val MESSENGER = Regex("(^|/)(telegram( x)?|whatsapp( business)?|viber|signal)( images| video| documents)?(/|$)", RegexOption.IGNORE_CASE)

    /** Favourites are never offered. */
    fun group(kind: CleanupKind, images: List<MediaImage>, favorites: Set<Long>, blurry: Set<Long>): List<MediaImage> {
        val pool = images.filter { it.id > 0 && it.id !in favorites }
        return when (kind) {
            CleanupKind.BIG_VIDEOS -> pool.filter { it.isVideo && it.sizeBytes >= BIG_VIDEO }.sortedByDescending { it.sizeBytes }
            CleanupKind.OLD_SHOTS -> {
                val days = if (TempShots.enabled) TempShots.days else OLD_SHOT_DAYS
                val limit = System.currentTimeMillis() - days * 86_400_000L
                pool.filter { !it.isVideo && it.isScreenshot && it.dateTaken in 1 until limit && it.id !in TempShots.kept }
                    .sortedBy { it.dateTaken }
            }
            CleanupKind.MESSENGERS -> pool.filter { fromMessenger(it) }.sortedByDescending { it.sizeBytes }
            CleanupKind.BLURRY -> pool.filter { it.id in blurry }.sortedByDescending { it.dateTaken }
        }
    }

    fun fromMessenger(item: MediaImage): Boolean = fromMessenger(item.path, item.bucketName)

    fun fromMessenger(path: String, bucket: String): Boolean =
        MESSENGER.containsMatchIn(path) || MESSENGER.containsMatchIn("/$bucket/")

    /** Before starting: about 30 ms per photo on a mid-range phone, in whole minutes. */
    fun estimateMinutes(count: Int): Int = ((count * 30L + 59_999) / 60_000).toInt().coerceAtLeast(1)

    /** Photos worth checking for blur: not videos, GIFs or screenshots. */
    fun blurCandidates(images: List<MediaImage>, favorites: Set<Long>): List<MediaImage> =
        images.filter { it.id > 0 && !it.isVideo && !it.isGif && !it.isScreenshot && it.id !in favorites }
}

/**
 * Finding possibly blurred photos: a small thumbnail of each photo is measured (Sharpness). The
 * results are kept in the app's private storage (photo number, its change time and the score), so
 * the next time only new photos are measured. Runs only while "Free up space" is open, with
 * progress, the time left and "Stop".
 */
object BlurScan {
    var scores by mutableStateOf<Map<Long, Pair<Long, Double>>>(emptyMap())
        private set
    @Volatile
    var loaded = false
        private set
    var running by mutableStateOf(false)
        private set
    var done by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    var leftMinutes by mutableStateOf(-1)
        private set

    private fun file(context: Context) = android.util.AtomicFile(File(context.filesDir, "blur_scores"))

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val map = HashMap<Long, Pair<Long, Double>>()
        runCatching {
            file(context).openRead().bufferedReader().forEachLine { line ->
                val p = line.split(',')
                if (p.size == 3) {
                    val id = p[0].toLongOrNull(); val mod = p[1].toLongOrNull(); val s = p[2].toDoubleOrNull()
                    if (id != null && mod != null && s != null) map[id] = mod to s
                }
            }
        }
        scores = map
        loaded = true
    }

    @Synchronized
    private fun save(context: Context, map: Map<Long, Pair<Long, Double>>) {
        val f = file(context)
        var out: java.io.FileOutputStream? = null
        runCatching {
            out = f.startWrite()
            val w = out!!.bufferedWriter()
            for ((id, v) in map) w.write("$id,${v.first},${v.second}\n")
            w.flush()
            f.finishWrite(out)
        }.onFailure { out?.let { f.failWrite(it) } }
    }

    fun blurry(images: List<MediaImage>): Set<Long> {
        val byId = images.associateBy { it.id }
        return scores.filter { (id, v) -> byId[id]?.dateModified == v.first && v.second < Sharpness.BLURRY }.keys
    }

    fun unmeasured(candidates: List<MediaImage>): List<MediaImage> =
        candidates.filter { scores[it.id]?.first != it.dateModified }

    /** Measures [todo]; stops when the calling scope is cancelled ("Stop" or leaving the screen). */
    suspend fun run(context: Context, todo: List<MediaImage>, keep: Set<Long>) = withContext(Dispatchers.IO) {
        if (todo.isEmpty()) return@withContext
        running = true
        done = 0
        total = todo.size
        leftMinutes = -1
        var count = 0
        // Results for photos that are gone are dropped.
        val map = HashMap(scores.filterKeys { it in keep })
        val signal = CancellationSignal()
        // "Stop" also cancels the thumbnail being loaded.
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { signal.cancel() }
        val started = System.nanoTime()
        var savedAt = started
        try {
            for (item in todo) {
                coroutineContext.ensureActive()
                val score = runCatching {
                    val bmp = context.contentResolver.loadThumbnail(item.uri, Size(384, 384), signal)
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    Sharpness.variance(Sharpness.gray(px), w, h)
                }.getOrNull()
                // Unreadable: marked sharp, so it is not offered and not measured again until it changes.
                map[item.id] = item.dateModified to (score ?: Double.MAX_VALUE)
                count++
                if (count % 10 == 0 || count == total) done = count
                if (count == 20 || count % 50 == 0) {
                    val perItem = (System.nanoTime() - started) / count
                    leftMinutes = (((total - count) * perItem) / 60_000_000_000L).toInt() + 1
                }
                // Saved and shown every 15 s, not after every photo (big galleries).
                if (System.nanoTime() - savedAt > 15_000_000_000L) {
                    savedAt = System.nanoTime()
                    scores = HashMap(map)
                    save(context, map)
                }
            }
        } finally {
            signal.cancel()
            scores = HashMap(map)
            save(context, map)
            running = false
        }
    }
}

/** The head of "Free up space": how much can be freed, the groups, blur search and "Select all". */
@Composable
fun CleanupHeader(
    images: List<MediaImage>,
    favorites: Set<Long>,
    kind: CleanupKind,
    shown: List<MediaImage>,
    onKind: (CleanupKind) -> Unit,
    onDuplicates: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BlurScan.load(context) }
    val scores = BlurScan.scores
    val blurry = remember(images, scores) { BlurScan.blurry(images) }
    val groups = remember(images, favorites, blurry, TempShots.kept, TempShots.days, TempShots.enabled) {
        CleanupKind.entries.associateWith { Cleanup.group(it, images, favorites, blurry) }
    }
    fun size(list: List<MediaImage>) = Formatter.formatShortFileSize(context, list.sumOf { it.sizeBytes.coerceAtLeast(0) })
    val total = remember(groups) { groups.values.flatten().distinctBy { it.id } }
    var scanNow by remember { mutableStateOf(false) }
    if (scanNow) {
        LaunchedEffect(Unit) {
            try {
                BlurScan.load(context)
                val candidates = Cleanup.blurCandidates(images, favorites)
                BlurScan.run(context, BlurScan.unmeasured(candidates), images.mapTo(HashSet()) { it.id })
            } finally {
                scanNow = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sv_cleanup_total, size(total)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = mapOf(
                CleanupKind.BIG_VIDEOS to R.string.sv_cleanup_big_videos,
                CleanupKind.OLD_SHOTS to R.string.sv_cleanup_old_shots,
                CleanupKind.MESSENGERS to R.string.sv_cleanup_messengers,
                CleanupKind.BLURRY to R.string.sv_cleanup_blurry,
            )
            for (k in CleanupKind.entries) item {
                val list = groups.getValue(k)
                FilterChip(
                    selected = kind == k,
                    onClick = { onKind(k) },
                    label = { Text(stringResource(labels.getValue(k), list.size, size(list))) },
                )
            }
            item { FilterChip(selected = false, onClick = onDuplicates, label = { Text(stringResource(R.string.sv_cleanup_duplicates)) }) }
        }
        if (kind == CleanupKind.BLURRY) {
            if (BlurScan.running) {
                Text(
                    if (BlurScan.leftMinutes > 0) stringResource(R.string.sv_cleanup_blurry_progress, BlurScan.done, BlurScan.total, BlurScan.leftMinutes)
                    else stringResource(R.string.sv_cleanup_blurry_progress_start, BlurScan.done, BlurScan.total),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { if (BlurScan.total > 0) BlurScan.done.toFloat() / BlurScan.total else 0f },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { scanNow = false }) { Text(stringResource(R.string.sv_stop)) }
                }
            } else {
                val left = remember(images, favorites, scores) { BlurScan.unmeasured(Cleanup.blurCandidates(images, favorites)).size }
                if (left > 0) {
                    OutlinedButton(onClick = { scanNow = true }) {
                        Text(pluralStringResource(R.plurals.sv_cleanup_blurry_scan, left, left, Cleanup.estimateMinutes(left)))
                    }
                }
            }
            Text(stringResource(R.string.sv_cleanup_blurry_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.sv_cleanup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (shown.isNotEmpty()) TextButton(onClick = onSelectAll) { Text(stringResource(R.string.sv_cleanup_select_all)) }
        }
    }
}
