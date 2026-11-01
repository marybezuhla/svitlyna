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
import ua.svitlyna.edit.StitchMaker
import ua.svitlyna.media.Timeline
import ua.svitlyna.screenshots.Screenshots

private sealed interface Page {
    data object Photos : Page
    data object Folders : Page
    data class InFolder(val folder: Folder) : Page
    data object Trash : Page
    data object Settings : Page
    data object FixDates : Page
}

private enum class RequestKind { TRASH, RESTORE, DELETE }

/** Android passes the list of photos between apps in one message of limited size. */
private const val REQUEST_CHUNK = 500

/** Which photos the open viewer swipes through: all of them, or one folder. */
private data class ViewerAt(val bucketId: Long?, val index: Int, val readText: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(openRequest: MutableState<OpenRequest?>? = null, vm: GalleryViewModel = viewModel()) {
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
    // One photo is sent through "Before sending" (check, black out, size); several go as before.
    var prepare by remember { mutableStateOf<MediaItem?>(null) }
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

    // Fix date taken: candidates, the chosen ones, Android's write permission, then the writing.
    val fixes = remember(state.items) { FixDates.candidates(state.items) }
    var fixChosen by remember(fixes) { mutableStateOf(fixes.map { it.item.id }.toSet()) }
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
    val visibleItems = folderContent?.items ?: state.items
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
            page == Page.FixDates -> goTo(Page.Settings)
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
                    Page.Photos -> stringResource(R.string.app_name)
                    Page.Folders -> stringResource(R.string.folders)
                    is Page.InFolder -> p.folder.name.ifBlank { stringResource(R.string.no_folder_name) }
                    Page.Trash -> stringResource(R.string.trash)
                    Page.Settings -> stringResource(R.string.settings)
                    Page.FixDates -> stringResource(R.string.fix_dates)
                }
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (page is Page.InFolder || page == Page.Trash || page == Page.Settings || page == Page.FixDates) {
                            IconButton(onClick = {
                                goTo(
                                    when (page) {
                                        is Page.InFolder -> Page.Folders
                                        Page.FixDates -> Page.Settings
                                        else -> lastTab
                                    },
                                )
                            }) {
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
                    onFixDates = { goTo(Page.FixDates) },
                    fixDatesCount = fixes.size,
                    fixes = fixes,
                    fixChosen = fixChosen,
                    onFixChosen = { fixChosen = it },
                    onFix = ::fixDates,
                    fixing = fixing,
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
            if (page == Page.Photos && selected.isEmpty() && oldShots.isNotEmpty() && !tempLater && !state.loading) {
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
            viewer = ViewerAt(null, index, wanted.readText)
            openRequest.value = null
        } else if (!state.loading) {
            vm.start(accessChanged = false)
        }
    }

    viewer?.let { at ->
        val list = if (at.bucketId == null) state.items else state.byFolder[at.bucketId]?.items.orEmpty()
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
            onKeep = ::toggleKept,
        )
        }
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
        Page.Settings -> SettingsScreen(
            access, canManageMedia, removeLocation, onRemoveLocation, onPickMore, screenshotSettings, onFixDates, fixDatesCount, padding,
        )
        Page.FixDates -> FixDatesScreen(fixes, fixChosen, onFixChosen, onFix, fixing, padding)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
