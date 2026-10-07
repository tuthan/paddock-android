package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ports.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything Pro does between the screens and the store (Phase 13): one store action at a time ([ProSession]), nothing a store call throws reaches the
 * process, prices only when shown, and the gate: whether a control may run, the sheet that asks for Pro, and the one sentence a deferred tap shows.
 * [unlocked] is the build's flag (the foss build carries every capability).
 */
class ProService(
    private val entitlements: Entitlements,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val manualInput: ManualInputMode,
    private val journal: OperationJournal,
    unlocked: Boolean,
) {
    private val session = ProSession(entitlements, scope, clock, unlocked)

    /** What Settings and the Pro gate show about Pro: the saved state first, then whatever the store said. */
    val view: StateFlow<ProView> = session.view

    private val _gateRequest = MutableStateFlow<String?>(null)
    /**
     * The Pro capability the user just chose without having Pro, or [ProCopy.OVERVIEW_ID] for Settings' overview; the gate sheet shows while it is
     * set and the user is idle.
     */
    val gateRequest: StateFlow<String?> = _gateRequest.asStateFlow()

    private val gateNoticeHolder = GateNotice(scope)
    /** Why the last tap on a Pro control did nothing (a deferral, see [ProGate.deferNotice]); the root shows it as a notice, and it goes by itself after [GateNotice.SHOW_MILLIS]. */
    val gateNotice: StateFlow<String?> = gateNoticeHolder.text

    /** Loads the saved state, verifies once and follows the store and [foreground]; whatever an earlier purchase said is not about this opening of the gate sheet. */
    fun start(foreground: Flow<Boolean>) {
        session.start(foreground)
        // The store's prices are asked for the first time the sheet is shown.
        scope.launch { _gateRequest.collect { request -> session.gateChanged(opened = request != null) } }
    }

    /** Whether this phone holds Pro by the same rule the widgets use ([Entitlements.hasPro]). */
    fun hasPro(current: ProView = view.value): Boolean = entitlements.hasPro(current.state)

    /** Asks the store what the account owns (app start, a store change, a return to the foreground); waits behind a store action in flight. */
    suspend fun verify() = session.verify()

    fun restore() = session.restore()

    /** The store's prices, asked for the first time Settings' Pro card or the gate sheet is shown (a no-op after that and in a build without a store). */
    fun loadPrices() = session.loadPrices()

    fun buyTip(productId: String) = session.buyTip(productId)

    fun buyPro() = session.buyPro { _gateRequest.value = null }

    /**
     * The user chose a capability. Returns whether it may run now; when it is a Pro one without Pro and the user is idle, the gate
     * sheet is asked for instead. [pendingAnswerOnScreen] is the one fact only the screen knows: the decision sheet or guarded answers is up.
     */
    fun requestCapability(capabilityId: String, pendingAnswerOnScreen: Boolean): Boolean {
        val context = gateContext(pendingAnswerOnScreen)
        val decision = ProGate.decide(capabilityId, hasPro(), context)
        decided(capabilityId, decision, context)
        return decision == GateDecision.PROCEED
    }

    /**
     * Settings' "What Pro covers" row (decision D5): the gate sheet as an overview of Pro, asked for as [ProCopy.OVERVIEW_ID]. It is not a capability
     * ([ProGate.decide] would let it through), so this decides by the same rule a gated capability gets from an idle app: idle opens the sheet, busy
     * defers it and the notice says why, as a deferred capability does. Whether Pro is held is the host's to check when it draws, as for any request.
     * The sheet stays the only place Pro is bought; Settings only opens it.
     */
    fun openProOverview() {
        val context = gateContext(pendingAnswerOnScreen = false)
        decided(ProCopy.OVERVIEW_ID, if (context == GateContext.IDLE) GateDecision.SHOW_GATE else GateDecision.DEFER, context)
    }

    /** What a decision does besides its answer: the notice, and the sheet request for [SHOW_GATE][GateDecision.SHOW_GATE]. */
    private fun decided(requestId: String, decision: GateDecision, context: GateContext) {
        // A deferral shows no sheet, but a tap that does nothing and says nothing reads as a broken app: the notice says why, in one sentence.
        gateNoticeHolder.decided(decision, context)
        if (decision == GateDecision.SHOW_GATE) _gateRequest.value = requestId
    }

    fun dismissGate() { _gateRequest.value = null }

    fun dismissGateNotice() = gateNoticeHolder.dismiss()

    /** Whether a control for [capability] carries its Pro label and does not run: gated, and neither held nor unlocked by the build. A screen passes the [view] value it collected, so it follows a purchase. */
    fun locked(capability: ProCapability, current: ProView = view.value): Boolean = ProGate.locked(capability.id, hasPro(current))

    /** What the user is in the middle of; the rule, including which journal rows still count as running, is [ProGate.context]. */
    fun gateContext(pendingAnswerOnScreen: Boolean): GateContext =
        ProGate.context(pendingAnswerOnScreen, manualInput.current.value != null, journal.records.value, clock.nowMillis())
}
