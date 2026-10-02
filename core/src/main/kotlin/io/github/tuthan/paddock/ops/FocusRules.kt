package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.identity.TerminalKey

/**
 * Whether the desktop-focus button may be tapped now. Focus has no mode and asks nothing of the agent's state: it moves the
 * desktop's cursor, so it is closed only by the shared conditions that the journal would refuse anyway: the agent is gone,
 * the link is down, the connection is not the one [key] was made in, nothing has been read yet, an operation is already
 * running on the terminal, an unknown outcome is still waiting for a re-read, or the saved journal cannot be read.
 */
object FocusRules {
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
