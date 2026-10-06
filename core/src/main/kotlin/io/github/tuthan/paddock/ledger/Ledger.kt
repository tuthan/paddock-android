package io.github.tuthan.paddock.ledger

import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TerminalKey
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's own record: what it observed and what the user did here. Entries come from observations and phone
 * actions only, never from herdr history. Marking a Done seen is local; it never calls herdr (`agent focus` included).
 *
 * Every call updates memory at once and returns; the file is written on one background thread, newest state wins, so
 * a tap on the main thread never waits for the disk. [flush] waits for the write (tests, and before the process ends).
 */
class Ledger(private val store: LedgerStore, private val now: () -> Long) {
    private val lock = Any()
    private var data: LedgerData = pruned(store.load(), now())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "paddock-ledger").apply { isDaemon = true } }
    private var writeQueued = false

    /** True when the last write to the store failed. The in-memory ledger stays correct; the next write retries. */
    @Volatile var saveFailed: Boolean = false; private set

    fun observe(kind: ObservationKind, key: TerminalKey, detail: String = "") = synchronized(lock) {
        append(kind, key.target.host, key.target.session, key.epoch, key.target.terminalId, detail)
    }

    fun observeHost(kind: ObservationKind, host: HostProfileId, session: String, epoch: Long, detail: String = "") = synchronized(lock) {
        append(kind, host, session, epoch, null, detail)
    }

    /** A Done tap: Done becomes Ready locally through [seenLookup]. Keeps the highest seq seen for that terminal and epoch. */
    fun markSeen(key: TerminalKey, stateChangeSeq: Long) = synchronized(lock) {
        val t = now()
        val h = key.target.host.value
        val s = key.target.session
        val id = key.target.terminalId
        val e = key.epoch
        val existing = data.seen.firstOrNull { it.host == h && it.session == s && it.terminalId == id && it.epoch == e }
        val kept = maxOf(existing?.stateChangeSeq ?: Long.MIN_VALUE, stateChangeSeq)
        val seen = data.seen.filterNot { it === existing } + SeenEntry(h, s, id, e, kept, t)
        val action = PhoneAction(data.nextId, h, s, id, e, ActionKind.MarkSeen, t, ActionOutcome.Ok)
        commit(data.copy(nextId = data.nextId + 1, seen = seen, actions = data.actions + action))
    }

    /** Reads live state on every call, so a home recomputed after [markSeen] sees it. Facts from other epochs do not apply. */
    fun seenLookup(host: HostProfileId, session: String, epoch: Long): SeenLookup = SeenLookup { terminalId ->
        synchronized(lock) {
            data.seen.firstOrNull { it.host == host.value && it.session == session && it.terminalId == terminalId && it.epoch == epoch }?.stateChangeSeq
        }
    }

    /** A new epoch for [host] and [session], never handed out before on this phone. */
    fun allocateEpoch(host: HostProfileId, session: String): Long = synchronized(lock) {
        val mark = data.epochs.firstOrNull { it.host == host.value && it.session == session }
        val next = (mark?.allocated ?: 0) + 1
        commit(data.copy(epochs = data.epochs.filterNot { it === mark } + EpochMark(host.value, session, next, mark?.installed)))
        next
    }

    /**
     * A read was installed in [epoch]. On the first install of a new epoch, an acknowledgement from the previous
     * installed epoch carries over only for a terminal this fresh read still shows Done at the same seq ([doneSeqs]:
     * terminal id to `state_change_seq` of every Done agent in the read). Anything else stays behind in its old epoch.
     */
    fun onInstalled(host: HostProfileId, session: String, epoch: Long, doneSeqs: Map<String, Long>) = synchronized(lock) {
        val mark = data.epochs.firstOrNull { it.host == host.value && it.session == session }
        if (mark?.installed == epoch) return@synchronized
        val from = mark?.installed
        val carried = if (from == null) emptyList() else data.seen
            .filter { it.host == host.value && it.session == session && it.epoch == from && doneSeqs[it.terminalId] == it.stateChangeSeq }
            .filter { old -> data.seen.none { it.host == old.host && it.session == old.session && it.terminalId == old.terminalId && it.epoch == epoch } }
            .map { it.copy(epoch = epoch) }
        val updated = EpochMark(host.value, session, maxOf(mark?.allocated ?: 0, epoch), epoch)
        commit(data.copy(seen = data.seen + carried, epochs = data.epochs.filterNot { it === mark } + updated))
    }

    /** The epoch of the last read installed for [host] and [session], or null when none was: what a background reader needs to ask [seenLookup]. */
    fun installedEpoch(host: HostProfileId, session: String): Long? = synchronized(lock) {
        data.epochs.firstOrNull { it.host == host.value && it.session == session }?.installed
    }

    /**
     * Forgets everything this ledger holds about [host] (a machine the user removed): its observations, acknowledgements and actions. The epoch
     * counters stay, without their installed mark, because an epoch is never handed out twice on this phone: the same machine added again must not get
     * numbers that rows kept elsewhere (the operation journal) already carry. A counter is a host id, a session name and a number; no text from a machine.
     */
    fun forgetHost(host: HostProfileId) = synchronized(lock) {
        val id = host.value
        commit(data.copy(
            observations = data.observations.filterNot { it.host == id },
            seen = data.seen.filterNot { it.host == id },
            actions = data.actions.filterNot { it.host == id },
            epochs = data.epochs.map { if (it.host == id) it.copy(installed = null) else it },
        ))
    }

    fun observations(): List<Observation> = synchronized(lock) { data.observations }
    fun actions(): List<PhoneAction> = synchronized(lock) { data.actions }

    /** Waits until everything committed so far is on disk (or failed to be). */
    fun flush() { writer.submit {}.get(10, TimeUnit.SECONDS) }

    private fun append(kind: ObservationKind, host: HostProfileId, session: String, epoch: Long, terminalId: String?, detail: String) {
        val o = Observation(data.nextId, host.value, session, terminalId, epoch, kind, now(), detail.take(MAX_DETAIL))
        commit(data.copy(nextId = data.nextId + 1, observations = data.observations + o))
    }

    private fun commit(next: LedgerData) {
        data = pruned(next, now())
        if (writeQueued) return
        writeQueued = true
        writer.execute {
            val snapshot = synchronized(lock) { writeQueued = false; data }
            try { store.save(snapshot); saveFailed = false } catch (_: IOException) { saveFailed = true }
        }
    }

    private fun pruned(d: LedgerData, t: Long) = d.copy(
        observations = Retention.prune(d.observations, t) { it.at },
        seen = Retention.prune(d.seen, t) { it.at },
        actions = Retention.prune(d.actions, t) { it.at },
    )

    companion object { const val MAX_DETAIL = 80 }
}
