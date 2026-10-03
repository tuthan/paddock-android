package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.KeyStrip
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.OutputSlab
import io.github.tuthan.paddock.ui.components.PaddockIconButton
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SegmentedTabs
import io.github.tuthan.paddock.ui.components.StateDot
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

enum class AgentTab(val label: String) { Output("Output"), Terminal("Terminal") }

/**
 * Whether the Output tab's feed may poll: the screen is in the foreground and Output is the tab shown. The Terminal tab streams
 * the terminal itself; a hidden Output feed would go on opening an SSH command and parsing a 200-line capture every second
 * beside it, for text nobody can see.
 */
fun outputFeedVisible(resumed: Boolean, tab: AgentTab): Boolean = resumed && tab == AgentTab.Output

/**
 * What the header says about the agent. [observedAtMillis] is when the phone saw this state, null when it never has.
 * [agentKind] is the agent's own name ("claude"), for the composer's "Ask claude…".
 */
data class AgentHeader(val title: String, val context: String, val state: StateWord, val observedAtMillis: Long?, val agentKind: String? = null)

/** The state the phone last saw and when: "Blocked · observed 40 s ago". Shared by the Output and Compose screens. */
@Composable
fun StateChip(header: AgentHeader, nowMillis: Long) {
    val c = PaddockTokens.colors
    val observed = header.observedAtMillis?.let { AgeText.observed(nowMillis - it) } ?: "not observed by this phone"
    val tone = when (header.state) { StateWord.Blocked -> c.needsYou; StateWord.Done -> c.done; StateWord.Working -> c.accent; else -> null }
    Chip("${header.state.word} · $observed", tone = tone, description = "${header.state.word}, $observed", leading = { StateDot(header.state) })
}

/**
 * One agent: who it is, then a row of chips with the state the phone last saw (and when) and whether the output is
 * following, then the Output tab (the live tail) or the Terminal tab (the live screen, read-only until control is requested). A terminal or agent that has gone
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
    /** The Terminal tab's content. It owns the keys, so nothing is drawn under it. */
    terminal: (@Composable () -> Unit)? = null,
    /** Opens the composer; null (no journal, so no operations) leaves the entry out. */
    onCompose: (() -> Unit)? = null,
    /** Manual input (Esc and Ctrl+C) and what it does; without both the tab keeps the inert key strip and its note. */
    manualInput: ManualInputUi? = null,
    manualActions: ManualInputActions? = null,
    /** The newest permission request the hook published for this blocked agent (Phase 8); its entry sits above the output and opens the sheet. */
    decision: DecisionEntryModel? = null,
    onOpenDecision: (() -> Unit)? = null,
    /**
     * The Terminal tab with the Android keyboard up: the title shrinks to one line and the state chip and the tabs go, so the
     * terminal gets the room (Hide keyboard brings them back). Follows the keyboard itself; a test sets it.
     */
    terminalFocus: Boolean = tab == AgentTab.Terminal && terminal != null && WindowInsets.isImeVisible,
) {
    val c = PaddockTokens.colors
    val gone = output == OutputState.PaneGone || output == OutputState.AgentGone
    val focus = terminalFocus && tab == AgentTab.Terminal && !gone
    Column(modifier.fillMaxSize()) {
        if (focus) TerminalFocusBar(header, onBack)
        else ScreenHeader(header.title, onBack = onBack, subtitle = header.context.ifEmpty { null }, compact = true, backDescription = "Back")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = if (focus) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (focus) 6.dp else 10.dp),
        ) {
            if (gone) {
                Gone(output, onBack)
                return@Column
            }
            if (!focus) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    StateChip(header, nowMillis)
                    if (tab == AgentTab.Output && output is OutputState.Showing) {
                        if (following) Chip("Following", icon = PaddockIcons.Eye, description = "Following the newest output")
                        else Chip("Paused · tap to follow", icon = PaddockIcons.Pause, tone = c.accent, onClick = onResumeFollowing, description = "Output is paused. Tap to follow the newest output")
                    }
                }
                SegmentedTabs(AgentTab.entries.map { it.label }, tab.ordinal, { onTab(AgentTab.entries[it]) })
            }
            if (tab == AgentTab.Output && decision != null && onOpenDecision != null && header.state == StateWord.Blocked) {
                DecisionEntry(decision, onOpenDecision)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    AgentTab.Terminal -> terminal?.invoke() ?: Text(
                        "The terminal is not available here. Output shows what the agent is writing.",
                        style = PaddockTokens.type.body, color = c.dim, modifier = Modifier.padding(top = 4.dp),
                    )
                    AgentTab.Output -> OutputBody(output, nowMillis, following, onUserScrolledUp)
                }
            }
            if (tab == AgentTab.Output) {
                if (onCompose != null) PromptEntry(header.agentKind, onCompose)
                if (manualInput != null && manualActions != null) {
                    // At most 40% of the window, scrolling inside it: a large font or a long sentence must not squeeze the output out.
                    val density = LocalDensity.current
                    val cap = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.4f
                    Column(Modifier.heightIn(max = cap).verticalScroll(rememberScrollState())) { ManualInput(manualInput, nowMillis, manualActions) }
                } else KeyStrip()
            }
        }
    }
}

/**
 * The agent screen's top while the keyboard shares the Terminal tab: back, the title on one line, and the state as a dot and a
 * word. The title stays, because it says which terminal the keys go to; the context line, the age of the observation and the tabs
 * are what Hide keyboard brings back.
 */
@Composable
private fun TerminalFocusBar(header: AgentHeader, onBack: () -> Unit) {
    val c = PaddockTokens.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = PaddockTokens.spacing.gutter),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PaddockIconButton(PaddockIcons.Back, "Back", onBack)
        Text(
            header.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = header.state.word },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StateDot(header.state)
            Text(header.state.word, style = PaddockTokens.type.chip, color = c.dim, maxLines = 1)
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
