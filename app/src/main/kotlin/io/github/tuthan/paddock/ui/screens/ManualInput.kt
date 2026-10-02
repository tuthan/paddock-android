package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.ops.KeyGate
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val MANUAL_INPUT_FACT = "Esc and Ctrl+C reach the agent only in Manual input. It reads the agent first, and each key is one recorded operation."
const val MANUAL_KEYS_FACT = "Each key is sent once and recorded. herdr accepting a key does not say what the agent did with it."

/**
 * What the Output tab shows for Manual input. [gate] is null while the mode is off; once the user has chosen it, it says
 * whether the keys may be sent now (closed, with the sentence, while the fresh read is still coming or when the link or the
 * agent is gone). [running] is a call from this phone still on its way; [readAtMillis] is the read the mode started with;
 * [outcome] is how the last operation on this terminal ended.
 */
data class ManualInputUi(
    val gate: KeyGate?,
    val running: Boolean = false,
    val readAtMillis: Long? = null,
    val outcome: ResultLine? = null,
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
)

/**
 * Esc and Ctrl+C, kept behind a choice. Off, the tab shows one button and the rule. On, the mode opens with the fresh read
 * (the keys stay off until it arrives), then Esc and Ctrl+C each send one recorded key. A refused or unknown key is shown
 * with the same outcome line as a prompt, and an unknown one offers a re-read and never a resend.
 */
@Composable
fun ManualInput(ui: ManualInputUi, nowMillis: Long, actions: ManualInputActions, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val closed = ui.gate as? KeyGate.Closed
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.active) {
            PaddockButton("Manual input", actions.onEnter, kind = ButtonKind.Ghost, small = true, fillWidth = false)
            Note(MANUAL_INPUT_FACT)
        } else {
            Column(Modifier.card(c).semantics { contentDescription = "Manual input is on" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kicker("Manual input · on", Modifier.weight(1f))
                    PaddockButton("Done", actions.onLeave, kind = ButtonKind.Ghost, small = true, fillWidth = false)
                }
                val reading = closed?.block == SendBlock.Reading
                Text(
                    if (reading || ui.readAtMillis == null) "Reading the agent's state…" else "Read ${AgeText.span(nowMillis - ui.readAtMillis)}",
                    style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val open = ui.gate is KeyGate.Open && !ui.running
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
