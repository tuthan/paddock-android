package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * What the app does about Pro, against a store the test controls (review F2, F4, F9, F10, F14): the same [ProSession] the graph builds, with the
 * graph's scope shape (a supervisor with a handler that records what would have ended the process).
 */
class ProSessionTest {
    /** A store whose every call is counted and can be held, answered with a throw, or given another answer. */
    private class Store(override val sellsPro: Boolean = true) : Billing {
        val owned = CopyOnWriteArrayList<StorePurchase>()
        val purchasesCalls = AtomicInteger()
        val productsCalls = AtomicInteger()
        val purchaseCalls = AtomicInteger()
        val acknowledged = CopyOnWriteArrayList<String>()
        val consumed = CopyOnWriteArrayList<String>()
        @Volatile var purchasesGate: CompletableDeferred<Unit>? = null
        @Volatile var purchaseGate: CompletableDeferred<Unit>? = null
        @Volatile var purchasesThrows: Throwable? = null
        @Volatile var purchaseThrows: Throwable? = null
        @Volatile var productsThrows: Throwable? = null
        @Volatile var changesThrows: Throwable? = null
        @Volatile var nextPurchase: StoreResult<PurchaseOutcome> = StoreResult.Ok(PurchaseOutcome.Cancelled)
        @Volatile var prices: List<ProductInfo> = listOf(ProductInfo(Products.PRO, "US$19.99"), ProductInfo("tip_3", "US$2.99"))
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val changes get() = changesThrows?.let { throw it } ?: changeFlow

        override suspend fun purchases(): StoreResult<List<StorePurchase>> {
            purchasesCalls.incrementAndGet()
            purchasesGate?.await()
            purchasesThrows?.let { throw it }
            return StoreResult.Ok(owned.toList())
        }

        override suspend fun products(): StoreResult<List<ProductInfo>> {
            productsCalls.incrementAndGet()
            productsThrows?.let { throw it }
            return StoreResult.Ok(prices)
        }

        override suspend fun purchase(productId: String): StoreResult<PurchaseOutcome> {
            purchaseCalls.incrementAndGet()
            purchaseGate?.await()
            purchaseThrows?.let { throw it }
            return nextPurchase
        }

        override suspend fun acknowledge(token: String): StoreResult<Unit> {
            acknowledged += token
            owned.replaceAll { if (it.token == token) it.copy(acknowledged = true) else it }
            return StoreResult.Ok(Unit)
        }

        override suspend fun consume(token: String): StoreResult<Unit> { consumed += token; owned.removeAll { it.token == token }; return StoreResult.Ok(Unit) }
    }

    /** The session as the graph builds it, in a scope whose handler records what an unguarded throw would have done to the process. */
    private class Rig(sellsPro: Boolean = true, unlocked: Boolean = false, inFront: Boolean = false, initial: EntitlementState = EntitlementState(), loadDelayMillis: Long = 0) {
        val store = Store(sellsPro)
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
        @Volatile var now = 1_000_000L
        private val clock = Clock { now }
        val foreground = MutableStateFlow(inFront)
        val session = ProSession(Entitlements(store, SlowStore(InMemoryEntitlementStore(initial), loadDelayMillis), clock, unlocked), scope, clock, unlocked)
        val view get() = session.view.value

        fun start() = session.start(foreground)

        /** Starts and waits for the start verification to end. */
        suspend fun started() { start(); verified(1) }

        suspend fun verified(calls: Int) = eventually("$calls verification(s) done and the card idle") { store.purchasesCalls.get() >= calls && !view.busy }

        fun close() = scope.cancel()
    }

    /** The saved file, slow to read: the start coroutine is still loading it when the foreground collector first looks. */
    private class SlowStore(private val inner: InMemoryEntitlementStore, private val millis: Long) : EntitlementStore {
        override suspend fun load(): EntitlementState { if (millis > 0) delay(millis); return inner.load() }
        override suspend fun save(state: EntitlementState) = inner.save(state)
    }

    private fun rig(
        sellsPro: Boolean = true, unlocked: Boolean = false, inFront: Boolean = false, initial: EntitlementState = EntitlementState(), loadDelayMillis: Long = 0,
        body: suspend Rig.() -> Unit,
    ) = runBlocking<Unit> {
        val r = Rig(sellsPro, unlocked, inFront, initial, loadDelayMillis)
        try { withTimeout(30_000) { r.body() } } finally { r.close() }
    }

    private suspend fun Rig.subscribedToChanges() = eventually("the session collects the store's changes") { store.changeFlow.subscriptionCount.value > 0 }

    private fun pro(state: PurchaseState = PurchaseState.PURCHASED, acknowledged: Boolean = true) = StorePurchase(Products.PRO, "t-pro", state, acknowledged, 500)
    private fun tip() = StorePurchase("tip_5", "t-tip", PurchaseState.PURCHASED, false, 600)

    // F2: a throw is a sentence, never the process

    @Test fun aStoreThatThrowsOnRestoreEndsAsASentenceAndTheCardIsIdleAgain() = rig {
        started()
        store.purchasesThrows = IllegalStateException("Client is closed")
        session.restore()
        eventually("the sentence") { view.message != null && !view.busy }
        assertEquals("Google Play could not be used: IllegalStateException", view.message)
        assertTrue(uncaught.isEmpty(), "an uncaught throw in the graph's scope ends the app: $uncaught")
        // And the next try works: nothing is stuck.
        store.purchasesThrows = null
        session.restore()
        eventually("the next Restore answers") { view.message == ProMessages.RESTORE_NONE }
    }

    @Test fun aStoreThatThrowsAtStartDoesNotEndTheApp() = rig {
        store.purchasesThrows = IllegalStateException("not connected")
        start()
        eventually("the start verification ended") { store.purchasesCalls.get() >= 1 && !view.busy }
        assertEquals("Google Play could not be used: IllegalStateException", view.message)
        assertTrue(uncaught.isEmpty(), "$uncaught")
    }

    @Test fun aPurchaseThatThrowsIsASentenceOnTheGateAndTheCardKeepsItsOwn() = rig {
        started()
        session.restore()
        eventually("a Restore sentence") { view.message == ProMessages.RESTORE_NONE && !view.busy }
        store.purchaseThrows = SecurityException("no")
        session.buyPro { }
        eventually("the gate's sentence") { view.gateMessage != null && !view.busy }
        assertEquals("Google Play could not be used: SecurityException", view.gateMessage)
        assertEquals(ProMessages.RESTORE_NONE, view.message)
        assertTrue(uncaught.isEmpty(), "$uncaught")
    }

    @Test fun aTipThatThrowsIsASentenceOnTheCard() = rig {
        started()
        store.purchaseThrows = IllegalStateException("closed")
        session.buyTip("tip_3")
        eventually("the sentence") { view.message != null && !view.busy }
        assertEquals("Google Play could not be used: IllegalStateException", view.message)
        assertNull(view.gateMessage)
        assertTrue(uncaught.isEmpty(), "$uncaught")
    }

    @Test fun aPriceQueryThatThrowsIsSilentAndTriedAgainAtTheNextShowing() = rig {
        started()
        store.productsThrows = IllegalStateException("closed")
        session.loadPrices()
        eventually("the query ran") { store.productsCalls.get() == 1 }
        assertTrue(view.prices.isEmpty())
        store.productsThrows = null
        eventually("the next showing asks again and gets prices") { session.loadPrices(); view.prices.isNotEmpty() }
        assertTrue(uncaught.isEmpty(), "$uncaught")
    }

    @Test fun aStoreWhoseChangeReportThrowsDoesNotEndTheApp() = rig {
        store.changesThrows = IllegalStateException("broken")
        started()
        delay(100)
        assertTrue(uncaught.isEmpty(), "$uncaught")
    }

    // F4: a change the store reports, and a return to the foreground

    @Test fun aPurchaseTheStoreFinishedWhileTheAppWasOpenIsVerifiedAndAcknowledgedAtOnce() = rig {
        store.owned += pro(PurchaseState.PENDING, acknowledged = false)
        started()
        assertEquals(ProStatus.PENDING, view.state.status)
        // The payment completes in the store; Play reports it on the purchases listener and the app was not waiting for it.
        store.owned.clear(); store.owned += pro(acknowledged = false)
        subscribedToChanges()
        store.changeFlow.tryEmit(Unit)
        eventually("Pro, acknowledged") { view.state.status == ProStatus.PRO && view.state.acknowledged }
        assertEquals(listOf("t-pro"), store.acknowledged.toList())
        assertEquals(2, store.purchasesCalls.get())
    }

    @Test fun aReturnWithinTheBoundDoesNotAskTheStoreAgain() = rig {
        started()
        now += Entitlements.REVERIFY_AFTER_MILLIS - 1
        foreground.value = true
        delay(300)
        assertEquals(1, store.purchasesCalls.get(), "a tab switch must not cost a store round trip")
    }

    @Test fun aReturnAfterTheBoundVerifiesAgain() = rig {
        started()
        now += Entitlements.REVERIFY_AFTER_MILLIS
        foreground.value = true
        eventually("a second verification") { store.purchasesCalls.get() == 2 }
        // Back to the foreground again at once: that attempt is recent.
        foreground.value = false; delay(50); foreground.value = true
        delay(300)
        assertEquals(2, store.purchasesCalls.get())
    }

    @Test fun aPendingPurchaseThatCompletedInTheBackgroundIsAcknowledgedOnTheNextReturn() = rig {
        store.owned += pro(PurchaseState.PENDING, acknowledged = false)
        started()
        assertEquals(ProStatus.PENDING, view.state.status)
        store.owned.clear(); store.owned += pro(acknowledged = false)
        now += 3L * 60 * 60 * 1000
        foreground.value = true
        eventually("Pro, acknowledged inside Play's three days") { view.state.status == ProStatus.PRO && view.state.acknowledged }
        assertEquals(listOf("t-pro"), store.acknowledged.toList())
    }

    @Test fun anAppThatStartsInFrontVerifiesOnceNotTwice() = rig(inFront = true, loadDelayMillis = 150) {
        started()
        delay(600)
        assertEquals(1, store.purchasesCalls.get(), "the start verification and the first foreground report are one attempt")
    }

    @Test fun aBuildWithoutAStoreNeverAsksOneWhateverHappens() = rig(sellsPro = false) {
        start()
        store.changeFlow.tryEmit(Unit)
        foreground.value = true
        now += 24L * 60 * 60 * 1000
        session.restore(); session.buyTip("tip_3"); session.buyPro { }; session.loadPrices(); session.gateChanged(opened = true)
        delay(300)
        assertEquals(0, store.purchasesCalls.get() + store.productsCalls.get() + store.purchaseCalls.get())
    }

    // F9: one store action at a time

    @Test fun aSecondBuyWhileOneIsInFlightDoesNothing() = rig {
        started()
        val sheet = CompletableDeferred<Unit>().also { store.purchaseGate = it }
        session.buyPro { }
        eventually("the purchase sheet is up") { store.purchaseCalls.get() == 1 }
        session.buyPro { }
        session.buyTip("tip_3")
        session.restore()
        delay(300)
        assertEquals(1, store.purchaseCalls.get(), "a double tap or a Tip beside a Buy must not start a second purchase")
        assertEquals(1, store.purchasesCalls.get(), "Restore beside a Buy is refused too")
        assertTrue(view.busy, "busy stays true until the purchase ends, however many other taps were refused")
        sheet.complete(Unit)
        eventually("the card idle again") { !view.busy }
        assertEquals(1, store.purchaseCalls.get())
    }

    @Test fun aTapDuringTheStartVerificationIsRefused() = rig {
        val held = CompletableDeferred<Unit>().also { store.purchasesGate = it }
        start()
        eventually("the start verification is waiting on the store") { store.purchasesCalls.get() == 1 }
        session.restore()
        session.buyTip("tip_3")
        delay(300)
        assertEquals(1, store.purchasesCalls.get())
        assertEquals(0, store.purchaseCalls.get())
        assertTrue(view.busy)
        held.complete(Unit)
        verified(1)
        assertFalse(view.busy)
    }

    @Test fun aStoreChangeThatArrivesDuringABuyIsVerifiedAfterwardsNotDropped() = rig {
        started()
        subscribedToChanges()
        val sheet = CompletableDeferred<Unit>().also { store.purchaseGate = it }
        session.buyPro { }
        eventually("the purchase sheet is up") { store.purchaseCalls.get() == 1 }
        store.owned += pro(acknowledged = false)
        store.changeFlow.tryEmit(Unit)
        delay(300)
        assertEquals(1, store.purchasesCalls.get(), "a verification must not run beside a purchase")
        sheet.complete(Unit)
        eventually("the change was looked at after the purchase") { store.purchasesCalls.get() == 2 && !view.busy }
        assertEquals(ProStatus.PRO, view.state.status)
    }

    @Test fun anActionIsAllowedAgainOnceTheLastOneEnded() = rig {
        started()
        session.buyTip("tip_3")
        eventually("the first tip ended") { store.purchaseCalls.get() == 1 && !view.busy }
        session.buyTip("tip_3")
        eventually("the second tip ran") { store.purchaseCalls.get() == 2 && !view.busy }
    }

    // F10: which sentence, and where

    @Test fun aRestoreThatRestoredProIsNotReplacedByTheTipThankYou() = rig {
        started()
        store.owned += pro(acknowledged = true); store.owned += tip()
        session.restore()
        eventually("the restore sentence") { view.message != null && !view.busy }
        assertEquals("Pro restored from this Google account. Thank you for your earlier tip.", view.message)
        assertEquals(ProStatus.PRO, view.state.status)
        eventually("the tip was consumed once the thank-you was on screen") { store.consumed.toList() == listOf("t-tip") }
    }

    @Test fun theGateShowsItsOwnSentenceAndNeverTheRestoreOne() = rig {
        started()
        session.restore()
        eventually("the Restore sentence") { view.message == ProMessages.RESTORE_NONE && !view.busy }
        assertNull(view.gateMessage, "the gate sheet shows gateMessage, so a Restore sentence under its title cannot happen")
        session.gateChanged(opened = true)
        store.nextPurchase = StoreResult.Failed("declined")
        session.buyPro { }
        eventually("the gate's sentence") { view.gateMessage != null && !view.busy }
        assertEquals("The purchase did not go through: declined.", view.gateMessage)
        assertEquals(ProMessages.RESTORE_NONE, view.message, "a failed purchase is not a Settings sentence")
        // Closing and opening the sheet again does not bring the old purchase sentence back.
        session.gateChanged(opened = false)
        assertNull(view.gateMessage)
        assertEquals(ProMessages.RESTORE_NONE, view.message)
    }

    @Test fun aBoughtProCallsBackSoTheGateCanClose() = rig {
        started()
        store.nextPurchase = StoreResult.Ok(PurchaseOutcome.Purchased(pro(acknowledged = false)))
        val bought = CompletableDeferred<Unit>()
        session.buyPro { bought.complete(Unit) }
        withTimeout(5_000) { bought.await() }
        eventually("settled") { view.state.status == ProStatus.PRO && !view.busy }
        assertEquals(ProMessages.BOUGHT, view.message)
    }

    @Test fun aPurchaseThatWasCancelledOrIsPendingDoesNotCloseTheGate() = rig {
        started()
        var closed = false
        session.buyPro { closed = true }
        eventually("cancelled") { store.purchaseCalls.get() == 1 && !view.busy }
        store.nextPurchase = StoreResult.Ok(PurchaseOutcome.Pending(pro(PurchaseState.PENDING, acknowledged = false)))
        session.buyPro { closed = true }
        eventually("pending") { view.state.status == ProStatus.PENDING && !view.busy }
        assertFalse(closed)
        assertEquals(ProMessages.BOUGHT_PENDING, view.gateMessage)
    }

    // F14: prices only when a screen that shows them is

    @Test fun aStartAsksNoPrices() = rig {
        started()
        delay(200)
        assertEquals(0, store.productsCalls.get(), "the widgets' 15-minute cold process starts the graph too")
        assertTrue(view.prices.isEmpty())
    }

    @Test fun thePricesAreAskedForOnceWhenAScreenShowsThemAndKept() = rig {
        started()
        session.loadPrices()
        eventually("the prices") { view.prices.isNotEmpty() }
        assertEquals("US$19.99", view.prices[Products.PRO])
        session.loadPrices(); session.loadPrices()
        delay(200)
        assertEquals(1, store.productsCalls.get())
    }

    @Test fun openingTheGateAsksForThePrices() = rig {
        started()
        session.gateChanged(opened = true)
        eventually("the price of Pro") { view.prices[Products.PRO] == "US$19.99" }
        assertEquals(1, store.productsCalls.get())
    }

    @Test fun aStoreThatListsNoPricesIsAskedAgainTheNextTime() = rig {
        started()
        store.prices = emptyList()
        session.loadPrices()
        eventually("asked") { store.productsCalls.get() == 1 }
        store.prices = listOf(ProductInfo(Products.PRO, "US$19.99"))
        eventually("asked again at the next showing") { session.loadPrices(); view.prices.isNotEmpty() }
    }

    @Test fun theUnlockedBuildShowsAsUnlockedAndAsksNothing() = rig(sellsPro = false, unlocked = true) {
        start()
        assertTrue(view.unlocked)
        assertFalse(view.sellsPro)
        assertEquals(0, store.purchasesCalls.get())
    }
}

private suspend fun eventually(what: String, limitMillis: Long = 5_000, cond: () -> Boolean) {
    val until = System.nanoTime() + limitMillis * 1_000_000
    while (!cond()) {
        check(System.nanoTime() < until) { "timed out waiting for: $what" }
        delay(5)
    }
}
