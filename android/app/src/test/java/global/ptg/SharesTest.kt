package global.ptg

import global.ptg.backup.Selection
import global.ptg.backup.Shares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SharesTest {
    @Test
    fun failedFilesStayWithTheirAccountAndAlbum() {
        val root = Files.createTempDirectory("shares").toFile()
        val batch = Shares.newBatch(root, "me@example.com", "Trip")
        val ok = File(batch, "a.jpg").apply { writeText("a") }
        val bad = File(batch, "b.jpg").apply { writeText("b") }

        Shares.done(listOf(ok.path))
        assertEquals(listOf(Selection.Files(listOf(bad.path), "me@example.com", "Trip")), Shares.pending(root))

        Shares.done(listOf(bad.path))
        assertTrue(Shares.pending(root).isEmpty())
        assertFalse(batch.exists())
        root.deleteRecursively()
    }
}
