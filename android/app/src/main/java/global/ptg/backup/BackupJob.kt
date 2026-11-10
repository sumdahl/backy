package global.ptg.backup

import android.app.NotificationManager
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import global.ptg.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One backup run, whatever hosts it: keeps the notification, widget and tile live, records the
 * run, and posts the result. Shared by [BackupJob] (Android 14+) and [BackupService] (12–13).
 */
@OptIn(FlowPreview::class)
private suspend fun runHosted(context: Context, selection: Selection) = kotlinx.coroutines.coroutineScope {
    val c = context.container
    val nm = context.getSystemService(NotificationManager::class.java)
    val ticker = launch {
        Backup.state.sample(1000).collect { nm.notify(Notifs.PROGRESS_ID, Notifs.progress(context, it)) }
    }
    // Widgets and tiles are cheaper to update less often.
    val shortcuts = launch {
        Backup.state.sample(3000).collect {
            BackupWidget.refresh(context)
            BackupTile.refresh(context)
        }
    }
    try {
        Backup.run(c, selection)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Backup.state.value = Backup.state.value.copy(phase = Phase.Error, message = e.message ?: "Backup failed")
    } finally {
        ticker.cancel()
        shortcuts.cancel()
        withContext(NonCancellable) {
            Backup.record(c)
            BackupWidget.refresh(context)
            BackupTile.refresh(context)
        }
        Notifs.finished(context, Backup.state.value)
    }
}

private fun markStopped() {
    Backup.state.value = Backup.state.value.copy(phase = Phase.Stopped, threads = emptyList(), endedAt = System.currentTimeMillis())
}

/**
 * A user-initiated data transfer job (Android 14+): the platform's API for a long upload the
 * user asked for. No 6-hour foreground-service cap, survives the app going to the background.
 */
class BackupJob : JobService() {
    private var work: Job? = null

    // Only ever scheduled on 34+ (see Backup.start); older versions use BackupService.
    @RequiresApi(34)
    override fun onStartJob(params: JobParameters): Boolean {
        setNotification(params, Notifs.PROGRESS_ID, Notifs.progress(this, Backup.state.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        work = container.scope.launch {
            try { runHosted(this@BackupJob, Selection.from(params.extras)) } finally { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        markStopped()
        return false
    }
}

/** Android 12–13, which have no user-initiated jobs: the same run in a data-sync foreground service. */
class BackupService : Service() {
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifs.PROGRESS_ID, Notifs.progress(this, Backup.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val extras = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_SELECTION, PersistableBundle::class.java) }
        if (work?.isActive == true || extras == null) return START_NOT_STICKY
        work = container.scope.launch {
            try { runHosted(this@BackupService, Selection.from(extras)) } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (work?.isActive == true) { work?.cancel(); markStopped() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SELECTION = "selection"
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Backup.stop(context)
}
