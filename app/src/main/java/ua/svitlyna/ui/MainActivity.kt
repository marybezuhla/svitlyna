package ua.svitlyna.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SvitlynaTheme { App() } }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    secondary = Color(0xFF8A6A1F),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    secondary = Color(0xFFF5C451),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
)

@Composable
fun SvitlynaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Colours follow the wallpaper on Android 12+ (Material You), as in the other apps on the phone.
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
