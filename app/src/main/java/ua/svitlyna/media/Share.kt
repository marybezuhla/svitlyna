package ua.svitlyna.media

import android.content.Context
import java.io.File

/** Shared copies (cleaned for sending) live in the app's cache only as long as needed. */
object Share {
    /** Removes shared copies older than an hour (by then the receiving app has long read them). */
    fun cleanup(context: Context) {
        val root = File(context.cacheDir, "share")
        val old = System.currentTimeMillis() - 60 * 60 * 1000L
        root.listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
    }
}
