package io.github.tuthan.paddock.alerts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone did the last time it turned alerts on for one machine: how they arrive, and for the ntfy-app mode the server, the topic and any token, so
 * the subscribe link can be shown again and a second run keeps the same topic (a new one would orphan the ntfy app's subscription). The same things are in the
 * machine's own configuration file; this is the phone's copy of them. App-private storage, like the push registration.
 */
@Serializable
data class SavedAlertSetup(
    val mode: DeliveryMode,
    val ntfyUrl: String = NtfyServer.PUBLIC,
    val topic: String = "",
    val token: String = "",
    val atMillis: Long = 0,
    /** False after "Turn off": the choices (and the topic) are kept so turning alerts on again changes nothing the ntfy app subscribed to. */
    val enabled: Boolean = true,
)

interface AlertSetupStore {
    suspend fun get(profileId: String): SavedAlertSetup?
    suspend fun put(profileId: String, setup: SavedAlertSetup)
    suspend fun remove(profileId: String)
}

class InMemoryAlertSetupStore : AlertSetupStore {
    private val map = LinkedHashMap<String, SavedAlertSetup>()
    override suspend fun get(profileId: String) = synchronized(map) { map[profileId] }
    override suspend fun put(profileId: String, setup: SavedAlertSetup) { synchronized(map) { map[profileId] = setup } }
    override suspend fun remove(profileId: String) { synchronized(map) { map.remove(profileId) } }
}

/** One JSON file, rewritten whole through a temp file and an atomic move. A file that cannot be read is "not set up": the screen then offers the setup again. */
class FileAlertSetupStore(private val file: File) : AlertSetupStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    private class FileDto(val version: Int = 1, val setups: Map<String, SavedAlertSetup> = emptyMap())

    override suspend fun get(profileId: String): SavedAlertSetup? = lock.withLock { load()[profileId] }

    override suspend fun put(profileId: String, setup: SavedAlertSetup) { lock.withLock { store(load() + (profileId to setup)) } }

    override suspend fun remove(profileId: String) { lock.withLock { val all = load(); if (profileId in all) store(all - profileId) } }

    private suspend fun load(): Map<String, SavedAlertSetup> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        try { json.decodeFromString(FileDto.serializer(), file.readText()).setups } catch (_: IllegalArgumentException) { emptyMap() }
    }

    private suspend fun store(setups: Map<String, SavedAlertSetup>) { withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(FileDto.serializer(), FileDto(setups = setups.toSortedMap())))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } }
}
