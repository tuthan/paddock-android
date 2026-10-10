package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.wake.WakeReading
import io.github.tuthan.paddock.wake.WakeRelay
import io.github.tuthan.paddock.wake.WakeTarget
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the machine list stands at start-up. */
enum class Boot { Loading, NoMachines, Ready }

/**
 * What one Remove… from a machine's page came to ([MachineBook.requestRemoval]): the machine's [id] and the [name] the page showed, and the [result].
 * Not a data class: two removals are two events even when they read alike, so consuming one never clears the other.
 */
class RemovalOutcome(val id: String, val name: String, val result: MachineRemoval.Result)

/**
 * The machines saved on this phone and the one of them that is watched: every change to the list (add, switch, remove, rename, the readings a bring-up
 * brings) runs here, one at a time, and tells the rest of the app through [Effects]. [watch] is the machine the phone watches, [chosen] (in
 * [SettingsController]) the one the user picked; see [MachineRoster] for the rules between them.
 */
class MachineBook(
    private val profiles: HostProfileStore,
    private val prefs: SettingsController,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** What the phone forgets about a machine before its profile goes. */
    private val removal: MachineRemoval,
    private val effects: Effects,
) {
    /** What the book needs from the rest of the app. */
    interface Effects {
        /** The app is in front and uncovered: a machine may be connected to now. */
        val inForeground: Boolean
        /** Watches [profile] (resumes its controller, or replaces it). */
        fun watch(profile: HostProfile)
        /** The watched machine's connection and everything that follows it, stopped. */
        fun stopWatching()
        /** A machine's connection (and the grace after it) is closed now. */
        suspend fun dropConnection(id: String)
        /** [id] is the watched machine now: what was kept about the watched one before, and this machine's "not watching" facts, go. */
        fun becameWatched(id: String)
        /** [id] was removed: what the app kept about it, other than what [removal] forgets, goes. */
        fun forgotten(id: String)
        /** The watched machine was removed: what the screens show about it goes. */
        fun watchedRemoved()
        /** [id] moved to another address: what was seen at the old one (a changed-key warning, a chip's last look) goes. */
        fun addressChanged(id: String)
    }

    private val _boot = MutableStateFlow(Boot.Loading)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _profile = MutableStateFlow<HostProfile?>(null)
    /** The machine watched now; null before the list is read and with no machines. */
    val profile: StateFlow<HostProfile?> = _profile.asStateFlow()

    private val _machines = MutableStateFlow<List<HostProfile>>(emptyList())
    /** Every machine saved on this phone, by name: the Machines screen, each machine's page and Home's chips. Only one is watched ([profile]). */
    val machines: StateFlow<List<HostProfile>> = _machines.asStateFlow()

    /** One change to the saved machines at a time: add, switch and remove each read and write the profile store and the watched machine. */
    private val machineLock = Mutex()
    /** Machines being removed: nothing may start watching them (a foreground flip watches [profile], and the removed machine is still that until the end). */
    private val removing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Whether [id] is being removed now. */
    fun isRemoving(id: String): Boolean = id in removing

    private val _removingMachine = MutableStateFlow<String?>(null)
    /**
     * The machine a removal is running for, or null. Held here, not in the screen, because the removal runs in [scope] and outlives a rotation: the
     * Machines screen and a machine's page keep Watch and Remove… waiting for as long as it runs, whichever composition draws them.
     */
    val removingMachine: StateFlow<String?> = _removingMachine.asStateFlow()

    private val _removalOutcome = MutableStateFlow<RemovalOutcome?>(null)
    /**
     * What the last removal asked for from a machine's page ([requestRemoval]) came to, until the root has shown it ([consumeRemoval]). State, not a
     * callback: a rotation or a trip away while the removal runs would leave a callback in a composition that is gone, and "Removed X." would be lost.
     */
    val removalOutcome: StateFlow<RemovalOutcome?> = _removalOutcome.asStateFlow()

    /** One write to [machines] at a time: a re-read of the store must not land after, and so undo, an in-place write made meanwhile ([machineWritten]). */
    private val machinesWrite = Mutex()

    private suspend fun refreshMachines() { machinesWrite.withLock { runCatching { profiles.list() }.onSuccess { _machines.value = it } } }

    /**
     * Reads the saved machines at start-up (after the settings were loaded): the machine watched last time, not whichever sorts first, and the machine
     * the user chose. Settings written before there was a chosen machine, or naming one removed meanwhile: the watched machine is the choice. Saved once,
     * and never from a list that could not be read ([MachineRoster.startupChoice]).
     */
    suspend fun load() {
        val read = runCatching { profiles.list() }
        val all = read.getOrDefault(emptyList())
        _machines.value = all
        val watched = all.firstOrNull { it.id == prefs.value.watchedProfileId } ?: all.firstOrNull()
        _profile.value = watched
        MachineRoster.startupChoice(read.isSuccess, all, prefs.value, watched?.id)?.let { migrated ->
            prefs.update { it.copy(chosenProfileId = migrated.chosenProfileId) }
            prefs.saveQuietly()
        }
        _boot.value = if (watched == null) Boot.NoMachines else Boot.Ready
    }

    /**
     * Saves [profile], makes it the watched machine and connects. [chosen]: the user picked it, so it is also the chosen one ([AppSettings.chosenProfileId]).
     * Setting up the watched machine's key passes false ([MachineRoster.choosesOnAdd]): after an alert moved the phone to that machine, fixing its key
     * must not take away the free return to the machine the user chose.
     */
    suspend fun add(profile: HostProfile, chosen: Boolean = true) = machineLock.withLock {
        profiles.put(profile)
        refreshMachines()
        prefs.update { it.copy(watchedProfileId = profile.id, chosenProfileId = if (chosen) profile.id else it.chosenProfileId) }
        prefs.save()
        _profile.value = profile
        _boot.value = Boot.Ready
        if (effects.inForeground) effects.watch(profile)
    }

    /**
     * Removes a machine from this phone (see [MachineRemoval]). Removing the watched one stops watching it first, so nothing it writes comes after the
     * forgetting, then watches the next saved machine ([MachineRoster.after]: the chosen machine when it is another one, else the first by name), or none;
     * the user confirmed "Paddock then watches <next>", so it is also the chosen one. Removing the chosen machine while another is watched makes the watched one the choice, as start-up would. When something could not be forgotten
     * the machine stays listed (and watched again) for another try. Free: the user must always be able to forget a machine.
     */
    suspend fun remove(id: String): MachineRemoval.Result = try {
        // Before the lock, so Watch and Remove… wait from the moment it is asked for, also while another change to the machines holds the lock.
        _removingMachine.value = id
        removeLocked(id)
    } finally {
        // Only its own mark: a second removal asked for meanwhile keeps the screens waiting until it ends.
        _removingMachine.compareAndSet(id, null)
    }

    /**
     * Remove… on a machine's page, run in [scope] so leaving the page or turning the phone does not stop it. What it came to is kept in
     * [removalOutcome] until the root has said so.
     */
    fun requestRemoval(id: String, name: String) {
        // Marked at the tap, before the coroutine runs, so the next frame already has Watch and Remove… waiting; remove clears it.
        _removingMachine.value = id
        scope.launch { _removalOutcome.value = RemovalOutcome(id, name, remove(id)) }
    }

    /** The root showed [outcome]: it goes, unless a newer removal has replaced it meanwhile. */
    fun consumeRemoval(outcome: RemovalOutcome) { _removalOutcome.compareAndSet(outcome, null) }

    private suspend fun removeLocked(id: String): MachineRemoval.Result = machineLock.withLock {
        if (profiles.get(id) == null) return@withLock MachineRemoval.Result.Removed
        val wasWatched = _profile.value?.id == id
        val before = _profile.value
        // From here nothing may start watching it, and its connection (and the grace after it) is closed now: a reconnect would raise a first-trust prompt for a key just forgotten.
        removing += id
        try {
            if (wasWatched) effects.stopWatching()
            effects.dropConnection(id)
            val result = removal.remove(id)
            refreshMachines()
            if (result != MachineRemoval.Result.Removed) {
                removing -= id
                if (wasWatched && before != null && effects.inForeground) effects.watch(before)
                return@withLock result
            }
            // Its Wake facts and their flow go with it: a later machine with the same id starts with none.
            effects.forgotten(id)
            if (wasWatched) {
                val next = MachineRoster.after(_machines.value, id, prefs.value.chosenProfileId)
                prefs.update { it.copy(watchedProfileId = next?.id, chosenProfileId = next?.id) }
                prefs.saveQuietly()
                _profile.value = next
                effects.watchedRemoved()
                if (next == null) _boot.value = Boot.NoMachines else if (effects.inForeground) effects.watch(next)
            } else {
                val chosen = MachineRoster.chosen(_machines.value, prefs.value.chosenProfileId, _profile.value?.id)
                if (chosen != prefs.value.chosenProfileId) {
                    prefs.update { it.copy(chosenProfileId = chosen) }
                    prefs.saveQuietly()
                }
            }
            result
        } finally {
            removing -= id
        }
    }

    /**
     * Makes [id] the watched machine. Null when this phone has no such machine. [chosen]: the user picked it themselves (the machine list's Watch), so it
     * also becomes the chosen machine ([AppSettings.chosenProfileId]). An alert calls this with the default: the machine an alert moves the phone to is
     * never the one a phone without Pro may always return to.
     */
    suspend fun watch(id: String, chosen: Boolean = false): HostProfile? = machineLock.withLock {
        val profile = profiles.get(id) ?: return@withLock null
        val switching = _profile.value?.id != profile.id
        if (switching || (chosen && prefs.value.chosenProfileId != profile.id)) {
            prefs.update { it.copy(watchedProfileId = profile.id, chosenProfileId = if (chosen) profile.id else it.chosenProfileId) }
            prefs.saveQuietly()
        }
        if (switching) {
            _profile.value = profile
            // At the switch, not only when its connection starts (watch runs only in the foreground): from now on this machine's facts are the watched
            // tap's, and its send-only "not watching this machine" line is no longer true. The watcher says it again for a controller it replaces; twice is harmless.
            effects.becameWatched(profile.id)
            if (effects.inForeground) effects.watch(profile)
        }
        profile
    }

    /**
     * Keeps what a bring-up read about waking the machine, with the relay the user saved. Skipped when the stored profile is no longer
     * the machine that was read (the user edited its address meanwhile). What to keep is [WakeTarget.afterReading]: a run that failed
     * never replaces a hardware address already read, and a reading that says what is stored is not a write. One critical section in
     * the store, so a profile edit or a relay save made meanwhile is not overwritten with a stale copy.
     */
    suspend fun saveWake(read: HostProfile, reading: WakeReading) {
        val written = profiles.update(read.id) { stored ->
            if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port) null
            else WakeTarget.afterReading(stored.wake, reading)?.let { stored.copy(wake = it) }
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Keeps what a bring-up learned about the machine's OS as [HostProfile.detectedOs]. Skipped when the stored profile is no longer the machine that
     * asked (its address was edited meanwhile), and a reading that says what is stored is not a write. The user's own pick ([HostProfile.os]) is never touched.
     */
    suspend fun saveDetectedOs(read: HostProfile, os: HostOs) {
        val written = profiles.update(read.id) { stored ->
            if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port || stored.detectedOs == os) null else stored.copy(detectedOs = os)
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Keeps the hostname a bring-up read ([HostProfile.detectedName]), under the same rules as [saveDetectedOs]: skipped when the stored profile is no
     * longer at the address that asked, and an equal reading is not a write. Never reconnects.
     */
    suspend fun saveDetectedName(read: HostProfile, name: String) {
        val written = profiles.update(read.id) { stored ->
            if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port || stored.detectedName == name) null else stored.copy(detectedName = name)
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Renames [profileId] on this phone (the id, the pin and every fact that hangs off it stay). [MachineName] has already judged [name]; the store's own
     * check is the last word. False when there is no such machine. The watched machine reconnects once: its name is part of what its connection was
     * started with (the label the answer host writes), so a rename is the one edit here that is not free.
     */
    suspend fun rename(profileId: String, name: String): Boolean {
        val written = profiles.update(profileId) { stored -> stored.copy(name = name) }
        if (written == null) return profiles.get(profileId)?.name == name
        if (_profile.value?.id == written.id) { _profile.value = written; effects.watch(written) }
        machineWritten(written)
        return true
    }

    /**
     * Moves [profileId] to another address ([MachineAddress] has judged it): its IP address changed, or it is reached by a name now. The id stays, so
     * the trusted host key, the ledger, the alert registration and the wake facts stay attached, and the key is still checked at the new address: a
     * device answering there with another key stops at the changed-key warning and is not trusted unless the user chooses to. What was seen at the old address goes
     * ([Effects.addressChanged]), and a connection to it is closed at once; the watched machine is watched again from the start, so its next
     * connection goes to the new address (as a removal does, the watch stops before the connection is dropped). Serialized with add, switch and
     * remove. False when there is no such machine. [onlyFrom]: move it only while it is still at that profile's address (Paddock found it elsewhere
     * by itself, and the user may have changed the address meanwhile); false when it is not.
     */
    suspend fun setAddress(profileId: String, host: String, port: Int, onlyFrom: HostProfile? = null): Boolean = machineLock.withLock {
        val written = profiles.update(profileId) { stored ->
            if (onlyFrom != null && (!stored.host.equals(onlyFrom.host, ignoreCase = true) || stored.port != onlyFrom.port)) null else stored.copy(host = host, port = port)
        }
            ?: return@withLock profiles.get(profileId)?.let { it.host.equals(host, ignoreCase = true) && it.port == port } == true
        machineWritten(written)
        effects.addressChanged(profileId)
        val watched = _profile.value?.id == profileId
        if (watched) { _profile.value = written; effects.stopWatching() }
        effects.dropConnection(profileId)
        if (watched && effects.inForeground) effects.watch(written)
        true
    }

    /**
     * Runs [block] only while [profileId] is still saved at [endpoint] (`host:port`), with moves of the machine held off ([machineLock]), so a decision
     * about the machine at that address cannot land after the machine moved (a key approved for whatever answered at its old address). Null, without
     * running [block], when the machine is gone or saved elsewhere now. [block] must not call the book.
     */
    suspend fun <T> whileAt(profileId: String, endpoint: String, block: suspend () -> T): T? = machineLock.withLock {
        val stored = profiles.get(profileId) ?: return@withLock null
        if (stored.toTarget().endpoint != endpoint) null else block()
    }

    /** The user's switch for searching the machine's network when its address stops answering ([HostProfile.searchNetwork]). Never reconnects. */
    suspend fun setSearchNetwork(profileId: String, on: Boolean) {
        val written = profiles.update(profileId) { stored -> stored.copy(searchNetwork = on) } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /** The user's own icon for [profileId]: an OS, or null to follow what the machine says. Never reconnects. */
    suspend fun setOs(profileId: String, os: HostOs?) {
        val written = profiles.update(profileId) { stored -> stored.copy(os = os) } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Saves, replaces or clears the relay for waking [profileId] from away. Before any reading the profile gets a "not read yet" target
     * that carries only the relay; the next bring-up fills in the rest and keeps it.
     */
    suspend fun setWakeRelay(profileId: String, relay: WakeRelay?) {
        val written = profiles.update(profileId) { stored ->
            val base = stored.wake ?: WakeTarget.unavailable("Not read yet: connect once so Paddock can read the machine's network interface.", clock.nowMillis())
            stored.copy(wake = base.copy(relay = relay))
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * The saved list follows a write to one machine (its wake reading or relay), so the Machines screen and the machine page show it without a
     * re-read of the store. Only a machine still listed is replaced: a removal that finished meanwhile is not undone. Behind [machinesWrite], so a
     * re-read that started before this write reached the store ([refreshMachines]) cannot land after it with the old copy.
     */
    private suspend fun machineWritten(written: HostProfile) { machinesWrite.withLock { _machines.update { all -> all.map { if (it.id == written.id) written else it } } } }
}
