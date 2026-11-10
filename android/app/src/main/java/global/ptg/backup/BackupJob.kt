package global.ptg.backup

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import global.ptg.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A user-initiated data transfer job (Android 14+): the platform's API for a long upload the
 * user asked for. No 6-hour foreground-service cap, survives the app going to the background.
 */
class BackupJob : JobService() {
    private var work: Job? = null

    @OptIn(FlowPreview::class)
    override fun onStartJob(params: JobParameters): Boolean {
        val c = container
        setNotification(params, Notifs.PROGRESS_ID, Notifs.progress(this, Backup.state.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        work = c.scope.launch {
            val ticker = launch {
                Backup.state.sample(1000).collect { nm.notify(Notifs.PROGRESS_ID, Notifs.progress(this@BackupJob, it)) }
            }
            // Widgets and tiles are cheaper to update less often.
            val shortcuts = launch {
                Backup.state.sample(3000).collect {
                    BackupWidget.refresh(this@BackupJob)
                    BackupTile.refresh(this@BackupJob)
                }
            }
            try {
                Backup.run(c, Selection.from(params.extras))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Backup.state.value = Backup.state.value.copy(phase = Phase.Error, message = e.message ?: "Backup failed")
            } finally {
                ticker.cancel()
                shortcuts.cancel()
                withContext(NonCancellable) {
                    Backup.record(c)
                    BackupWidget.refresh(this@BackupJob)
                    BackupTile.refresh(this@BackupJob)
                }
                Notifs.finished(this@BackupJob, Backup.state.value)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        Backup.state.value = Backup.state.value.copy(phase = Phase.Stopped, threads = emptyList(), endedAt = System.currentTimeMillis())
        return false
    }
}

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Backup.stop(context)
}
