package global.ptg.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** What happened to one MediaStore item. size+modified tell us if the file changed since. */
@Entity(tableName = "uploads")
data class UploadRecord(
    @PrimaryKey val mediaId: Long,
    val path: String,
    val size: Long,
    val modified: Long,
    /** uploaded, exists, skipped, failed, never (user excluded it) */
    val status: String,
    val detail: String,
    val account: String,
    val at: Long,
    /** Google's key for the item, for uploaded and already-existing items alike. */
    @ColumnInfo(defaultValue = "") val mediaKey: String = "",
) {
    fun isDone(item: MediaItem) =
        (status == "uploaded" || status == "exists") && size == item.size && modified == item.modified
}

/**
 * One Google Photos album the app made (or was given). [target] is what it stands for:
 * `folder:<bucketId>`, `name:<album name>` or `key:<album key>`. A target past 20,000 items
 * continues in part 2, "Name (2)", and so on.
 */
@Entity(tableName = "albums", primaryKeys = ["account", "target", "part"])
data class AlbumRecord(
    val account: String,
    val target: String,
    val part: Int,
    val title: String,
    val albumKey: String,
    val count: Int,
)

/** An item that belongs in a target's album. [added] flips once Google has accepted it. */
@Entity(tableName = "album_items", primaryKeys = ["account", "target", "mediaKey"])
data class AlbumItem(
    val account: String,
    val target: String,
    val mediaKey: String,
    val added: Boolean = false,
)

/** One finished, stopped or failed backup, for the stats screen and the widget. */
@Entity(tableName = "runs")
data class RunRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    /** Finished, Stopped or Error */
    val outcome: String,
    val uploaded: Int,
    val alreadyThere: Int,
    val skipped: Int,
    val failed: Int,
    val bytes: Long,
    val message: String,
)

@Dao
interface RunDao {
    @androidx.room.Insert
    suspend fun add(run: RunRecord)

    @Query("SELECT * FROM runs ORDER BY endedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<RunRecord>>

    @Query("SELECT * FROM runs ORDER BY endedAt DESC LIMIT 1")
    suspend fun last(): RunRecord?
}

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads")
    fun all(): Flow<List<UploadRecord>>

    @Upsert
    suspend fun put(records: List<UploadRecord>)

    @Query("DELETE FROM uploads WHERE mediaId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM uploads WHERE status = 'never'")
    suspend fun never(): List<UploadRecord>

    @Query("DELETE FROM uploads WHERE status = 'never'")
    suspend fun clearNever()

    @Query("DELETE FROM uploads WHERE account = :account")
    suspend fun forget(account: String)
}

@Dao
interface AlbumDao {
    /** Never resets [AlbumItem.added]: an item already in its album stays recorded as such. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun want(items: List<AlbumItem>)

    @Query("SELECT * FROM album_items WHERE added = 0 ORDER BY account, target")
    suspend fun pending(): List<AlbumItem>

    @Query("SELECT * FROM albums WHERE account = :account AND target = :target ORDER BY part DESC LIMIT 1")
    suspend fun latest(account: String, target: String): AlbumRecord?

    @Upsert
    suspend fun save(album: AlbumRecord)

    @Query("UPDATE album_items SET added = 1 WHERE account = :account AND target = :target AND mediaKey IN (:keys)")
    suspend fun markAdded(account: String, target: String, keys: List<String>)

    @Query("DELETE FROM albums WHERE account = :account AND target = :target")
    suspend fun dropAlbums(account: String, target: String)

    @Query("UPDATE album_items SET added = 0 WHERE account = :account AND target = :target")
    suspend fun unmark(account: String, target: String)

    @Query("SELECT COUNT(*) FROM album_items WHERE added = 0")
    fun pendingCount(): Flow<Int>
}

@Database(entities = [UploadRecord::class, AlbumRecord::class, AlbumItem::class, RunRecord::class], version = 3, exportSchema = false)
abstract class PtgDb : RoomDatabase() {
    abstract fun uploads(): UploadDao
    abstract fun albums(): AlbumDao
    abstract fun runs(): RunDao

    companion object {
        /** v2: media keys get their own column (they lived in `detail`), plus the album tables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE uploads ADD COLUMN mediaKey TEXT NOT NULL DEFAULT ''")
                connection.execSQL("UPDATE uploads SET mediaKey = detail WHERE status = 'uploaded'")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS albums (account TEXT NOT NULL, target TEXT NOT NULL, part INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, albumKey TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(account, target, part))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS album_items (account TEXT NOT NULL, target TEXT NOT NULL, mediaKey TEXT NOT NULL, " +
                        "added INTEGER NOT NULL, PRIMARY KEY(account, target, mediaKey))",
                )
            }
        }

        /** v3: run history. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAt INTEGER NOT NULL, " +
                        "endedAt INTEGER NOT NULL, outcome TEXT NOT NULL, uploaded INTEGER NOT NULL, alreadyThere INTEGER NOT NULL, " +
                        "skipped INTEGER NOT NULL, failed INTEGER NOT NULL, bytes INTEGER NOT NULL, message TEXT NOT NULL)",
                )
            }
        }

        fun open(context: Context) =
            Room.databaseBuilder(context, PtgDb::class.java, "ptg.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
