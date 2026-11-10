package global.ptg

import global.ptg.backup.Backup
import global.ptg.data.FolderPrefs
import global.ptg.data.MediaItem
import global.ptg.data.MediaTypes
import global.ptg.data.Settings
import global.ptg.data.UploadRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun item(size: Long = 10, modified: Long = 1, video: Boolean = false) = MediaItem(
        id = 1, path = "/x.jpg", name = "x.jpg", bucketId = "b", bucket = "Camera",
        size = size, modified = modified, taken = 0, isVideo = video, durationMs = 0,
    )

    @Test fun backoffMatchesCli() {
        assertEquals(5_000, Backup.backoffMillis("file is corrupt", 1))
        assertEquals(60_000, Backup.backoffMillis("status 503", 1))
        assertEquals(120_000, Backup.backoffMillis("429 too many requests", 2))
        assertEquals(600_000, Backup.backoffMillis("resource_exhausted", 9))
    }

    @Test fun doneOnlyWhileFileUnchanged() {
        val rec = UploadRecord(1, "/x.jpg", 10, 1, "uploaded", "key", "a@b", 0)
        assertTrue(rec.isDone(item()))
        assertFalse(rec.isDone(item(modified = 2)))
        assertFalse(rec.copy(status = "failed").isDone(item()))
    }

    @Test fun folderOverridesBeatDefaults() {
        val s = Settings(saver = false, albumName = "All", albumPerFolder = true)
        val o = s.options("a@b", FolderPrefs(enabled = true, album = "Trip", saver = true))
        assertTrue(o.api.saver)
        // The upload run never does albums; AlbumSync does.
        assertEquals("", o.albumName)
        assertFalse(o.albumAuto)
    }

    @Test fun albumTargetPrecedence() {
        val it = item()
        assertEquals(null, Settings().albumTarget(it))
        assertEquals("name:All", Settings(albumName = "All").albumTarget(it))
        assertEquals("folder:b", Settings(albumName = "All", albumPerFolder = true).albumTarget(it))
        val withFolder = Settings(albumPerFolder = true, folders = mapOf("b" to FolderPrefs(enabled = true, album = "Trip")))
        assertEquals("name:Trip", withFolder.albumTarget(it))
        assertEquals("key:AF1QipXYZ", Settings(albumName = "AF1QipXYZ").albumTarget(it))
    }

    @Test fun cameraIsOnByDefault() {
        assertTrue(Settings().folder("b", "Camera").enabled)
        assertFalse(Settings().folder("c", "Screenshots").enabled)
    }

    @Test fun typeAndSizeFilters() {
        assertFalse(Settings(mediaTypes = MediaTypes.Photos).wants(item(video = true)))
        assertFalse(Settings(maxFileMb = 1).wants(item(size = 2L * 1024 * 1024)))
        assertTrue(Settings().wants(item(size = 2L * 1024 * 1024)))
    }
}

class AlbumRefTest {
    @org.junit.Test
    fun albumLinksBecomeKeys() {
        val key = "AF1QipOxyz_-123"
        org.junit.Assert.assertEquals(key, global.ptg.backup.albumRef("https://photos.google.com/album/$key"))
        org.junit.Assert.assertEquals(key, global.ptg.backup.albumRef("https://photos.google.com/u/1/album/$key?pli=1"))
        org.junit.Assert.assertEquals(key, global.ptg.backup.albumRef("  $key "))
        org.junit.Assert.assertEquals("Panchpokhari", global.ptg.backup.albumRef(" Panchpokhari "))
    }
}

class AlbumLinkSafetyTest {
    @org.junit.Test
    fun shareAddressesGiveKeysAndUnresolvedLinksNeverBecomeNames() {
        val key = "AF1QipFakeAlbumKey1"
        org.junit.Assert.assertEquals(key, global.ptg.backup.albumRef("https://photos.google.com/share/$key?key=abc"))
        val item = global.ptg.data.MediaItem(1, "/p", "a.jpg", "b1", "Trip", 1, 0, 0, false, 0)
        fun target(album: String) = global.ptg.data.Settings(folders = mapOf("b1" to global.ptg.data.FolderPrefs(enabled = true, album = album))).albumTarget(item)
        org.junit.Assert.assertNull(target("https://photos.app.goo.gl/ExampleShareLink"))
        org.junit.Assert.assertEquals("key:$key", target("https://photos.google.com/share/$key?key=abc"))
        org.junit.Assert.assertEquals("name:Trip 2026", target("Trip 2026"))
    }
}
