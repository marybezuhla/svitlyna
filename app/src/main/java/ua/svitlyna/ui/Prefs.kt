package ua.svitlyna.ui

import android.content.Context
import ua.svitlyna.screenshots.Bars

/** A few plain settings. Nothing personal is kept here, and Android's cloud backup is off for the app. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var columns: Int
        get() = p.getInt("columns", 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        set(v) = p.edit().putInt("columns", v).apply()

    /** Share copies without location (on by default). */
    var removeLocation: Boolean
        get() = p.getBoolean("remove_location", true)
        set(v) = p.edit().putBoolean("remove_location", v).apply()

    /** Hints after a screenshot (off until the person switches them on). */
    var screenshotHints: Boolean
        get() = p.getBoolean("screenshot_hints", false)
        set(v) = p.edit().putBoolean("screenshot_hints", v).apply()

    /** "Without bars" also removes the navigation bar at the bottom. */
    var cropNavigation: Boolean
        get() = p.getBoolean("crop_navigation", true)
        set(v) = p.edit().putBoolean("crop_navigation", v).apply()

    /** Save a copy without bars right away, with no tap (the original stays). */
    var autoCrop: Boolean
        get() = p.getBoolean("auto_crop", false)
        set(v) = p.edit().putBoolean("auto_crop", v).apply()

    /** "Media location" was asked for once after an update; not asked again if refused. */
    var askedMediaLocation: Boolean
        get() = p.getBoolean("asked_media_location", false)
        set(v) = p.edit().putBoolean("asked_media_location", v).apply()

    /** The last screenshot a hint was shown for: each one gets at most one. */
    var lastScreenshotId: Long
        get() = p.getLong("last_screenshot", 0)
        set(v) = p.edit().putLong("last_screenshot", v).apply()

    /** The screen and bar sizes measured by the app in portrait; null until measured once. */
    var bars: Bars?
        get() = if (p.contains("bars_w")) {
            Bars(p.getInt("bars_w", 0), p.getInt("bars_h", 0), p.getInt("bars_top", 0), p.getInt("bars_bottom", 0))
        } else {
            null
        }
        set(v) {
            if (v == null) return
            p.edit().putInt("bars_w", v.screenW).putInt("bars_h", v.screenH)
                .putInt("bars_top", v.top).putInt("bars_bottom", v.bottom).apply()
        }
}
