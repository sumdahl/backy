package global.ptg.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import global.ptg.container
import global.ptg.data.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Opt-in automatic backup. Two jobs:
 * - [GalleryWatchJob] wakes when MediaStore changes (a new photo) and only queues the work.
 * - [AutoBackupJob] does the backup under the user's constraints (Wi-Fi, charging). If the
 *   system stops it (time limit, lost Wi-Fi), it is rescheduled and the next run continues where
 *   this one left off, because finished items are already recorded.
 */
object AutoBackup {
    private const val WATCH_ID = 43
    private const val WORK_ID = 44

    private fun jobs(context: Context) = context.getSystemService(JobScheduler::class.java)

    /** Brings the scheduled jobs in line with [s]. Cheap and idempotent: call it whenever. */
    fun sync(context: Context, s: Settings) {
        val js = jobs(context)
        if (!s.autoBackup) {
            js.cancel(WATCH_ID)
            js.cancel(WORK_ID)
            return
        }
        // Rescheduling a pending watch would drop the changes it has already seen.
        if (js.getPendingJob(WATCH_ID) == null) watch(context)
        // A queued run keeps the constraints it was queued with; requeue it with the new ones.
        if (js.getPendingJob(WORK_ID) != null && !Backup.state.value.active) {
            js.cancel(WORK_ID)
            queueWork(context, s)
        }
    }

    internal fun watch(context: Context) {
        val flags = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
        jobs(context).schedule(JobInfo.Builder(WATCH_ID, ComponentName(context, GalleryWatchJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, flags))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, flags))
            // Let a burst of shots settle into one backup.
            .setTriggerContentUpdateDelay(30_000)
            .setTriggerContentMaxDelay(10 * 60_000)
            .build())
    }

    /** Queues one backup run, unless one is already queued or running. */
    fun queueWork(context: Context, s: Settings) {
        val js = jobs(context)
        if (js.getPendingJob(WORK_ID) != null) return
        js.schedule(JobInfo.Builder(WORK_ID, ComponentName(context, AutoBackupJob::class.java))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(s.autoChargingOnly)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build())
    }

    /** A manual backup or the Stop button takes over from an automatic one. */
    fun cancelWork(context: Context) = jobs(context).cancel(WORK_ID)

    /** Watches don't survive a reboot (content-trigger jobs can't be persisted). */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            val pending = goAsync()
            context.container.scope.launch {
                try { sync(context, context.container.settings.data.first()) } finally { pending.finish() }
            }
        }
    }
}

class GalleryWatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        container.scope.launch {
            val s = container.settings.data.first()
            jobFinished(params, false)
            // A content trigger fires once; watch again for the next change.
            if (s.autoBackup) {
                AutoBackup.queueWork(this@GalleryWatchJob, s)
                AutoBackup.watch(this@GalleryWatchJob)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

class AutoBackupJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // A manual backup is already doing this.
        if (Backup.state.value.active) return false
        work = container.scope.launch {
            try { runHosted(this@AutoBackupJob, Selection.All, quiet = true) } finally { jobFinished(params, false) }
        }
        return true
    }

    /** Stopped by the system: try again later with backoff. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        markStopped()
        return true
    }
}
