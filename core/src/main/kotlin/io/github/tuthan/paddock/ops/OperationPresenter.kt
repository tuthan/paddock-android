package io.github.tuthan.paddock.ops

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

/** Turns operation results and journal rows into the plain sentences the screens show. Pure, with an injectable zone. */
class OperationPresenter(private val zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val minutes = DateTimeFormatter.ofPattern("HH:mm", locale)
    private fun at(millis: Long) = seconds.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun short(millis: Long) = minutes.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun line(kind: OperationKind, result: OperationResult<*>): ResultLine = when (result) {
        is OperationResult.Acknowledged -> ResultLine(acknowledged(kind, result.record), ResultTone.Ok)
        is OperationResult.Rejected -> rejected(kind, result)
        is OperationResult.NotSent -> notSent(kind, result)
        is OperationResult.Unknown -> ResultLine(unknownText(result.record), ResultTone.Unknown, unknown = true)
        is OperationResult.Busy -> ResultLine("Another send to this agent is still running (${describe(result.first)}).", ResultTone.Refused)
        is OperationResult.NeedsReread -> ResultLine("An earlier send's outcome is unknown. Re-read before sending again.", ResultTone.Refused, unknown = true)
        is OperationResult.Stale -> ResultLine("This screen is out of date. Re-read the agent, then try again. Nothing was sent.", ResultTone.Problem)
        is OperationResult.JournalFailed -> ResultLine("Nothing was sent: the operation record could not be written on this phone.", ResultTone.Problem)
    }

    /** "prompt sent 14:03:12 · outcome unknown · re-read before sending again", the note's own wording. */
    fun unknownText(record: OperationRecord): String {
        val sent = record.sentAt?.let { " ${at(it)}" }.orEmpty()
        val tail = record.resolvedAt?.let { "re-read ${short(it)}" } ?: "re-read before sending again"
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
