package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ssh.SshTarget
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class KeyKind { Phone, Imported }

/**
 * One machine the phone can reach. [id] is the key every phone-side fact hangs off (pin, ledger rows, imported key),
 * so it is validated to the strictest of those uses. Invalid values never construct, which also means a hand-edited
 * file with a bad entry fails to load instead of half-loading.
 */
@Serializable
data class HostProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val key: KeyKind = KeyKind.Phone,
    val importedKeyId: String? = null,
    /** The herdr session to watch on this machine, or null for the running default. Names come from `herdr session list`. */
    val session: String? = null,
) {
    init {
        require(ID.matches(id)) { "invalid profile id" }
        require(name.isNotBlank() && name.length <= 60) { "invalid profile name" }
        require(host.length in 1..253 && HOST.matches(host)) { "invalid host" }
        require(port in 1..65535) { "invalid port" }
        require(USER.matches(user)) { "invalid user" }
        require(session == null || SESSION.matches(session)) { "invalid session name" }
        when (key) {
            KeyKind.Phone -> require(importedKeyId == null) { "a phone-key profile has no imported key" }
            KeyKind.Imported -> require(importedKeyId != null && ID.matches(importedKeyId)) { "an imported-key profile needs a key id" }
        }
    }

    val hostId: HostProfileId get() = HostProfileId(id)

    fun toTarget() = SshTarget(id, host, port, user)

    companion object {
        /** Also the imported-key id rule, so a profile id can name its key. */
        val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
        private val HOST = Regex("[A-Za-z0-9._:\\[][A-Za-z0-9._:%\\[\\]-]*")
        /** The CLI's rule, so a profile can never hold a name the herdr calls would refuse. */
        val SESSION = io.github.tuthan.paddock.cli.HerdrCli.SESSION_NAME
        private val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}")
    }
}

/** The profiles file exists but cannot be read. Never treated as "no profiles": that would silently drop machines. */
class HostProfilesCorrupt(cause: Throwable?) : IOException("host profiles are unreadable", cause)

interface HostProfileStore {
    suspend fun list(): List<HostProfile>
    suspend fun get(id: String): HostProfile?

    /** Adds or replaces by id. */
    suspend fun put(profile: HostProfile)

    /** Removes only the profile. The caller removes the pin, the imported key and the ledger rows that hang off it. */
    suspend fun remove(id: String)
}

class InMemoryHostProfileStore : HostProfileStore {
    private val profiles = LinkedHashMap<String, HostProfile>()
    override suspend fun list() = synchronized(profiles) { profiles.values.sortedBy { it.name.lowercase() } }
    override suspend fun get(id: String) = synchronized(profiles) { profiles[id] }
    override suspend fun put(profile: HostProfile) { synchronized(profiles) { profiles[profile.id] = profile } }
    override suspend fun remove(id: String) { synchronized(profiles) { profiles.remove(id) } }
}

/** One JSON file in app-private storage, rewritten whole through a temp file and an atomic move. A handful of entries. */
class FileHostProfileStore(private val file: File) : HostProfileStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    private class FileDto(val version: Int = 1, val profiles: List<HostProfile> = emptyList())

    override suspend fun list(): List<HostProfile> = lock.withLock { load().values.sortedBy { it.name.lowercase() } }
    override suspend fun get(id: String): HostProfile? = lock.withLock { load()[id] }

    override suspend fun put(profile: HostProfile) {
        lock.withLock { val all = load().toMutableMap(); all[profile.id] = profile; store(all) }
    }

    override suspend fun remove(id: String) {
        lock.withLock { val all = load().toMutableMap(); if (all.remove(id) != null) store(all) }
    }

    private suspend fun load(): Map<String, HostProfile> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        try {
            json.decodeFromString(FileDto.serializer(), file.readText()).profiles.associateBy { it.id }
        } catch (e: IllegalArgumentException) { // SerializationException is one, and so is a failed profile check
            throw HostProfilesCorrupt(e)
        }
    }

    private suspend fun store(profiles: Map<String, HostProfile>) { withContext(Dispatchers.IO) {
        file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(FileDto.serializer(), FileDto(profiles = profiles.values.sortedBy { it.id })))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } }
}
