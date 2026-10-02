package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.identity.TerminalKey

/** Why Send is off. The composer shows the sentence beside the button, so every disabled state names its condition. */
enum class SendBlock { AgentGone, NotLive, Reading, InFlight, NeedsReread, NotReady, EmptyPrompt, TooLong }

sealed interface SendGate {
    /** Send may be tapped. [hintsUnreported]: herdr gave no readiness hint for this agent, so only its status backs the send. */
    data class Open(val hintsUnreported: Boolean) : SendGate
    data class Closed(val block: SendBlock, val sentence: String, val notReady: NotReadyReason? = null) : SendGate
}

/**
 * Whether the composer's Send may be tapped. The checks run in the order that is most useful to read: the agent and the
 * link first, then whether the phone has read the agent since the composer opened, then any earlier operation on the
 * terminal, then [isReady], and last the text itself.
 *
 * The 2 s freshness inside [isReady] is enforced at the send, by a fresh read immediately before the write
 * ([AgentOperations.prompt]). Here the installed read stands for "now": a live monitor installs a new read on every
 * event and every 30 s, and [openedAtMillis] makes Send wait for a read made after the composer opened, so a button is
 * never enabled by a read from before the user was looking.
 */
object ComposerRules {
    fun gate(
        agent: Agent?,
        installedReadAtMillis: Long?,
        openedAtMillis: Long,
        live: Boolean,
        records: List<OperationRecord>,
        key: TerminalKey,
        text: String,
        requireHints: Boolean = false,
    ): SendGate {
        fun closed(block: SendBlock, sentence: String, why: NotReadyReason? = null) = SendGate.Closed(block, sentence, why)
        if (agent == null) return closed(SendBlock.AgentGone, "This agent is no longer in the session.")
        if (!live) return closed(SendBlock.NotLive, "Not connected to the machine right now. Sending is off until the link is back.")
        if (installedReadAtMillis == null || installedReadAtMillis < openedAtMillis) return closed(SendBlock.Reading, "Reading the agent's state…")
        val mine = records.filter { it.sameTerminal(key) }
        if (mine.any { it.inFlight }) return closed(SendBlock.InFlight, "Another send to this agent is still running.")
        if (mine.any { it.awaitsReread }) return closed(SendBlock.NeedsReread, "An earlier send's outcome is unknown. Re-read before sending again.")
        val ready = when (val r = isReady(agent, readAtMillis = installedReadAtMillis, nowMillis = installedReadAtMillis, requireHints = requireHints)) {
            is Readiness.NotReady -> return closed(SendBlock.NotReady, r.primary.sentence, r.primary)
            is Readiness.Ready -> r
        }
        if (text.isBlank()) return closed(SendBlock.EmptyPrompt, "Write a prompt to send.")
        if (text.length > AgentOperations.MAX_PROMPT_CHARS) return closed(SendBlock.TooLong, "A prompt is at most ${"%,d".format(AgentOperations.MAX_PROMPT_CHARS)} characters.")
        return SendGate.Open(ready.hintsUnreported)
    }
}
