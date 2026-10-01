package io.github.tuthan.paddock.ops

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** Where the journal persists. A file store ships; Room stays deferred (user decision 2026-10-01). */
interface JournalStore {
    fun load(): JournalData
    /** Durable on return, or an [IOException]. The journal relies on it for its write-ahead rule. */
    fun save(data: JournalData)
}

class InMemoryJournalStore(private var data: JournalData = JournalData()) : JournalStore {
    /** Set to make [save] fail, for the tests of the write-ahead rule. */
    @Volatile var failSaves = false
    override fun load(): JournalData = data
    override fun save(data: JournalData) { if (failSaves) throw IOException("disk full"); this.data = data }
}

/**
 * One JSON file, written to a sibling temp file, synced, and moved over the target, so a crash leaves the old file or
 * the new one. An unreadable file is moved aside to `<name>.corrupt` (one kept, for a person to look at) and the
 * journal starts empty: nothing is lost silently, and no row in it could have been replayed anyway.
 */
class FileJournalStore(private val file: File) : JournalStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun load(): JournalData {
        if (!file.exists()) return JournalData()
        return try {
            json.decodeFromString(JournalData.serializer(), file.readText())
        } catch (_: RuntimeException) {
            runCatching { Files.move(file.toPath(), File(file.absolutePath + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            JournalData()
        } catch (_: IOException) {
            JournalData()
        }
    }

    override fun save(data: JournalData) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(JournalData.serializer(), data).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
