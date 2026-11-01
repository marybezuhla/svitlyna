package ua.svitlyna.search

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.svitlyna.core.Prefs

/**
 * Reading photos' text in the background: only while the phone is charging and not in use, so the
 * battery and the person's work are not touched. Android stops it whenever that ends; the next run
 * goes on where it stopped. No extra permission is needed.
 */
class IndexJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            // Android gives a run about ten minutes; a little is kept for finishing.
            val until = android.os.SystemClock.elapsedRealtime() + 9 * 60_000L
            val finished = runCatching { Indexer.run(applicationContext, until) }.getOrDefault(false)
            jobFinished(params, !finished)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // Not finished: asked to run again.
        return true
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4301

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!Prefs(context).searchText) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, IndexJob::class.java))
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                // New photos are read on the next quiet charge.
                .setPeriodic(60 * 60_000L)
                .build()
            runCatching { js.schedule(job) }
        }
    }
}
