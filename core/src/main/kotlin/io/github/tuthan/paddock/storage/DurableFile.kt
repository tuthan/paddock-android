package io.github.tuthan.paddock.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces a file so that after a power loss it holds the old content or the new, and so that a caller told "saved" can
 * rely on the new. Syncing the temp file is not enough: the rename is an update of the parent directory, and that entry is
 * only durable once the directory itself is synced (fsync(2), NOTES).
 */
object DurableFile {
    /**
     * Writes [bytes] to a sibling `<name>.tmp`, syncs it, moves it over [target] and syncs the directory. Throws an
     * [IOException] when any step fails; the temp file is removed then and [target] is left as it was.
     */
    fun replace(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.path}")
        val tmp = File(target.absolutePath + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        if (dir != null) syncDirectory(dir)
    }

    /**
     * Makes the directory's entries durable. A platform that cannot open a directory has no directory sync to offer and is
     * skipped; a sync that fails after the directory opened is a real I/O error and propagates.
     */
    fun syncDirectory(dir: File) {
        val channel = try { FileChannel.open(dir.toPath(), StandardOpenOption.READ) } catch (_: IOException) { return }
        channel.use { it.force(true) }
    }
}
