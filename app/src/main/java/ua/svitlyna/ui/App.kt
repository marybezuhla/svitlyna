package ua.svitlyna.ui

import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.media.Folder
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.Share
import ua.svitlyna.media.Timeline

private sealed interface Page {
    data object Photos : Page
    data object Folders : Page
    data class InFolder(val folder: Folder) : Page
    data object Trash : Page
    data object Settings : Page
}

/** Which photos the open viewer swipes through: all of them, or one folder. */
private data class ViewerAt(val bucketId: Long?, val index: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsState()

    var access by remember { mutableStateOf(Access.level(context)) }
    var canManageMedia by remember { mutableStateOf(Access.canManageMedia(context)) }
    var page by remember { mutableStateOf<Page>(Page.Photos) }
    var lastTab by remember { mutableStateOf<Page>(Page.Photos) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewer by remember { mutableStateOf<ViewerAt?>(null) }
    var columns by remember { mutableIntStateOf(prefs.columns) }
    var removeLocation by remember { mutableStateOf(prefs.removeLocation) }
    var askShare by remember { mutableStateOf<List<MediaItem>?>(null) }

    // Access can change in Android settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = Access.level(context)
        canManageMedia = Access.canManageMedia(context)
        if (access != AccessLevel.NONE) vm.start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = Access.level(context)
        if (access != AccessLevel.NONE) vm.start()
    }
    // Moving to the trash, restoring and deleting for good: Android asks the person itself
    // (no window when "Media management" is switched on).
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        selected = emptySet()
        vm.reload()
    }

    fun trash(items: List<MediaItem>, toTrash: Boolean) {
        if (items.isEmpty()) return
        val pi = MediaStore.createTrashRequest(resolver, items.map { it.uri }, toTrash)
        writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    fun deleteForGood(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val pi = MediaStore.createDeleteRequest(resolver, items.map { it.uri })
        writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    fun doShare(items: List<MediaItem>, clean: Boolean) {
        scope.launch {
            val prepared = Share.prepare(context, items, clean)
            if (prepared.skipped > 0) {
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.share_skipped, prepared.skipped, prepared.skipped))
            }
            prepared.intent?.let { context.startActivity(it) }
        }
    }

    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (removeLocation && items.any { !Share.canClean(it) }) askShare = items else doShare(items, removeLocation)
    }

    val folderId = (page as? Page.InFolder)?.folder?.id
    val folderItems = remember(state.items, folderId) { folderId?.let { id -> state.items.filter { it.bucketId == id } } }
    val folderTimeline = remember(folderItems) { folderItems?.let { Timeline.byDay(it) } }
    val visibleItems = folderItems ?: state.items
    val selectedItems = remember(selected, state.items, state.trash, page) {
        (if (page == Page.Trash) state.trash else state.items).filter { it.id in selected }
    }

    fun toggle(item: MediaItem) {
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    fun toggleDay(date: java.time.LocalDate) {
        val zone = java.time.ZoneId.systemDefault()
        val ids = visibleItems.filter { Timeline.dayOf(it.dateMs, zone) == date }.map { it.id }.toSet()
        selected = if (selected.containsAll(ids)) selected - ids else selected + ids
    }

    fun goTo(p: Page) {
        selected = emptySet()
        if (p == Page.Photos || p == Page.Folders) lastTab = p
        page = p
    }

    BackHandler(enabled = viewer == null && (selected.isNotEmpty() || page !is Page.Photos)) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            page is Page.InFolder -> goTo(Page.Folders)
            page == Page.Trash || page == Page.Settings -> goTo(lastTab)
            else -> goTo(Page.Photos)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (access == AccessLevel.NONE) {
                // The access screen has its own title.
            } else if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected, selected.size, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    },
                    actions = {
                        if (page == Page.Trash) {
                            IconButton(onClick = { trash(selectedItems, toTrash = false) }) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.restore))
                            }
                            IconButton(onClick = { deleteForGood(selectedItems) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete_for_good))
                            }
                        } else {
                            IconButton(onClick = { share(selectedItems) }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                            IconButton(onClick = { trash(selectedItems, toTrash = true) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.move_to_trash))
                            }
                        }
                    },
                )
            } else {
                val title = when (val p = page) {
                    Page.Photos -> stringResource(R.string.app_name)
                    Page.Folders -> stringResource(R.string.folders)
                    is Page.InFolder -> p.folder.name.ifBlank { stringResource(R.string.no_folder_name) }
                    Page.Trash -> stringResource(R.string.trash)
                    Page.Settings -> stringResource(R.string.settings)
                }
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (page is Page.InFolder || page == Page.Trash || page == Page.Settings) {
                            IconButton(onClick = { goTo(if (page is Page.InFolder) Page.Folders else lastTab) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (page == Page.Photos || page == Page.Folders) {
                            IconButton(onClick = { goTo(Page.Trash) }) { Icon(Icons.Filled.Delete, stringResource(R.string.trash)) }
                            IconButton(onClick = { goTo(Page.Settings) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                        }
                        if (page == Page.Trash && state.trash.isNotEmpty()) {
                            TextButton(onClick = { deleteForGood(state.trash) }) { Text(stringResource(R.string.empty_trash)) }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (access != AccessLevel.NONE && selected.isEmpty() && (page == Page.Photos || page == Page.Folders)) {
                NavigationBar {
                    NavigationBarItem(
                        selected = page == Page.Photos,
                        onClick = { goTo(Page.Photos) },
                        icon = { Icon(Icons.Filled.DateRange, null) },
                        label = { Text(stringResource(R.string.photos)) },
                    )
                    NavigationBarItem(
                        selected = page == Page.Folders,
                        onClick = { goTo(Page.Folders) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        label = { Text(stringResource(R.string.folders)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                access == AccessLevel.NONE ->
                    AccessScreen(onRequest = { permissionLauncher.launch(Access.permissionsToRequest()) }, contentPadding = padding)
                state.loading && page != Page.Settings ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Content(
                    page = page,
                    padding = padding,
                    state = state,
                    folderTimeline = folderTimeline,
                    columns = columns,
                    onColumns = { columns = it; prefs.columns = it },
                    selected = selected,
                    onToggle = ::toggle,
                    onToggleDay = ::toggleDay,
                    onOpen = { index -> viewer = ViewerAt((page as? Page.InFolder)?.folder?.id, index) },
                    onOpenFolder = { goTo(Page.InFolder(it)) },
                    access = access,
                    canManageMedia = canManageMedia,
                    removeLocation = removeLocation,
                    onRemoveLocation = { removeLocation = it; prefs.removeLocation = it },
                    onPickMore = { permissionLauncher.launch(Access.permissionsToRequest()) },
                )
            }
        }
    }

    viewer?.let { at ->
        val list = remember(state.items, at.bucketId) {
            if (at.bucketId == null) state.items else state.items.filter { it.bucketId == at.bucketId }
        }
        Viewer(
            items = list,
            startIndex = at.index,
            onClose = { viewer = null },
            onShare = { share(listOf(it)) },
            onTrash = { trash(listOf(it), toTrash = true) },
        )
    }

    askShare?.let { items ->
        val cannot = items.count { !Share.canClean(it) }
        AlertDialog(
            onDismissRequest = { askShare = null },
            title = { Text(stringResource(R.string.share_location_title)) },
            text = { Text(pluralStringResource(R.plurals.share_location_text, cannot, cannot)) },
            confirmButton = {
                TextButton(onClick = { askShare = null; doShare(items, clean = true) }) {
                    Text(stringResource(R.string.share_as_is))
                }
            },
            dismissButton = {
                Box {
                    androidx.compose.foundation.layout.Row {
                        TextButton(onClick = { askShare = null }) { Text(stringResource(R.string.cancel)) }
                        val cleanable = items.filter { Share.canClean(it) }
                        if (cleanable.isNotEmpty()) {
                            TextButton(onClick = { askShare = null; doShare(cleanable, clean = true) }) {
                                Text(stringResource(R.string.share_without_them))
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Content(
    page: Page,
    padding: PaddingValues,
    state: GalleryState,
    folderTimeline: List<ua.svitlyna.media.TimelineEntry>?,
    columns: Int,
    onColumns: (Int) -> Unit,
    selected: Set<Long>,
    onToggle: (MediaItem) -> Unit,
    onToggleDay: (java.time.LocalDate) -> Unit,
    onOpen: (Int) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    access: AccessLevel,
    canManageMedia: Boolean,
    removeLocation: Boolean,
    onRemoveLocation: (Boolean) -> Unit,
    onPickMore: () -> Unit,
) {
    when (page) {
        Page.Photos -> if (state.items.isEmpty()) {
            EmptyNote(stringResource(R.string.no_photos))
        } else {
            MediaGrid(state.timeline, columns, onColumns, selected, onOpen, onToggle, onToggleDay, contentPadding = padding)
        }
        Page.Folders -> FoldersScreen(state.folders, onOpenFolder, padding)
        is Page.InFolder -> MediaGrid(
            folderTimeline.orEmpty(), columns, onColumns, selected, onOpen, onToggle, onToggleDay, contentPadding = padding,
        )
        Page.Trash -> TrashScreen(state.trash, selected, onToggle, padding)
        Page.Settings -> SettingsScreen(access, canManageMedia, removeLocation, onRemoveLocation, onPickMore, padding)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
