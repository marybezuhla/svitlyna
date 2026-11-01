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
    val trash: List<MediaItem> = emptyList(),
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app.contentResolver)
    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    private var reloadJob: Job? = null
    private var observing = false

    /** Any change in the system media list (new photo, deleted, edited elsewhere) reloads, at most twice a second. */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(delayMs = 500)
    }

    /** Called once access is granted (and again when it changes). */
    fun start() {
        if (!observing) {
            getApplication<Application>().contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
            )
            observing = true
        }
        reload(delayMs = 0)
    }

    fun reload(delayMs: Long = 0) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val loaded = withContext(Dispatchers.IO) {
                val items = repo.loadAll()
                GalleryState(
                    loading = false,
                    items = items,
                    timeline = Timeline.byDay(items),
                    folders = MediaRepository.folders(items),
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
