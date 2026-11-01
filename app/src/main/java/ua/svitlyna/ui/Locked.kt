package ua.svitlyna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * True while the lock screen covers the gallery. The gallery stays composed underneath (so work in
 * progress goes on), but anything that would show above the lock — dialogs (each is its own window),
 * Android's request windows, the Back button — waits until it is unlocked.
 */
val LocalLocked = compositionLocalOf { false }

/** [BackHandler] that does nothing while locked: Back then belongs to the lock screen. */
@Composable
fun GuardedBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled && !LocalLocked.current, onBack = onBack)
}
