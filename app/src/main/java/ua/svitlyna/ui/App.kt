package ua.svitlyna.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import ua.svitlyna.edit.FixDates
import ua.svitlyna.media.FileOps
import ua.svitlyna.edit.StitchMaker
import ua.svitlyna.media.Filter
import ua.svitlyna.media.Timeline
import ua.svitlyna.media.matches
import ua.svitlyna.screenshots.Screenshots

private sealed interface Page {
    data object Photos : Page
    data object Folders : Page
    data class InFolder(val folder: Folder) : Page
    data object Trash : Page
    data object Settings : Page
    data object FixDates : Page
    data object Duplicates : Page
}

private enum class RequestKind { TRASH, RESTORE, DELETE }

/** Android passes the list of photos between apps in one message of limited size. */
private const val REQUEST_CHUNK = 500

/** Which photos the open viewer swipes through: all of them, or one folder. */
private data class ViewerAt(val bucketId: Long?, val index: Int, val readText: Boolean = false, val unfiltered: Boolean = false)

/** The timeline as shown: filtered by kind and without hidden folders. */
private class Shown(val items: List<MediaItem>, val timeline: List<ua.svitlyna.media.TimelineEntry>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(openRequest: MutableState<OpenRequest?>? = null, pick: PickRequest? = null, vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsState()

    var access by remember { mutableStateOf(Access.level(context)) }
    var canManageMedia by remember { mutableStateOf(Access.canManageMedia(context)) }
    var page by remember { mutableStateOf<Page>(if (prefs.startTab == 1) Page.Folders else Page.Photos) }
    var lastTab by remember { mutableStateOf(page) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var viewer by remember { mutableStateOf<ViewerAt?>(null) }
    var columns by remember { mutableIntStateOf(prefs.columns) }
    var removeLocation by remember { mutableStateOf(prefs.removeLocation) }
    var askShare by remember { mutableStateOf<List<MediaItem>?>(null) }
    // One photo is sent through "Before sending" (check, black out, size); several go as before.
    var prepare by remember { mutableStateOf<MediaItem?>(null) }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    // Timeline filter (photos, videos, screenshots…) and folders hidden from the timeline.
    var filter by remember {
        mutableStateOf(
            when {
                pick == null -> Filter.ALL
                pick.photos && !pick.videos -> Filter.PHOTOS
                pick.videos && !pick.photos -> Filter.VIDEOS
                else -> Filter.ALL
            },
        )
    }
    var hidden by remember { mutableStateOf(prefs.hiddenFolders) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    LaunchedEffect(state.items, state.timeline, filter, hidden) {
        shown = if (filter == Filter.ALL && hidden.isEmpty()) {
            Shown(state.items, state.timeline)
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val list = state.items.filter { it.bucketId !in hidden && it.matches(filter) }
                Shown(list, Timeline.byDay(list))
            }
        }
    }
    var pinned by remember { mutableStateOf(prefs.pinnedFolders) }
    var folderSort by remember { mutableIntStateOf(prefs.folderSort) }
    val sortedFolders = remember(state.folders, pinned, folderSort) {
        val byChoice = when (folderSort) {
            1 -> state.folders.sortedBy { it.name.lowercase() }
            2 -> state.folders.sortedByDescending { it.count }
            else -> state.folders
        }
        byChoice.sortedByDescending { it.id in pinned }
    }
    var folderMenu by remember { mutableStateOf<Folder?>(null) }
    fun togglePinned(folder: Folder) {
        pinned = if (folder.id in pinned) pinned - folder.id else pinned + folder.id
        prefs.pinnedFolders = pinned
    }
    fun toggleHidden(folder: Folder) {
        hidden = if (folder.id in hidden) hidden - folder.id else hidden + folder.id
        prefs.hiddenFolders = hidden
    }
    // Temporary screenshots: an offer to move old ones to the trash (Android asks to confirm).
    var tempOn by remember { mutableStateOf(prefs.tempScreenshots) }
    var tempDays by remember { mutableIntStateOf(prefs.tempDays) }
    var kept by remember { mutableStateOf(prefs.keptScreenshots) }
    var tempLater by remember { mutableStateOf(System.currentTimeMillis() < prefs.tempLaterUntil) }
    // "Keep" marks of screenshots that are gone are dropped, so the list doesn't only grow.
    LaunchedEffect(state.items) {
        if (state.loading || kept.isEmpty()) return@LaunchedEffect
        val present = state.items.mapTo(HashSet()) { it.id }
        val still = kept.filterTo(HashSet()) { it in present }
        if (still.size != kept.size) {
            kept = still
            prefs.keptScreenshots = still
        }
    }
    val oldShots = remember(state.items, tempOn, tempDays, kept) {
        if (!tempOn) {
            emptyList()
        } else {
            val limit = System.currentTimeMillis() - tempDays * 86_400_000L
            state.items.filter { it.isScreenshot && it.dateMs < limit && it.id !in kept }
        }
    }
    fun toggleKept(item: MediaItem) {
        kept = if (item.id in kept) kept - item.id else kept + item.id
        prefs.keptScreenshots = kept
    }
    var hints by remember { mutableStateOf(prefs.screenshotHints) }
    var cropNavigation by remember { mutableStateOf(prefs.cropNavigation) }
    var autoCrop by remember { mutableStateOf(prefs.autoCrop) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hints = granted
        prefs.screenshotHints = granted
        Screenshots.schedule(context)
    }
    fun setHints(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        hints = on
        prefs.screenshotHints = on
        Screenshots.schedule(context)
    }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    // Big requests go to Android in parts, one confirmation window after another.
    var pendingRequests by remember { mutableStateOf(emptyList<List<MediaItem>>()) }
    var pendingKind by remember { mutableStateOf(RequestKind.TRASH) }

    // Access can change in Android settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val level = Access.level(context)
        val changed = level != access
        access = level
        canManageMedia = Access.canManageMedia(context)
        if (access != AccessLevel.NONE) vm.start(accessChanged = changed)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = Access.level(context)
        if (access != AccessLevel.NONE) vm.start(accessChanged = true)
    }
    // Updated from a version without "media location": asked once, so saved copies keep the location.
    LaunchedEffect(access) {
        if (access != AccessLevel.NONE &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !prefs.askedMediaLocation
        ) {
            prefs.askedMediaLocation = true
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
        }
    }

    // Moving to the trash, restoring and deleting for good: Android asks the person itself
    // (no window when "Media management" is switched on).
    lateinit var launchNext: () -> Unit
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selected = emptySet()
        // Cancelled in Android's window: the remaining parts are dropped too.
        if (result.resultCode != android.app.Activity.RESULT_OK) pendingRequests = emptyList()
        launchNext()
        // The media list's own change notice reloads the gallery.
    }

    launchNext = {
        val next = pendingRequests.firstOrNull()
        if (next != null) {
            pendingRequests = pendingRequests.drop(1)
            try {
                val uris = next.map { it.uri }
                val pi = when (pendingKind) {
                    RequestKind.TRASH -> MediaStore.createTrashRequest(resolver, uris, true)
                    RequestKind.RESTORE -> MediaStore.createTrashRequest(resolver, uris, false)
                    RequestKind.DELETE -> MediaStore.createDeleteRequest(resolver, uris)
                }
                writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                pendingRequests = emptyList()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.request_failed)) }
            }
        }
    }

    fun request(kind: RequestKind, items: List<MediaItem>) {
        if (items.isEmpty()) return
        pendingKind = kind
        pendingRequests = items.chunked(REQUEST_CHUNK)
        launchNext()
    }

    fun trash(items: List<MediaItem>, toTrash: Boolean) =
        request(if (toTrash) RequestKind.TRASH else RequestKind.RESTORE, items)

    /** Deleting for good can't be undone, so the app asks first (Android may not, with Media management on). */
    fun deleteForGood(items: List<MediaItem>) {
        if (items.isNotEmpty()) confirmDelete = items
    }

    // Changes to the person's files (move, rename, favourite) go through Android's own permission
    // window first; the action runs only after a "yes".
    var afterWrite by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val writeThenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = afterWrite
        afterWrite = null
        if (result.resultCode == android.app.Activity.RESULT_OK && action != null) scope.launch { action() }
    }
    fun askToWrite(pi: android.app.PendingIntent, action: suspend () -> Unit) {
        afterWrite = action
        try {
            writeThenLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            afterWrite = null
            scope.launch { snackbar.showSnackbar(context.getString(R.string.request_failed)) }
        }
    }
    fun tooMany(items: List<MediaItem>): Boolean {
        if (items.size <= REQUEST_CHUNK) return false
        scope.launch { snackbar.showSnackbar(context.getString(R.string.too_many_selected, REQUEST_CHUNK)) }
        return true
    }
    fun favorite(items: List<MediaItem>, on: Boolean) {
        if (items.isEmpty() || tooMany(items)) return
        runCatching { MediaStore.createFavoriteRequest(resolver, items.take(REQUEST_CHUNK).map { it.uri }, on) }
            .onSuccess { askToWrite(it) { selected = emptySet() } }
    }
    var moveItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    fun moveOrCopy(items: List<MediaItem>, path: String, copy: Boolean) {
        if (tooMany(items)) return
        val todo = items
        if (copy) {
            scope.launch {
                val n = FileOps.copy(context, todo, path)
                selected = emptySet()
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.copied_n, n, n, todo.size))
            }
        } else {
            runCatching { MediaStore.createWriteRequest(resolver, todo.map { it.uri }) }.onSuccess { pi ->
                askToWrite(pi) {
                    val n = FileOps.move(context, todo, path)
                    selected = emptySet()
                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.moved_n, n, n, todo.size))
                }
            }
        }
    }
    var renaming by remember { mutableStateOf<MediaItem?>(null) }

    // Duplicates: found on request, in the background, with Stop.
    var dupGroups by remember { mutableStateOf<List<ua.svitlyna.media.Duplicates.Group>?>(null) }
    var dupProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var dupJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun findDuplicates() {
        dupProgress = 0 to 0
        dupJob = scope.launch {
            val found = ua.svitlyna.media.Duplicates.find(context, state.items) { d, t ->
                scope.launch { dupProgress = d to t }
            }
            dupGroups = found
            dupProgress = null
        }
        dupJob?.invokeOnCompletion { dupProgress = null }
    }

    // Fix date taken: candidates, the chosen ones, Android's write permission, then the writing.
    val fixes = remember(state.items) { FixDates.candidates(state.items) }
    // What the person un-ticked stays un-ticked when the gallery changes.
    var fixExcluded by remember { mutableStateOf(emptySet<Long>()) }
    val fixChosen = remember(fixes, fixExcluded) { fixes.map { it.item.id }.filterTo(HashSet()) { it !in fixExcluded } }
    var fixing by remember { mutableStateOf(false) }
    var pendingFix by remember { mutableStateOf(emptyList<FixDates.Fix>()) }
    val fixLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val todo = pendingFix
        pendingFix = emptyList()
        if (result.resultCode != android.app.Activity.RESULT_OK || todo.isEmpty()) {
            fixing = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val done = FixDates.apply(context, todo)
            fixing = false
            snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.fix_dates_done, done, done))
        }
    }
    fun fixDates() {
        val todo = fixes.filter { it.item.id in fixChosen }.take(REQUEST_CHUNK)
        if (todo.isEmpty()) return
        fixing = true
        pendingFix = todo
        try {
            val pi = MediaStore.createWriteRequest(resolver, todo.map { it.item.uri })
            fixLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            fixing = false
            pendingFix = emptyList()
            scope.launch { snackbar.showSnackbar(context.getString(R.string.request_failed)) }
        }
    }

    // Stitching screenshots into one long one: runs in the background with a Stop button.
    var stitching by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var askStack by remember { mutableStateOf<List<MediaItem>?>(null) }
    fun canStitch(items: List<MediaItem>) = items.size >= 2 && items.all { !it.isVideo } &&
        items.map { it.width }.distinct().size == 1 && items.map { it.height }.distinct().size == 1
    fun stitch(items: List<MediaItem>, stack: Boolean) {
        stitching = scope.launch {
            val result = StitchMaker.make(context, items, stack)
            stitching = null
            when (result) {
                is StitchMaker.Result.Saved -> {
                    selected = emptySet()
                    val id = result.uri.lastPathSegment?.toLongOrNull()
                    val open = snackbar.showSnackbar(
                        context.getString(R.string.stitch_saved),
                        actionLabel = context.getString(R.string.open),
                        duration = androidx.compose.material3.SnackbarDuration.Long,
                    )
                    if (open == androidx.compose.material3.SnackbarResult.ActionPerformed && id != null) {
                        openRequest?.value = OpenRequest(id, readText = false)
                    }
                }
                StitchMaker.Result.NoOverlap -> askStack = items
                StitchMaker.Result.TooTall -> snackbar.showSnackbar(context.getString(R.string.stitch_too_tall))
                StitchMaker.Result.Failed -> snackbar.showSnackbar(context.getString(R.string.stitch_failed))
            }
        }
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
        val one = items.singleOrNull()
        // GIFs would lose their movement in a fresh copy: they go the usual way.
        if (one != null && !one.isVideo && !one.mime.equals("image/gif", ignoreCase = true)) {
            prepare = one
            return
        }
        if (removeLocation && items.any { !Share.canClean(it) }) askShare = items else doShare(items, removeLocation)
    }

    val folderId = (page as? Page.InFolder)?.folder?.id
    val folderContent = folderId?.let { state.byFolder[it] }
    val folderTimeline = folderContent?.timeline
    val visibleItems = folderContent?.items ?: shown?.items ?: state.items
    val selectedItems = remember(selected, state.items, state.trash, page) {
        (if (page == Page.Trash) state.trash else state.items).filter { it.id in selected }
    }

    fun toggle(item: MediaItem) {
        if (pick != null) {
            // Choosing for another app: only the kinds it asked for; one photo is handed over at once.
            if (!pick.accepts(item)) {
                scope.launch { snackbar.showSnackbar(context.getString(R.string.pick_not_this_kind)) }
                return
            }
            if (!pick.multiple) {
                pick.onPicked(listOf(item))
                return
            }
        }
        selected = if (item.id in selected) selected - item.id else selected + item.id
    }

    fun toggleDay(date: java.time.LocalDate) {
        if (pick != null && !pick.multiple) return
        val zone = java.time.ZoneId.systemDefault()
        val ids = visibleItems.filter { Timeline.dayOf(it.dateMs, zone) == date && (pick == null || pick.accepts(it)) }.map { it.id }.toSet()
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
            page == Page.FixDates || page == Page.Duplicates -> goTo(Page.Settings)
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
                        if (pick != null) {
                            TextButton(onClick = { pick.onPicked(selectedItems.filter(pick::accepts)) }) { Text(stringResource(R.string.pick_done)) }
                        } else if (page == Page.Trash) {
                            IconButton(onClick = { trash(selectedItems, toTrash = false) }) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.restore))
                            }
                            IconButton(onClick = { deleteForGood(selectedItems) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete_for_good))
                            }
                        } else {
                            TextButton(onClick = { moveItems = selectedItems }) { Text(stringResource(R.string.to_folder)) }
                            TextButton(onClick = { favorite(selectedItems, !selectedItems.all { it.isFavorite }) }) {
                                Text(if (selectedItems.all { it.isFavorite }) "♡" else "♥")
                            }
                            if (canStitch(selectedItems)) {
                                TextButton(enabled = stitching == null, onClick = { stitch(selectedItems, stack = false) }) {
                                    Text(stringResource(R.string.stitch))
                                }
                            }
                            IconButton(onClick = { share(selectedItems) }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                            IconButton(onClick = { trash(selectedItems, toTrash = true) }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.move_to_trash))
                            }
                        }
                    },
                )
            } else {
                val title = when (val p = page) {
                    Page.Photos -> stringResource(if (pick != null) R.string.pick_title else R.string.app_name)
                    Page.Folders -> stringResource(R.string.folders)
                    is Page.InFolder -> p.folder.name.ifBlank { stringResource(R.string.no_folder_name) }
                    Page.Trash -> stringResource(R.string.trash)
                    Page.Settings -> stringResource(R.string.settings)
                    Page.FixDates -> stringResource(R.string.fix_dates)
                    Page.Duplicates -> stringResource(R.string.dupes)
                }
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (page is Page.InFolder || page == Page.Trash || page == Page.Settings || page == Page.FixDates || page == Page.Duplicates) {
                            IconButton(onClick = {
                                goTo(
                                    when (page) {
                                        is Page.InFolder -> Page.Folders
                                        Page.FixDates, Page.Duplicates -> Page.Settings
                                        else -> lastTab
                                    },
                                )
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (pick == null && (page == Page.Photos || page == Page.Folders)) {
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
                    onOpen = { index ->
                        if (pick != null) visibleItems.getOrNull(index)?.let(::toggle)
                        else viewer = ViewerAt((page as? Page.InFolder)?.folder?.id, index)
                    },
                    onOpenFolder = { goTo(Page.InFolder(it)) },
                    access = access,
                    canManageMedia = canManageMedia,
                    removeLocation = removeLocation,
                    onRemoveLocation = { removeLocation = it; prefs.removeLocation = it },
                    onPickMore = { permissionLauncher.launch(Access.permissionsToRequest()) },
                    onFixDates = { goTo(Page.FixDates) },
                    fixDatesCount = fixes.size,
                    fixes = fixes,
                    fixChosen = fixChosen,
                    onFixChosen = { chosen -> fixExcluded = fixes.map { it.item.id }.filterTo(HashSet()) { it !in chosen } },
                    onFix = ::fixDates,
                    fixing = fixing,
                    filter = filter,
                    onFilter = { filter = it },
                    shownTimeline = shown?.timeline ?: state.timeline,
                    hidden = hidden,
                    onHideFolder = { f -> folderMenu = f },
                    sortedFolders = sortedFolders,
                    dupGroups = dupGroups,
                    dupProgress = dupProgress,
                    onFindDupes = ::findDuplicates,
                    onStopDupes = { dupJob?.cancel() },
                    onTrashDupes = {
                        val extra = dupGroups.orEmpty().flatMap { it.extra }
                        dupGroups = null
                        trash(extra, toTrash = true)
                    },
                    onOpenDupes = { goTo(Page.Duplicates) },
                    pinned = pinned,
                    folderSort = folderSort,
                    onFolderSort = { folderSort = it; prefs.folderSort = it },
                    screenshotSettings = ScreenshotSettings(
                        hints = hints,
                        cropNavigation = cropNavigation,
                        autoCrop = autoCrop,
                        onHints = ::setHints,
                        onCropNavigation = { cropNavigation = it; prefs.cropNavigation = it },
                        onAutoCrop = {
                            autoCrop = it
                            prefs.autoCrop = it
                            Screenshots.schedule(context)
                        },
                        temporary = tempOn,
                        days = tempDays,
                        onTemporary = { tempOn = it; prefs.tempScreenshots = it },
                        onDays = { tempDays = it; prefs.tempDays = it },
                    ),
                )
            }
            if (pick == null && page == Page.Photos && selected.isEmpty() && oldShots.isNotEmpty() && !tempLater && !state.loading) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(padding)
                        .padding(12.dp),
                ) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pluralStringResource(R.plurals.old_screenshots, oldShots.size, oldShots.size, tempDays),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            tempLater = true
                            // Asked again in a week, not at every start.
                            prefs.tempLaterUntil = System.currentTimeMillis() + 7 * 86_400_000L
                        }) { Text(stringResource(R.string.later)) }
                        TextButton(onClick = { tempLater = true; trash(oldShots, toTrash = true) }) {
                            Text(stringResource(R.string.move_to_trash))
                        }
                    }
                }
            }
        }
    }

    // A photo asked for from a screenshot hint: opened as soon as the gallery has it.
    val wanted = openRequest?.value
    LaunchedEffect(wanted, state.items) {
        if (wanted == null || access == AccessLevel.NONE) return@LaunchedEffect
        val index = state.items.indexOfFirst { it.id == wanted.id }
        if (index >= 0) {
            selected = emptySet()
            // Opened from outside the timeline's filter: swipes through everything.
            viewer = ViewerAt(null, index, wanted.readText, unfiltered = true)
            openRequest.value = null
        } else if (!state.loading) {
            vm.start(accessChanged = false)
        }
    }

    viewer?.let { at ->
        val list = when {
            at.bucketId != null -> state.byFolder[at.bucketId]?.items.orEmpty()
            at.unfiltered -> state.items
            else -> shown?.items ?: state.items
        }
        // A new request (a hint tapped while a photo is open) starts the viewer afresh at its photo.
        androidx.compose.runtime.key(at) {
        Viewer(
            items = list,
            startIndex = at.index,
            readTextAtStart = at.readText,
            onClose = { viewer = null },
            onShare = { share(listOf(it)) },
            onTrash = { trash(listOf(it), toTrash = true) },
            keep = { if (tempOn && it.isScreenshot) it.id in kept else null },
            onEdit = { editing = it },
            onFavorite = { favorite(listOf(it), !it.isFavorite) },
            onRename = { renaming = it },
            onKeep = ::toggleKept,
        )
        }
    }

    editing?.let { item ->
        EditorScreen(
            item,
            onClose = { editing = null },
            onSaved = { uri ->
                editing = null
                scope.launch {
                    val open = snackbar.showSnackbar(
                        context.getString(R.string.edit_saved),
                        actionLabel = context.getString(R.string.open),
                        duration = androidx.compose.material3.SnackbarDuration.Long,
                    )
                    val id = uri.lastPathSegment?.toLongOrNull()
                    if (open == androidx.compose.material3.SnackbarResult.ActionPerformed && id != null) {
                        viewer = null
                        openRequest?.value = OpenRequest(id, readText = false)
                    }
                }
            },
        )
    }

    prepare?.let { item ->
        PrepareScreen(
            item,
            onClose = { prepare = null },
            onSendAsIs = {
                prepare = null
                if (removeLocation && !Share.canClean(item)) askShare = listOf(item) else doShare(listOf(item), removeLocation)
            },
        )
    }

    askShare?.let { items ->
        val cannot = items.count { !Share.canClean(it) }
        AlertDialog(
            onDismissRequest = { askShare = null },
            title = { Text(stringResource(R.string.share_location_title)) },
            text = { Text(pluralStringResource(R.plurals.share_location_text, cannot, cannot)) },
            // The safe choice is the main button: send only what was cleaned.
            confirmButton = {
                val cleanable = items.filter { Share.canClean(it) }
                if (cleanable.isNotEmpty()) {
                    TextButton(onClick = { askShare = null; doShare(cleanable, clean = true) }) {
                        Text(stringResource(R.string.share_without_them))
                    }
                } else {
                    TextButton(onClick = { askShare = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { askShare = null; doShare(items, clean = true) }) {
                    Text(stringResource(R.string.share_as_is))
                }
            },
        )
    }

    folderMenu?.let { f ->
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text(f.name.ifBlank { stringResource(R.string.no_folder_name) }) },
            text = {
                Column {
                    TextButton(onClick = { togglePinned(f); folderMenu = null }) {
                        Text(stringResource(if (f.id in pinned) R.string.folder_unpin else R.string.folder_pin))
                    }
                    TextButton(onClick = {
                        toggleHidden(f)
                        folderMenu = null
                        scope.launch {
                            snackbar.showSnackbar(context.getString(if (f.id in hidden) R.string.folder_hidden else R.string.folder_shown, f.name))
                        }
                    }) {
                        Text(stringResource(if (f.id in hidden) R.string.folder_show else R.string.folder_hide))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { folderMenu = null }) { Text(stringResource(R.string.close)) } },
        )
    }

    moveItems?.let { items ->
        FolderPickDialog(
            folders = state.folders,
            videos = items.any { it.isVideo },
            photos = items.any { !it.isVideo },
            onDismiss = { moveItems = null },
            onPick = { path, copy ->
                moveItems = null
                moveOrCopy(items, path, copy)
            },
        )
    }
    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = null
                    runCatching { MediaStore.createWriteRequest(resolver, listOf(item.uri)) }.onSuccess { pi ->
                        askToWrite(pi) {
                            if (!FileOps.rename(context, item, name)) snackbar.showSnackbar(context.getString(R.string.request_failed))
                        }
                    }
                }) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    stitching?.let { job ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.stitching)) },
            text = { androidx.compose.material3.LinearProgressIndicator(Modifier.padding(top = 8.dp)) },
            confirmButton = {
                TextButton(onClick = { job.cancel(); stitching = null }) { Text(stringResource(R.string.stop)) }
            },
        )
    }

    askStack?.let { items ->
        AlertDialog(
            onDismissRequest = { askStack = null },
            title = { Text(stringResource(R.string.stitch_no_overlap_title)) },
            text = { Text(stringResource(R.string.stitch_no_overlap)) },
            confirmButton = {
                TextButton(onClick = { askStack = null; stitch(items, stack = true) }) { Text(stringResource(R.string.stitch_stack)) }
            },
            dismissButton = { TextButton(onClick = { askStack = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmDelete?.let { items ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_for_good)) },
            text = { Text(pluralStringResource(R.plurals.delete_confirm, items.size, items.size)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; request(RequestKind.DELETE, items) }) {
                    Text(stringResource(R.string.delete_for_good))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
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
    onFixDates: () -> Unit,
    fixDatesCount: Int,
    fixes: List<FixDates.Fix>,
    fixChosen: Set<Long>,
    onFixChosen: (Set<Long>) -> Unit,
    onFix: () -> Unit,
    fixing: Boolean,
    screenshotSettings: ScreenshotSettings,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    shownTimeline: List<ua.svitlyna.media.TimelineEntry>,
    hidden: Set<Long>,
    onHideFolder: (Folder) -> Unit,
    sortedFolders: List<Folder>,
    pinned: Set<Long>,
    folderSort: Int,
    onFolderSort: (Int) -> Unit,
    dupGroups: List<ua.svitlyna.media.Duplicates.Group>?,
    dupProgress: Pair<Int, Int>?,
    onFindDupes: () -> Unit,
    onStopDupes: () -> Unit,
    onTrashDupes: () -> Unit,
    onOpenDupes: () -> Unit,
) {
    when (page) {
        Page.Photos -> if (state.items.isEmpty()) {
            EmptyNote(stringResource(R.string.no_photos))
        } else {
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                FilterRow(filter, onFilter)
                MediaGrid(
                    shownTimeline, columns, onColumns, selected, onOpen, onToggle, onToggleDay,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                )
            }
        }
        Page.Folders -> FoldersScreen(sortedFolders, hidden, pinned, folderSort, onFolderSort, onOpenFolder, onHideFolder, padding)
        is Page.InFolder -> MediaGrid(
            folderTimeline.orEmpty(), columns, onColumns, selected, onOpen, onToggle, onToggleDay, contentPadding = padding,
        )
        Page.Trash -> TrashScreen(state.trash, selected, onToggle, padding)
        Page.Settings -> SettingsScreen(
            access, canManageMedia, removeLocation, onRemoveLocation, onPickMore, screenshotSettings, onFixDates, fixDatesCount, onOpenDupes, padding,
        )
        Page.FixDates -> FixDatesScreen(fixes, fixChosen, onFixChosen, onFix, fixing, padding)
        Page.Duplicates -> DuplicatesScreen(dupGroups, dupProgress, onFindDupes, onStopDupes, onTrashDupes, padding)
    }
}

@Composable
private fun FilterRow(filter: Filter, onFilter: (Filter) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        Filter.entries.forEach { f ->
            androidx.compose.material3.FilterChip(
                selected = filter == f,
                onClick = { onFilter(f) },
                label = {
                    Text(
                        stringResource(
                            when (f) {
                                Filter.ALL -> R.string.filter_all
                                Filter.FAVORITES -> R.string.filter_favorites
                                Filter.PHOTOS -> R.string.filter_photos
                                Filter.VIDEOS -> R.string.filter_videos
                                Filter.SCREENSHOTS -> R.string.filter_screenshots
                                Filter.GIF -> R.string.filter_gif
                                Filter.RAW -> R.string.filter_raw
                                Filter.PANORAMAS -> R.string.filter_panoramas
                            },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
