package io.github.tuthan.paddock.ledger

import io.github.tuthan.paddock.attention.AgeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ActivityRowKind { Observed, Acted, Gap }

/** One line of the Activity screen. [description] is what TalkBack reads: the time, then the text. */
data class ActivityRow(val key: String, val timeLabel: String, val text: String, val kind: ActivityRowKind) {
    val description: String get() = "$timeLabel, $text"
}

data class ActivitySection(val heading: String, val rows: List<ActivityRow>)

/**
 * Turns ledger items into day-grouped rows. It says only what the ledger knows: what the phone observed and what the
 * user did here. It never claims a cause ("answered elsewhere"): a state that changed is "went blocked → working", and
 * time with no link is a gap. Titles are looked up from the current snapshot by [titleOf] and fall back to a short id,
 * because the ledger stores no titles, prompts or output.
 */
class ActivityPresenter(
    private val zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    private val hostName: (hostId: String) -> String = { it },
    private val titleOf: (host: String, session: String, terminalId: String) -> String? = { _, _, _ -> null },
) {
    private val time = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val date = DateTimeFormatter.ofPattern("EEE d MMM", locale)

    fun present(items: List<ActivityItem>, nowMillis: Long): List<ActivitySection> {
        val today = day(nowMillis)
        val groups = LinkedHashMap<LocalDate, MutableList<ActivityRow>>()
        for (item in items) groups.getOrPut(day(item.at)) { ArrayList() } += row(item)
        return groups.map { (d, rows) -> ActivitySection(heading(d, today), rows) }
    }

    private fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    private fun clock(millis: Long) = time.format(Instant.ofEpochMilli(millis).atZone(zone))
    private fun heading(d: LocalDate, today: LocalDate) = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> date.format(d) }

    private fun title(host: String, session: String, terminalId: String) =
        titleOf(host, session, terminalId)?.takeIf { it.isNotBlank() } ?: "agent ${terminalId.takeLast(6)}"

    private fun row(item: ActivityItem): ActivityRow = when (item) {
        is ActivityItem.Observed -> {
            val o = item.observation
            val host = hostName(o.host)
            val who = o.terminalId?.let { title(o.host, o.session, it) }.orEmpty()
            val text = when (o.kind) {
                ObservationKind.AgentAppeared -> "$who appeared"
                ObservationKind.StateChanged -> "$who: ${o.detail.replace("->", "→").ifBlank { "state changed" }}"
                ObservationKind.AgentGone -> "$who is no longer listed"
                ObservationKind.Connected -> "Connected to $host"
                ObservationKind.Disconnected -> "Lost the connection to $host"
            }
            ActivityRow("o-${o.id}", clock(o.at), text, ActivityRowKind.Observed)
        }
        is ActivityItem.Acted -> {
            val a = item.action
            val who = title(a.host, a.session, a.terminalId)
            val text = when (a.kind) { ActionKind.MarkSeen -> "You marked $who as seen" } +
                when (a.outcome) { ActionOutcome.Ok -> ""; ActionOutcome.Failed -> " (failed)"; ActionOutcome.Unknown -> " (outcome unknown)" }
            ActivityRow("a-${a.id}", clock(a.at), text, ActivityRowKind.Acted)
        }
        is ActivityItem.Gap -> {
            val host = hostName(item.host).ifEmpty { "the host" }
            val text = item.to?.let { "No connection to $host for ${AgeText.span(it - item.from).removeSuffix(" ago").replace("just now", "a few seconds")}" }
                ?: "No connection to $host since ${clock(item.from)}"
            ActivityRow("g-${item.host}-${item.session}-${item.from}", clock(item.from), text, ActivityRowKind.Gap)
        }
    }
}
