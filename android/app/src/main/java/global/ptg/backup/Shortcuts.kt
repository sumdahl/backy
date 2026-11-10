package global.ptg.backup

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import global.ptg.MainActivity
import global.ptg.R
import global.ptg.container
import global.ptg.data.RunRecord
import global.ptg.ui.formatBytes
import global.ptg.ui.formatCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Invisible activity behind the widget and the Quick Settings tile. A user-initiated job may
 * only be scheduled while the app is visible; this makes it visible for one frame, schedules
 * the backup, and closes. If there's nothing it can start, it opens the app instead.
 */
class StartBackupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val c = container
            c.refreshAccounts()
            val signedIn = c.accounts.value.selected.isNotEmpty()
            if (signedIn && !Backup.state.value.active) {
                Backup.start(this@StartBackupActivity, Selection.All, c.settings.data.first().wifiOnly)
            } else {
                startActivity(Intent(this@StartBackupActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 7, Intent(context, StartBackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Quick Settings tile: "Back up now", or the live percentage while a backup runs. */
class BackupTile : TileService() {
    override fun onStartListening() {
        val s = Backup.state.value
        qsTile?.apply {
            state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            subtitle = when {
                s.phase == Phase.Queued -> "Waiting for network"
                s.active -> "${(s.fraction * 100).toInt()}% backed up"
                else -> "Back up now"
            }
            updateTile()
        }
    }

    override fun onClick() {
        // While running, a tap opens the app to show progress.
        val target = if (Backup.state.value.active) {
            PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        } else StartBackupActivity.pendingIntent(this)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(target)
        else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, StartBackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, BackupTile::class.java))
    }
}

/** Home-screen widget: status, live progress, and a Back up now button. Material You colours. */
class BackupWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        context.container.scope.launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BackupWidget::class.java))
            if (ids.isEmpty()) return
            val last = if (Backup.state.value.active) null else context.container.db.runs().last()
            manager.updateAppWidget(ids, render(context, Backup.state.value, last))
        }

        private fun render(context: Context, s: RunState, last: RunRecord?) = RemoteViews(context.packageName, R.layout.widget_backup).apply {
            val open = PendingIntent.getActivity(context, 9, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_root, open)
            if (s.active) {
                val pct = (s.fraction * 100).toInt()
                setTextViewText(R.id.widget_title, if (s.phase == Phase.Queued) s.message else "Backing up · $pct%")
                setTextViewText(R.id.widget_status,
                    if (s.total > 0) "${formatCount(s.done)} of ${formatCount(s.total)} · ${formatBytes(s.bytesDone)}" else s.message)
                setViewVisibility(R.id.widget_progress, View.VISIBLE)
                setProgressBar(R.id.widget_progress, 100, pct, s.phase == Phase.Queued || s.phase == Phase.Preparing)
                setTextViewText(R.id.widget_button, "Open")
                setOnClickPendingIntent(R.id.widget_button, open)
            } else {
                setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
                setTextViewText(R.id.widget_status, when {
                    last == null -> "No backups yet"
                    else -> "Last backup " + DateUtils.getRelativeTimeSpanString(last.endedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        " · " + when (last.outcome) {
                            "Error" -> last.message
                            "Stopped" -> "stopped"
                            else -> "${formatCount(last.uploaded)} uploaded"
                        }
                })
                setViewVisibility(R.id.widget_progress, View.GONE)
                setTextViewText(R.id.widget_button, "Back up now")
                setOnClickPendingIntent(R.id.widget_button, StartBackupActivity.pendingIntent(context))
            }
        }
    }
}
