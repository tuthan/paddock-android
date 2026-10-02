package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityRow
import io.github.tuthan.paddock.ledger.ActivitySection
import io.github.tuthan.paddock.ledger.ActivityTone
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Dot
import io.github.tuthan.paddock.ui.components.FilterChips
import io.github.tuthan.paddock.ui.components.Hairline
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockTokens

private val FILTERS = listOf(
    ActivityFilter.All to "All",
    ActivityFilter.StateChanges to "State changes",
    ActivityFilter.Connection to "Connection",
    ActivityFilter.PhoneActions to "From this phone",
)

/**
 * The phone's own ledger as a timeline: what it observed and what the user did here, newest first, grouped by day. Each
 * event is a time, a dot in the colour of what it was about, a plain sentence and the facts under it. Time with no
 * connection is a gap. It claims nothing about causes and never shows prompt or output text.
 */
@Composable
fun ActivityLog(
    sections: List<ActivitySection>,
    filter: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
    /** Re-read the unknown row with this journal id; null leaves the button out. */
    onReread: ((operationId: Long) -> Unit)? = null,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Activity")
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "filters") { FilterChips(FILTERS.map { it.second }, FILTERS.indexOfFirst { it.first == filter }, { onFilter(FILTERS[it].first) }) }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text("Nothing here yet. This list fills as this phone sees agents change and as you act on it.", style = PaddockTokens.type.body, color = c.dim)
                }
            }
            sections.forEach { section ->
                item(key = "h-${section.heading}") { Kicker(section.heading, Modifier.padding(top = 4.dp)) }
                items(section.rows, key = { it.key }) { row -> Event(row, onReread) }
            }
            item(key = "note") {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hairline()
                    Note("This is this phone's own record of what it saw and did. Time with no connection shows as a gap.")
                }
            }
        }
    }
}

@Composable
private fun Event(row: ActivityRow, onReread: ((Long) -> Unit)?) {
    val c = PaddockTokens.colors
    val dot = when (row.tone) {
        ActivityTone.NeedsYou -> c.needsYou
        ActivityTone.Done -> c.done
        ActivityTone.Working, ActivityTone.Phone -> c.accent
        ActivityTone.Host -> c.attention
        ActivityTone.Quiet -> c.faint
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.description },
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
        ) {
            // The time never wraps: its column grows with the font instead of breaking 14:50 into two lines.
            Text(row.timeLabel, style = PaddockTokens.type.monoFact, color = c.dim, softWrap = false, modifier = Modifier.widthIn(min = 44.dp).padding(top = 1.dp))
            Dot(dot, Modifier.padding(top = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.text, style = PaddockTokens.type.rowTitle.copy(fontSize = PaddockTokens.type.summary.fontSize), color = c.title)
                if (row.detail != null) Text(row.detail!!, style = PaddockTokens.type.secondary, color = c.dim)
            }
        }
        // Outside the merged description so TalkBack finds the button on its own. Only a re-read: never a resend.
        val id = row.rereadOperationId
        if (id != null && onReread != null) {
            PaddockButton("Re-read", { onReread(id) }, Modifier.padding(start = 70.dp), kind = ButtonKind.Ghost, small = true, fillWidth = false)
        }
    }
}
