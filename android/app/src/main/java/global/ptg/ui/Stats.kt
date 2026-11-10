package global.ptg.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import global.ptg.container

/** Totals, per account, per folder, and the history of every backup. */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val lib by c.library.collectAsStateWithLifecycle()
    val runs by c.db.runs().recent().collectAsStateWithLifecycle(initialValue = emptyList())
    val topFolders = lib.folders.filter { it.done > 0 || it.prefs.enabled }.sortedByDescending { it.done }.take(8)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Stats") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        ReadableLazyColumn(pad, bottom = 32.dp) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(formatCount(lib.doneCount), "Items safe", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                        StatTile(formatBytes(lib.doneBytes), "Backed up", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(formatCount(lib.pending), "Waiting", Modifier.weight(1f))
                        StatTile(formatCount(runs.size), "Backups run", Modifier.weight(1f))
                    }
                }
            }

            if (lib.doneByAccount.isNotEmpty()) {
                item { SectionHeader("By account") }
                item {
                    SegmentGroup(lib.doneByAccount.entries.sortedByDescending { it.value }.map { (email, n) ->
                        @Composable {
                            ListItem(
                                leadingContent = { Avatar(email, 40.dp) },
                                headlineContent = { Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(plural(n, "item")) },
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                    })
                }
            }

            if (topFolders.isNotEmpty()) {
                item { SectionHeader("By folder") }
                items(topFolders, key = { "f" + it.id }) { f ->
                    Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatCount(f.done)} / ${formatCount(f.items)}", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.size(6.dp))
                        LinearProgressIndicator(progress = { if (f.items == 0) 0f else f.done.toFloat() / f.items }, Modifier.fillMaxWidth())
                    }
                }
            }

            item { SectionHeader("History") }
            if (runs.isEmpty()) item {
                Text("No backups yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
            }
            items(runs, key = { "r" + it.id }) { r ->
                val (icon, tint) = when (r.outcome) {
                    "Error" -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
                    "Stopped" -> Icons.Outlined.StopCircle to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                }
                ListItem(
                    leadingContent = { Icon(icon, r.outcome, tint = tint) },
                    headlineContent = {
                        Text(DateUtils.getRelativeTimeSpanString(r.endedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                    },
                    supportingContent = {
                        Text(
                            if (r.outcome == "Error") r.message else buildString {
                                append("${formatCount(r.uploaded)} uploaded")
                                if (r.alreadyThere > 0) append(" · ${formatCount(r.alreadyThere)} already there")
                                if (r.failed > 0) append(" · ${formatCount(r.failed)} failed")
                                if (r.bytes > 0) append(" · ${formatBytes(r.bytes)}")
                            },
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        val secs = ((r.endedAt - r.startedAt) / 1000).coerceAtLeast(0)
                        if (secs > 0) Text(formatDuration(secs), style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}
