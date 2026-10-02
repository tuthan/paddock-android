package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.output.OutputFeed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ResultTone { Ok, Refused, Unknown, Problem }

/**
 * One sentence about how an operation ended. [opensTerminal] offers the terminal as the next step (a refused or stalled
 * prompt hands off to it). [unknown] marks a send whose outcome was lost: the screen must draw it as unknown and offer
 * a re-read, never a resend.
 */
data class ResultLine(val text: String, val tone: ResultTone, val opensTerminal: Boolean = false, val unknown: Boolean = false)

/** The label an accepted prompt gets when no state observation follows it in [NO_PROGRESS_AFTER_MILLIS]. */
const val NO_PROGRESS = "no progress observed"
const val NO_PROGRESS_AFTER_MILLIS = 5_000L

/** Turns operation results and journal rows into the plain sentences the screens show. Pure, with an injectable zone. */
class OperationPresenter(private val zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val minutes = DateTimeFormatter.ofPattern("HH:mm", locale)
    private fun at(millis: Long) = seconds.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun short(millis: Long) = minutes.format(Instant.ofEpochMilli(millis).atZone(zone))

    /**
     * [noProgress] adds the label for an acknowledged prompt after which the agent's state has not changed for
     * [NO_PROGRESS_AFTER_MILLIS] (see [noProgress]); it is a label, and changes nothing about what may be sent.
     */
    fun line(kind: OperationKind, result: OperationResult<*>, noProgress: Boolean = false): ResultLine = when (result) {
        is OperationResult.Acknowledged -> ResultLine(acknowledged(kind, result.record) + if (noProgress && kind == OperationKind.Prompt) " · $NO_PROGRESS" else "", ResultTone.Ok)
        is OperationResult.Rejected -> rejected(kind, result)
        is OperationResult.NotSent -> notSent(kind, result)
        is OperationResult.Unknown -> ResultLine(unknownText(result.record), ResultTone.Unknown, unknown = true)
        is OperationResult.Busy -> ResultLine("Another send to this agent is still running (${describe(result.first)}).", ResultTone.Refused)
        is OperationResult.NeedsReread -> ResultLine("An earlier send's outcome is unknown. Re-read before sending again.", ResultTone.Refused, unknown = true)
        is OperationResult.Stale -> ResultLine("This screen is out of date. Re-read the agent, then try again. Nothing was sent.", ResultTone.Problem)
        is OperationResult.JournalFailed -> ResultLine("Nothing was sent: the operation record could not be written on this phone.", ResultTone.Problem)
        is OperationResult.JournalUnreadable -> ResultLine("Nothing was sent: the record of earlier sends on this phone cannot be read. Retry or reset it in Settings.", ResultTone.Problem)
    }

    /**
     * Five seconds after a prompt was accepted, with the agent's `state_change_seq` still the one the send began with, no
     * state observation followed it. [currentSeq] is null when the agent is not in the installed read: nothing is claimed then.
     * The label is only a label: it never re-enables a send.
     */
    fun noProgress(record: OperationRecord, currentSeq: Long?, nowMillis: Long): Boolean {
        val sentAt = record.sentAt ?: return false
        val seq = record.seqAtSend ?: return false
        return record.kind == OperationKind.Prompt && record.outcome == OperationOutcome.Acknowledged &&
            currentSeq == seq && nowMillis - sentAt >= NO_PROGRESS_AFTER_MILLIS
    }

    /**
     * What a re-read shows: when and what herdr reports now, then what that does and does not tell about the row it freed.
     * Facts only. A prompt's text is looked for in the output the screen holds; a key or a focus has nothing to look for.
     */
    fun rereadLines(report: ReReadReport, check: TextCheck): List<String> {
        val state = "Re-read ${at(report.readAtMillis)} · herdr reports the agent as ${report.status.name.lowercase()}"
        val freed = report.resolved.lastOrNull() ?: return listOf(state)
        val what = when (freed.kind) {
            OperationKind.Prompt -> when (check) {
                TextCheck.NotKept -> "Paddock kept only a hash of this prompt, so it cannot look for its text. Check the terminal."
                TextCheck.NoOutput -> "The output has not been read yet, so the prompt's text cannot be looked for."
                TextCheck.Found -> "The prompt's text appears in the last ${OutputFeed.LINES} lines of output. That does not say the agent took it as a prompt."
                TextCheck.NotFound -> "The prompt's text does not appear in the last ${OutputFeed.LINES} lines of output. That does not say it was not received."
            }
            OperationKind.Esc, OperationKind.CtrlC -> "Paddock cannot tell whether the key reached the agent. Look at the terminal."
            OperationKind.Focus -> "Paddock cannot tell whether the desktop focused the agent. Look at the desktop."
        }
        return listOf(state, what)
    }

    /** Why a re-read did nothing: every row is still waiting. */
    fun rereadFailure(failed: RereadOutcome.Failed): String =
        if (failed.gone) "This agent is no longer in the session, or its pane now holds another terminal. Nothing was re-read."
        else "Could not read the agent, so nothing was re-read (${failed.detail.take(120)})."

    /** "prompt sent 14:03:12 · outcome unknown · re-read before sending again", the note's own wording. */
    fun unknownText(record: OperationRecord): String {
        val sent = record.sentAt?.let { " ${at(it)}" }.orEmpty()
        val tail = record.resolvedAt?.let { "re-read ${short(it)}" } ?: "re-read before sending again"
        // Known, not unknown: herdr named another terminal in its answer, so the write landed on a different agent.
        if (record.note.startsWith(Misdelivered.PREFIX)) return "${record.kind.wire} sent$sent · herdr says it reached a different agent than this one · check that agent · $tail"
        return "${record.kind.wire} sent$sent · outcome unknown · $tail"
    }

    /** A journal row as one line, for Activity and the agent screen. */
    fun describe(record: OperationRecord): String = when (record.outcome) {
        OperationOutcome.Requested -> "${record.kind.wire} requested ${at(record.requestedAt)}"
        OperationOutcome.Sent -> "${record.kind.wire} sent${record.sentAt?.let { " ${at(it)}" }.orEmpty()}, waiting for herdr's answer"
        OperationOutcome.Acknowledged -> acknowledged(record.kind, record)
        OperationOutcome.Rejected -> "${record.kind.wire} refused by herdr${record.code?.let { " ($it)" }.orEmpty()}"
        OperationOutcome.NotSent -> "${record.kind.wire} not sent${record.code?.let { reasonWords(it)?.let { w -> " ($w)" } }.orEmpty()}"
        OperationOutcome.Unknown -> unknownText(record)
    }

    private fun acknowledged(kind: OperationKind, record: OperationRecord): String {
        val time = record.sentAt?.let { " ${at(it)}" }.orEmpty()
        return when (kind) {
            OperationKind.Prompt -> "Prompt sent$time · accepted by herdr, which is not a receipt for any turn"
            OperationKind.Esc, OperationKind.CtrlC -> "${kind.wire} sent$time · accepted by herdr"
            OperationKind.Focus -> "The desktop now has this agent focused${record.sentAt?.let { " · ${at(it)}" }.orEmpty()}"
        }
    }

    private fun rejected(kind: OperationKind, r: OperationResult.Rejected): ResultLine = when (r.code) {
        "agent_blocked" -> ResultLine("herdr refused the prompt: the agent is blocked and needs an answer. Nothing was typed. Use the terminal.", ResultTone.Refused, opensTerminal = true)
        "agent_not_ready" -> ResultLine("herdr refused: the agent is not ready for input (${r.message}). Nothing was typed.", ResultTone.Refused, opensTerminal = true)
        "agent_not_found" -> ResultLine("herdr no longer knows this agent. Nothing was sent.", ResultTone.Refused)
        "invalid_key" -> ResultLine("herdr does not accept that key (${r.message}).", ResultTone.Refused)
        else -> ResultLine("herdr refused the ${kind.wire}: ${r.message.ifBlank { r.code }}", ResultTone.Refused)
    }

    private fun notSent(kind: OperationKind, r: OperationResult.NotSent): ResultLine {
        val notReady = NotReadyReason.entries.firstOrNull { it.code == r.reason }
        return when {
            notReady != null -> ResultLine("${notReady.sentence} Nothing was sent.", ResultTone.Refused, opensTerminal = notReady == NotReadyReason.Blocked)
            r.reason == "pane_moved" -> ResultLine("This agent's pane now holds another terminal. Re-read, then try again. Nothing was sent.", ResultTone.Problem)
            r.reason == "preflight_failed" -> ResultLine("Could not read the agent just before sending, so nothing was sent. (${r.message})", ResultTone.Problem)
            r.reason == "transport_failed" -> ResultLine("Could not reach the machine, so nothing was sent. (${r.message})", ResultTone.Problem)
            r.reason == "journal_unwritable" -> ResultLine("Nothing was sent: the operation record could not be written on this phone.", ResultTone.Problem)
            r.reason == "cancelled" -> ResultLine("Cancelled before anything was sent.", ResultTone.Problem)
            else -> ResultLine("The ${kind.wire} was not sent (${r.reason}).", ResultTone.Problem)
        }
    }

    private fun reasonWords(code: String): String? =
        NotReadyReason.entries.firstOrNull { it.code == code }?.let { it.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase() }
            ?: when (code) { "pane_moved" -> "pane moved"; "transport_failed" -> "host unreachable"; "preflight_failed" -> "agent not readable"; "app_restarted" -> "app ended first"; else -> null }
}
