package global.ptg.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

enum class ThemeMode { System, Light, Dark }
enum class MediaTypes { All, Photos, Videos }

/** Per-folder choices that override the global defaults. Null means "use the default". */
@Serializable
data class FolderPrefs(
    val enabled: Boolean = false,
    val account: String? = null,
    val album: String? = null,
    val saver: Boolean? = null,
)

/** Every option the Go core supports, plus the app's own. Stored as one JSON document. */
@Serializable
data class Settings(
    val folders: Map<String, FolderPrefs> = emptyMap(),
    /** Folders turned on before the user has touched anything. Matched by bucket name. */
    val defaultFolders: Set<String> = setOf("Camera"),

    // Quality & storage
    val saver: Boolean = false,
    val useQuota: Boolean = false,

    // Upload
    val threads: Int = 3,
    val retries: Int = 2,
    val force: Boolean = false,
    val dateFromFilename: Boolean = true,
    val excludePattern: String = "",
    val allowUnsupported: Boolean = false,
    val mediaTypes: MediaTypes = MediaTypes.All,
    val maxFileMb: Int = 0,

    // Albums
    val albumName: String = "",
    val albumPerFolder: Boolean = false,

    // Live / motion photos
    val pairLive: Boolean = true,
    val skipIncompleteLive: Boolean = false,
    val updateExistingToLive: Boolean = false,

    // Network
    val wifiOnly: Boolean = true,
    val proxy: String = "",

    // Appearance
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,

    val onboarded: Boolean = false,
    /** The user said "Not now" to the battery-optimisation notice. */
    val batteryNoticeDismissed: Boolean = false,
) {
    fun folder(bucketId: String, bucketName: String): FolderPrefs =
        folders[bucketId] ?: FolderPrefs(enabled = bucketName in defaultFolders)

    fun options(account: String, folder: FolderPrefs?) = UploadOptions(
        api = ApiOptions(account, proxy, folder?.saver ?: saver, useQuota),
        threads = threads,
        excludePattern = excludePattern,
        force = force,
        allowUnsupported = allowUnsupported,
        dateFromFilename = dateFromFilename,
        pairLive = pairLive,
        skipIncompleteLive = skipIncompleteLive,
        updateExistingToLive = updateExistingToLive,
        // Albums are handled by the app after upload (see AlbumSync), never by the upload run.
        albumName = "",
        albumAuto = false,
    )

    /**
     * Which album an item belongs in, or null for none. A folder's own album wins, then
     * "one album per folder", then the default album. Folders are matched by id, so renaming
     * a folder on the phone keeps it in the same album.
     */
    fun albumTarget(item: MediaItem): String? {
        fun named(n: String) = if (global.ptg.backup.isAlbumKey(n)) "key:$n" else "name:$n"
        folder(item.bucketId, item.bucket).album?.takeIf { it.isNotBlank() }?.let { return named(it.trim()) }
        if (albumPerFolder) return "folder:${item.bucketId}"
        if (albumName.isNotBlank()) return named(albumName.trim())
        return null
    }
}

private object SettingsSerializer : Serializer<Settings> {
    override val defaultValue = Settings()
    override suspend fun readFrom(input: InputStream): Settings = try {
        Engine.json.decodeFromString(Settings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("settings", e)
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) =
        output.write(Engine.json.encodeToString(Settings.serializer(), t).encodeToByteArray())
}

fun settingsStore(context: Context): DataStore<Settings> =
    DataStoreFactory.create(SettingsSerializer) { context.dataStoreFile("settings.json") }
