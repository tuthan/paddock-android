package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ssh.SshTarget
import io.github.tuthan.paddock.wake.WakeTarget
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
    /** What the phone knows about waking this machine (Phase 14), or null before it has been read. Old files without it load. */
    val wake: WakeTarget? = null,
    /** The icon the user picked for this machine (Linux, macOS, Windows), or null to follow [detectedOs]. Old files without it load. */
    val os: HostOs? = null,
    /** What the machine said it is (`uname -s`) on the last bring-up that could ask, or null before that. Never the user's choice, so a pick is never overwritten. */
    val detectedOs: HostOs? = null,
    /**
     * The machine's own hostname, one DNS label (`uname -n` up to its first dot, [MachineHostName.label]), from the last bring-up that could ask, or
     * null before that. Only used to look for the machine when its saved address stops answering ([io.github.tuthan.paddock.discovery.MachineRelocator]).
     * Old files without it load.
     */
    val detectedName: String? = null,
    /**
     * The user's switch for searching the machine's network when its LAN address stops answering: every other SSH service announced there, then every
     * address of the subnet on the saved port ([io.github.tuthan.paddock.discovery.MachineRelocator]). Off unless the user turned it on, because those steps
     * reach other machines. A setting, not a reading: changing it never reconnects. Old files without it load as off.
     */
    val searchNetwork: Boolean = false,
    /**
     * Where a file shared to Paddock with no agent goes on this machine (Phase 15, X10): a folder under the home folder, written as `~/Paddock` or as an absolute
     * path, or null for the default ([DEFAULT_DROP_FOLDER]). What it names is resolved and judged on the machine before it is saved and again on every drop
     * ([io.github.tuthan.paddock.files.TransferScripts]), so this only holds what the user wrote. A setting, not a reading: changing it never reconnects.
     * Old files without it load as the default.
     */
    val dropFolder: String? = null,
) {
    init {
        require(ID.matches(id)) { "invalid profile id" }
        require(name.isNotBlank() && name.length <= 60) { "invalid profile name" }
        require(host.length in 1..253 && HOST.matches(host)) { "invalid host" }
        require(port in 1..65535) { "invalid port" }
        require(USER.matches(user)) { "invalid user" }
        require(session == null || SESSION.matches(session)) { "invalid session name" }
        require(detectedName == null || MachineHostName.LABEL.matches(detectedName)) { "invalid hostname" }
        require(dropFolder == null || validDropFolder(dropFolder)) { "invalid drop folder" }
        when (key) {
            KeyKind.Phone -> require(importedKeyId == null) { "a phone-key profile has no imported key" }
            KeyKind.Imported -> require(importedKeyId != null && ID.matches(importedKeyId)) { "an imported-key profile needs a key id" }
        }
    }

    val hostId: HostProfileId get() = HostProfileId(id)

    /** The OS whose glyph the machine's card draws: the user's pick, else what the machine said, else none (no glyph is better than a wrong one). */
    val shownOs: HostOs? get() = os ?: detectedOs

    /** The drop folder's value in use: the user's, else the default. */
    val dropFolderValue: String get() = dropFolder ?: DEFAULT_DROP_FOLDER

    fun toTarget() = SshTarget(id, host, port, user)

    /**
     * Whether the name is one Paddock gave the machine rather than the user: the address it was added by, or the hostname the machine said. Only such a
     * name follows the machine ([MachineBook.saveDetectedName], [MachineBook.setAddress]). A name the user typed is kept, whatever the machine is called
     * (typing the hostname or the address as the name is the same as having none).
     */
    val nameIsDefault: Boolean get() = name.equals(host.take(MachineName.MAX), ignoreCase = true) ||
        (detectedName != null && name.equals(detectedName.take(MachineName.MAX), ignoreCase = true))

    /**
     * Whether a connection made for this profile is still the one [other] needs. What was read about waking the machine, the icon (read or
     * picked), the hostname read from it and the search switch change without the user touching the connection and are no reason to reconnect;
     * neither is a name Paddock chose ([nameIsDefault]: it follows what the machine says, and only labels the answer host). Anything else, a name the
     * user typed included (the label the answer host writes), is.
     */
    fun sameConnectionAs(other: HostProfile): Boolean = readingsDropped() == other.readingsDropped()

    private fun readingsDropped() = copy(
        wake = null, os = null, detectedOs = null, detectedName = null, searchNetwork = false, dropFolder = null,
        name = if (nameIsDefault) host.take(MachineName.MAX) else name,
    )

    companion object {
        /** Where a drop goes until the user says otherwise: `~` is the machine's home for the account Paddock signs in as. */
        const val DEFAULT_DROP_FOLDER = "~/Paddock"

        /** The longest drop folder written, a path of a machine and nothing a person types by accident. */
        const val MAX_DROP_FOLDER = 200

        /**
         * What a drop folder may be written as before the machine is asked: absolute, or `~` and `~/…`, with no control character and no more than
         * [MAX_DROP_FOLDER] characters. The machine decides the rest (not home itself, not hidden, not a link, inside home).
         */
        fun validDropFolder(value: String): Boolean =
            value.length in 1..MAX_DROP_FOLDER && value.none(Char::isISOControl) && (value.startsWith("/") || value == "~" || value.startsWith("~/"))

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

    /**
     * Reads profile [id], applies [transform] and writes the result, all in one critical section, so a change made meanwhile by
     * another caller is never overwritten with a stale copy (a get followed by a put is two). [transform] must be quick and must not
     * call the store. Returns the profile as written, or null when nothing was written: there is no such profile, [transform]
     * returned null, or it returned an equal profile (no rewrite of the file).
     */
    suspend fun update(id: String, transform: (HostProfile) -> HostProfile?): HostProfile?

    /** Removes only the profile. The caller removes the pin, the imported key and the ledger rows that hang off it. */
    suspend fun remove(id: String)
}

class InMemoryHostProfileStore : HostProfileStore {
    private val profiles = LinkedHashMap<String, HostProfile>()
    override suspend fun list() = synchronized(profiles) { profiles.values.sortedBy { it.name.lowercase() } }
    override suspend fun get(id: String) = synchronized(profiles) { profiles[id] }
    override suspend fun put(profile: HostProfile) { synchronized(profiles) { profiles[profile.id] = profile } }
    override suspend fun update(id: String, transform: (HostProfile) -> HostProfile?): HostProfile? = synchronized(profiles) {
        val current = profiles[id] ?: return@synchronized null
        transform(current)?.takeIf { it != current }?.also { require(it.id == id) { "update cannot rename a profile" }; profiles[id] = it }
    }
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

    override suspend fun update(id: String, transform: (HostProfile) -> HostProfile?): HostProfile? = lock.withLock {
        val all = load().toMutableMap()
        val current = all[id] ?: return@withLock null
        val next = transform(current)?.takeIf { it != current } ?: return@withLock null
        require(next.id == id) { "update cannot rename a profile" }
        all[id] = next
        store(all)
        next
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
