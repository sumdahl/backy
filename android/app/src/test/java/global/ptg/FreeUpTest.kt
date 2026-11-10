package global.ptg

import global.ptg.data.MediaItem
import global.ptg.data.MediaTypes
import global.ptg.ui.DAY
import global.ptg.ui.plan
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeUpTest {
    private val now = System.currentTimeMillis()
    private fun item(id: Long, bucket: String, ageDays: Int, video: Boolean = false) =
        MediaItem(id, "/p/$id", "$id.jpg", bucket, bucket, 100, 0, now - ageDays * DAY, video, 0)

    @Test
    fun onlyBackedUpOldEnoughChosenItems() {
        val items = listOf(item(1, "Camera", 100), item(2, "Camera", 5), item(3, "Camera", 100, video = true),
            item(4, "WhatsApp", 100), item(5, "Camera", 100))
        val state = mapOf(1L to ItemState.Done, 2L to ItemState.Done, 3L to ItemState.Done, 4L to ItemState.Done, 5L to ItemState.Pending)
        val lib = Library(loaded = true, items = items, state = state)

        assertEquals(listOf(1L, 3L, 4L), plan(lib, 30, MediaTypes.All, emptySet()).items.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), plan(lib, 0, MediaTypes.All, emptySet()).items.map { it.id })
        assertEquals(listOf(3L), plan(lib, 30, MediaTypes.Videos, emptySet()).items.map { it.id })
        // Excluding a folder drops its items but keeps it listed, so it can be ticked back on.
        val p = plan(lib, 30, MediaTypes.All, setOf("WhatsApp"))
        assertEquals(listOf(1L, 3L), p.items.map { it.id })
        assertEquals(setOf("Camera", "WhatsApp"), p.folders.map { it.second }.toSet())
    }
}
