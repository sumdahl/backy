package global.ptg.backup

import global.ptg.data.AlbumDao
import global.ptg.data.AlbumItem
import global.ptg.data.AlbumRecord
import global.ptg.mobile.Mobile

/** Google Photos album calls. Errors are classified so the sync knows what to do with them. */
interface AlbumApi {
    /** Creates an album holding [keys] and returns its album key. */
    fun create(account: String, title: String, keys: List<String>): String
    fun add(account: String, albumKey: String, keys: List<String>)
}

/** Google is overloaded or the network dropped: try again later. */
class AlbumBusy(message: String) : Exception(message)

/** Google no longer knows this album (most likely deleted by the user). */
class AlbumMissing(message: String) : Exception(message)

/** The real thing, through the Go bridge. */
class GoAlbumApi(private val proxy: String) : AlbumApi {
    override fun create(account: String, title: String, keys: List<String>): String =
        classify { Mobile.createAlbum(account, proxy, title, keysJson(keys)) }

    override fun add(account: String, albumKey: String, keys: List<String>) =
        classify { Mobile.addToAlbum(account, proxy, albumKey, keysJson(keys)) }

    private fun keysJson(keys: List<String>) = global.ptg.data.Engine.json.encodeToString(keys)

    private fun <T> classify(call: () -> T): T = try {
        call()
    } catch (e: Exception) {
        val msg = e.message.orEmpty()
        val status = Regex("status (\\d{3})").find(msg)?.groupValues?.get(1)?.toInt()
        throw when {
            status == null || status == 429 || status >= 500 -> AlbumBusy(msg)
            // ponytail: 400/404 is how a deleted album appears to be reported; confirmed only
            // by recreating at most once per run, so a wrong guess costs one extra album at worst.
            status == 404 || status == 400 -> AlbumMissing(msg)
            else -> e
        }
    }
}

/**
 * Puts every pending [AlbumItem] into its target's album. All state is in the database, so a
 * run killed halfway simply continues next time. Never creates the same album twice:
 * the first create stores the key, every later call adds to that key.
 */
class AlbumSync(
    private val api: AlbumApi,
    private val dao: AlbumDao,
    /** Human title for a target, e.g. "folder:123" → "Camera". */
    private val title: (String) -> String,
) {
    data class Result(val added: Int, val albums: Int)

    /** @throws AlbumBusy when Google is overloaded; what's done so far is kept. */
    suspend fun run(onProgress: (added: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val pending = dao.pending()
        var added = 0
        val touched = HashSet<Pair<String, String>>()
        for ((group, items) in pending.groupBy { it.account to it.target }) {
            val (account, target) = group
            var queue = items.map { it.mediaKey }
            var recreated = false
            while (queue.isNotEmpty()) {
                val album = dao.latest(account, target) ?: seed(account, target)
                val room = if (album == null) 0 else LIMIT - album.count
                try {
                    val batch: List<String>
                    if (album == null || room <= 0) {
                        if (target.startsWith("key:") && album != null) throw IllegalStateException("Album is full")
                        val part = (album?.part ?: 0) + 1
                        val name = title(target).let { if (part > 1) "$it ($part)" else it }
                        batch = queue.take(BATCH)
                        val key = api.create(account, name, batch)
                        dao.save(AlbumRecord(account, target, part, name, key, batch.size))
                    } else {
                        batch = queue.take(minOf(BATCH, room))
                        api.add(account, album.albumKey, batch)
                        dao.save(album.copy(count = album.count + batch.size))
                    }
                    dao.markAdded(account, target, batch)
                    queue = queue.drop(batch.size)
                    added += batch.size
                    touched += group
                    onProgress(added, pending.size)
                } catch (e: AlbumMissing) {
                    // Deleted in Google Photos. Rebuild it once with everything that belongs there;
                    // a second miss in the same run means something else is wrong.
                    if (recreated || target.startsWith("key:")) throw e
                    recreated = true
                    dao.dropAlbums(account, target)
                    dao.unmark(account, target)
                    queue = dao.pending().filter { it.account == account && it.target == target }.map { it.mediaKey }
                }
            }
        }
        return Result(added, touched.size)
    }

    /** An album key the user pasted is used as-is; nothing to create. */
    private suspend fun seed(account: String, target: String): AlbumRecord? {
        if (!target.startsWith("key:")) return null
        return AlbumRecord(account, target, 1, target.removePrefix("key:"), target.removePrefix("key:"), 0)
            .also { dao.save(it) }
    }

    companion object {
        /** Google's per-call limit, as the core uses (core.AlbumBatchSize). */
        const val BATCH = 500
        /** Items per album, as the core uses (core.AlbumLimit). */
        const val LIMIT = 20_000
    }
}

fun isAlbumKey(s: String) = s.startsWith("AF1Qip") && s.length > 6
