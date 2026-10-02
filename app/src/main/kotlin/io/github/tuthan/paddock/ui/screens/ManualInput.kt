package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val MANUAL_INPUT_FACT = "Esc and Ctrl+C reach the agent only in Manual input. It reads the agent first, and each key is one recorded operation."
const val FOCUS_QUESTION = "Focus this agent on the desktop?"
const val FOCUS_FACT = "This moves herdr's cursor on the desktop to this agent. If the agent had finished, herdr also marks that completion as seen on the machine, " +
    "so it stops asking for attention there. It changes nothing in the agent itself. Paddock asks this once."

const val MANUAL_KEYS_FACT = "Each key is sent once and recorded. herdr accepting a key does not say what the agent did with it."

/**
 * What the Output tab shows for Manual input. [gate] is null while the mode is off; once the user has chosen it, it says
 * whether the keys may be sent now (closed, with the sentence, while the fresh read is still coming or when the link or the
 * agent is gone). [running] is a call from this phone still on its way; [readAtMillis] is the read the mode started with;
 * [outcome] is how the last operation on this terminal ended. [focus] is the desktop-focus button's gate, or null when the
 * button is not offered. [unknown] is the journal's line for a row that still waits for a re-read (it survives a restart,
 * unlike [outcome]); [rereadLines] is what the last re-read found and [rereadFailure] why one did nothing.
 */
data class ManualInputUi(
    val gate: OperationGate?,
    val running: Boolean = false,
    val readAtMillis: Long? = null,
    val outcome: ResultLine? = null,
    val focus: OperationGate? = null,
    val unknown: String? = null,
    val rereadLines: List<String> = emptyList(),
    val rereadFailure: String? = null,
) {
    val active: Boolean get() = gate != null
}

class ManualInputActions(
    val onEnter: () -> Unit,
    val onLeave: () -> Unit,
    val onKey: (OperationKind) -> Unit,
    val onDismissOutcome: () -> Unit,
    val onOpenTerminal: () -> Unit,
    val onReread: (() -> Unit)? = null,
    val onFocus: () -> Unit = {},
    val onDismissReread: () -> Unit = {},
)

/**
 * Esc and Ctrl+C, kept behind a choice. Off, the tab shows one button and the rule. On, the mode opens with the fresh read
 * (the keys stay off until it arrives), then Esc and Ctrl+C each send one recorded key. A refused or unknown key is shown
 * with the same outcome line as a prompt, and an unknown one offers a re-read and never a resend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualInput(ui: ManualInputUi, nowMillis: Long, actions: ManualInputActions, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    // The unknown row says "re-read before sending again" itself, with the button; the gates need not say it a second and third time.
    fun OperationGate.Closed?.unlessExplainedAbove() = this?.takeUnless { ui.unknown != null && it.block == SendBlock.NeedsReread }
    val closed = (ui.gate as? OperationGate.Closed).unlessExplainedAbove()
    val focusClosed = (ui.focus as? OperationGate.Closed).unlessExplainedAbove()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.unknown?.let { line ->
            // Never a resend: the only action is to read the agent again, and only the user can decide what to do after it.
            Banner(line, actionLabel = if (ui.running || actions.onReread == null) null else "Re-read", onAction = { actions.onReread?.invoke() })
        }
        if (ui.rereadLines.isNotEmpty()) RereadReport(ui.rereadLines, actions.onDismissReread)
        ui.rereadFailure?.let { Banner(it, actionLabel = "Dismiss", onAction = actions.onDismissReread) }
        if (!ui.active || ui.focus != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.active) PaddockButton("Manual input", actions.onEnter, kind = ButtonKind.Ghost, small = true, fillWidth = false)
                if (ui.focus != null) PaddockButton("Focus on desktop", actions.onFocus, kind = ButtonKind.Ghost, small = true, fillWidth = false, enabled = ui.focus is OperationGate.Open && !ui.running)
            }
        }
        if (focusClosed != null) {
            Text(focusClosed.sentence, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { contentDescription = "Focus on desktop is off. ${focusClosed.sentence}" })
        }
        if (!ui.active) {
            Note(MANUAL_INPUT_FACT)
        } else {
            Column(Modifier.card(c).semantics { contentDescription = "Manual input is on" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kicker("Manual input · on", Modifier.weight(1f))
                    PaddockButton("Done", actions.onLeave, kind = ButtonKind.Ghost, small = true, fillWidth = false)
                }
                val reading = (ui.gate as? OperationGate.Closed)?.block == SendBlock.Reading
                Text(
                    if (reading || ui.readAtMillis == null) "Reading the agent's state…" else "Read ${AgeText.span(nowMillis - ui.readAtMillis)}",
                    style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val open = ui.gate is OperationGate.Open && !ui.running
                ButtonPair(
                    first = { m -> PaddockButton("Esc", { actions.onKey(OperationKind.Esc) }, m, kind = ButtonKind.Danger, enabled = open, small = true) },
                    second = { m -> PaddockButton("Ctrl+C", { actions.onKey(OperationKind.CtrlC) }, m, kind = ButtonKind.Danger, enabled = open, small = true) },
                )
                if (closed != null && !reading) {
                    Text(closed.sentence, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { contentDescription = "Keys are off. ${closed.sentence}" })
                    if (closed.block == SendBlock.Stale) PaddockButton("Re-read", actions.onEnter, kind = ButtonKind.Ghost, small = true, fillWidth = false)
                }
                Note(MANUAL_KEYS_FACT)
            }
        }
        ui.outcome?.let { OutcomeLine(it, actions.onOpenTerminal, actions.onDismissOutcome, actions.onReread) }
    }
}

/** What a re-read found: plain lines, no verdict, and a way to put them away. Announced politely when it appears. */
@Composable
internal fun RereadReport(lines: List<String>, onDismiss: () -> Unit) {
    val c = PaddockTokens.colors
    Column(Modifier.fillMaxWidth().card(c).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { Text(it, style = PaddockTokens.type.secondary, color = c.text) }
        PaddockButton("Dismiss", onDismiss, kind = ButtonKind.Ghost, small = true, fillWidth = false)
    }
}

/**
 * The desktop-focus button's first-time confirmation. [content] is given the action to run when the user taps Focus: until
 * [confirmed] it asks first and says what focus does (it moves herdr's cursor on the desktop and marks a completion seen on
 * the machine); agreeing is remembered through [onConfirmed] and the focus goes ahead; once confirmed a tap goes straight
 * through. Cancelling does nothing and remembers nothing.
 */
@Composable
fun FocusGuard(confirmed: Boolean, onConfirmed: () -> Unit, focus: () -> Unit, content: @Composable (requestFocus: () -> Unit) -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    content { if (confirmed) focus() else asking = true }
    if (asking) {
        ConfirmDialog(
            title = FOCUS_QUESTION, body = FOCUS_FACT, confirm = "Focus on desktop",
            onConfirm = { asking = false; onConfirmed(); focus() }, onCancel = { asking = false },
        )
    }
}
