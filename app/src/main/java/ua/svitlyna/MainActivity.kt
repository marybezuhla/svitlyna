@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ua.svitlyna

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.provider.MediaStore
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.svitlyna.R
import ua.svitlyna.data.SettingsPreferences
import ua.svitlyna.data.ThemeMode
import ua.svitlyna.ui.theme.IrisTheme
import ua.svitlyna.ui.GalleryApp

class MainActivity : FragmentActivity() {
    private val currentIntentState = mutableStateOf<Intent?>(null)
    private val intentGenerationState = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntentState.value = intent
        IrisPhotoWidget.refreshAll(this)
        // Svitlyna: reading photos' text for search, in the background while charging; hints and
        // automatic cropping after a screenshot (if switched on); the bars' exact heights for cropping.
        ua.svitlyna.search.IndexJob.schedule(this)
        ua.svitlyna.screenshots.Screenshots.schedule(this)
        measureBars()
        enableEdgeToEdge()
        val settingsPreferences = SettingsPreferences(this)
        // Copies made for sending (possibly decrypted from the vault) don't outlive half an hour.
        Thread {
            val old = System.currentTimeMillis() - 30 * 60_000L
            listOf("shared_media", "share").forEach { d ->
                java.io.File(cacheDir, d).listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
            }
            // Video parts of live photos, cut out for playing: only while the app is open.
            cacheDir.listFiles()?.forEach { if (it.name.startsWith("motion_") || it.name.startsWith("livp_")) it.delete() }
            ua.svitlyna.media.Originals.cleanup(this)
        }.start()
        // Svitlyna 0.4 → 0.5: the app lock and "send without location" carry over (once).
        val old = getSharedPreferences("settings", MODE_PRIVATE)
        if (!old.getBoolean("migrated_to_iris_base", false)) {
            if (old.getBoolean("app_lock", false)) settingsPreferences.setAppLockEnabled(true)
            if (!old.getBoolean("remove_location", true)) settingsPreferences.setSecureSharingMode(ua.svitlyna.data.SecureSharingMode.OFF)
            old.edit().putBoolean("migrated_to_iris_base", true).apply()
        }
        // Svitlyna: favourites of 0.4 were the phone's own marks (IS_FAVORITE); they join the app's
        // favourites once, so nothing marked is lost and "Free up space" keeps protecting them.
        val gallery = getSharedPreferences("gallery", MODE_PRIVATE)
        if (!gallery.getBoolean("favorites_from_system", false)) {
            val ids = runCatching {
                val found = HashSet<String>()
                for (table in listOf(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                    contentResolver.query(table, arrayOf(android.provider.MediaStore.MediaColumns._ID), "${android.provider.MediaStore.MediaColumns.IS_FAVORITE} = 1", null, null)?.use { c ->
                        while (c.moveToNext()) found += c.getLong(0).toString()
                    }
                }
                found
            }.getOrNull()
            if (ids != null) {
                val merged = gallery.getStringSet("favorites", emptySet()).orEmpty() + ids
                gallery.edit().putStringSet("favorites", merged).putBoolean("favorites_from_system", true).apply()
            }
        }
        val initialSettings = settingsPreferences.state.value
        val isDark = when (initialSettings.themeMode) {
            ua.svitlyna.data.ThemeMode.LIGHT -> false
            ua.svitlyna.data.ThemeMode.DARK -> true
            ua.svitlyna.data.ThemeMode.SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val bgColor = when {
            isDark && initialSettings.amoledBlack -> android.graphics.Color.BLACK
            isDark -> 0xFF141218.toInt()
            else -> 0xFFFFF8FF.toInt()
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        // With the app lock on, the app's content is hidden in recent apps.
        ua.svitlyna.core.AppLock.applyRecents(this, initialSettings.appLockEnabled)
        if (initialSettings.language.isNotEmpty()) {
            ua.svitlyna.ui.setAppLanguage(this, initialSettings.language)
        }
        // Loaded before the first frame, so nothing reads it half-loaded.
        ua.svitlyna.ui.TempShots.load(this)
        setContent {
            val activeIntent = currentIntentState.value ?: intent
            val pickerMode = activeIntent.action == Intent.ACTION_PICK || activeIntent.action == Intent.ACTION_GET_CONTENT
            val isCameraReviewAction = activeIntent.action == "com.android.camera.action.REVIEW" ||
                activeIntent.action == "android.provider.MediaStore.ACTION_REVIEW"
            val isViewAction = activeIntent.action == Intent.ACTION_VIEW ||
                activeIntent.action == Intent.ACTION_EDIT ||
                isCameraReviewAction
            val requestedType = activeIntent.type
            val rawViewUri = activeIntent.data ?: activeIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            // Svitlyna: only content:// from other apps; never the app's own providers (trash, vault,
            // shared copies) handed back to it, and never plain file paths.
            val viewUri = rawViewUri?.takeIf {
                isViewAction && it.scheme == "content" && it.authority?.startsWith(packageName) != true
            }
            val isEditAction = activeIntent.action == Intent.ACTION_EDIT
            val shouldOpenLatest = isCameraReviewAction && rawViewUri == null
            val intentGeneration = intentGenerationState.value

            val settings by settingsPreferences.state.collectAsStateWithLifecycle()
            IrisTheme(
                themeMode = settings.themeMode,
                amoledBlack = settings.amoledBlack,
                accentColor = settings.accentColor,
            ) {
                GalleryApp(
                    settings = settings,
                    settingsPreferences = settingsPreferences,
                    requestedType = requestedType.takeIf { pickerMode },
                    initialViewUri = viewUri,
                    initialEditMode = isEditAction,
                    initialMemories = activeIntent.getBooleanExtra("open_memories", false),
                    initialOpenLatest = shouldOpenLatest,
                    initialIntentGeneration = intentGeneration,
                    onPick = if (pickerMode) {{ media ->
                        // Svitlyna: with secure sharing on, the other app gets a cleaned copy (as when
                        // sharing); a file that can't be cleaned is not handed over.
                        val mode = settingsPreferences.state.value.secureSharingMode
                        lifecycleScope.launch {
                            val uri = if (mode == ua.svitlyna.data.SecureSharingMode.OFF) media.uri
                                else ua.svitlyna.ui.prepareShareUris(this@MainActivity, listOf(media), mode).firstOrNull()
                            if (uri == null) {
                                android.widget.Toast.makeText(this@MainActivity, R.string.sv_pick_not_cleaned, android.widget.Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val result = Intent().apply {
                                data = uri
                                clipData = ClipData.newUri(contentResolver, "photo", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            setResult(Activity.RESULT_OK, result)
                            finish()
                        }
                    }} else null,
                )
            }
        }
    }

    /** The exact heights of the status and navigation bars, for cropping screenshots precisely. */
    private fun measureBars() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            val metrics = windowManager.currentWindowMetrics.bounds
            // Only a full-screen portrait window measures the whole screen (not split screen).
            val full = !isInMultiWindowMode && metrics == windowManager.maximumWindowMetrics.bounds
            if (full && metrics.width() < metrics.height()) {
                val status = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                // With gesture navigation there is no bar, only a thin handle drawn over the app:
                // cutting it would cut real content, so nothing is cut at the bottom then.
                val buttons = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.tappableElement()).bottom > 0
                val nav = if (buttons) insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom else 0
                val prefs = ua.svitlyna.core.Prefs(this)
                val measured = ua.svitlyna.core.Bars(metrics.width(), metrics.height(), status, nav)
                if (status > 0 && prefs.bars != measured) prefs.bars = measured
            }
            androidx.core.view.ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
        intentGenerationState.value += 1
    }
}
