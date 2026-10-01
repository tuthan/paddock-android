package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityRow
import io.github.tuthan.paddock.ledger.ActivityRowKind
import io.github.tuthan.paddock.ledger.ActivitySection
import io.github.tuthan.paddock.ui.components.FilterChips
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.theme.PaddockTokens

private val FILTERS = listOf(
    ActivityFilter.All to "All",
    ActivityFilter.StateChanges to "State changes",
    ActivityFilter.Connection to "Connection",
    ActivityFilter.PhoneActions to "From this phone",
)

/**
 * The phone's own ledger: what it observed and what the user did here, newest first, grouped by day, with time that had
 * no connection shown as a gap. It claims nothing about causes and never shows prompt or output text.
 */
@Composable
fun ActivityLog(
    sections: List<ActivitySection>,
    filter: ActivityFilter,
    onFilter: (ActivityFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(PaddockTokens.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(PaddockTokens.spacing.rowGap),
    ) {
        item(key = "title") { Text("Activity", style = PaddockTokens.type.screenTitle, color = c.title, modifier = Modifier.semantics { heading() }) }
        item(key = "filters") { FilterChips(FILTERS.map { it.second }, FILTERS.indexOfFirst { it.first == filter }, { onFilter(FILTERS[it].first) }) }
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text("Nothing here yet. This list fills as this phone sees agents change and as you act on it.", style = PaddockTokens.type.body, color = c.dim)
            }
        }
        sections.forEach { section ->
            item(key = "h-${section.heading}") { Kicker(section.heading, Modifier.padding(top = 6.dp)) }
            items(section.rows, key = { it.key }) { row -> ActivityRowView(row) }
        }
        item(key = "note") {
            Text(
                "This is this phone's own record of what it saw and did. Time with no connection shows as a gap.",
                style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ActivityRowView(row: ActivityRow) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    val gap = row.kind == ActivityRowKind.Gap
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (gap) c.wash(c.attention) else c.surface, shape)
            .border(1.dp, if (gap) c.washBorder(c.attention) else c.line(), shape)
            .heightIn(min = PaddockTokens.spacing.touchTarget)
            .padding(PaddockTokens.spacing.cardPadding)
            .semantics(mergeDescendants = true) { contentDescription = row.description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The time never wraps: its column grows with the font instead of breaking 14:50 into two lines.
        Text(row.timeLabel, style = PaddockTokens.type.monoFact, color = c.dim, softWrap = false, modifier = Modifier.widthIn(min = 48.dp))
        Text(row.text, style = PaddockTokens.type.body, color = if (gap) c.title else c.text, modifier = Modifier.weight(1f))
    }
}
