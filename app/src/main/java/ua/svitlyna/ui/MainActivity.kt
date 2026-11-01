package ua.svitlyna.ui

import android.os.Build
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ua.svitlyna.media.Share
import ua.svitlyna.screenshots.Bars
import ua.svitlyna.screenshots.Screenshots

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_ID = "open_id"
        const val EXTRA_READ_TEXT = "read_text"

        /** The private entrance used by Svitlyna's own notifications; other apps can't open photos by id. */
        const val FROM_HINT = "ua.svitlyna.ui.FromHint"
    }

    /** A photo to open (from a screenshot hint), and whether to read its text right away. */
    val openRequest = mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Copies made for sharing are removed an hour later.
        Thread { Share.cleanup(applicationContext) }.start()
        Screenshots.schedule(this)
        // Reopened from the recent apps after the process was gone: the old request is not repeated.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) takeRequest(intent)
        measureBars()
        setContent { SvitlynaTheme { App(openRequest = openRequest) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRequest(intent)
    }

    private fun takeRequest(intent: Intent?) {
        if (intent?.component?.className != FROM_HINT) return
        val id = intent.getLongExtra(EXTRA_OPEN_ID, -1L)
        if (id > 0) {
            openRequest.value = OpenRequest(id, intent.getBooleanExtra(EXTRA_READ_TEXT, false))
            // Opened once: turning the phone or coming back must not open it again.
            intent.removeExtra(EXTRA_OPEN_ID)
        }
    }

    /** The exact heights of the status and navigation bars, for cropping screenshots precisely. */
    private fun measureBars() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val metrics = windowManager.currentWindowMetrics.bounds
            // Only a full-screen portrait window measures the whole screen (not split screen).
            val full = !isInMultiWindowMode && metrics == windowManager.maximumWindowMetrics.bounds
            if (full && metrics.width() < metrics.height()) {
                val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                // With gesture navigation there is no bar, only a thin handle drawn over the app:
                // cutting it would cut real content, so nothing is cut at the bottom then.
                val buttons = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom > 0
                val nav = if (buttons) insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom else 0
                val prefs = Prefs(this)
                val measured = Bars(metrics.width(), metrics.height(), status, nav)
                if (status > 0 && prefs.bars != measured) prefs.bars = measured
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }
}

data class OpenRequest(val id: Long, val readText: Boolean)

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
