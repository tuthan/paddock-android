package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * The Pro gate: shown only when the user chose a Pro capability without Pro and nothing else is in progress (the host decides that
 * with [io.github.tuthan.paddock.billing.ProGate]). The safe choice, Not now, has focus, and the Free path is said in words beside the buy button:
 * what stays free is the paragraph under the title, not a hidden option.
 */
@Composable
fun ProGateSheet(
    coverage: String,
    price: String?,
    busy: Boolean,
    /** What the purchase started from this sheet said, and nothing else: a Restore sentence from Settings would read as being about this purchase. */
    message: String?,
    onBuy: () -> Unit,
    onNotNow: () -> Unit,
    /** False in the free version (the foss build): nothing can be bought there, so the sheet says where Pro comes from instead of offering a button. */
    canBuy: Boolean = true,
    /** Names the capability the user chose ([io.github.tuthan.paddock.billing.ProCopy.gateTitle]). */
    title: String = "This is a Pro capability",
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onNotNow,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = PaddockTokens.type.headerTitle, color = c.title)
            Text(coverage, style = PaddockTokens.type.secondary, color = c.text)
            if (!canBuy) Text(io.github.tuthan.paddock.billing.ProCopy.FREE_VERSION_ROUTE, style = PaddockTokens.type.secondary, color = c.dim)
            if (message != null) Text(message, style = PaddockTokens.type.secondary, color = c.dim)
            if (canBuy) PaddockButton(if (price != null) "Buy Pro · $price" else "Buy Pro", onBuy, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !busy)
            PaddockButton("Not now", onNotNow, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/**
 * What a deferred tap on a Pro control says in place of the sheet ([io.github.tuthan.paddock.billing.ProGate.deferNotice]): the app's notice bar,
 * announced politely so TalkBack reads it when it appears. It is never the gate: no buy button, nothing to decide.
 */
@Composable
fun GateNoticeBar(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) =
    io.github.tuthan.paddock.ui.components.NoticeBar(text, onDismiss, modifier.semantics { liveRegion = LiveRegionMode.Polite })
