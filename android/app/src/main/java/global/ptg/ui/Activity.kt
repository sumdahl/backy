package global.ptg.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import global.ptg.ItemState
import global.ptg.backup.Backup
import global.ptg.backup.Phase
import global.ptg.backup.Selection
import global.ptg.container
import global.ptg.data.Settings
import kotlinx.coroutines.delay

/** The phone version of `ptg --serve`: totals, per-thread transfers, and every failure. */
@Composable
fun ActivityScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val run by Backup.state.collectAsStateWithLifecycle()
    val lib by c.library.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.active) { while (run.active) { now = System.currentTimeMillis(); delay(1000) } }
    val failedItems = rememberComputed(lib, emptyList()) { l ->
        l.items.filter { l.state[it.id] == ItemState.Failed }.map { it to l.records[it.id]?.detail.orEmpty() }
    }
    val progress by animateFloatAsState(run.fraction, MaterialTheme.motionScheme.slowEffectsSpec(), label = "p")

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Backup activity") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp).animateContentSize()) {
                    Text(
                        when (run.phase) {
                            Phase.Idle -> "No backup has run yet"
                            Phase.Uploading -> "Backing up · ${(progress * 100).toInt()}%"
                            else -> run.message.ifEmpty { run.phase.name }
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    if (run.active) LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                    if (run.phase != Phase.Idle) {
                        val speed = run.speed(if (run.active) now else run.endedAt)
                        Text(
                            "${formatBytes(run.bytesDone)} of ${formatBytes(run.bytesTotal)}" +
                                (if (speed > 0) " · ${formatBytes(speed)}/s" else "") +
                                (run.etaSeconds(now).takeIf { run.active && it > 0 }?.let { " · ${formatDuration(it)} left" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(formatCount(run.uploaded), "Uploaded", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                        StatTile(formatCount(run.exists), "Already there", Modifier.weight(1f))
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(formatCount(run.skipped), "Skipped", Modifier.weight(1f))
                        StatTile(formatCount(run.failed), "Failed", Modifier.weight(1f),
                            if (run.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    if (run.active) {
                        Spacer(Modifier.size(16.dp))
                        OutlinedButton({ Backup.stop(context) }, Modifier.fillMaxWidth()) { Text("Stop backup") }
                    }
                }
            }

            if (run.threads.isNotEmpty()) {
                item { SectionHeader("Sending now") }
                items(run.threads, key = { "t" + it.worker }) { t ->
                    Surface(
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp).animateContentSize(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge)
                                AssistChip(onClick = {}, label = { Text(t.status.replaceFirstChar { it.uppercase() }) })
                            }
                            if (t.total > 0) {
                                LinearProgressIndicator(progress = { t.sent.toFloat() / t.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                Text("${formatBytes(t.sent)} of ${formatBytes(t.total)}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }

            if (failedItems.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                        SectionHeader("Couldn't back up · ${formatCount(failedItems.size)}", Modifier.weight(1f))
                        if (!run.active) FilledTonalButton({ startBackup(context, settings, Selection.Failed) }) { Text("Retry all") }
                    }
                }
                items(failedItems.take(500), key = { "x" + it.first.id }) { (item, why) ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(why.ifEmpty { "Unknown error" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}
