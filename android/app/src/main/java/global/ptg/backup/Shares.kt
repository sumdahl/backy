package global.ptg.backup

import android.content.Context
import java.io.File

/**
 * Files shared into Backy from other apps. The share grant ends when the sheet closes, so each
 * share is copied into its own folder here (with the chosen account and album) and kept until
 * every file in it is backed up; whatever failed or never ran can be retried later.
 */
object Shares {
    private const val META = ".meta"

    private fun root(context: Context) = File(context.filesDir, "share")

    /** A new folder for one share, remembering where it should go. */
    fun newBatch(context: Context, account: String, album: String) = newBatch(root(context), account, album)

    internal fun newBatch(root: File, account: String, album: String): File =
        File(root, System.nanoTime().toString()).apply {
            mkdirs()
            File(this, META).writeText("$account\n$album")
        }

    /** Shares with files still waiting, oldest first. Empty folders are cleaned up on the way. */
    fun pending(context: Context) = pending(root(context))

    internal fun pending(root: File): List<Selection.Files> =
        root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.mapNotNull { dir ->
            val files = dir.listFiles().orEmpty().filter { it.name != META }.map { it.path }.sorted()
            if (files.isEmpty()) { dir.deleteRecursively(); return@mapNotNull null }
            val meta = File(dir, META).takeIf { it.isFile }?.readLines().orEmpty()
            Selection.Files(files, meta.getOrNull(0)?.ifEmpty { null }, meta.getOrNull(1)?.ifEmpty { null })
        }

    /** Backed up (or permanently skipped): no reason to keep the copy. */
    fun done(paths: Collection<String>) {
        for (p in paths) {
            val f = File(p)
            f.delete()
            val dir = f.parentFile ?: continue
            if (dir.list()?.all { it == META } == true) dir.deleteRecursively()
        }
    }

    fun discard(context: Context) { root(context).deleteRecursively() }
}
