package ua.svitlyna.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.svitlyna.BuildConfig
import ua.svitlyna.R
import ua.svitlyna.media.Folder
import ua.svitlyna.media.MediaItem

/** First start, or access was taken away: explains and asks. */
@Composable
fun AccessScreen(onRequest: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.access_explain), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.no_internet_note),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.allow_access)) }
        TextButton(onClick = { context.startActivity(Access.appSettings(context)) }) {
            Text(stringResource(R.string.open_app_settings))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersScreen(
    folders: List<Folder>,
    hidden: Set<Long>,
    pinned: Set<Long>,
    sort: Int,
    onSort: (Int) -> Unit,
    onOpen: (Folder) -> Unit,
    onHide: (Folder) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                listOf(R.string.sort_newest, R.string.sort_name, R.string.sort_biggest).forEachIndexed { i, label ->
                    androidx.compose.material3.FilterChip(selected = sort == i, onClick = { onSort(i) }, label = { Text(stringResource(label)) })
                }
            }
        }
        items(folders, key = { it.id }) { folder ->
            Column(
                Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    // Long press: hide the folder from the timeline (or show it again).
                    .combinedClickable(onClick = { onOpen(folder) }, onLongClick = { onHide(folder) }),
            ) {
                Thumbnail(folder.cover, 384, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                Text(
                    folder.name.ifBlank { stringResource(R.string.no_folder_name) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
                Text(
                    (if (folder.id in pinned) "📌 " else "") + pluralStringResource(R.plurals.items, folder.count, folder.count) +
                        if (folder.id in hidden) " · " + stringResource(R.string.folder_hidden_mark) else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** The system trash: items stay there about 30 days, then Android deletes them for good. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    trash: List<MediaItem>,
    selected: Set<Long>,
    onToggle: (MediaItem) -> Unit,
    contentPadding: PaddingValues,
) {
    if (trash.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.trash_empty), textAlign = TextAlign.Center)
        }
        return
    }
    val now = System.currentTimeMillis()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.trash_explain),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        items(trash, key = { it.id }) { item ->
            val days = if (item.expiresMs > 0) ((item.expiresMs - now) / 86_400_000L).coerceAtLeast(0).toInt() else -1
            Cell(
                item = item,
                px = 256,
                selected = item.id in selected,
                badge = if (days >= 0) pluralStringResource(R.plurals.days_left, days, days) else null,
                modifier = Modifier.combinedClickable(onClick = { onToggle(item) }, onLongClick = { onToggle(item) }),
            )
        }
    }
}

class ScreenshotSettings(
    val hints: Boolean,
    val cropNavigation: Boolean,
    val autoCrop: Boolean,
    val onHints: (Boolean) -> Unit,
    val onCropNavigation: (Boolean) -> Unit,
    val onAutoCrop: (Boolean) -> Unit,
    val temporary: Boolean,
    val days: Int,
    val onTemporary: (Boolean) -> Unit,
    val onDays: (Int) -> Unit,
)

@Composable
private fun SwitchRow(title: String, explain: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (explain != null) Text(explain, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SettingsScreen(
    level: AccessLevel,
    canManageMedia: Boolean,
    removeLocation: Boolean,
    onRemoveLocation: (Boolean) -> Unit,
    onPickMore: () -> Unit,
    screenshots: ScreenshotSettings,
    onFixDates: () -> Unit,
    fixDatesCount: Int,
    onDuplicates: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    fun open(intent: Intent?) {
        if (intent == null) return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Access.appSettings(context))
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(stringResource(R.string.settings_access))
        Text(
            stringResource(
                when (level) {
                    AccessLevel.ALL -> R.string.access_all
                    AccessLevel.SELECTED -> R.string.access_selected
                    AccessLevel.NONE -> R.string.access_none
                },
            ),
        )
        if (level == AccessLevel.SELECTED) {
            OutlinedButton(onClick = onPickMore) { Text(stringResource(R.string.pick_more)) }
        }
        OutlinedButton(onClick = { open(Access.appSettings(context)) }) { Text(stringResource(R.string.change_access)) }

        if (Access.mediaManagementAvailable()) {
            Text(stringResource(R.string.media_management), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.media_management_explain), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (canManageMedia) R.string.media_management_on else R.string.media_management_off))
            OutlinedButton(onClick = { open(Access.mediaManagementSettings(context)) }) {
                Text(stringResource(R.string.media_management_change))
            }
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_sharing))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.remove_location))
                Text(stringResource(R.string.remove_location_explain), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = removeLocation, onCheckedChange = onRemoveLocation)
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_screenshots))
        SwitchRow(
            stringResource(R.string.screenshot_hints),
            stringResource(R.string.screenshot_hints_explain),
            screenshots.hints,
            screenshots.onHints,
        )
        SwitchRow(
            stringResource(R.string.crop_navigation),
            null,
            screenshots.cropNavigation,
            screenshots.onCropNavigation,
            enabled = screenshots.hints || screenshots.autoCrop,
        )
        SwitchRow(
            stringResource(R.string.auto_crop),
            stringResource(R.string.auto_crop_explain),
            screenshots.autoCrop,
            screenshots.onAutoCrop,
        )
        SwitchRow(
            stringResource(R.string.temp_screenshots),
            stringResource(R.string.temp_screenshots_explain),
            screenshots.temporary,
            screenshots.onTemporary,
        )
        if (screenshots.temporary) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in listOf(7, 30, 90)) {
                    androidx.compose.material3.FilterChip(
                        selected = screenshots.days == d,
                        onClick = { screenshots.onDays(d) },
                        label = { Text(pluralStringResource(R.plurals.after_days, d, d)) },
                    )
                }
            }
        }

        HorizontalDivider()
        Section(stringResource(R.string.fix_dates))
        Text(pluralStringResource(R.plurals.fix_dates_found, fixDatesCount, fixDatesCount), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onFixDates) { Text(stringResource(R.string.fix_dates_open)) }

        OutlinedButton(onClick = onDuplicates) { Text(stringResource(R.string.dupes)) }

        HorizontalDivider()
        Section(stringResource(R.string.settings_look))
        val prefs = androidx.compose.runtime.remember { Prefs(context) }
        var amoled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.amoled) }
        SwitchRow(stringResource(R.string.amoled), stringResource(R.string.amoled_explain), amoled, { on ->
            amoled = on
            prefs.amoled = on
            (context as? android.app.Activity)?.recreate()
        })
        var startTab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(prefs.startTab) }
        Text(stringResource(R.string.start_tab), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.photos, R.string.folders).forEachIndexed { i, label ->
                androidx.compose.material3.FilterChip(selected = startTab == i, onClick = { startTab = i; prefs.startTab = i }, label = { Text(stringResource(label)) })
            }
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_lock))
        var lock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.appLock) }
        var noLockScreen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        SwitchRow(stringResource(R.string.app_lock), stringResource(R.string.app_lock_explain), lock, { on ->
            val activity = context as? androidx.fragment.app.FragmentActivity ?: return@SwitchRow
            if (on && !AppLock.deviceSecure(activity)) {
                noLockScreen = true
                return@SwitchRow
            }
            // Turning it on or off is confirmed with the fingerprint or PIN itself.
            AppLock.ask(activity) {
                lock = on
                prefs.appLock = on
                AppLock.applyRecents(activity, on)
            }
        })
        if (noLockScreen) Text(stringResource(R.string.app_lock_no_screen_lock), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        Access.languageSettings(context)?.let { intent ->
            HorizontalDivider()
            Section(stringResource(R.string.settings_language))
            OutlinedButton(onClick = { open(intent) }) { Text(stringResource(R.string.change_language)) }
        }

        HorizontalDivider()
        Section(stringResource(R.string.settings_about))
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.about_certificate), style = MaterialTheme.typography.bodySmall)
        Text(CERTIFICATE, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.about_licence), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }) {
            Text(stringResource(R.string.about_source))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private const val SOURCE_URL = "https://github.com/Marybezu/svitlyna"
private const val CERTIFICATE = "53027b1a87085eb9e40a74820ff2aa067dd3350a0e3010a8f6c559e04a628630"

/**
 * Where to move or copy photos: an existing folder or a new one (Pictures/<name>). Moving asks
 * Android first; copying makes new files and keeps the originals where they are.
 */
@Composable
fun FolderPickDialog(folders: List<Folder>, onDismiss: () -> Unit, onPick: (path: String, copy: Boolean) -> Unit) {
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var newName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val target = if (newName.isNotBlank()) ua.svitlyna.media.FileOps.newFolderPath(newName) else chosen
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.to_folder)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.new_folder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                folders.forEach { f ->
                    val path = f.cover.relativePath
                    if (path.isBlank()) return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { chosen = path; newName = "" }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = newName.isBlank() && chosen == path, onClick = { chosen = path; newName = "" })
                        Text(f.name.ifBlank { path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = target != null, onClick = { target?.let { onPick(it, true) } }) { Text(stringResource(R.string.copy_here)) }
                TextButton(enabled = target != null, onClick = { target?.let { onPick(it, false) } }) { Text(stringResource(R.string.move_here)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Shown instead of the gallery while the app is locked. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.lock_open)) }
    }
}
