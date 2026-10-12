package io.github.tuthan.paddock.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Where the file features keep what they hold on the phone, all under the app's cache directory, so the platform may reclaim it and no backup takes it
 * (`allowBackup` is off and the extraction rules exclude every domain):
 *
 *  - `camera/<id>.jpg`, a capture the camera app wrote through the provider, deleted as soon as its snapshot exists;
 *  - `incoming/<id>/`, a snapshot: one private copy made at intake (SEC-2) that is sniffed, stripped, hashed and sent;
 *  - `transfers/<machine>/`, what *Get* brought from a machine.
 *
 * The ids are random and checked before they become a path, so a value that came back through saved state cannot name anything outside the folders.
 * [sweep] runs at start. A snapshot the draft still names is kept for a day, whatever else is old; one it does not name goes once it is past [GRACE_MILLIS],
 * which is there so an intake that is writing right now (and so is not named yet) is never swept under it.
 */
class TransferCache(private val cacheDir: File, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val camera = File(cacheDir, "camera")
    private val incoming = File(cacheDir, "incoming")
    private val transfers = File(cacheDir, "transfers")

    /** The capture file for [id], made ready: the folder exists. */
    fun cameraFile(id: String): File {
        require(validId(id)) { "bad id" }
        camera.mkdirs()
        return File(camera, "$id.jpg")
    }

    /** The folder holding snapshot [id]; it exists when this returns. */
    fun snapshotDir(id: String): File {
        require(validId(id)) { "bad id" }
        return File(incoming, id).also { it.mkdirs() }
    }

    /** The snapshot's folder if it is still there, else null (the sweep took it, or it was sent and deleted). */
    fun existingSnapshotDir(id: String): File? = if (!validId(id)) null else File(incoming, id).takeIf { it.isDirectory }

    /** The folder for what *Get* fetched from machine [machineId]. */
    fun machineDir(machineId: String): File {
        require(validId(machineId)) { "bad id" }
        return File(transfers, machineId).also { it.mkdirs() }
    }

    /**
     * The file a *Get* writes: `transfers/<machine>/<id>/<name>`, in a folder of its own so two fetches of one name never meet. [name] is [FileNames.cachedName]'s
     * and is checked again here (SEC-6): a plain name, and a canonical path that lands directly inside its folder, which holds a link in the cache against it.
     */
    fun fetchFile(machineId: String, id: String, name: String): File {
        require(validId(machineId) && validId(id)) { "bad id" }
        val dir = File(File(transfers, machineId), id).also { it.mkdirs() }
        require(FileNames.containedIn(dir.path, name)) { "bad name" }
        val file = File(dir, name)
        require(file.canonicalFile.parentFile == dir.canonicalFile) { "bad name" }
        return file
    }

    /** One fetch's folder, with whatever is in it (a failed or cancelled read leaves a part). */
    fun deleteFetched(machineId: String, id: String) {
        if (validId(machineId) && validId(id)) deleteTree(File(File(transfers, machineId), id))
    }

    fun deleteSnapshot(id: String) {
        if (validId(id)) deleteTree(File(incoming, id))
    }

    fun deleteCamera(id: String) {
        if (validId(id)) File(camera, "$id.jpg").delete()
    }

    /** Remove forgets the machine's fetched files with it (slice 12). */
    fun forgetMachine(machineId: String) {
        if (validId(machineId)) deleteTree(File(transfers, machineId))
    }

    /** What the sweep took, for a test to read. */
    data class Swept(val camera: Int, val snapshots: Int, val fetched: Int)

    /**
     * Deletes what is old or not named. [referenced] is the set of snapshot ids a draft still holds. Fetched files go when they are a day old.
     * Nothing here lists a folder it did not make: anything under the three roots that does not look like one of ours is left alone.
     */
    fun sweep(referenced: Set<String>): Swept {
        val now = nowMillis()
        var cam = 0
        var snaps = 0
        var fetched = 0
        camera.listFiles()?.forEach { f ->
            // A capture is deleted once its snapshot exists, so what is here is a capture whose snapshot never was.
            val age = now - f.lastModified()
            if (age >= GRACE_MILLIS) { f.delete(); cam++ }
        }
        incoming.listFiles()?.forEach { d ->
            if (!d.isDirectory || !validId(d.name)) return@forEach
            val age = now - newest(d)
            val drop = age >= MAX_AGE_MILLIS || (d.name !in referenced && age >= GRACE_MILLIS)
            if (drop) { deleteTree(d); snaps++ }
        }
        transfers.listFiles()?.forEach { m ->
            if (!m.isDirectory || !validId(m.name)) return@forEach
            m.listFiles()?.forEach { f -> if (now - f.lastModified() >= MAX_AGE_MILLIS) { deleteTree(f); fetched++ } }
            if (m.list().isNullOrEmpty()) m.delete()
        }
        return Swept(cam, snaps, fetched)
    }

    /** The newest change in [dir] or directly under it, read without following a link. */
    private fun newest(dir: File): Long {
        fun mtime(f: File): Long = try {
            Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).lastModifiedTime().toMillis()
        } catch (_: IOException) { 0L }
        return (dir.listFiles()?.maxOfOrNull(::mtime) ?: 0L).coerceAtLeast(mtime(dir))
    }

    companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(1)
        val GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10)
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

        fun validId(id: String): Boolean = ID.matches(id)

        /** A tree is deleted without following a link out of it. */
        fun deleteTree(file: File) {
            val path: Path = file.toPath()
            try {
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult { Files.deleteIfExists(f); return FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult { Files.deleteIfExists(d); return FileVisitResult.CONTINUE }
                })
            } catch (_: NoSuchFileException) {
                // already gone
            } catch (_: IOException) {
                // left for the next sweep
            }
        }
    }
}
