package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.alerts.AlertHint
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.alerts.DeepLink
import io.github.tuthan.paddock.alerts.DeepLinkResult
import io.github.tuthan.paddock.attention.AgentMarks
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WidgetTone { NeedsYou, Quiet }

/**
 * One urgent line. [link] is the alert's own link (`paddock://open?...`), so a tap does what a notification's Review does: the app
 * resolves it against a fresh read before saying anything about the agent. Null when the cache's ids do not make a valid link; the row
 * is then drawn without a Review button and its tap opens the herd.
 */
data class WidgetRowContent(val title: String, val code: String, val detail: String, val link: String?, val description: String)

/**
 * What every widget size draws, as text. The one rule that matters: **a count never appears without its time**. Content with data always
 * has [asOf]; content without data has no count, no detail and no rows, only [headline] saying how to get one. [stale] means the read is
 * older than [STALE_AFTER_MILLIS] (two refresh periods missed, a phone that slept) or from the future (a clock that moved): the widget draws
 * dimmed and its description says so, and the time is still the thing that tells the truth.
 */
data class WidgetContent(
    val hasData: Boolean,
    /** `as of 14:02`: the time of the read, always. Null only without data. */
    val asOf: String?,
    /** What the widget prints for the time: [asOf], plus ` · old` when [stale], so an old number says it is old in words and not only in a dimmer colour. */
    val asOfLabel: String?,
    val stale: Boolean,
    val tone: WidgetTone,
    val machine: String,
    /** The 2 x 2's big number: how many need the user. Null without data. */
    val count: String?,
    val countLabel: String,
    val headline: String,
    val detail: String,
    val rows: List<WidgetRowContent>,
    /** What TalkBack reads for the whole widget. */
    val description: String,
)

class WidgetPresenter(private val zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayAndClock = DateTimeFormatter.ofPattern("EEE HH:mm", locale)

    /** [locked]: widgets are a Pro capability (vault M8) and Pro is not held, so whatever the cache holds, the widget draws no count and says why. */
    fun present(cache: WidgetCache?, nowMillis: Long, locked: Boolean = false): WidgetContent {
        if (locked) return LOCKED
        if (cache == null) return NO_DATA
        val c = cache.counts
        val needs = c.needsYou
        val asOf = "as of " + stamp(cache.readAtMillis, nowMillis)
        val stale = nowMillis - cache.readAtMillis > STALE_AFTER_MILLIS || cache.readAtMillis > nowMillis + FUTURE_SLACK_MILLIS
        val headline = when { needs == 1 -> "1 needs you"; needs > 1 -> "$needs need you"; else -> "Nothing needs you" }
        val detail = when {
            c.total == 0 -> "No agents running"
            else -> listOfNotNull(c.done.takeIf { it > 0 }?.let { "$it done" }, c.working.takeIf { it > 0 }?.let { "$it working" }, c.ready.takeIf { it > 0 }?.let { "$it ready" },
                c.unknown.takeIf { it > 0 }?.let { "$it unknown" }).joinToString(" · ").ifEmpty { "" }
        }
        val rows = cache.urgent.take(WidgetCache.MAX_ROWS).map { row(cache, it, nowMillis) }
        val description = buildString {
            append("Paddock, ${cache.machine}. $headline.")
            if (detail.isNotEmpty()) append(" $detail.")
            append(" $asOf.")
            if (stale) append(" This read is old.")
        }
        return WidgetContent(
            hasData = true, asOf = asOf, asOfLabel = if (stale) "$asOf · old" else asOf, stale = stale, tone = if (needs > 0) WidgetTone.NeedsYou else WidgetTone.Quiet, machine = cache.machine,
            count = needs.toString(), countLabel = if (needs == 1) "needs you" else "need you", headline = headline, detail = detail, rows = rows, description = description,
        )
    }

    private fun row(cache: WidgetCache, r: WidgetRow, nowMillis: Long): WidgetRowContent {
        val seen = stamp(r.observedAtMillis, nowMillis)
        val link = DeepLink.build(AlertHint(TargetRef(HostProfileId(cache.hostId), cache.session, r.terminalId), r.paneId, AlertState.Blocked,
            (r.observedAtMillis / 1000).coerceAtLeast(1), (r.seq ?: 0).coerceAtLeast(0))).takeIf { DeepLink.parse(it) is DeepLinkResult.Valid }
        return WidgetRowContent(r.title, AgentMarks.of(r.kind).code, "Blocked · seen $seen", link, "Review ${r.title}, blocked, seen $seen")
    }

    /** `14:02` for today, `Fri 14:02` for another day: a widget is read at a glance and must not suggest an old read is a new one. */
    private fun stamp(millis: Long, nowMillis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return (if (at.toLocalDate() == now.toLocalDate()) clock else dayAndClock).format(at)
    }

    companion object {
        /** Two missed 15-minute refreshes. */
        const val STALE_AFTER_MILLIS = 30 * 60_000L
        private const val FUTURE_SLACK_MILLIS = 5 * 60_000L

        /** A locked widget: no count, no machine, no rows, and nothing read from the cache. It is not a stale read, so it never shows a time. */
        val LOCKED = WidgetContent(
            hasData = false, asOf = null, asOfLabel = null, stale = false, tone = WidgetTone.Quiet, machine = "", count = null, countLabel = "Pro", headline = "Widgets are Pro",
            detail = "", rows = emptyList(), description = "Paddock widgets are a Pro capability. Open Paddock, then Settings, to see where Pro comes from. No count is shown.",
        )

        /** No cache yet, or none readable: no number at all. */
        val NO_DATA = WidgetContent(
            hasData = false, asOf = null, asOfLabel = null, stale = false, tone = WidgetTone.Quiet, machine = "", count = null, countLabel = "", headline = "Open Paddock to read your herd",
            detail = "", rows = emptyList(), description = "Paddock. Open the app to read your herd. No count is shown until it has been read.",
        )
    }
}
