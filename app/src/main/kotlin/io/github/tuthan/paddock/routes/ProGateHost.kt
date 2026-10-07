package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.ui.screens.ProGateSheet
import io.github.tuthan.paddock.ui.screens.Settings
import kotlinx.coroutines.flow.first

/**
 * Shows the Pro gate when, and only when, the user chose a Pro capability without Pro and is idle. If the user has since become busy
 * (a pending answer on screen, Manual input, an operation in flight) or bought Pro, the request is dropped without a word.
 *
 * Settings' overview ([io.github.tuthan.paddock.billing.ProCopy.OVERVIEW_ID], D5) is not a capability, so `ProGate.decide` would let it through; it is
 * shown by the same rule instead (idle and Pro not held), titled "What Pro covers", with every line and no capability first and no free path.
 * Internal, like [SettingsRoute], so `SettingsTest` can open the overview through the app's real graph in each build.
 */
@Composable
internal fun ProGateHost(graph: AppGraph, pendingAnswerOnScreen: Boolean) {
    val request by graph.proService.gateRequest.collectAsState()
    val pro by graph.proService.view.collectAsState()
    // Read so the sheet reacts the moment either changes; the decision itself reads them again through gateContext.
    val manual by graph.manualInput.current.collectAsState()
    val records by graph.journal.records.collectAsState()
    val capability = request ?: return
    val context = graph.proService.gateContext(pendingAnswerOnScreen)
    val hasPro = graph.entitlements.hasPro(pro.state)
    val overview = capability == io.github.tuthan.paddock.billing.ProCopy.OVERVIEW_ID
    val show = if (overview) context == GateContext.IDLE && !hasPro
        else io.github.tuthan.paddock.billing.ProGate.decide(capability, hasPro, context) == io.github.tuthan.paddock.billing.GateDecision.SHOW_GATE
    if (!show) {
        androidx.compose.runtime.LaunchedEffect(capability, context, pro.state, manual, records) { graph.proService.dismissGate() }
        return
    }
    ProGateSheet(
        title = io.github.tuthan.paddock.billing.ProCopy.gateTitle(capability),
        // D6: the list starts with what the tap was about, and the sheet names the Free way to the same outcome. The overview chose nothing.
        lines = io.github.tuthan.paddock.billing.ProCopy.coverageLines(io.github.tuthan.paddock.billing.ProGate.GATED, first = capability.takeUnless { overview }),
        freePath = if (overview) null else io.github.tuthan.paddock.billing.ProCapabilities.freePath(capability),
        price = pro.prices[io.github.tuthan.paddock.billing.Products.PRO], busy = pro.busy, message = pro.gateMessage, canBuy = pro.sellsPro,
        onBuy = { graph.proService.buyPro() }, onNotNow = { graph.proService.dismissGate() }, overview = overview,
    )
}
