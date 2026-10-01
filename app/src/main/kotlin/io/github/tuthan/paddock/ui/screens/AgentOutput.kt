package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.KeyStrip
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.OutputSlab
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SegmentedTabs
import io.github.tuthan.paddock.ui.components.StateDot
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

enum class AgentTab(val label: String) { Output("Output"), Terminal("Terminal") }

/** What the header says about the agent. [observedAtMillis] is when the phone saw this state, null when it never has. */
data class AgentHeader(val title: String, val context: String, val state: StateWord, val observedAtMillis: Long?)

/**
 * One agent: who it is, then a row of chips with the state the phone last saw (and when) and whether the output is
 * following, then the Output tab (the live tail) or the Terminal tab (not built yet). A terminal or agent that has gone
 * replaces everything under the header with a plain statement and the way back.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val gone = output == OutputState.PaneGone || output == OutputState.AgentGone
    Column(modifier.fillMaxSize()) {
        ScreenHeader(header.title, onBack = onBack, subtitle = header.context.ifEmpty { null }, compact = true, backDescription = "Back")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (gone) {
                Gone(output, onBack)
                return@Column
            }
            val observed = header.observedAtMillis?.let { AgeText.observed(nowMillis - it) } ?: "not observed by this phone"
            val tone = when (header.state) { StateWord.Blocked -> c.needsYou; StateWord.Done -> c.done; StateWord.Working -> c.accent; else -> null }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Chip(
                    "${header.state.word} · $observed", tone = tone, description = "${header.state.word}, $observed",
                    leading = { StateDot(header.state) },
                )
                if (tab == AgentTab.Output && output is OutputState.Showing) {
                    if (following) Chip("Following", icon = PaddockIcons.Eye, description = "Following the newest output")
                    else Chip("Paused · tap to follow", icon = PaddockIcons.Pause, tone = c.accent, onClick = onResumeFollowing, description = "Output is paused. Tap to follow the newest output")
                }
            }
            SegmentedTabs(AgentTab.entries.map { it.label }, tab.ordinal, { onTab(AgentTab.entries[it]) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    AgentTab.Terminal -> Text(
                        "The terminal view is not built yet. Output shows what the agent is writing.",
                        style = PaddockTokens.type.body, color = c.dim, modifier = Modifier.padding(top = 4.dp),
                    )
                    AgentTab.Output -> OutputBody(output, nowMillis, following, onUserScrolledUp)
                }
            }
            KeyStrip()
        }
    }
}

@Composable
private fun OutputBody(output: OutputState, nowMillis: Long, following: Boolean, onUserScrolledUp: () -> Unit) {
    val c = PaddockTokens.colors
    when (output) {
        OutputState.Loading -> Text("Reading output…", style = PaddockTokens.type.body, color = c.dim)
        is OutputState.Unavailable -> Banner("Output is unavailable: ${output.message}")
        OutputState.PaneGone, OutputState.AgentGone -> Unit
        is OutputState.Showing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (output.stale) Banner("Last read failed. Showing the output from ${AgeText.span(nowMillis - output.readAtMillis)}.")
            Box(Modifier.weight(1f)) { OutputSlab(output.lines, following, onUserScrolledUp) }
            Note("The last 200 lines, read again every second while following.")
        }
    }
}

@Composable
private fun Gone(output: OutputState, onBack: () -> Unit) {
    val c = PaddockTokens.colors
    val (what, why) = if (output == OutputState.AgentGone) "The agent in this terminal has exited." to "Its terminal is still open in herdr, at a shell prompt."
    else "This terminal is no longer in the session." to "It was closed, or moved to another session."
    Column(Modifier.fillMaxWidth().padding(top = 24.dp).semantics(mergeDescendants = true) { contentDescription = "$what $why" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(what, style = PaddockTokens.type.rowTitle, color = c.title)
        Text(why, style = PaddockTokens.type.secondary, color = c.dim)
    }
    PaddockButton("Back to the herd", onBack, kind = ButtonKind.Ghost, icon = PaddockIcons.Back)
}
