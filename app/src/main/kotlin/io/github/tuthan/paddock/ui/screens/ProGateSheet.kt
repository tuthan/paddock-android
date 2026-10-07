package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.billing.ProCopy
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Dot
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * The Pro gate: shown only when the user chose a Pro capability without Pro and nothing else is in progress (the host decides that
 * with [io.github.tuthan.paddock.billing.ProGate]). The safe choice, Not now, has focus, and the Free path is said in words beside the buy button,
 * not left as a hidden option (decision D6).
 *
 * Where Pro can be bought: the title, then what Pro covers as a list with the chosen capability first ([lines], from
 * [ProCopy.coverageLines]), then the free way to the same outcome ([freePath]), then what stays free, then what the purchase said, then Buy Pro
 * and Not now. In the free version ([canBuy] false) nothing can be bought, so the sheet is short: the title, the free path, where Pro comes from
 * ([ProCopy.FREE_VERSION_ROUTE]) and Not now; a list of what a purchase would bring is not shown where no purchase exists. The sheet scrolls, so
 * at a large font both buttons stay reachable.
 *
 * The overview ([overview], Settings' "What Pro covers" row, decision D5) answers "what is Pro" rather than a tap on a locked control, so the list is
 * the point: it is shown in both versions, no line is set apart as chosen, and what stays free follows it. The rest is as above: Buy Pro where Pro
 * is sold, where Pro comes from in the free version, Not now. It is the same sheet, so it is still the only place Pro is bought.
 */
@Composable
fun ProGateSheet(
    /** What Pro covers, one capability a line, the chosen one first ([ProCopy.coverageLines]). Read by TalkBack as one block. */
    lines: List<String>,
    /** The one sentence naming the Free way to what the user chose ([io.github.tuthan.paddock.billing.ProCapabilities.freePath]); null leaves the line out. */
    freePath: String?,
    price: String?,
    busy: Boolean,
    /** What the purchase started from this sheet said, and nothing else: a Restore sentence from Settings would read as being about this purchase. */
    message: String?,
    onBuy: () -> Unit,
    onNotNow: () -> Unit,
    /** False in the free version (the foss build): nothing can be bought there, so the sheet says where Pro comes from instead of offering a button. */
    canBuy: Boolean = true,
    /** Names the capability the user chose ([ProCopy.gateTitle]). */
    title: String = "This is a Pro capability",
    /** Opened as the overview of Pro ([ProCopy.OVERVIEW_ID]): the list in both versions, no chosen line, and what stays free. */
    overview: Boolean = false,
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onNotNow,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = PaddockTokens.type.headerTitle, color = c.title)
            if (canBuy) {
                if (lines.isNotEmpty()) CoverageList(lines, chosenFirst = !overview)
                if (freePath != null) Text(freePath, style = PaddockTokens.type.secondary, color = c.dim)
                Text(ProCopy.STAYS_FREE, style = PaddockTokens.type.secondary, color = c.dim)
                if (message != null) Text(message, style = PaddockTokens.type.secondary, color = c.dim)
                PaddockButton(if (price != null) "Buy Pro · $price" else "Buy Pro", onBuy, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy)
            } else {
                if (overview && lines.isNotEmpty()) CoverageList(lines, chosenFirst = false)
                if (freePath != null) Text(freePath, style = PaddockTokens.type.secondary, color = c.dim)
                if (overview) Text(ProCopy.STAYS_FREE, style = PaddockTokens.type.secondary, color = c.dim)
                Text(ProCopy.FREE_VERSION_ROUTE, style = PaddockTokens.type.secondary, color = c.dim)
            }
            PaddockButton("Not now", onNotNow, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/**
 * "Pro covers:" and one line per capability, each after a 4 dp dot set on its first line. The first line, the capability the user chose, is in
 * the title colour and the rest in the text colour; the order says the same without the colour. With [chosenFirst] false (the overview, where
 * nothing was chosen) every line is in the text colour. TalkBack reads the block once, as "Pro covers: a, b, c", instead of stopping on every line.
 */
@Composable
private fun CoverageList(lines: List<String>, chosenFirst: Boolean = true) {
    val c = PaddockTokens.colors
    val density = LocalDensity.current
    val style = PaddockTokens.type.secondary
    // The dot sits on the middle of the line's first row: the row is the style's line height, and `Dot` grows with the font up to 1.5x.
    val dot = 4.dp * density.fontScale.coerceIn(1f, 1.5f)
    val rowHeight = with(density) { style.lineHeight.toDp() }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${ProCopy.COVERS} ${lines.joinToString(", ")}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(ProCopy.COVERS, style = style, color = c.dim)
        lines.forEachIndexed { i, line ->
            val color = if (i == 0 && chosenFirst) c.title else c.text
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(color, Modifier.padding(top = ((rowHeight - dot) / 2).coerceAtLeast(0.dp)), size = 4.dp)
                Text(line, style = style, color = color, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * What a deferred tap on a Pro control says in place of the sheet ([io.github.tuthan.paddock.billing.ProGate.deferNotice]): the app's notice bar,
 * announced politely so TalkBack reads it when it appears (the notice bar's sentence is the live region, so nothing is added here and it is not
 * announced twice). It is never the gate: no buy button, nothing to decide.
 */
@Composable
fun GateNoticeBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) =
    io.github.tuthan.paddock.ui.components.NoticeBar(text, onDismiss, modifier)
