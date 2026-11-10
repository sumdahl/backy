package global.ptg.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import global.ptg.FolderUi
import global.ptg.Library
import global.ptg.backup.Backup
import global.ptg.backup.Phase
import global.ptg.backup.RunState
import global.ptg.backup.Selection
import global.ptg.backup.Shares
import global.ptg.container
import global.ptg.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(settings: Settings, access: Access, go: (Route) -> Unit, onAllowAll: () -> Unit, onTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val lib by c.library.collectAsStateWithLifecycle()
    val run by Backup.state.collectAsStateWithLifecycle()
    val accounts by c.accounts.collectAsStateWithLifecycle()
    val signedIn = accounts.selected.isNotEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val health = rememberHealth()
    // Re-read after every run: a finished backup clears what it uploaded.
    val scope = rememberCoroutineScope()
    var shareCheck by remember { mutableIntStateOf(0) }
    val shares by produceState(emptyList<Selection.Files>(), run.active, shareCheck) {
        value = withContext(Dispatchers.IO) { Shares.pending(context) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AdaptiveTopBar(
                "Backup", scroll,
                subtitle = { Text(if (signedIn) accounts.selected else "Not signed in", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { go(Route.Stats) }) { Icon(Icons.Outlined.Insights, "Stats") }
                    IconButton(onClick = { onTab(Tab.Accounts) }) {
                        if (signedIn) Avatar(accounts.selected, 32.dp)
                        else Icon(Icons.Outlined.CloudUpload, "Accounts")
                    }
                },
            )
        },
    ) { pad ->
        val hero = @Composable {
            HeroCard(
                lib, run, signedIn,
                onBackup = { startBackup(context, settings) },
                onStop = { Backup.stop(context) },
                onDetails = { go(Route.Activity) },
                onSignIn = { go(Route.Login) },
            )
        }
        val list: LazyListScope.() -> Unit = {
            if (!health.notifications) item(key = "notif") {
                Notice(Icons.Outlined.NotificationsOff, "Notifications are off",
                    "You won't see backup progress or know when it's done. Android also hides the running backup.",
                    "Turn on", { context.startActivity(appNotificationSettings(context)) })
            }
            if (!health.unrestricted && !settings.batteryNoticeDismissed) item(key = "battery") {
                Notice(Icons.Outlined.BatteryAlert, "Let backups run with the screen off",
                    "Some phones stop long uploads to save battery. Allowing Backy to run unrestricted keeps them going.",
                    "Allow", { context.startActivity(batteryExemption(context)) },
                    secondary = "Not now" to { c.edit { it.copy(batteryNoticeDismissed = true) } })
            }
            if (access == Access.Partial) item(key = "partial") {
                Notice(Icons.Outlined.PhotoLibrary, "Only some photos are shared with the app",
                    "Allow access to all photos to back up your whole gallery.", "Allow all", onAllowAll)
            }
            if (lib.failed > 0 && !run.active) item(key = "failed") {
                Notice(Icons.Outlined.ErrorOutline, "${plural(lib.failed, "item")} couldn't be backed up",
                    "Usually a network hiccup or a file Google Photos won't take.", "Retry",
                    { startBackup(context, settings, Selection.Failed) },
                    secondary = "Details" to { go(Route.Activity) }, error = true)
            }
            if (shares.isNotEmpty() && !run.active) item(key = "shares") {
                val n = shares.sumOf { it.paths.size }
                Notice(Icons.Outlined.ErrorOutline, "${plural(n, "shared item")} not backed up yet",
                    "Kept on this phone until they're in Google Photos.", "Retry",
                    { startBackup(context, settings, shares.first()) },
                    secondary = "Discard" to { scope.launch(Dispatchers.IO) { Shares.discard(context); shareCheck++ } }, error = true)
            }
            item(key = "folders-h") {
                SectionHeader("Folders · ${lib.enabledFolders} of ${lib.folders.size} backing up")
            }
            if (!lib.loaded) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            }
            itemsIndexed(lib.folders, key = { _, f -> "f-" + f.id }, contentType = { _, _ -> "folder" }) { i, f ->
                FolderRow(f, i, lib.folders.size, onToggle = { on ->
                    c.edit { s -> s.copy(folders = s.folders + (f.id to f.prefs.copy(enabled = on))) }
                }, onOpen = { go(Route.Folder(f.id)) })
            }
            if (lib.doneCount > 0 && !run.active) item(key = "free") {
                Spacer(Modifier.size(16.dp))
                Notice(Icons.Outlined.DeleteSweep, "Free up ${formatBytes(lib.doneBytes)}",
                    "${plural(lib.doneCount, "item")} on this phone are already safe in Google Photos.",
                    "Review", { go(Route.FreeUp) })
            }
        }
        if (isTwoPane()) {
            // Landscape: the backup card stays in view on the left, notices and folders scroll on the right.
            val dir = LocalLayoutDirection.current
            Row(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding(), start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir))) {
                Column(Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { hero() }
                LazyColumn(Modifier.weight(0.58f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp), content = list)
            }
        } else {
            ReadableLazyColumn(pad, bottom = 24.dp) {
                item(key = "hero") { hero() }
                list()
            }
        }
    }
}

@Composable
private fun HeroCard(lib: Library, run: RunState, signedIn: Boolean, onBackup: () -> Unit, onStop: () -> Unit, onDetails: () -> Unit, onSignIn: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.active) { while (run.active) { now = System.currentTimeMillis(); delay(1000) } }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        AnimatedContent(
            targetState = run.phase.takeIf { run.active } ?: Phase.Idle,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "hero",
        ) { phase ->
            Column(Modifier.padding(24.dp)) {
                when (phase) {
                    Phase.Uploading, Phase.Waiting -> Running(run, now, onStop, onDetails)
                    Phase.Queued, Phase.Preparing, Phase.Albums -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ContainedLoadingIndicator(Modifier.size(64.dp))
                        Spacer(Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when (phase) { Phase.Queued -> run.message; Phase.Albums -> "Updating albums"; else -> "Getting ready" },
                                style = MaterialTheme.typography.titleLarge)
                            Text(when (phase) {
                                Phase.Queued -> "Starts as soon as you're connected"
                                Phase.Albums -> run.message.substringAfter("· ", "")
                                else -> "Looking for new photos"
                            },
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onStop) { Text("Cancel") }
                    }
                    else -> Idle(lib, run, signedIn, onBackup, onSignIn, onDetails)
                }
            }
        }
    }
}

@Composable
private fun Running(run: RunState, now: Long, onStop: () -> Unit, onDetails: () -> Unit) {
    val progress by animateFloatAsState(run.fraction, MaterialTheme.motionScheme.slowEffectsSpec(), label = "p")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(104.dp))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            if (run.phase == Phase.Waiting) {
                val left = ((run.waitUntil - now) / 1000).coerceAtLeast(0)
                Text("Retrying in ${formatDuration(left)}", style = MaterialTheme.typography.titleLarge)
                Text(run.message, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${formatCount(run.done)} of ${formatCount(run.total)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${formatBytes(run.bytesDone)} of ${formatBytes(run.bytesTotal)}", style = MaterialTheme.typography.bodyMedium)
                val speed = run.speed(now)
                val eta = run.etaSeconds(now)
                if (speed > 0) Text("${formatBytes(speed)}/s" + if (eta > 0) " · ${formatDuration(eta)} left" else "",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
        }
    }
    Spacer(Modifier.size(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onStop, Modifier.weight(1f)) { Text("Stop") }
        FilledTonalButton(onDetails, Modifier.weight(1f)) { Text("Details") }
    }
}

@Composable
private fun Idle(lib: Library, run: RunState, signedIn: Boolean, onBackup: () -> Unit, onSignIn: () -> Unit, onDetails: () -> Unit) {
    val pending = lib.pending
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(
            when { !signedIn -> Icons.Outlined.AccountCircle; pending == 0 -> Icons.Outlined.CloudDone; else -> Icons.Outlined.CloudUpload },
            cs.primary, cs.onPrimary, size = 72.dp,
        )
        Spacer(Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !signedIn -> "Sign in to start"
                    !lib.loaded -> "Checking your gallery"
                    lib.enabledFolders == 0 -> "No folders selected"
                    pending == 0 -> "All backed up"
                    else -> "${plural(pending, "item")} to back up"
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    !signedIn -> "Add a Google account to back up to"
                    lib.enabledFolders == 0 -> "Turn on a folder below to back it up"
                    pending > 0 -> "${formatBytes(lib.pendingBytes)} · ${formatCount(lib.done)} already safe"
                    else -> "${formatCount(lib.done)} items safe in Google Photos"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (run.phase == Phase.Finished || run.phase == Phase.Error || run.phase == Phase.Stopped) {
        Spacer(Modifier.size(16.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surface.copy(alpha = 0.6f), modifier = Modifier.clickable(onClick = onDetails)) {
            Text(
                when (run.phase) {
                    Phase.Error -> run.message
                    Phase.Stopped -> "Stopped · ${formatCount(run.uploaded)} uploaded"
                    else -> "${run.message} · ${formatCount(run.uploaded)} uploaded" +
                        (if (run.exists > 0) ", ${formatCount(run.exists)} already there" else "")
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.size(20.dp))
    Button(
        onClick = if (signedIn) onBackup else onSignIn,
        // Stays enabled with nothing new to upload: a run also finishes pending album work.
        enabled = !signedIn || lib.enabledFolders > 0,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) {
        Icon(Icons.Outlined.CloudUpload, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
        Text(if (signedIn) "Back up now" else "Sign in", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
    }
}

@Composable
private fun FolderRow(f: FolderUi, index: Int, count: Int, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Surface(
        shape = segmentShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
    ) {
        Column {
            ListItem(
                modifier = Modifier.clickable(onClick = onOpen),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    AsyncImage(
                        model = f.cover?.let(::Thumb), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                    )
                },
                headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        when {
                            !f.prefs.enabled -> "${plural(f.items, "item")} · not backing up"
                            f.done == f.items -> "All ${formatCount(f.items)} backed up"
                            else -> "${formatCount(f.done)} of ${formatCount(f.items)} backed up" +
                                if (f.pendingBytes > 0) " · ${formatBytes(f.pendingBytes)} to go" else ""
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Switch(checked = f.prefs.enabled, onCheckedChange = onToggle) },
            )
            AnimatedVisibility(f.prefs.enabled && f.items > 0 && f.done < f.items) {
                LinearProgressIndicator(
                    progress = { f.done.toFloat() / f.items },
                    modifier = Modifier.fillMaxWidth().padding(start = 88.dp, end = 24.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
fun Notice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
    error: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (error) cs.errorContainer else cs.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (error) cs.onErrorContainer else cs.primary)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = if (error) cs.onErrorContainer else cs.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onAction) { Text(action) }
                    secondary?.let { (label, f) -> TextButton(f) { Text(label) } }
                }
            }
        }
    }
}
