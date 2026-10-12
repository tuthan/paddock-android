package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.files.AttachmentPhrase
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.identity.TerminalKey

/** Why Send is off. The composer shows the sentence beside the button, so every disabled state names its condition. */
enum class SendBlock { AgentGone, NotLive, Stale, Reading, JournalUnreadable, InFlight, NeedsReread, NotReady, InboxUnread, EmptyPrompt, TooLong }

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
 * [key] carries the epoch the screen opened in and [currentEpoch] is the epoch of the installed read: after a reconnect
 * they differ, nothing is sent, and the screen asks for a re-read that takes the new epoch.
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
        currentEpoch: Long? = key.epoch,
        journalUnreadable: Boolean = false,
        hasAttachment: Boolean = false,
        attachmentUnknown: Boolean = false,
    ): SendGate {
        fun closed(block: SendBlock, sentence: String, why: NotReadyReason? = null) = SendGate.Closed(block, sentence, why)
        terminalBlock(agent, installedReadAtMillis, openedAtMillis, live, records, key, currentEpoch, journalUnreadable)?.let { return it }
        val present = requireNotNull(agent) { "terminalBlock lets only a present agent through" }
        val ready = when (val r = isReady(present, readAtMillis = installedReadAtMillis!!, nowMillis = installedReadAtMillis, requireHints = requireHints)) {
            is Readiness.NotReady -> return closed(SendBlock.NotReady, r.primary.sentence, r.primary)
            is Readiness.Ready -> r
        }
        // A cut attachment holds only the prompt that would name its file (X9), not the terminal: the caller says it concerns this draft ([attachmentUnknown]).
        if (attachmentUnknown) return closed(SendBlock.InboxUnread, "This file may already be on the machine. Read the inbox first.")
        // An attachment alone is a prompt (X12); the phrase line that names it takes some of the length.
        if (text.isBlank() && !hasAttachment) return closed(SendBlock.EmptyPrompt, "Write a prompt to send.")
        val max = AgentOperations.MAX_PROMPT_CHARS - if (hasAttachment) AttachmentPhrase.RESERVED_CHARS else 0
        if (text.length > max) return closed(SendBlock.TooLong, "A prompt ${if (hasAttachment) "with a file " else ""}is at most ${"%,d".format(max)} characters.")
        return SendGate.Open(ready.hintsUnreported)
    }

    /** Whether [key] has a cut attachment whose inbox has not been read since: what a caller passes as `attachmentUnknown`. */
    fun attachmentUnknown(records: List<OperationRecord>, key: TerminalKey): Boolean = records.any { it.sameTerminal(key) && it.awaitsInboxCheck }
}

/**
 * The conditions every operation on a terminal shares, in the order that reads best: the agent, the link, whether the
 * connection is still the one the screen opened in, whether the phone has read the agent since the screen opened, and
 * any earlier operation on the terminal. Null means the terminal can take an operation; what each kind needs beyond
 * that (readiness and text for a prompt, nothing for a key) is the caller's. When this returns null, [installedReadAtMillis]
 * is not null and [agent] is present. [journalUnreadable] is the operation journal's own state: with its saved rows lost to the
 * phone, every operation is off, whatever the terminal looks like.
 */
internal fun terminalBlock(
    agent: Agent?,
    installedReadAtMillis: Long?,
    openedAtMillis: Long,
    live: Boolean,
    records: List<OperationRecord>,
    key: TerminalKey,
    currentEpoch: Long?,
    journalUnreadable: Boolean = false,
): SendGate.Closed? {
    fun closed(block: SendBlock, sentence: String) = SendGate.Closed(block, sentence)
    if (agent == null) return closed(SendBlock.AgentGone, "This agent is no longer in the session.")
    if (!live) return closed(SendBlock.NotLive, "Not connected to the machine right now. Sending is off until the link is back.")
    if (currentEpoch != null && currentEpoch != key.epoch) return closed(SendBlock.Stale, "The connection was re-established since this screen opened. Re-read the agent first.")
    if (installedReadAtMillis == null || installedReadAtMillis < openedAtMillis) return closed(SendBlock.Reading, "Reading the agent's state…")
    if (journalUnreadable) return closed(SendBlock.JournalUnreadable, "The record of earlier sends on this phone cannot be read, so a duplicate cannot be ruled out. Sending is off until it is restored or reset in Settings.")
    val mine = records.filter { it.sameTerminal(key) }
    if (mine.any { it.inFlight }) return closed(SendBlock.InFlight, "Another send to this agent is still running.")
    if (mine.any { it.awaitsReread }) return closed(SendBlock.NeedsReread, "An earlier send's outcome is unknown. Re-read before sending again.")
    return null
}
