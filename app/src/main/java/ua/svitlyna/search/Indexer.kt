package ua.svitlyna.search

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.MediaRepository
import ua.svitlyna.media.isRaw
import ua.svitlyna.ocr.PhotoTextReader
import ua.svitlyna.ocr.TextRecognizer
import ua.svitlyna.ocr.joinedText
import ua.svitlyna.ui.Prefs

/**
 * Reading the text of every photo into the search index, newest first. Runs either in the
 * background (charging and not in use, [IndexJob]) or on request with a time estimate and Stop;
 * never both at once. Already read photos are skipped, changed ones read again, deleted ones removed.
 */
object Indexer {
    /** How far it is: [done] of [total] photos read; [running] while reading; [secondsLeft] estimated. */
    data class Progress(
        val done: Int = 0,
        val total: Int = 0,
        val running: Boolean = false,
        val secondsLeft: Int = 0,
        /** The photos have been counted (so total = 0 really means "none to read"). */
        val counted: Boolean = false,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    /** Photos with text (for the "with text" filter); refreshed as reading goes. */
    private val _withText = MutableStateFlow<Set<Long>>(emptySet())
    val withText: StateFlow<Set<Long>> = _withText

    private val mutex = Mutex()

    /** Seconds a photo takes on average, measured as it goes (a cautious guess at first). */
    @Volatile
    private var perPhoto = 1.2

    /** What changes in a photo's file changes this (size, date taken). */
    fun stamp(item: MediaItem): Long = item.sizeBytes * 31 + item.takenMs

    /** Photos worth reading: pictures, not videos or RAW files (too slow for what they hold). */
    fun readable(items: List<MediaItem>): List<MediaItem> = items.filter { !it.isVideo && !it.isRaw }

    /** Counts what is done without reading anything, from the gallery's own list of [items]. */
    suspend fun refreshCount(context: Context, items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (!Prefs(context).searchText) return@withContext
        runCatching {
            val index = IndexDb.index(context)
            // Photos that couldn't be read in this run are not counted as still to read.
            val photos = readable(items).filter { it.id !in failed }
            val stamps = index.stamps()
            val done = photos.count { stamps[it.id] == stamp(it) }
            _withText.value = index.withText()
            if (!_progress.value.running) _progress.value = Progress(done, photos.size, false, ((photos.size - done) * perPhoto).toInt(), counted = true)
        }
    }

    /** Photos that couldn't be read in this run of the app (a damaged file): not tried again and again. */
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    /**
     * Reads what isn't read yet, until done, cancelled or [untilMs] (elapsed realtime) is reached.
     * Returns true when everything is read. Waits if another pass is already running.
     */
    suspend fun run(context: Context, untilMs: Long = Long.MAX_VALUE): Boolean = withContext(Dispatchers.IO) {
        if (!Prefs(context).searchText) return@withContext true
        mutex.withLock {
            val recognizer = TextRecognizer.get(context)
            recognizer.hold()
            try {
                val index = IndexDb.index(context)
                val photos = readable(MediaRepository(context.contentResolver).loadAll())
                val stamps = index.stamps()
                // Gone from the gallery: gone from the index. Only with full access and a real list:
                // an empty or partial list (storage not ready, "selected photos" access, permission
                // taken away for a while) must not erase what was read.
                if (photos.isNotEmpty() && ua.svitlyna.ui.Access.level(context) == ua.svitlyna.ui.AccessLevel.ALL) {
                    val alive = photos.mapTo(HashSet()) { it.id }
                    index.remove(stamps.keys.filter { it !in alive })
                }
                val todo = photos.filter { stamps[it.id] != stamp(it) && it.id !in failed }
                var total = photos.count { it.id !in failed }
                var done = total - todo.size
                _progress.value = Progress(done, total, true, (todo.size * perPhoto).toInt(), counted = true)
                var failedInRow = 0
                for ((i, item) in todo.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (android.os.SystemClock.elapsedRealtime() > untilMs) return@withLock false
                    val started = android.os.SystemClock.elapsedRealtime()
                    // Null: the photo couldn't be read (not "no text"): nothing is written, it is tried
                    // in a later run; many failures in a row mean the reader itself is in trouble.
                    val read = PhotoTextReader.read(context, item)
                    if (read == null) {
                        failed += item.id
                        total--
                        if (++failedInRow >= 5) return@withLock false
                        continue
                    }
                    failedInRow = 0
                    // Turned off meanwhile: nothing more is written.
                    if (!Prefs(context).searchText) return@withLock true
                    index.put(item.id, stamp(item), read.lines.joinedText())
                    done++
                    val took = (android.os.SystemClock.elapsedRealtime() - started) / 1000.0
                    perPhoto = perPhoto * 0.9 + took * 0.1
                    _progress.value = Progress(done, total, true, ((todo.size - i - 1) * perPhoto).toInt(), counted = true)
                    if (i % 25 == 0) _withText.value = index.withText()
                }
                _withText.value = index.withText()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                _progress.value = _progress.value.copy(running = false)
                withContext(kotlinx.coroutines.NonCancellable) { recognizer.letGo() }
            }
        }
    }

    // "Read now": runs on its own (not tied to a screen), until done or Stop.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var now: kotlinx.coroutines.Job? = null

    fun start(context: Context) {
        if (now?.isActive == true) return
        val app = context.applicationContext
        now = scope.launch { runCatching { run(app) } }
    }

    fun stop() {
        now?.cancel()
        now = null
    }

    /** Search off: the text read so far is erased, even if the screen that asked is gone meanwhile. */
    fun eraseSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { erase(app) } }
    }

    /** Search off: the text read so far is erased. */
    suspend fun erase(context: Context) = withContext(Dispatchers.IO) {
        stop()
        IndexDb.erase(context)
        _withText.value = emptySet()
        _progress.value = Progress()
    }
}
