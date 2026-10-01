package io.github.tuthan.paddock.ledger

import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TerminalKey
import java.io.IOException

/**
 * The phone's own record: what it observed and what the user did here. Entries come from observations and phone
 * actions only, never from herdr history. Marking a Done seen is local; it never calls herdr (`agent focus` included).
 */
class Ledger(private val store: LedgerStore, private val now: () -> Long) {
    private val lock = Any()
    private var data: LedgerData = store.load()

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

    fun observations(): List<Observation> = synchronized(lock) { data.observations }
    fun actions(): List<PhoneAction> = synchronized(lock) { data.actions }

    private fun append(kind: ObservationKind, host: HostProfileId, session: String, epoch: Long, terminalId: String?, detail: String) {
        val o = Observation(data.nextId, host.value, session, terminalId, epoch, kind, now(), detail.take(MAX_DETAIL))
        commit(data.copy(nextId = data.nextId + 1, observations = data.observations + o))
    }

    private fun commit(next: LedgerData) {
        val t = now()
        data = next.copy(
            observations = Retention.prune(next.observations, t) { it.at },
            seen = Retention.prune(next.seen, t) { it.at },
            actions = Retention.prune(next.actions, t) { it.at },
        )
        try { store.save(data); saveFailed = false } catch (_: IOException) { saveFailed = true }
    }

    companion object { const val MAX_DETAIL = 80 }
}
