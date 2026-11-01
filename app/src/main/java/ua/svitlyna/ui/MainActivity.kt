package ua.svitlyna.ui

import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ua.svitlyna.R
import ua.svitlyna.media.Share
import ua.svitlyna.screenshots.Bars
import ua.svitlyna.screenshots.Screenshots

open class MainActivity : FragmentActivity() {
    companion object {
        const val EXTRA_OPEN_ID = "open_id"
        const val EXTRA_READ_TEXT = "read_text"

        /** The private entrance used by Svitlyna's own notifications; other apps can't open photos by id. */
        const val FROM_HINT = "ua.svitlyna.ui.FromHint"
    }

    /** A photo to open (from a screenshot hint), and whether to read its text right away. */
    val openRequest = mutableStateOf<OpenRequest?>(null)

    /** App lock: true while the fingerprint/PIN hasn't been given. */
    val locked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Copies made for sharing are removed an hour later.
        Thread { Share.cleanup(applicationContext) }.start()
        Screenshots.schedule(this)
        // Reopened from the recent apps after the process was gone: the old request is not repeated.
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) takeRequest(intent)
        measureBars()
        val prefs = Prefs(this)
        // Recreated soon after (theme change): still unlocked. A new process or a long absence: locked.
        locked.value = AppLock.shouldLock(prefs.appLock)
        AppLock.applyRecents(this, prefs.appLock)
        setContent {
            SvitlynaTheme(amoled = prefs.amoled) {
                // The gallery stays underneath the lock, covered and hidden from screen readers, so
                // work in progress (copying, finding duplicates…) isn't stopped by the lock.
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize().then(
                            if (locked.value) androidx.compose.ui.Modifier.clearAndSetSemantics {} else androidx.compose.ui.Modifier,
                        ),
                    ) {
                        App(openRequest = openRequest, pick = pick)
                    }
                    if (locked.value) {
                        // An opaque surface that also takes every touch meant for the gallery.
                        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                            androidx.activity.compose.BackHandler { finish() }
                            LockScreen(onUnlock = { AppLock.ask(this, onNoScreenLock = ::lockImpossible) { unlocked() } })
                        }
                    }
                }
            }
        }
    }

    /** Set by [PickActivity]: another app asked for photos to be chosen. */
    protected open fun pickRequest(): PickRequest? = null
    private val pick by lazy { pickRequest() }

    private fun unlocked() {
        locked.value = false
        // The PIN screen itself takes the app to the background: that doesn't count as leaving.
        AppLock.leftAt = 0
        AppLock.unlocked = true
    }

    /** The phone's screen lock was removed: the app lock can't work, so it is switched off. */
    private fun lockImpossible() {
        Prefs(this).appLock = false
        MemoriesWidget.refresh(this)
        AppLock.applyRecents(this, false)
        locked.value = false
        android.widget.Toast.makeText(this, R.string.app_lock_turned_off, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onStop() {
        super.onStop()
        AppLock.leftAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val prefs = Prefs(this)
        if (AppLock.shouldLock(prefs.appLock)) {
            locked.value = true
            AppLock.unlocked = false
        } else if (!locked.value) {
            // Back within half a minute: the absence is over.
            AppLock.leftAt = 0
        }
        if (locked.value) AppLock.ask(this, onNoScreenLock = ::lockImpossible) { unlocked() }
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
fun SvitlynaTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Colours follow the wallpaper on Android 12+ (Material You), as in the other apps on the phone.
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }.let { c -> if (dark && amoled) c.copy(background = Color.Black, surface = Color.Black) else c }
    MaterialTheme(colorScheme = colors, content = content)
}
