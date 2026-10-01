package io.github.tuthan.paddock.identity

import io.github.tuthan.paddock.herdr.Snapshot

@JvmInline value class HostProfileId(val value: String)

/** A herdr session on a host profile. */
data class SessionRef(val host: HostProfileId, val name: String)

/**
 * The stable identity of a terminal that alerts, deep links and the relay may name. `terminalId` outlives pane ids
 * (a pane can be renumbered; the terminal behind it keeps its id), so it, not `pane_id`, is what leaves the phone.
 */
data class TargetRef(val host: HostProfileId, val session: String, val terminalId: String) {
    val sessionRef get() = SessionRef(host, session)
}

/**
 * Identity for facts stored on the phone. [epoch] is phone-local, increments on reconnect and on any snapshot whose
 * `version` or `protocol` changed, and never leaves the phone: a fact stored under an old epoch is not applied to
 * the terminal as it is now.
 */
data class TerminalKey(val target: TargetRef, val epoch: Long)

/**
 * Counts epochs. A reconnect or a changed server version or protocol starts a new one. Epochs come from [allocate],
 * which the owner backs with the ledger so they never repeat for a host and session: not across reconnects, not
 * across app restarts. A fact stored under one epoch therefore never applies to a later connection by accident.
 */
class EpochTracker(private val allocate: () -> Long) {
    /** In-process numbering from [initial]; for tests and tools that store nothing. */
    constructor(initial: Long = 1) : this(LocalEpochs(initial)::next)

    var epoch: Long = allocate(); private set
    private var version: String? = null
    private var protocol: Int? = null

    fun onReconnect(): Long { epoch = allocate(); return epoch }

    /** Returns true when this snapshot started a new epoch. The first snapshot only records the baseline. */
    fun onSnapshot(s: Snapshot): Boolean {
        val changed = version != null && (version != s.version || protocol != s.protocol)
        version = s.version; protocol = s.protocol
        if (changed) epoch = allocate()
        return changed
    }

    private class LocalEpochs(private var next: Long) { fun next(): Long = next++ }
}

/** The pane a call may be sent to was not the one the caller named. Refused before anything is sent. */
class StalePane(val key: TerminalKey) : Exception("terminal ${key.target.terminalId} is not current for epoch ${key.epoch}")

/** Resolves identities against one installed snapshot. Nothing is ever compared against a cached pane id. */
class PaneResolver(private val snapshot: Snapshot, private val epoch: Long) {
    /** The pane id for a stored fact, or null when the epoch moved on or the terminal is gone. */
    fun current(key: TerminalKey): String? =
        if (key.epoch != epoch) null else snapshot.panes.firstOrNull { it.terminalId == key.target.terminalId }?.paneId

    /** Same as [current] but refuses instead of returning null, for the call path. */
    fun require(key: TerminalKey): String = current(key) ?: throw StalePane(key)

    /** For anything arriving from outside (a notification, a deep link): resolved by terminal id, never by epoch. */
    fun resolve(target: TargetRef, expectedHost: HostProfileId, expectedSession: String): String? =
        if (target.host != expectedHost || target.session != expectedSession) null
        else snapshot.panes.firstOrNull { it.terminalId == target.terminalId }?.paneId
}
