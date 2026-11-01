package ua.svitlyna.core

/**
 * The phone's screen and its system bars, measured by the app itself (window insets) while it is
 * open in portrait. A screenshot of exactly this size is a full-screen one, and its status bar is
 * exactly [top] pixels high: cropping is precise, not a guess.
 */
data class Bars(val screenW: Int, val screenH: Int, val top: Int, val bottom: Int) {
    /**
     * The rows to keep (first, end-exclusive) for a screenshot of [w] × [h], or null when it isn't
     * a full portrait screenshot of this phone (landscape, another phone, already cropped).
     */
    fun keep(w: Int, h: Int, withNavigation: Boolean): IntRange? {
        if (screenW <= 0 || w != screenW || h != screenH || w >= h) return null
        val cutBottom = if (withNavigation) bottom.coerceAtLeast(0) else 0
        val cutTop = top.coerceAtLeast(0)
        if (cutTop + cutBottom == 0 || cutTop + cutBottom >= h / 2) return null
        return cutTop until h - cutBottom
    }
}
