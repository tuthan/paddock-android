package io.github.tuthan.paddock.ledger

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** Where the ledger persists. A file store ships; Room is deferred (see the Phase 04 evidence report). */
interface LedgerStore {
    fun load(): LedgerData
    fun save(data: LedgerData)
}

class InMemoryLedgerStore(private var data: LedgerData = LedgerData()) : LedgerStore {
    override fun load(): LedgerData = data
    override fun save(data: LedgerData) { this.data = data }
}

/**
 * One JSON file, written to a sibling temp file and moved over the target so a crash leaves the old file or the new
 * one, never half of either. An unreadable file loads as empty: the ledger is a convenience record, not authority.
 */
class FileLedgerStore(private val file: File) : LedgerStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): LedgerData {
        if (!file.exists()) return LedgerData()
        return try {
            json.decodeFromString(LedgerData.serializer(), file.readText())
        } catch (_: IllegalArgumentException) { // SerializationException is one
            LedgerData()
        } catch (_: IOException) {
            LedgerData()
        }
    }

    override fun save(data: LedgerData) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(LedgerData.serializer(), data))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
