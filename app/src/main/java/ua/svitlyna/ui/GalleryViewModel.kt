package ua.svitlyna.ui

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.svitlyna.media.Folder
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.MediaRepository
import ua.svitlyna.media.Timeline
import ua.svitlyna.media.TimelineEntry

data class GalleryState(
    val loading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val timeline: List<TimelineEntry> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Each folder's items and timeline, built in the background with the rest. */
    val byFolder: Map<Long, FolderContent> = emptyMap(),
    val trash: List<MediaItem> = emptyList(),
)

data class FolderContent(val items: List<MediaItem>, val timeline: List<TimelineEntry>)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app.contentResolver)
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    private var reloadJob: Job? = null
    private var observing = false
    /** The media list's change counter at the last load: unchanged means nothing to reload. */
    private var loadedGeneration = -1L

    /** Any change in the system media list (new photo, deleted, edited elsewhere) reloads once the changes settle. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(delayMs = 500)
    }

    /**
     * Called when the app comes back and whenever access may have changed. Reloads only when the
     * access changed or the media list did (returning from the share sheet costs nothing).
     */
    fun start(accessChanged: Boolean) {
        if (!observing) {
            getApplication<Application>().contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
            )
            observing = true
        }
        if (accessChanged || generation() != loadedGeneration) reload(delayMs = 0)
    }

    private fun generation(): Long =
        runCatching { MediaStore.getGeneration(getApplication(), MediaStore.VOLUME_EXTERNAL) }.getOrDefault(-2L)

    fun reload(delayMs: Long = 0) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val loaded = withContext(Dispatchers.IO) {
                val generation = generation()
                val items = repo.loadAll()
                val byFolder = items.groupBy { it.bucketId }.mapValues { (_, list) -> FolderContent(list, Timeline.byDay(list)) }
                loadedGeneration = generation
                GalleryState(
                    loading = false,
                    items = items,
                    timeline = Timeline.byDay(items),
                    folders = MediaRepository.folders(items),
                    byFolder = byFolder,
                    trash = repo.loadTrash(),
                )
            }
            _state.value = loaded
        }
    }

    override fun onCleared() {
        if (observing) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}
