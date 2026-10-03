package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.terminalBlock

/**
 * Whether Yes and No may be tapped for a terminal now, on the conditions the journal would refuse anyway: the agent is gone, the link is
 * down, the connection is not the one [key] was made in, nothing has been read yet, another operation is running on the terminal, an
 * unknown outcome still waits for a re-read, or the saved journal cannot be read. Like focus it asks nothing of the agent's state: the
 * request's own listing says whether there is anything to answer ([AnswerRules]).
 */
object AnswerGate {
    fun gate(
        agent: Agent?,
        installedReadAtMillis: Long?,
        live: Boolean,
        records: List<OperationRecord>,
        key: TerminalKey,
        currentEpoch: Long? = key.epoch,
        journalUnreadable: Boolean = false,
    ): OperationGate = terminalBlock(agent, installedReadAtMillis, openedAtMillis = 0, live = live, records = records, key = key, currentEpoch = currentEpoch, journalUnreadable = journalUnreadable)
        ?.let { OperationGate.Closed(it.block, it.sentence) } ?: OperationGate.Open
}
