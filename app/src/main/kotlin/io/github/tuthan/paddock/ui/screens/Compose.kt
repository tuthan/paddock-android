package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ops.SendGate
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val ESC_OFF_NOTE = "Esc is available in Manual input, on the agent screen."
/** Shown while Manual input is off and Esc is live: the first tap turns the mode on, it sends nothing. */
const val ESC_ENTERS_MANUAL_NOTE = "Esc needs Manual input. Tap Esc to turn it on: Paddock reads the agent first, then tap Esc again to send it. Each Esc is one recorded key."

const val COMPOSER_FACT =
    "Sent as one submission, Enter included. Line breaks travel inside the text; an agent that does not accept pasted text may take one as Enter. " +
        "herdr refuses a blocked agent, and an accepted send is not a receipt for any turn."

/** What the composer shows besides what the user typed: the send gate, a send under way, the last outcome, and the snippets. */
data class ComposerUi(
    val header: AgentHeader,
    val gate: SendGate,
    val sending: Boolean = false,
    val outcome: ResultLine? = null,
    val snippets: List<String> = emptyList(),
    /** What the last re-read found, as lines; empty when there is none to show. */
    val rereadLines: List<String> = emptyList(),
)

/** The row at the foot of the Output tab that opens the composer. It looks like a field and is one button. */
@Composable
fun PromptEntry(agentKind: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.field)
    val label = "Ask ${agentKind ?: "the agent"}…"
    Row(
        modifier.fillMaxWidth().heightIn(min = PaddockTokens.spacing.touchTarget).clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape)
            .clickable(role = Role.Button, onClickLabel = "Write a prompt", onClick = onClick)
            .padding(horizontal = 14.dp).semantics(mergeDescendants = true) { contentDescription = "Write a prompt for ${agentKind ?: "the agent"}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = PaddockTokens.type.body, color = c.dim, modifier = Modifier.weight(1f))
        Icon(PaddockIcons.Send, contentDescription = null, tint = c.dim, modifier = Modifier.size(20.dp))
    }
}

/**
 * The prompt composer: a multi-line field, the user's snippets as chips, Esc kept apart from Send, and under them the
 * one fact that matters (one submission, Enter included, refused while blocked). Send is open only for [SendGate.Open];
 * otherwise the sentence that says which condition failed sits right under the buttons. A send that was refused keeps
 * the text, so nothing the user typed is lost; one that went out clears nothing here, the route does that.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    ui: ComposerUi,
    nowMillis: Long,
    text: String,
    onText: (String) -> Unit,
    onSnippet: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onEditSnippets: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDismissOutcome: () -> Unit,
    modifier: Modifier = Modifier,
    /** Esc is live only in Manual input mode, or offers to turn it on; [escNote], when there is one, says which and why. */
    escEnabled: Boolean = false,
    escNote: String? = ESC_OFF_NOTE,
    onEsc: () -> Unit = {},
    onReread: (() -> Unit)? = null,
    onDismissReread: () -> Unit = {},
    /** An action that clears the reason Send is off (re-read after a reconnect, or after an unknown outcome). */
    gateActionLabel: String? = null,
    onGateAction: () -> Unit = {},
    /** An extra control in the header, such as the desktop-focus button. */
    headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val c = PaddockTokens.colors
    val open = ui.gate is SendGate.Open
    val closed = ui.gate as? SendGate.Closed
    Column(modifier.fillMaxSize()) {
        ScreenHeader(ui.header.title, onBack = onBack, subtitle = ui.header.context.ifEmpty { null }, compact = true, backDescription = "Back", actions = headerActions)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StateChip(ui.header, nowMillis)
            Field(
                "Prompt", text, onText, singleLine = false, imeAction = ImeAction.Default,
                placeholder = "Ask ${ui.header.agentKind ?: "the agent"}…",
            )
            if (ui.snippets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.snippets.forEach { s -> Chip(s.abbreviated(), onClick = { onSnippet(s) }, description = "Insert snippet: $s") }
                }
            }
            ButtonPair(
                first = { m -> PaddockButton("Esc · Interrupt", onEsc, m, kind = ButtonKind.Danger, enabled = escEnabled, small = true) },
                second = { m ->
                    PaddockButton(if (ui.sending) "Sending…" else "Send prompt", onSend, m, enabled = open && !ui.sending, icon = PaddockIcons.Send)
                },
            )
            // An unknown outcome is explained once, by its own row with the Re-read next to it; the gate repeats it only when there is no such row (after a restart).
            val explainedBelow = closed?.block == SendBlock.NeedsReread && ui.outcome?.unknown == true && onReread != null
            if (closed != null && closed.block != SendBlock.EmptyPrompt && !explainedBelow) {
                Text(closed.sentence, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { contentDescription = "Send is off. ${closed.sentence}" })
            } else if (ui.gate is SendGate.Open && ui.gate.hintsUnreported) {
                Note("Ready by herdr's status. This agent reports no readiness hint, so Paddock checks the status again right before it sends.")
            }
            if (closed != null && gateActionLabel != null && !explainedBelow) PaddockButton(gateActionLabel, onGateAction, kind = ButtonKind.Ghost, small = true, fillWidth = false)
            if (escNote != null) Note(escNote)
            ui.outcome?.let { OutcomeLine(it, onOpenTerminal, onDismissOutcome, onReread) }
            if (ui.rereadLines.isNotEmpty()) RereadReport(ui.rereadLines, onDismissReread)
            Note(COMPOSER_FACT)
            Kicker("Snippets are yours", Modifier.padding(top = 6.dp))
            Row(
                Modifier.fillMaxWidth().card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = "Edit snippets", onClick = onEditSnippets)
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Edit snippets · ${ui.snippets.size} saved · synced nowhere, kept on this phone", style = PaddockTokens.type.secondary, color = c.title, modifier = Modifier.weight(1f))
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** How the last operation ended, announced politely when it appears. Unknown offers a re-read and never a resend. */
@Composable
internal fun OutcomeLine(line: ResultLine, onOpenTerminal: () -> Unit, onDismiss: () -> Unit, onReread: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            line.tone == ResultTone.Ok -> Note(line.text, icon = PaddockIcons.Eye)
            line.unknown && onReread != null -> Banner(line.text, actionLabel = "Re-read", onAction = onReread)
            line.opensTerminal -> Banner(line.text, actionLabel = "Open terminal", onAction = onOpenTerminal)
            else -> Banner(line.text)
        }
        if (!line.unknown) PaddockButton("Dismiss", onDismiss, kind = ButtonKind.Secondary, small = true, fillWidth = false)
    }
}

private fun String.abbreviated(max: Int = 28): String {
    val oneLine = replace(Regex("\\s+"), " ")
    return if (oneLine.length <= max) oneLine else oneLine.take(max - 1).trimEnd() + "…"
}
