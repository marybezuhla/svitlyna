package ua.svitlyna.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import ua.svitlyna.data.AccentColor
import ua.svitlyna.data.ThemeMode

// Svitlyna: every accent stays readable (WCAG AA, 4.5:1): the accent is darkened on light
// backgrounds and lightened on dark ones until it is, and the text on it is chosen by contrast.
private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return if (la > lb) la / lb.toDouble() else lb / la.toDouble()
}

private fun readable(color: Color, on: Color, toward: Color): Color {
    var t = 0f
    var c = color
    while (contrast(c, on) < 4.6 && t < 1f) {
        t += 0.05f
        c = lerp(color, toward, t)
    }
    return c
}

private fun textOn(c: Color, dark: Color = Color(0xFF1D1B20)): Color =
    if (contrast(Color.White, c) >= contrast(dark, c)) Color.White else dark

private val LIGHT_BG = Color(0xFFFBF8FE)
private val DARK_BG = Color(0xFF141218)

private fun createLightPalette(accent: Color, accent2: Color, tertiary: Color): androidx.compose.material3.ColorScheme {
    val primary = readable(accent, LIGHT_BG, Color.Black)
    val secondary = readable(accent2, LIGHT_BG, Color.Black)
    return createLightPaletteOf(primary, secondary, tertiary)
}

private fun createLightPaletteOf(primary: Color, secondary: Color, tertiary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = textOn(primary),
    primaryContainer = primary.copy(alpha = 0.15f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = textOn(secondary),
    secondaryContainer = secondary.copy(alpha = 0.15f),
    onSecondaryContainer = secondary,
    tertiary = tertiary,
    background = Color(0xFFFBF8FE),
    surface = Color(0xFFFBF8FE),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurface = Color(0xFF1D1B20),
    onSurfaceVariant = Color(0xFF49454E),
)

private fun createDarkPalette(accent: Color, accent2: Color, tertiary: Color, amoled: Boolean): androidx.compose.material3.ColorScheme {
    // The container is the accent at 25 % over the background: the accent must read on that too.
    val primary = readable(readable(accent, DARK_BG, Color.White), lerp(DARK_BG, accent, 0.25f), Color.White)
    val secondary = readable(readable(accent2, DARK_BG, Color.White), lerp(DARK_BG, accent2, 0.25f), Color.White)
    return createDarkPaletteOf(primary, secondary, tertiary, amoled)
}

private fun createDarkPaletteOf(primary: Color, secondary: Color, tertiary: Color, amoled: Boolean) = darkColorScheme(
    primary = primary,
    onPrimary = textOn(primary),
    primaryContainer = primary.copy(alpha = 0.25f),
    onPrimaryContainer = primary,
    secondary = secondary,
    onSecondary = textOn(secondary),
    secondaryContainer = secondary.copy(alpha = 0.25f),
    onSecondaryContainer = secondary,
    tertiary = tertiary,
    background = if (amoled) Color.Black else Color(0xFF141218),
    surface = if (amoled) Color.Black else Color(0xFF141218),
    surfaceContainer = if (amoled) Color.Black else Color(0xFF1E1B24),
    surfaceContainerLow = if (amoled) Color.Black else Color(0xFF19171D),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF0F0D13),
    surfaceContainerHigh = if (amoled) Color(0xFF161618) else Color(0xFF28252E),
    surfaceContainerHighest = if (amoled) Color(0xFF201E26) else Color(0xFF332F39),
    surfaceVariant = if (amoled) Color(0xFF161616) else Color(0xFF49454E),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

private fun createMonochromeLightPalette() = lightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4E7),
    onPrimaryContainer = Color(0xFF09090B),
    secondary = Color(0xFF3F3F46),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF71717A),
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    onSurface = Color(0xFF09090B),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE4E4E7),
)

private fun createMonochromeDarkPalette(amoled: Boolean) = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF09090B),
    primaryContainer = Color(0xFF27272A),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFFD4D4D8),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = if (amoled) Color(0xFF141414) else Color(0xFF202023),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFFA1A1AA),
    onTertiary = Color(0xFF09090B),
    background = if (amoled) Color.Black else Color(0xFF09090B),
    surface = if (amoled) Color.Black else Color(0xFF09090B),
    surfaceContainer = if (amoled) Color.Black else Color(0xFF121215),
    surfaceContainerLow = if (amoled) Color.Black else Color(0xFF0D0D10),
    surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF060608),
    surfaceContainerHigh = if (amoled) Color(0xFF1C1C1F) else Color(0xFF27272A),
    surfaceContainerHighest = if (amoled) Color(0xFF242428) else Color(0xFF323238),
    surfaceVariant = if (amoled) Color(0xFF141414) else Color(0xFF18181B),
    onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272A),
)

@Composable
fun IrisTheme(
    accentColor: AccentColor = AccentColor.IRIS,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colors = if (accentColor == AccentColor.MONOCHROME) {
        if (isDark) createMonochromeDarkPalette(amoledBlack) else createMonochromeLightPalette()
    } else if (accentColor == AccentColor.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (isDark && amoledBlack) {
            dynamicScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceVariant = Color(0xFF141414)
            )
        } else dynamicScheme
    } else {
        val (primary, secondary, tertiary) = when (accentColor) {
            AccentColor.IRIS -> Triple(Color(0xFF8A4AF3), Color(0xFF6C5CE7), Color(0xFFD0BCFF))
            AccentColor.LAPIS_MESOPOTAMIA -> Triple(Color(0xFF1976D2), Color(0xFF0288D1), Color(0xFFFFD700))
            AccentColor.EMERALD -> Triple(Color(0xFF00897B), Color(0xFF26A69A), Color(0xFF80CBC4))
            AccentColor.ISHTAR_AMBER -> Triple(Color(0xFFF57C00), Color(0xFFFFA726), Color(0xFFFFD54F))
            AccentColor.ROSE -> Triple(Color(0xFFD81B60), Color(0xFFEC407A), Color(0xFFFF80AB))
            AccentColor.MATERIAL_YOU, AccentColor.MONOCHROME -> Triple(Color(0xFF8A4AF3), Color(0xFF6C5CE7), Color(0xFFD0BCFF))
        }

        if (isDark) {
            createDarkPalette(primary, secondary, tertiary, amoledBlack)
        } else {
            createLightPalette(primary, secondary, tertiary)
        }
    }

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
