package io.github.tuthan.paddock.pairing

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A pairing request in flight, kept on disk before the first byte is sent so a process death reopens the same request within its
 * window instead of losing it. [generation] rises on every start and every cancel: a reply for an older generation is discarded.
 * It holds the session handle from the QR (no credential) and the phone's public key line (public by definition).
 */
@Serializable
data class PendingPairing(
    val sid: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val keyLine: String,
    val startedAtMillis: Long,
    val deadlineMillis: Long,
    val generation: Long,
)

interface PendingPairingStore {
    suspend fun load(): PendingPairing?

    /** Null clears the record. */
    suspend fun save(pending: PendingPairing?)
}

class MemoryPendingPairingStore(var saved: PendingPairing? = null) : PendingPairingStore {
    override suspend fun load() = saved
    override suspend fun save(pending: PendingPairing?) { saved = pending }
}

class FilePendingPairingStore(private val file: File) : PendingPairingStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private class FileDto(val version: Int = 1, val pending: PendingPairing? = null)

    override suspend fun load(): PendingPairing? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        // A file that cannot be read is no request: the user sends again, and the desktop answers `busy` or the same state for the same key.
        try { json.decodeFromString(FileDto.serializer(), file.readText()).pending } catch (_: IllegalArgumentException) { null }
    }

    override suspend fun save(pending: PendingPairing?) { withContext(Dispatchers.IO) {
        if (pending == null) { file.delete(); return@withContext }
        file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(FileDto.serializer(), FileDto(pending = pending)))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } }
}
