package global.ptg

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import global.ptg.backup.Notifs
import global.ptg.data.AccountsState
import global.ptg.data.Engine
import global.ptg.data.FolderPrefs
import global.ptg.data.MediaItem
import global.ptg.data.MediaRepo
import global.ptg.data.PtgDb
import global.ptg.data.Settings
import global.ptg.data.UploadRecord
import global.ptg.data.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PtgApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifs.ensureChannels(this)
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components {
            add(global.ptg.ui.ThumbFetcher.Factory())
            add(global.ptg.ui.ThumbKeyer())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .crossfade(true)
        .build()
}

val Context.container get() = (applicationContext as PtgApp).container

/** Item state as the UI shows it. */
enum class ItemState { Done, Pending, Failed, Never, Skipped, Off }

@Immutable
data class FolderUi(
    val id: String,
    val name: String,
    val items: Int,
    val done: Int,
    val failed: Int,
    val bytes: Long,
    val pendingBytes: Long,
    val cover: Uri?,
    val prefs: FolderPrefs,
)

@Immutable
data class Library(
    val loaded: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val state: Map<Long, ItemState> = emptyMap(),
    val records: Map<Long, UploadRecord> = emptyMap(),
    val folders: List<FolderUi> = emptyList(),
    /** Items already safe in Google Photos, which "Free up space" may delete. */
    val doneCount: Int = 0,
    val doneBytes: Long = 0,
    val doneByAccount: Map<String, Int> = emptyMap(),
) {
    // Computed once per snapshot, not on every recomposition.
    val done = folders.sumOf { it.done }
    val pending = state.values.count { it == ItemState.Pending }
    val failed = folders.sumOf { it.failed }
    val pendingBytes = folders.sumOf { it.pendingBytes }
    val enabledFolders = folders.count { it.prefs.enabled }
}

/** One per process. Owns the database, settings, gallery snapshot and account list. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = PtgDb.open(app)
    val media = MediaRepo(app)
    val settings = settingsStore(app)

    // Loading the Go library (~14 MB .so) off the main thread keeps cold start fast.
    private val engineReady = scope.async(Dispatchers.IO) { Engine.init(app.filesDir) }
    suspend fun engine() = engineReady.await()

    val accounts = MutableStateFlow(AccountsState())
    val accountsLoaded = MutableStateFlow(false)
    private val mediaItems = MutableStateFlow<List<MediaItem>?>(null)

    init {
        scope.launch { refreshAccounts() }
    }

    suspend fun refreshAccounts() {
        engine()
        accounts.value = Engine.accounts()
        accountsLoaded.value = true
    }

    suspend fun rescan(): List<MediaItem> = media.scan().also { mediaItems.value = it }

    val library: StateFlow<Library> = combine(
        mediaItems.filterNotNull(), db.uploads().all(), settings.data,
    ) { items, records, s -> build(items, records.associateBy { it.mediaId }, s) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, Library())

    private fun build(items: List<MediaItem>, records: Map<Long, UploadRecord>, s: Settings): Library {
        val state = HashMap<Long, ItemState>(items.size)
        val folders = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            folders.getOrPut(item.bucketId) { mutableListOf() } += item
            val rec = records[item.id]
            state[item.id] = when {
                rec == null -> null
                rec.isDone(item) -> ItemState.Done
                rec.status == "failed" -> ItemState.Failed
                rec.status == "never" -> ItemState.Never
                rec.status == "skipped" -> ItemState.Skipped
                else -> null
            } ?: if (s.folder(item.bucketId, item.bucket).enabled && s.wants(item)) ItemState.Pending else ItemState.Off
        }
        val folderUi = folders.map { (id, list) ->
            val first = list.first()
            FolderUi(
                id = id,
                name = first.bucket,
                items = list.size,
                done = list.count { state[it.id] == ItemState.Done },
                failed = list.count { state[it.id] == ItemState.Failed },
                bytes = list.sumOf { it.size },
                pendingBytes = list.filter { state[it.id] == ItemState.Pending }.sumOf { it.size },
                cover = first.uri,
                prefs = s.folder(id, first.bucket),
            )
        }.sortedWith(compareByDescending<FolderUi> { it.name == "Camera" }.thenByDescending { it.items })
        var doneCount = 0
        var doneBytes = 0L
        for (item in items) if (state[item.id] == ItemState.Done) { doneCount++; doneBytes += item.size }
        val byAccount = records.values.filter { it.status == "uploaded" || it.status == "exists" }
            .groupingBy { it.account }.eachCount()
        return Library(true, items, state, records, folderUi, doneCount, doneBytes, byAccount)
    }
}

/** Media-type and size filters from settings. */
fun Settings.wants(item: MediaItem): Boolean {
    val typeOk = when (mediaTypes) {
        global.ptg.data.MediaTypes.All -> true
        global.ptg.data.MediaTypes.Photos -> !item.isVideo
        global.ptg.data.MediaTypes.Videos -> item.isVideo
    }
    return typeOk && (maxFileMb <= 0 || item.size <= maxFileMb * 1024L * 1024L)
}
