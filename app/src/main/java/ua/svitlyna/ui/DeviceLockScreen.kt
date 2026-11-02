package ua.svitlyna.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import ua.svitlyna.R
import ua.svitlyna.core.AppLock

/**
 * The lock screen: the phone's own fingerprint or PIN (Android checks it), asked at once and again
 * with the button. Replaces Iris Gallery's own PIN, which the app would have to store itself.
 */
@Composable
fun DeviceLockScreen(onUnlocked: () -> Unit, onNoScreenLock: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val ask = {
        if (activity != null) AppLock.ask(activity, onNoScreenLock = onNoScreenLock, onOk = onUnlocked)
    }
    LaunchedEffect(Unit) { ask() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF113F6B)), contentAlignment = Alignment.Center) {
                // The icon's circle is 72 of its 108 units: drawn at 1.5× so it fills the circle.
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(144.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.sv_lock_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = ask) {
                Icon(Icons.Rounded.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.sv_lock_open))
            }
        }
    }
}
