package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.identity.TerminalKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One Manual input session: the user chose it on the agent screen for [terminalId], at [enteredAtMillis], in connection
 * [epoch]. Keys are offered only while a session exists, and only once the phone has read the agent after
 * [enteredAtMillis] (the fresh read the mode starts with).
 */
data class ManualSession(val terminalId: String, val enteredAtMillis: Long, val epoch: Long)

/**
 * The explicit Manual input mode. It lives as long as the process, so a rotation keeps it, and it is never restored from
 * saved state: after the app was killed the user enters it again, which reads the agent again. It belongs to one terminal,
 * and the screens end it when the user leaves the agent.
 */
class ManualInputMode {
    private val _current = MutableStateFlow<ManualSession?>(null)
    val current: StateFlow<ManualSession?> = _current.asStateFlow()

    /** Starts a session, replacing any other: entering again is how the user takes a fresh read after a reconnect. */
    fun enter(terminalId: String, nowMillis: Long, epoch: Long) { _current.value = ManualSession(terminalId, nowMillis, epoch) }

    fun leave() { _current.value = null }

    /** Ends the session unless it is for [terminalId]; a session never carries over to another terminal. */
    fun leaveUnless(terminalId: String?) { _current.update { if (it != null && it.terminalId == terminalId) it else null } }
}

/** Whether an operation that needs no readiness (a manual key, desktop focus) may be sent now. Closed always carries the sentence that names the condition. */
sealed interface OperationGate {
    object Open : OperationGate
    data class Closed(val block: SendBlock, val sentence: String) : OperationGate
}

/**
 * The gate for the manual keys. It is the composer's gate without readiness and without text: a key says nothing about
 * what the agent is doing, and Esc is most useful when the agent is working or blocked, so no status closes it. What does
 * close it is the same list as for a prompt, because the journal refuses the same cases: the agent is gone, the link is
 * down, the connection was re-established since the session began, no read since the session began, an operation already
 * running on the terminal, an unknown outcome still waiting for a re-read, or a saved journal that cannot be read.
 */
object ManualInputRules {
    fun gate(
        agent: Agent?,
        installedReadAtMillis: Long?,
        enteredAtMillis: Long,
        live: Boolean,
        records: List<OperationRecord>,
        key: TerminalKey,
        currentEpoch: Long? = key.epoch,
        journalUnreadable: Boolean = false,
    ): OperationGate = terminalBlock(agent, installedReadAtMillis, enteredAtMillis, live, records, key, currentEpoch, journalUnreadable)
        ?.let { OperationGate.Closed(it.block, it.sentence) } ?: OperationGate.Open
}
