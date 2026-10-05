package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pro as the screens see it. [unlocked] is the build (the foss build), [sellsPro] whether this build can sell it; [state] is the last verified answer.
 * [message] is what the last Settings action (Restore, a tip, a verification) said and is shown on the Pro card only; [gateMessage] is what a purchase
 * started from the gate sheet said and is shown in the sheet only (review F10), so a Restore sentence never appears under the gate's title as if it
 * were about this purchase.
 */
data class ProView(
    val state: EntitlementState = EntitlementState(),
    val unlocked: Boolean = false,
    val sellsPro: Boolean = false,
    val reachable: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val gateMessage: String? = null,
    val prices: Map<String, String> = emptyMap(),
) {
    /** A store action starts: busy, and the sentence the last one left on the surface this one writes to is gone. */
    fun begun(forGate: Boolean): ProView = if (forGate) copy(busy = true, gateMessage = null) else copy(busy = true, message = null)

    /** [text] on the surface of the action that produced it. */
    fun said(forGate: Boolean, text: String?): ProView = if (forGate) copy(gateMessage = text) else copy(message = text)

    /**
     * A purchase from the gate ended. Its sentence is the gate's; the Settings card keeps only the success, where its headline now says Pro (a
     * failure or a pending order would be a stale purchase sentence next to an unrelated Restore).
     */
    fun afterPurchase(result: BuyResult): ProView {
        val said = ProMessages.purchase(result)
        val next = when (result) { is BuyResult.Done -> copy(state = result.state); is BuyResult.Pending -> copy(state = result.state); else -> this }
        return next.copy(gateMessage = said, message = if (result is BuyResult.Done) said else next.message)
    }
}

/**
 * What the app does about Pro between the screens and [Entitlements]: the view the screens read, and every store interaction that changes it. It
 * lives here, not in the app's graph, so that what it promises can be tested against a fake store.
 *
 * - One store action at a time ([StoreActions], review F9): Buy, Tip and Restore the user starts are refused while another runs; the app's own
 *   verifications wait their turn. `busy` is true exactly while one runs.
 * - Nothing a store call throws reaches the process ([storeGuarded], F2): it ends as a sentence on the surface the action belongs to.
 * - A purchase the store reports that nobody was waiting for is verified at once, and a return to the foreground verifies again once the last
 *   attempt is [Entitlements.REVERIFY_AFTER_MILLIS] old, so a pending purchase that completed meanwhile is acknowledged inside Play's three days (F4).
 * - Prices are asked for only when a screen that shows them is, not at start (F14).
 *
 * Every launch here is guarded, because the graph's scope has no exception handler and an uncaught throw in it ends the process.
 */
class ProSession(
    private val entitlements: Entitlements,
    private val scope: CoroutineScope,
    private val clock: Clock,
    unlocked: Boolean,
) {
    private val _view = MutableStateFlow(ProView(unlocked = unlocked, sellsPro = entitlements.sellsPro))
    val view: StateFlow<ProView> = _view.asStateFlow()

    private val storeActions = StoreActions()

    /** When this process last asked the store what the account owns: an attempt, not a success, so an unreachable store is not asked again on every return. */
    @Volatile private var lastVerifyAttemptMillis: Long? = null
    private val pricesLoading = AtomicBoolean(false)

    /** Loads the saved state, verifies once, and in a build that sells Pro follows the store's own changes and the app's [foreground]. */
    fun start(foreground: Flow<Boolean>) {
        scope.launch {
            storeGuarded(onFailure = { }) { _view.update { it.copy(state = entitlements.saved()) } }
            verify()
        }
        if (!entitlements.sellsPro) return
        // The start verification just launched counts as the last attempt, so the first foreground report does not repeat it.
        lastVerifyAttemptMillis = clock.nowMillis()
        // A purchase change nobody was waiting for (a pending order the store finished while the app was open) is verified, and so acknowledged, at once.
        scope.launch { storeGuarded(onFailure = { }) { entitlements.storeChanges.collect { verify() } } }
        // A pending purchase can finish while the app sits in the background: on a return, verify again once the last attempt is old enough. An action already
        // in flight is as good as that verification, so the return is not queued behind it.
        scope.launch {
            storeGuarded(onFailure = { }) {
                foreground.collect { visible ->
                    if (visible && Entitlements.reverifyDue(lastVerifyAttemptMillis, clock.nowMillis())) storeActions.tryRun { verifyNow(restore = false) }
                }
            }
        }
    }

    /** Asks the store what the account owns. It waits its turn behind a store action in flight. A store that cannot be reached leaves the saved state and its age on screen. */
    suspend fun verify() { if (entitlements.sellsPro) storeActions.run { verifyNow(restore = false) } }

    /** Restore: the same verification, said aloud. Refused, and nothing run, while another store action is running (the button is disabled then). */
    fun restore() { if (entitlements.sellsPro) scope.launch { storeActions.tryRun { verifyNow(restore = true) } } }

    fun buyTip(productId: String) {
        if (!entitlements.sellsPro) return
        scope.launch {
            storeActions.tryRun {
                storeAction(forGate = false) {
                    val result = entitlements.buyTip(productId)
                    _view.update { it.said(forGate = false, ProMessages.tip(result)) }
                    // The thank-you is on screen; now the tip is consumed, which is also what lets the same tip be bought again.
                    if (result is TipResult.Thanks) storeGuarded(onFailure = { }) { entitlements.tipThanked(result.token) }
                }
            }
        }
    }

    /** Buy Pro, from the gate sheet. [onBought] runs once Pro is held, which is when the sheet has nothing left to say. */
    fun buyPro(onBought: () -> Unit) {
        if (!entitlements.sellsPro) return
        scope.launch {
            storeActions.tryRun {
                storeAction(forGate = true) {
                    val result = entitlements.buyPro()
                    _view.update { it.afterPurchase(result) }
                    if (result is BuyResult.Done) onBought()
                }
            }
        }
    }

    /** The gate sheet opened ([opened]) or closed: whatever an earlier purchase said is not about this opening, and the prices are asked for the first time it is shown. */
    fun gateChanged(opened: Boolean) {
        _view.update { it.copy(gateMessage = null) }
        if (opened) loadPrices()
    }

    /**
     * The store's prices, asked for the first time Settings' Pro card or the gate sheet is shown and kept for the process: nothing else needs them,
     * so a start (including the cold process the widgets' job starts) does not ask. An answer without prices is asked for again at the next showing.
     */
    fun loadPrices() {
        if (!entitlements.sellsPro || _view.value.prices.isNotEmpty() || !pricesLoading.compareAndSet(false, true)) return
        scope.launch {
            try {
                storeGuarded(onFailure = { }) {
                    val prices = (entitlements.products() as? StoreResult.Ok)?.value?.associate { it.productId to it.price }.orEmpty()
                    if (prices.isNotEmpty()) _view.update { it.copy(prices = prices) }
                }
            } finally { pricesLoading.set(false) }
        }
    }

    /** One store action: busy while it runs, and whatever it throws ends as a sentence on [forGate]'s surface, never as a crash. The caller holds [storeActions]. */
    private suspend fun storeAction(forGate: Boolean, block: suspend () -> Unit) {
        _view.update { it.begun(forGate) }
        try {
            storeGuarded(onFailure = { said -> _view.update { it.said(forGate, said) } }) { block() }
        } finally {
            _view.update { it.copy(busy = false) }
        }
    }

    private suspend fun verifyNow(restore: Boolean) = storeAction(forGate = false) {
        lastVerifyAttemptMillis = clock.nowMillis()
        val v = entitlements.verify()
        _view.update {
            it.copy(state = v.state, reachable = v.reachable, message = ProMessages.verification(restore, v.reachable, v.state.status, tipThanked = v.unconsumedTips.isNotEmpty()))
        }
        // The thank-you is on screen; now the tip is consumed. A consume that fails is listed again by the next verification.
        storeGuarded(onFailure = { }) { for (tip in v.unconsumedTips) entitlements.tipThanked(tip.token) }
    }
}
