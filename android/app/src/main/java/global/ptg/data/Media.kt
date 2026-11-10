package global.ptg.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class MediaItem(
    val id: Long,
    val path: String,
    val name: String,
    val bucketId: String,
    val bucket: String,
    val size: Long,
    /** seconds, MediaStore DATE_MODIFIED */
    val modified: Long,
    /** millis */
    val taken: Long,
    val isVideo: Boolean,
    val durationMs: Long,
) {
    /** Derived, not stored: 50k gallery items don't need 50k extra Uri objects. */
    val uri: Uri get() = ContentUris.withAppendedId(
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
    )
}

/** Reads the phone's gallery from MediaStore. */
class MediaRepo(private val context: Context) {
    // ponytail: full query each scan (~0.3 s per 50k rows); use MediaStore.getGeneration() deltas if that shows up in traces.
    suspend fun scan(): List<MediaItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DURATION,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND " +
            "${MediaStore.Files.FileColumns.IS_PENDING} = 0 AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val out = ArrayList<MediaItem>(4096)
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC, ${MediaStore.Files.FileColumns._ID} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(projection[0])
            val data = c.getColumnIndexOrThrow(projection[1])
            val name = c.getColumnIndexOrThrow(projection[2])
            val bId = c.getColumnIndexOrThrow(projection[3])
            val bName = c.getColumnIndexOrThrow(projection[4])
            val size = c.getColumnIndexOrThrow(projection[5])
            val mod = c.getColumnIndexOrThrow(projection[6])
            val taken = c.getColumnIndexOrThrow(projection[7])
            val type = c.getColumnIndexOrThrow(projection[8])
            val dur = c.getColumnIndexOrThrow(projection[9])
            while (c.moveToNext()) {
                val path = c.getString(data) ?: continue
                val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val rowId = c.getLong(id)
                val modified = c.getLong(mod)
                out += MediaItem(
                    id = rowId,
                    path = path,
                    name = c.getString(name) ?: path.substringAfterLast('/'),
                    bucketId = c.getString(bId) ?: "",
                    bucket = c.getString(bName) ?: "Other",
                    size = c.getLong(size),
                    modified = modified,
                    taken = c.getLong(taken).takeIf { it > 0 } ?: (modified * 1000),
                    isVideo = video,
                    durationMs = if (video) c.getLong(dur) else 0,
                )
            }
        }
        out
    }
}
