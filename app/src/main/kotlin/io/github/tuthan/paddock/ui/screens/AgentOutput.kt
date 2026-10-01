package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.KeyStrip
import io.github.tuthan.paddock.ui.components.OutputSlab
import io.github.tuthan.paddock.ui.components.SegmentedTabs
import io.github.tuthan.paddock.ui.components.StateWord
import io.github.tuthan.paddock.ui.theme.PaddockTokens

enum class AgentTab(val label: String) { Output("Output"), Terminal("Terminal") }

/** What the header says about the agent. [observedAtMillis] is when the phone saw this state, null when it never has. */
data class AgentHeader(val title: String, val context: String, val state: StateWord, val observedAtMillis: Long?)

/**
 * One agent: who it is, what state the phone last saw and when, then the Output tab (the live tail) or the Terminal
 * tab (a placeholder until Phase 05). A vanished terminal replaces the body with a plain statement and a way back.
 */
@Composable
fun AgentOutput(
    header: AgentHeader,
    output: OutputState,
    following: Boolean,
    nowMillis: Long,
    tab: AgentTab,
    onTab: (AgentTab) -> Unit,
    onBack: () -> Unit,
    onUserScrolledUp: () -> Unit,
    onResumeFollowing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize().padding(PaddockTokens.spacing.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.minimumInteractiveComponentSize().size(PaddockTokens.spacing.touchTarget)
                    .clickable(role = Role.Button, onClickLabel = "Back to the list", onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Text("←", style = PaddockTokens.type.screenTitle, color = c.title) }
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(header.title, style = PaddockTokens.type.screenTitle, color = c.title, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (header.context.isNotEmpty()) Text(header.context, style = PaddockTokens.type.secondary, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val observed = header.observedAtMillis?.let { AgeText.observed(nowMillis - it) } ?: "not observed by this phone"
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${header.state.word}, $observed" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StateWord(header.state)
            Text(observed, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.weight(1f))
        }
        SegmentedTabs(AgentTab.entries.map { it.label }, tab.ordinal, { onTab(AgentTab.entries[it]) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AgentTab.Terminal -> Text("The terminal view arrives in Phase 05. Use Output to read what the agent is doing.", style = PaddockTokens.type.body, color = c.dim)
                AgentTab.Output -> OutputBody(output, nowMillis, following, onBack, onUserScrolledUp, onResumeFollowing)
            }
        }
        if (tab == AgentTab.Output && output is OutputState.Showing) {
            Chip(
                text = if (following) "Following" else "Paused · tap to follow",
                onClick = if (following) null else onResumeFollowing,
                description = if (following) "Following the newest output" else "Output is paused. Tap to follow the newest output",
            )
        }
        KeyStrip()
    }
}

@Composable
private fun OutputBody(
    output: OutputState, nowMillis: Long, following: Boolean,
    onBack: () -> Unit, onUserScrolledUp: () -> Unit, onResumeFollowing: () -> Unit,
) {
    val c = PaddockTokens.colors
    when (output) {
        OutputState.Loading -> Text("Reading output…", style = PaddockTokens.type.body, color = c.dim)
        is OutputState.Unavailable -> Banner("Output is unavailable: ${output.message}")
        OutputState.PaneGone -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This terminal is no longer in the session.", style = PaddockTokens.type.body, color = c.title)
            Text("Back to the list", style = PaddockTokens.type.rowTitle, color = c.accent, modifier = Modifier.clickable(role = Role.Button, onClick = onBack).minimumInteractiveComponentSize())
        }
        is OutputState.Showing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (output.stale) Banner("The last read failed. This is the output from ${AgeText.span(nowMillis - output.readAtMillis)}.")
            Box(Modifier.weight(1f)) { OutputSlab(output.lines, following, onUserScrolledUp) }
        }
    }
}
