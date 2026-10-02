package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.storage.DurableFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * The saved journal exists but cannot be read or understood. It is not "empty": the rows in it are the phone's only memory of
 * which sends may have reached a host, so the journal refuses new sends until a person restores it or resets it.
 */
class JournalUnreadable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Where the journal persists. A file store ships; Room stays deferred (user decision 2026-10-01). */
interface JournalStore {
    /** The saved journal, empty when nothing was ever saved. Throws [JournalUnreadable] when something is saved and cannot be read. */
    fun load(): JournalData
    /** Durable on return, or an [IOException]. The journal relies on it for its write-ahead rule. */
    fun save(data: JournalData)
    /** The user's explicit reset: keeps the unreadable saved journal aside (one copy) and forgets it, so [load] is empty again. */
    fun discardUnreadable()
}

class InMemoryJournalStore(private var data: JournalData = JournalData()) : JournalStore {
    /** Set to make [save] fail, for the tests of the write-ahead rule. */
    @Volatile var failSaves = false
    /** Set to make [load] report an unreadable journal until [discardUnreadable] runs. */
    @Volatile var unreadable = false
    @Volatile var discards = 0; private set
    override fun load(): JournalData { if (unreadable) throw JournalUnreadable("the saved journal is unreadable"); return data }
    override fun save(data: JournalData) { if (failSaves) throw IOException("disk full"); this.data = data }
    override fun discardUnreadable() { discards++; unreadable = false; data = JournalData() }
}

/**
 * One JSON file, written to a sibling temp file, synced, moved over the target and its directory synced ([DurableFile]), so
 * a crash or power loss leaves the old file or the new one and "saved" means saved. A file that cannot be read stays exactly
 * where it is and [load] says so ([JournalUnreadable]): nothing overwrites it, and only [discardUnreadable], the user's
 * explicit reset, moves it aside to `<name>.corrupt` (one kept, for a person to look at).
 */
class FileJournalStore(private val file: File) : JournalStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun load(): JournalData {
        if (!file.exists()) return JournalData()
        val text = try { file.readText() } catch (e: IOException) { throw JournalUnreadable("the saved journal cannot be read: ${e.message}", e) }
        return try {
            json.decodeFromString(JournalData.serializer(), text)
        } catch (e: RuntimeException) {
            throw JournalUnreadable("the saved journal is not valid", e)
        }
    }

    override fun save(data: JournalData) {
        DurableFile.replace(file, json.encodeToString(JournalData.serializer(), data).toByteArray(Charsets.UTF_8))
    }

    override fun discardUnreadable() {
        if (!file.exists()) return
        Files.move(file.toPath(), File(file.absolutePath + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
        DurableFile.syncDirectory(file.absoluteFile.parentFile)
    }
}
