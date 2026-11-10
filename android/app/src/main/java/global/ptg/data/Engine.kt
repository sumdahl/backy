package global.ptg.data

import global.ptg.mobile.Mobile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/** Kotlin face of the Go upload core (`mobile/mobile.go`). All calls block, so all run on IO. */
object Engine {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    suspend fun init(filesDir: File) = withContext(Dispatchers.IO) {
        Mobile.init(File(filesDir, "ptg.config").path, KeystoreSealer)
    }

    suspend fun login(token: String): String = withContext(Dispatchers.IO) { Mobile.login(token.trim()) }

    suspend fun accounts(): AccountsState = withContext(Dispatchers.IO) {
        json.decodeFromString(AccountsState.serializer(), Mobile.accounts())
    }

    suspend fun select(email: String) = withContext(Dispatchers.IO) { Mobile.selectAccount(email) }
    suspend fun logout(email: String) = withContext(Dispatchers.IO) { Mobile.logout(email) }

    /**
     * One pass over [paths]. Returns when the Go side reports `stop`. Cancelling the
     * coroutine cancels the run in Go, then waits for it to wind down.
     */
    suspend fun upload(paths: List<String>, options: UploadOptions, onEvent: (EngineEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            var finished = false
            val watcher = launch {
                try { awaitCancellation() } finally { if (!finished) Mobile.cancel() }
            }
            try {
                Mobile.upload(
                    json.encodeToString(paths),
                    json.encodeToString(UploadOptions.serializer(), options),
                ) { raw -> parse(raw)?.let(onEvent) }
            } finally {
                finished = true
                watcher.cancel()
            }
        }

    private fun parse(raw: String): EngineEvent? {
        val e = json.decodeFromString(RawEvent.serializer(), raw)
        val d = e.data
        return when (e.event) {
            "start" -> EngineEvent.Start
            "stop" -> EngineEvent.Stop
            "thread" -> d?.let { EngineEvent.Thread(json.decodeFromJsonElement(it)) }
            "result" -> d?.let { EngineEvent.Result(json.decodeFromJsonElement(it)) }
            "warning" -> d?.let { EngineEvent.Warning(json.decodeFromJsonElement<WarningJson>(it).message) }
            "album_complete" -> d?.let { EngineEvent.Album(json.decodeFromJsonElement<AlbumJson>(it).name, null) }
            "album_error" -> d?.let {
                val a = json.decodeFromJsonElement<AlbumErrorJson>(it)
                EngineEvent.Album(a.name, a.error)
            }
            else -> null
        }
    }
}

sealed interface EngineEvent {
    data object Start : EngineEvent
    data object Stop : EngineEvent
    data class Thread(val t: ThreadStatus) : EngineEvent
    data class Result(val r: FileResult) : EngineEvent
    data class Warning(val message: String) : EngineEvent
    data class Album(val name: String, val error: String?) : EngineEvent
}

@Serializable
private data class RawEvent(val event: String, val data: JsonElement? = null)

@Serializable
/** core.AccountsState: its JSON tags are lowercase, unlike the other core types. */
data class AccountsState(
    @SerialName("accounts") val accounts: List<Account> = emptyList(),
    @SerialName("selected") val selected: String = "",
)

@Serializable
data class Account(@SerialName("email") val email: String)

@Serializable
data class ThreadStatus(
    @SerialName("WorkerID") val worker: Int = 0,
    /** idle, hashing, checking, uploading, finalizing, completed, skipped, error */
    @SerialName("Status") val status: String = "",
    @SerialName("FilePath") val path: String = "",
    @SerialName("FileName") val name: String = "",
    @SerialName("Message") val message: String = "",
    @SerialName("BytesUploaded") val sent: Long = 0,
    @SerialName("BytesTotal") val total: Long = 0,
    @SerialName("Attempt") val attempt: Int = 0,
)

@Serializable
data class FileResult(
    @SerialName("MediaKey") val mediaKey: String = "",
    @SerialName("SkipReason") val skipReason: String = "",
    @SerialName("ErrorMessage") val error: String = "",
    @SerialName("Path") val path: String = "",
    @SerialName("Paths") val paths: List<String>? = null,
    /** uploaded, exists, skipped, failed — same as the CLI */
    @SerialName("Status") val status: String = "",
) {
    val allPaths get() = paths?.takeIf { it.isNotEmpty() } ?: listOf(path)
    val detail get() = when (status) {
        "failed" -> error
        "uploaded" -> mediaKey
        else -> skipReason
    }
}

@Serializable private data class WarningJson(@SerialName("Message") val message: String = "")
@Serializable private data class AlbumJson(@SerialName("AlbumName") val name: String = "")
@Serializable private data class AlbumErrorJson(
    @SerialName("AlbumName") val name: String = "",
    @SerialName("Error") val error: String = "",
)

/** Mirrors core.UploadOptions; field names are the Go ones. */
@Serializable
data class UploadOptions(
    @SerialName("Api") val api: ApiOptions,
    @SerialName("Threads") val threads: Int,
    @SerialName("ExcludePattern") val excludePattern: String,
    @SerialName("ForceUpload") val force: Boolean,
    @SerialName("DisableUnsupportedFilesFilter") val allowUnsupported: Boolean,
    @SerialName("SetDateFromFilename") val dateFromFilename: Boolean,
    @SerialName("PairLivePhotos") val pairLive: Boolean,
    @SerialName("SkipIncompleteLivePhotos") val skipIncompleteLive: Boolean,
    @SerialName("UpdateExistingPhotosToLive") val updateExistingToLive: Boolean,
    @SerialName("AlbumName") val albumName: String,
    @SerialName("AlbumAutoMode") val albumAuto: Boolean,
)

@Serializable
data class ApiOptions(
    @SerialName("Account") val account: String,
    @SerialName("Proxy") val proxy: String,
    @SerialName("Saver") val saver: Boolean,
    @SerialName("UseQuota") val useQuota: Boolean,
)
