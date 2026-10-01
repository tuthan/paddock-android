package io.github.tuthan.paddock.hostkey

import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The pin file exists but cannot be read. Never treated as "no pins": that would silently reset trust. */
class HostKeyStoreCorrupt(cause: Throwable?) : IOException("host key store is unreadable", cause)

/**
 * Pins in one JSON file, rewritten whole through a temp file and rename. A handful of entries per phone, so a
 * database buys nothing yet; the [HostKeyStore] port keeps a later move to Room local. Keep [file] in app-private,
 * backup-excluded storage.
 */
class FileHostKeyStore(private val file: File) : HostKeyStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private class Dto(
        val profileId: String, val endpoint: String, val algorithm: String, val blob: String,
        val fingerprint: String, val firstSeenMillis: Long, val lastSeenMillis: Long,
    )

    @Serializable
    private class FileDto(val version: Int = 1, val pins: List<Dto> = emptyList())

    override suspend fun find(profileId: String): PinnedHostKey? = lock.withLock { load()[profileId] }

    override suspend fun save(pin: PinnedHostKey) = lock.withLock {
        val all = load().toMutableMap(); all[pin.profileId] = pin; store(all)
    }

    override suspend fun touch(profileId: String, nowMillis: Long) = lock.withLock {
        val all = load().toMutableMap()
        val pin = all[profileId] ?: return@withLock
        all[profileId] = pin.copy(lastSeenMillis = nowMillis); store(all)
    }

    suspend fun remove(profileId: String) = lock.withLock {
        val all = load().toMutableMap(); if (all.remove(profileId) != null) store(all)
    }

    private suspend fun load(): Map<String, PinnedHostKey> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        try {
            json.decodeFromString<FileDto>(file.readText()).pins.associate {
                it.profileId to PinnedHostKey(
                    it.profileId, it.endpoint, it.algorithm, Base64.getDecoder().decode(it.blob),
                    it.fingerprint, it.firstSeenMillis, it.lastSeenMillis,
                )
            }
        } catch (e: SerializationException) {
            throw HostKeyStoreCorrupt(e)
        } catch (e: IllegalArgumentException) {
            throw HostKeyStoreCorrupt(e)
        }
    }

    private suspend fun store(pins: Map<String, PinnedHostKey>) = withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        val text = json.encodeToString(
            FileDto(pins = pins.values.sortedBy { it.profileId }.map {
                Dto(it.profileId, it.endpoint, it.algorithm, Base64.getEncoder().encodeToString(it.blob), it.fingerprint, it.firstSeenMillis, it.lastSeenMillis)
            }),
        )
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("could not write ${file.name}") }
    }
}
