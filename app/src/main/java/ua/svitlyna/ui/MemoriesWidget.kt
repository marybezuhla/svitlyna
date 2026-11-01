package ua.svitlyna.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import ua.svitlyna.R
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.MediaRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "This day" on the home screen: a photo taken on this date in an earlier year. With the app lock
 * on, no photo is shown (the home screen is seen by anyone holding the phone). Tapping opens it.
 */
class MemoriesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                update(context.applicationContext, manager, ids)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** Redrawn when the gallery's settings change (the app lock switched on hides the photo). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MemoriesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, MemoriesWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        /** Photos of this month and day from earlier years; the same one all day long. */
        fun pick(items: List<MediaItem>, today: LocalDate, zone: ZoneId): MediaItem? {
            val same = items.filter { item ->
                if (item.isVideo || item.isScreenshot || item.takenMs <= 0) return@filter false
                val d = Instant.ofEpochMilli(item.takenMs).atZone(zone).toLocalDate()
                d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year
            }
            if (same.isEmpty()) return null
            return same.sortedBy { it.id }[Math.floorMod(today.toEpochDay(), same.size.toLong()).toInt()]
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_memories)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setViewVisibility(R.id.widget_photo, View.GONE)
            if (Prefs(context).appLock) {
                views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_locked))
            } else {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val item = runCatching { pick(MediaRepository(context.contentResolver).loadAll(), today, zone) }.getOrNull()
                val picture = item?.let { runCatching { context.contentResolver.loadThumbnail(it.uri, Size(480, 480), null) }.getOrNull() }
                if (item != null && picture != null) {
                    val years = today.year - Instant.ofEpochMilli(item.takenMs).atZone(zone).year
                    views.setImageViewBitmap(R.id.widget_photo, picture)
                    views.setViewVisibility(R.id.widget_photo, View.VISIBLE)
                    views.setTextViewText(R.id.widget_text, context.resources.getQuantityString(R.plurals.widget_years_ago, years, years))
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(
                            context,
                            1,
                            Intent().setClassName(context, MainActivity.FROM_HINT)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(MainActivity.EXTRA_OPEN_ID, item.id),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                } else {
                    views.setTextViewText(R.id.widget_text, context.getString(R.string.widget_nothing))
                }
            }
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
