package ua.svitlyna.screenshots

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.svitlyna.R
import ua.svitlyna.media.MediaItem
import ua.svitlyna.media.MediaRepository
import ua.svitlyna.media.SaveCopy
import ua.svitlyna.ui.MainActivity
import ua.svitlyna.ui.Prefs

/**
 * Hints after a screenshot: Android tells the app (through a job, so nothing runs in the background
 * waiting) that the media list changed; if the newest picture is a fresh screenshot, a notification
 * offers "Without bars", "Text" and "Open". Nothing is changed without a tap, unless the person
 * switched on "always save a copy without bars". The original screenshot is never changed.
 */
object Screenshots {
    private const val JOB_ID = 4201
    private const val CHANNEL = "screenshots"
    const val NOTIFICATION_ID = 4202
    const val ACTION_CROP = "ua.svitlyna.CROP_BARS"
    const val EXTRA_ID = "id"

    /** Only screenshots taken in the last two minutes get a hint (not old ones copied to the phone). */
    private const val FRESH_MS = 2 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The job is needed for hints, for automatic cropping, or both. */
    fun enabled(context: Context) = Prefs(context).let { it.screenshotHints || it.autoCrop }

    /** Asks Android to wake the app up on the next change in the photos; done again after each one. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(context)) {
            js.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ScreenshotJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(3000)
            .build()
        runCatching { js.schedule(job) }
    }

    fun isScreenshot(relativePath: String?, bucket: String): Boolean =
        relativePath?.contains("Screenshot", ignoreCase = true) == true || bucket.contains("Screenshot", ignoreCase = true)

    /** The newest fresh screenshot not yet hinted about, if any. */
    fun newest(context: Context): MediaItem? {
        val prefs = Prefs(context)
        val since = System.currentTimeMillis() / 1000 - FRESH_MS / 1000
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATE_ADDED} >= ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 5)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        val ids = ArrayList<Long>()
        runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    // Svitlyna's own copies (a screenshot without bars) get no hint of their own.
                    val ours = c.getString(3) == context.packageName
                    if (!ours && isScreenshot(c.getString(1), c.getString(2) ?: "")) ids += c.getLong(0)
                }
            }
        }
        val id = ids.maxOrNull() ?: return null
        if (id <= prefs.lastScreenshotId) return null
        prefs.lastScreenshotId = id
        return MediaRepository(context.contentResolver).loadOne(id)?.takeIf { !it.isVideo }
    }

    /** Runs when Android reports a change in the photos. */
    suspend fun onPhotosChanged(context: Context) {
        val shot = newest(context) ?: return
        val prefs = Prefs(context)
        val keep = prefs.bars?.keep(shot.width, shot.height, prefs.cropNavigation)
        if (keep != null && prefs.autoCrop) {
            crop(context, shot)
        } else if (prefs.screenshotHints) {
            notify(context, shot, canCrop = keep != null)
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.screenshot_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ch.setSound(null, null)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
        return nm
    }

    private fun openIntent(context: Context, id: Long, text: Boolean, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent().setClassName(context, MainActivity.FROM_HINT)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ID, id)
                .putExtra(MainActivity.EXTRA_READ_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * No picture of the screenshot in the notification: screenshots often hold codes and chats, and a
     * notification can show on the lock screen and be read by apps with notification access. On the
     * lock screen only "Svitlyna" shows.
     */
    private fun base(context: Context, item: MediaItem): Notification.Builder {
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(context, item.id, text = false, request = 1))
    }

    fun notify(context: Context, item: MediaItem, canCrop: Boolean) {
        if (!canNotify(context)) return
        val nm = channel(context)
        val builder = base(context, item).setContentTitle(context.getString(R.string.screenshot_title))
            .setContentText(context.getString(if (canCrop) R.string.screenshot_text else R.string.screenshot_text_no_crop))
        if (canCrop) {
            val crop = PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, ScreenshotActionReceiver::class.java).setAction(ACTION_CROP).putExtra(EXTRA_ID, item.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.screenshot_crop), crop).build())
        }
        builder.addAction(
            Notification.Action.Builder(null, context.getString(R.string.text_on_photo), openIntent(context, item.id, text = true, request = 3)).build(),
        )
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /** Saves a copy without the status bar (and the navigation bar, if chosen) next to the screenshot. */
    suspend fun crop(context: Context, item: MediaItem) {
        val prefs = Prefs(context)
        val keep = prefs.bars?.keep(item.width, item.height, prefs.cropNavigation) ?: return
        val full = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return
        if (full.width != item.width || full.height != item.height) {
            full.recycle()
            return
        }
        val cropped = android.graphics.Bitmap.createBitmap(full, 0, keep.first, full.width, keep.last - keep.first + 1)
        val uri = SaveCopy.save(context, item, cropped, "no-bars")
        cropped.recycle()
        full.recycle()
        if (!canNotify(context)) return
        val nm = channel(context)
        val builder = base(context, item)
            .setContentTitle(context.getString(if (uri != null) R.string.screenshot_cropped else R.string.screenshot_crop_failed))
        if (uri != null) {
            val newId = uri.lastPathSegment?.toLongOrNull()
            if (newId != null) builder.setContentIntent(openIntent(context, newId, text = false, request = 4))
            builder.setContentText(context.getString(R.string.screenshot_cropped_text))
        }
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    fun launch(block: suspend () -> Unit) = scope.launch { block() }
}

/** The job Android starts when the photos change; it re-schedules itself for the next change. */
class ScreenshotJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Screenshots.launch {
            try {
                Screenshots.onPhotosChanged(applicationContext)
            } catch (_: Exception) {
                // A hint is a nicety: never a crash.
            } finally {
                Screenshots.schedule(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/** "Without bars" in the notification. */
class ScreenshotActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Screenshots.ACTION_CROP) return
        val id = intent.getLongExtra(Screenshots.EXTRA_ID, -1)
        val pending = goAsync()
        Screenshots.launch {
            try {
                // One tap, one copy: the hint goes away as soon as its button is used.
                context.getSystemService(NotificationManager::class.java)?.cancel(Screenshots.NOTIFICATION_ID)
                val item = MediaRepository(context.contentResolver).loadOne(id)
                if (item != null) Screenshots.crop(context.applicationContext, item)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a restart (or an update) Android forgets the job: it is asked for again. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Screenshots.schedule(context)
        }
    }
}
