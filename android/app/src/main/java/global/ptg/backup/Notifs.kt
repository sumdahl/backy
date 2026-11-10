package global.ptg.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import global.ptg.MainActivity
import global.ptg.R
import global.ptg.ui.formatBytes
import global.ptg.ui.formatDuration

object Notifs {
    private const val CHANNEL = "backup"
    private const val DONE_CHANNEL = "backup_done"
    const val PROGRESS_ID = 1
    private const val DONE_ID = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "Backup progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while photos are being backed up"
            },
            NotificationChannel(DONE_CHANNEL, "Backup results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a backup finishes"
            },
        ))
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Android 16 promotes this to a Live Update: status-bar chip and lock-screen progress. */
    fun progress(context: Context, s: RunState): Notification {
        val now = System.currentTimeMillis()
        val pct = (s.fraction * 100).toInt()
        val title = when (s.phase) {
            Phase.Queued -> s.message
            Phase.Preparing -> "Getting ready…"
            Phase.Waiting -> "Waiting to retry"
            Phase.Albums -> s.message
            else -> "${if (s.checkingOnly) "Checking" else "Backing up"} ${minOf(s.done + 1, s.total)} of ${s.total}"
        }
        val eta = s.etaSeconds(now)
        val text = buildString {
            append(s.detail())
            if (eta > 0) append(if (isEmpty()) "" else " · ").append("${formatDuration(eta)} left")
        }
        val stop = PendingIntent.getBroadcast(
            context, 1, Intent(context, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct, s.phase == Phase.Preparing || s.phase == Phase.Queued)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText("$pct%")
            .setContentIntent(openApp(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun finished(context: Context, s: RunState, quiet: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (s.phase == Phase.Stopped) return
        if (quiet && s.phase != Phase.Error && s.uploaded == 0 && s.failed == 0) return
        val title = when {
            s.phase == Phase.Error -> "Backup didn't run"
            s.total == 0 -> "Everything is backed up"
            s.failed == 0 -> "Backup complete"
            else -> "Backup finished with ${s.failed} problem${if (s.failed == 1) "" else "s"}"
        }
        val text = if (s.phase == Phase.Error) s.message else buildString {
            append("${s.uploaded} uploaded")
            if (s.exists > 0) append(" · ${s.exists} already there")
            if (s.bytesSent > 0) append(" · ${formatBytes(s.bytesSent)} sent")
        }
        nm.notify(DONE_ID, NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build())
    }
}
