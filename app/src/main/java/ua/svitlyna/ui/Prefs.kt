package ua.svitlyna.ui

import android.content.Context

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
}
