package ua.svitlyna.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ua.svitlyna.data.ThemeMode

/**
 * Svitlyna: Material colours from one of the shared themes ([Theme]); the phone's own (Material You)
 * for the system ones, or Android's plain colours on Android 11, which has none.
 */
fun colorScheme(theme: Theme, context: Context): ColorScheme {
    val p = theme.palette ?: return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (theme.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        theme.dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    fun c(v: Long) = Color(v)
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(p.primary),
        onPrimary = c(p.onPrimary),
        primaryContainer = c(mix(p.surface, p.primary, 0.2)),
        onPrimaryContainer = c(p.onSurface),
        secondary = c(p.secondary),
        onSecondary = c(p.onPrimary),
        secondaryContainer = c(mix(p.surface, p.secondary, 0.25)),
        onSecondaryContainer = c(p.onSurface),
        tertiary = c(p.secondary),
        onTertiary = c(p.onPrimary),
        background = c(p.background),
        onBackground = c(p.onSurface),
        surface = c(p.background),
        onSurface = c(p.onSurface),
        surfaceVariant = c(p.surfaceVariant),
        onSurfaceVariant = c(p.onSurfaceVariant),
        // Bars and sheets step up from the background towards the cards' colour
        surfaceContainerLowest = c(p.background),
        surfaceContainerLow = c(mix(p.background, p.surface, 0.5)),
        surfaceContainer = c(p.surface),
        surfaceContainerHigh = c(mix(p.surface, p.surfaceVariant, 0.5)),
        surfaceContainerHighest = c(p.surfaceVariant),
        surfaceBright = c(p.surface),
        surfaceDim = c(p.background),
        outline = c(p.onSurfaceVariant),
        outlineVariant = c(mix(p.surfaceVariant, p.onSurfaceVariant, 0.3)),
        error = c(p.error),
        errorContainer = c(mix(p.surface, p.error, if (theme.dark) 0.25 else 0.12)),
        onErrorContainer = c(p.onSurface),
    )
}

/** The theme shown now: the day or the night one, as [themeMode] and the phone say. */
fun currentTheme(themeMode: ThemeMode, phoneIsDark: Boolean, dayTheme: String, nightTheme: String): Theme {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> phoneIsDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    return if (dark) Theme.of(nightTheme, Theme.SYSTEM_DARK).takeIf { it.dark } ?: Theme.SYSTEM_DARK
    else Theme.of(dayTheme, Theme.SYSTEM).takeIf { !it.dark } ?: Theme.SYSTEM
}

@Composable
fun IrisTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dayTheme: String = Theme.SYSTEM.key,
    nightTheme: String = Theme.SYSTEM_DARK.key,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val theme = currentTheme(themeMode, isSystemInDarkTheme(), dayTheme, nightTheme)
    val isDark = theme.dark
    val colors = colorScheme(theme, context)

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
