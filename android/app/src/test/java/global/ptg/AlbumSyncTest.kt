package global.ptg

import global.ptg.backup.AlbumApi
import global.ptg.backup.AlbumMissing
import global.ptg.backup.AlbumSync
import global.ptg.data.AlbumDao
import global.ptg.data.AlbumItem
import global.ptg.data.AlbumRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two tables, in memory, behaving like the Room queries. */
private class FakeDao : AlbumDao {
    val items = LinkedHashMap<Triple<String, String, String>, AlbumItem>()
    val albums = HashMap<Triple<String, String, Int>, AlbumRecord>()

    override suspend fun want(items: List<AlbumItem>) {
        for (i in items) this.items.putIfAbsent(Triple(i.account, i.target, i.mediaKey), i)
    }
    override suspend fun pending() = items.values.filter { !it.added }.sortedWith(compareBy({ it.account }, { it.target }))
    override suspend fun latest(account: String, target: String) =
        albums.values.filter { it.account == account && it.target == target }.maxByOrNull { it.part }
    override suspend fun save(album: AlbumRecord) { albums[Triple(album.account, album.target, album.part)] = album }
    override suspend fun markAdded(account: String, target: String, keys: List<String>) {
        for (k in keys) items.computeIfPresent(Triple(account, target, k)) { _, v -> v.copy(added = true) }
    }
    override suspend fun dropAlbums(account: String, target: String) {
        albums.keys.removeAll { it.first == account && it.second == target }
    }
    override suspend fun unmark(account: String, target: String) {
        items.replaceAll { k, v -> if (k.first == account && k.second == target) v.copy(added = false) else v }
    }
    override fun pendingCount(): Flow<Int> = flowOf(items.values.count { !it.added })
}

/** Records calls; can pretend an album was deleted, or that a run dies halfway. */
private class FakeApi : AlbumApi {
    val creates = mutableListOf<Pair<String, Int>>()
    val adds = mutableListOf<Pair<String, Int>>()
    val deleted = HashSet<String>()
    var failAfterCalls = Int.MAX_VALUE
    private var n = 0

    private fun tick() { if (++n > failAfterCalls) throw IllegalStateException("killed") }

    override fun create(account: String, title: String, keys: List<String>): String {
        tick(); creates += title to keys.size
        return "AF1Qip-$title-${creates.size}"
    }
    override fun add(account: String, albumKey: String, keys: List<String>) {
        if (albumKey in deleted) throw AlbumMissing("status 404")
        tick(); adds += albumKey to keys.size
    }
}

class AlbumSyncTest {
    private fun keys(n: Int, from: Int = 0) = (from until from + n).map { AlbumItem("a@b", "folder:1", "k$it") }
    private fun sync(api: FakeApi, dao: FakeDao) = AlbumSync(api, dao) { "Camera" }

    @Test fun oneAlbumAcrossRuns() = runBlocking {
        val dao = FakeDao(); val api = FakeApi()
        dao.want(keys(3)); sync(api, dao).run()
        dao.want(keys(2, from = 3)); sync(api, dao).run()
        assertEquals(listOf("Camera" to 3), api.creates)        // created once
        assertEquals(1, api.adds.size)                          // second run added to it
        assertEquals(5, dao.latest("a@b", "folder:1")!!.count)
    }

    @Test fun secondRunWithNothingNewMakesNoCalls() = runBlocking {
        val dao = FakeDao(); val api = FakeApi()
        dao.want(keys(3)); sync(api, dao).run()
        dao.want(keys(3))                                       // same items queued again
        val before = api.creates.size + api.adds.size
        sync(api, dao).run()
        assertEquals(before, api.creates.size + api.adds.size)
    }

    @Test fun killedHalfwayFinishesNextRun() = runBlocking {
        val dao = FakeDao(); val api = FakeApi()
        dao.want(keys(1200))                                    // 3 batches of ≤ 500
        api.failAfterCalls = 1
        runCatching { sync(api, dao).run() }
        assertEquals(700, dao.pending().size)                   // first batch kept
        api.failAfterCalls = Int.MAX_VALUE
        sync(api, dao).run()
        assertEquals(0, dao.pending().size)
        assertEquals(1, api.creates.size)                       // still one album
        assertEquals(1200, dao.latest("a@b", "folder:1")!!.count)
    }

    @Test fun deletedAlbumIsRecreatedOnceWithEverything() = runBlocking {
        val dao = FakeDao(); val api = FakeApi()
        dao.want(keys(3)); sync(api, dao).run()
        api.deleted += dao.latest("a@b", "folder:1")!!.albumKey
        dao.want(keys(2, from = 3)); sync(api, dao).run()
        assertEquals(2, api.creates.size)
        assertEquals(5, api.creates.last().second)              // new album holds old + new items
        assertTrue(dao.pending().isEmpty())
    }

    @Test fun fullAlbumContinuesInPartTwo() = runBlocking {
        val dao = FakeDao(); val api = FakeApi()
        dao.save(AlbumRecord("a@b", "folder:1", 1, "Camera", "AF1Qip-full", AlbumSync.LIMIT - 1))
        dao.want(keys(3)); sync(api, dao).run()
        assertEquals(listOf("AF1Qip-full" to 1), api.adds)
        assertEquals(listOf("Camera (2)" to 2), api.creates)
    }
}
