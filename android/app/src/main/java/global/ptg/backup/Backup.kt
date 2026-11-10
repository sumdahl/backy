package global.ptg.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import androidx.compose.runtime.Immutable
import global.ptg.AppContainer
import global.ptg.data.Engine
import global.ptg.data.EngineEvent
import global.ptg.data.MediaItem
import global.ptg.data.AlbumItem
import global.ptg.data.Settings
import global.ptg.data.UploadRecord
import global.ptg.wants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext

/**
 * What to back up. Serialised into the job's extras. [Items] and [Files] may name an account
 * and album, overriding the folder's (the share sheet uses this).
 */
sealed interface Selection {
    data object All : Selection
    data class Folder(val bucketId: String) : Selection
    data class Items(val ids: Set<Long>, val account: String? = null, val album: String? = null) : Selection
    /** Files outside the gallery (shared from another app), copied into the cache first. */
    data class Files(val paths: List<String>, val account: String? = null, val album: String? = null) : Selection
    data object Failed : Selection

    fun toBundle() = PersistableBundle().apply {
        when (val s = this@Selection) {
            All -> putString("kind", "all")
            Failed -> putString("kind", "failed")
            is Folder -> { putString("kind", "folder"); putString("bucket", s.bucketId) }
            is Items -> {
                putString("kind", "items"); putLongArray("ids", s.ids.toLongArray())
                putString("account", s.account); putString("album", s.album)
            }
            is Files -> {
                putString("kind", "files"); putStringArray("paths", s.paths.toTypedArray())
                putString("account", s.account); putString("album", s.album)
            }
        }
    }

    companion object {
        fun from(b: PersistableBundle): Selection = when (b.getString("kind")) {
            "failed" -> Failed
            "folder" -> Folder(b.getString("bucket").orEmpty())
            "items" -> Items(b.getLongArray("ids")?.toSet().orEmpty(), b.getString("account"), b.getString("album"))
            "files" -> Files(b.getStringArray("paths")?.toList().orEmpty(), b.getString("account"), b.getString("album"))
            else -> All
        }
    }
}

@Immutable
data class ThreadUi(val worker: Int, val name: String, val status: String, val sent: Long, val total: Long)

enum class Phase { Idle, Queued, Preparing, Uploading, Waiting, Albums, Finished, Stopped, Error }

@Immutable
data class RunState(
    val phase: Phase = Phase.Idle,
    val total: Int = 0,
    val uploaded: Int = 0,
    val exists: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val waitUntil: Long = 0,
    val pass: Int = 0,
    val threads: List<ThreadUi> = emptyList(),
    val message: String = "",
    val recentFailures: List<Pair<String, String>> = emptyList(),
) {
    val done get() = uploaded + exists + skipped + failed
    val active get() = phase in setOf(Phase.Queued, Phase.Preparing, Phase.Uploading, Phase.Waiting, Phase.Albums)
    val fraction get() = when {
        bytesTotal > 0 -> (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
        total > 0 -> done.toFloat() / total
        else -> 0f
    }
    /** bytes per second since the run started */
    fun speed(now: Long): Long {
        val secs = (now - startedAt) / 1000.0
        return if (secs > 1) (bytesDone / secs).toLong() else 0
    }
    fun etaSeconds(now: Long): Long {
        val s = speed(now)
        return if (s > 0) (bytesTotal - bytesDone).coerceAtLeast(0) / s else -1
    }
}

object Backup {
    private const val JOB_ID = 42
    val state = MutableStateFlow(RunState())

    /** Must be called while the app is visible: user-initiated jobs and foreground services are started by a tap. */
    fun start(context: Context, selection: Selection, wifiOnly: Boolean) {
        AutoBackup.cancelWork(context)
        if (Build.VERSION.SDK_INT >= 34) {
            val net = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .apply { if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                .build()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(net)
                .setExtras(selection.toBundle())
                .build()
            state.value = RunState(phase = Phase.Queued, message = if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for network")
            context.getSystemService(JobScheduler::class.java).schedule(job)
        } else {
            // No job to wait for the network on Android 12-13, so check it up front.
            val caps = context.getSystemService(ConnectivityManager::class.java).let { it.getNetworkCapabilities(it.activeNetwork) }
            val problem = when {
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true -> "No internet connection"
                wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "Connect to Wi-Fi to back up"
                else -> null
            }
            if (problem != null) {
                val now = System.currentTimeMillis()
                state.value = RunState(phase = Phase.Error, message = problem, startedAt = now, endedAt = now)
                return
            }
            state.value = RunState(phase = Phase.Queued, message = "Starting")
            context.startForegroundService(Intent(context, BackupService::class.java)
                .putExtra(BackupService.EXTRA_SELECTION, selection.toBundle()))
        }
    }

    fun stop(context: Context) {
        AutoBackup.cancelWork(context)
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        else context.stopService(Intent(context, BackupService::class.java))
        state.update { if (it.active) it.copy(phase = Phase.Stopped, endedAt = System.currentTimeMillis(), threads = emptyList()) else it }
    }

    /** The work itself. Runs inside [BackupJob]. */
    suspend fun run(c: AppContainer, selection: Selection) = coroutineScope {
        state.value = RunState(phase = Phase.Preparing, startedAt = System.currentTimeMillis(), message = "Looking for new photos")
        c.engine()
        c.refreshAccounts()
        val selected = c.accounts.value.selected
        val known = c.accounts.value.accounts.map { it.email }.toSet()
        if (selected.isEmpty()) {
            state.value = RunState(phase = Phase.Error, message = "Sign in to a Google account first")
            return@coroutineScope
        }
        val items = c.rescan()
        val s = c.settings.data.first()
        val records = c.db.uploads().all().first().associateBy { it.mediaId }

        // Items already in Google Photos whose folder now has an album (e.g. albums were turned on
        // later): queue them for their album. Already-added items are left alone.
        c.db.albums().want(items.mapNotNull { item ->
            val rec = records[item.id] ?: return@mapNotNull null
            val target = s.albumTarget(item) ?: return@mapNotNull null
            if (rec.isDone(item) && rec.mediaKey.isNotEmpty()) AlbumItem(rec.account, target, rec.mediaKey) else null
        })

        val queue = if (selection is Selection.Files) sharedFiles(selection.paths) else items.filter { item ->
            val f = s.folder(item.bucketId, item.bucket)
            val rec = records[item.id]
            val open = rec?.status != "never" && (s.force || rec?.isDone(item) != true)
            when (selection) {
                Selection.All -> f.enabled && s.wants(item) && open
                is Selection.Folder -> item.bucketId == selection.bucketId && s.wants(item) && open
                is Selection.Items -> item.id in selection.ids
                Selection.Failed -> rec?.status == "failed"
                is Selection.Files -> false
            }
        }
        val accountOverride = when (selection) {
            is Selection.Items -> selection.account
            is Selection.Files -> selection.account
            else -> null
        }?.takeIf { it in known }
        val albumOverride = when (selection) {
            is Selection.Items -> selection.album
            is Selection.Files -> selection.album
            else -> null
        }?.takeIf { it.isNotBlank() }?.trim()
        fun targetFor(item: MediaItem) =
            albumOverride?.let { albumRef(it) }?.let { if (isAlbumKey(it)) "key:$it" else "name:$it" } ?: s.albumTarget(item)
        if (queue.isEmpty()) {
            val albumNote = syncAlbums(c, s, items)
            state.value = RunState(phase = Phase.Finished, endedAt = System.currentTimeMillis(),
                message = albumNote ?: "Everything is backed up")
            return@coroutineScope
        }

        // Folders with identical options share one run in Go, so threads stay busy across folders.
        val groups = queue.groupBy { item ->
            val f = s.folder(item.bucketId, item.bucket)
            val account = accountOverride ?: f.account?.takeIf { it in known } ?: selected
            s.options(account, f)
        }

        state.value = RunState(
            phase = Phase.Uploading, total = queue.size, bytesTotal = queue.sumOf { it.size },
            startedAt = System.currentTimeMillis(), message = "Backing up",
        )

        val outcome = HashMap<Long, String>()
        var finishedBytes = 0L
        val threads = HashMap<Int, ThreadUi>()
        // Go calls back from several worker threads; one lock guards everything below.
        val lock = Any()
        val toSave = ConcurrentLinkedQueue<UploadRecord>()
        val albumWants = ConcurrentLinkedQueue<AlbumItem>()
        val flusher = launch {
            while (isActive) { delay(1500); flush(c, toSave, albumWants) }
        }

        try {
            for ((opts, group) in groups) {
                var pending = group
                var lastErrors = ""
                for (pass in 0..s.retries) {
                    if (pass > 0) {
                        val wait = backoffMillis(lastErrors, pass)
                        state.update { it.copy(phase = Phase.Waiting, waitUntil = System.currentTimeMillis() + wait, pass = pass, threads = emptyList(), message = "Google is busy, retrying ${pending.size}") }
                        delay(wait)
                        state.update { it.copy(phase = Phase.Uploading, message = "Retrying") }
                    }
                    val byPath = pending.associateBy { it.path }
                    val failed = ArrayList<MediaItem>()
                    val errors = StringBuilder()
                    Engine.upload(pending.map { it.path }, opts) { ev ->
                        when (ev) {
                            is EngineEvent.Thread -> synchronized(lock) {
                                val t = ev.t
                                if (t.status == "idle" || t.status == "completed") threads.remove(t.worker)
                                else threads[t.worker] = ThreadUi(t.worker, t.name, t.status, t.sent, t.total)
                                val inflight = threads.values.filter { it.status == "uploading" }.sumOf { it.sent }
                                state.update { it.copy(threads = threads.values.sortedBy { x -> x.worker }, bytesDone = finishedBytes + inflight) }
                            }
                            is EngineEvent.Result -> synchronized(lock) {
                                val r = ev.r
                                val now = System.currentTimeMillis()
                                for (p in r.allPaths) {
                                    val item = byPath[p] ?: continue
                                    // Cut off by Stop, not refused: leave it as not backed up yet, for next time.
                                    if (r.status == "failed" && (state.value.phase == Phase.Stopped || "context canceled" in r.error)) continue
                                    val old = outcome.put(item.id, r.status)
                                    if (old == null) finishedBytes += item.size
                                    if (r.status == "failed") { failed += item; errors.append(r.error.lowercase()).append(' ') }
                                    // Shared files have no gallery row to remember them by.
                                    if (item.id > 0) toSave += UploadRecord(item.id, item.path, item.size, item.modified, r.status, r.detail, opts.api.account, now, r.mediaKey)
                                    if (r.status != "failed" && r.mediaKey.isNotEmpty()) {
                                        targetFor(item)?.let { albumWants += AlbumItem(opts.api.account, it, r.mediaKey) }
                                    }
                                    state.update { st ->
                                        var n = st
                                        if (old != null) n = n.minus(old)
                                        n = n.plus(r.status)
                                        if (r.status == "failed") n = n.copy(recentFailures = (listOf(item.name to r.error) + n.recentFailures).take(20))
                                        n.copy(bytesDone = finishedBytes)
                                    }
                                }
                            }
                            is EngineEvent.Warning, is EngineEvent.Album, EngineEvent.Start, EngineEvent.Stop -> Unit
                        }
                    }
                    lastErrors = errors.toString()
                    pending = failed
                    if (pending.isEmpty() || !coroutineContext.isActive) break
                }
            }
            flush(c, toSave, albumWants)
            val albumNote = syncAlbums(c, s, items)
            val st = state.value
            state.value = st.copy(
                phase = Phase.Finished, endedAt = System.currentTimeMillis(), threads = emptyList(),
                message = when {
                    st.failed > 0 -> "${st.failed} couldn't be backed up"
                    albumNote != null -> albumNote
                    else -> "Backup complete"
                },
            )
        } finally {
            flusher.cancel()
            withContext(NonCancellable) {
                flush(c, toSave, albumWants)
                // Keep failed and never-reached shared files so they can be retried.
                if (selection is Selection.Files) Shares.done(queue.filter { outcome[it.id].let { st -> st != null && st != "failed" } }.map { it.path })
                c.rescan()
            }
        }
    }

    /** Saves the run that just ended to the history (stats screen, widget). */
    suspend fun record(c: AppContainer) {
        val s = state.value
        if (s.phase !in setOf(Phase.Finished, Phase.Stopped, Phase.Error)) return
        c.db.runs().add(global.ptg.data.RunRecord(
            startedAt = s.startedAt.takeIf { it > 0 } ?: s.endedAt, endedAt = s.endedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            outcome = s.phase.name, uploaded = s.uploaded, alreadyThere = s.exists, skipped = s.skipped,
            failed = s.failed, bytes = s.bytesDone, message = s.message,
        ))
    }

    /** Shared files as pseudo gallery items (negative ids: never written to the upload table). */
    private fun sharedFiles(paths: List<String>) = paths.mapIndexedNotNull { i, p ->
        val f = java.io.File(p).takeIf { it.isFile } ?: return@mapIndexedNotNull null
        MediaItem(-(i + 1L), p, f.name, "shared", "Shared", f.length(), f.lastModified() / 1000, f.lastModified(), false, 0)
    }

    private suspend fun flush(c: AppContainer, q: ConcurrentLinkedQueue<UploadRecord>, albums: ConcurrentLinkedQueue<AlbumItem>) {
        val batch = generateSequence { q.poll() }.toList()
        if (batch.isNotEmpty()) c.db.uploads().put(batch)
        val wants = generateSequence { albums.poll() }.toList()
        if (wants.isNotEmpty()) c.db.albums().want(wants)
    }

    /**
     * Adds everything waiting for an album. Returns a note for the user when albums couldn't be
     * finished; what's left stays queued in the database for the next backup.
     */
    private suspend fun syncAlbums(c: AppContainer, s: Settings, items: List<MediaItem>): String? {
        val folderNames = items.associate { "folder:${it.bucketId}" to it.bucket }
        val sync = AlbumSync(GoAlbumApi(s.proxy), c.db.albums()) { t ->
            if (t.startsWith("folder:")) folderNames[t] ?: "Backy" else t.substringAfter(':')
        }
        for (pass in 0..s.retries) {
            if (pass > 0) {
                val wait = backoffMillis("status 503", pass)
                state.update { it.copy(phase = Phase.Waiting, waitUntil = System.currentTimeMillis() + wait, message = "Google is busy, albums will retry") }
                delay(wait)
            }
            try {
                withContext(Dispatchers.IO) {
                    sync.run { added, total ->
                        state.update { it.copy(phase = Phase.Albums, threads = emptyList(), message = "Adding to albums · $added of $total") }
                    }
                }
                return null
            } catch (e: AlbumBusy) {
                continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return "Backed up, but an album couldn't be updated: ${e.message}"
            }
        }
        return "Backed up. Albums will finish next time"
    }

    private fun RunState.plus(status: String) = when (status) {
        "uploaded" -> copy(uploaded = uploaded + 1)
        "exists" -> copy(exists = exists + 1)
        "skipped" -> copy(skipped = skipped + 1)
        else -> copy(failed = failed + 1)
    }

    private fun RunState.minus(status: String) = when (status) {
        "uploaded" -> copy(uploaded = uploaded - 1)
        "exists" -> copy(exists = exists - 1)
        "skipped" -> copy(skipped = skipped - 1)
        else -> copy(failed = failed - 1)
    }

    /** Same policy as the CLI: long waits only when Google is overloaded. */
    fun backoffMillis(errors: String, pass: Int): Long {
        val overloaded = listOf("429", "rate limit", "resource_exhausted", "too many requests", "status 500", "status 503")
            .any { it in errors }
        if (!overloaded) return 5_000
        return (60_000L shl (pass - 1)).coerceAtMost(600_000)
    }
}
