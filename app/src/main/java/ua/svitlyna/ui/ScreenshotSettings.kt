package ua.svitlyna.ui

import androidx.compose.foundation.selection.toggleable
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.svitlyna.R
import ua.svitlyna.core.Prefs
import ua.svitlyna.screenshots.Screenshots

/** Svitlyna's screenshot tools, in the settings: hints after a screenshot, automatic cropping of the bars. */
@Composable
fun ScreenshotSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var hints by remember { mutableStateOf(prefs.screenshotHints) }
    var autoCrop by remember { mutableStateOf(prefs.autoCrop) }
    var cropNav by remember { mutableStateOf(prefs.cropNavigation) }
    // The notification permission is asked only when hints are switched on.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hints = ok
        prefs.screenshotHints = ok
        Screenshots.schedule(context)
    }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sv_settings_screenshots), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Toggle(stringResource(R.string.sv_screenshot_hints), stringResource(R.string.sv_screenshot_hints_explain), hints) { on ->
            if (on && Build.VERSION.SDK_INT >= 33) {
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                hints = on
                prefs.screenshotHints = on
                Screenshots.schedule(context)
            }
        }
        Toggle(stringResource(R.string.sv_auto_crop), stringResource(R.string.sv_auto_crop_explain), autoCrop) { on ->
            autoCrop = on
            prefs.autoCrop = on
            Screenshots.schedule(context)
        }
        Toggle(stringResource(R.string.sv_crop_navigation), null, cropNav) { on ->
            cropNav = on
            prefs.cropNavigation = on
        }
        remember { TempShots.load(context); true }
        Toggle(stringResource(R.string.sv_temp_screenshots), stringResource(R.string.sv_temp_screenshots_explain), TempShots.enabled) { on ->
            TempShots.setEnabled(context, on)
        }
        if (TempShots.enabled) {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for (d in listOf(7, 30, 90)) {
                    androidx.compose.material3.FilterChip(
                        selected = TempShots.days == d,
                        onClick = { TempShots.setDays(context, d) },
                        label = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.sv_after_days, d, d)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, explain: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (explain != null) Text(explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
