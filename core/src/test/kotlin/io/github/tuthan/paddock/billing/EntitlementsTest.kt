package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ports.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A store that holds what the test puts in it and records every call, so each transition is exercised without Play. */
class FakeBilling(override val sellsPro: Boolean = true) : Billing {
    val owned = mutableListOf<StorePurchase>()
    var reachable = true
    var acknowledgeWorks = true
    var consumeWorks = true
    var nextOutcome: StoreResult<PurchaseOutcome> = StoreResult.Ok(PurchaseOutcome.Cancelled)
    val acknowledged = mutableListOf<String>()
    val consumed = mutableListOf<String>()
    var purchasesCalls = 0

    /** A store call that never answers (a Play callback that does not fire, a purchase sheet left open): set a gate and never complete it. */
    var purchasesGate: CompletableDeferred<Unit>? = null
    var purchaseGate: CompletableDeferred<Unit>? = null
    val purchasesStarted = CompletableDeferred<Unit>()
    val purchaseStarted = CompletableDeferred<Unit>()

    override suspend fun purchases(): StoreResult<List<StorePurchase>> {
        purchasesCalls++
        purchasesStarted.complete(Unit)
        purchasesGate?.await()
        return if (reachable) StoreResult.Ok(owned.toList()) else StoreResult.Failed("offline")
    }

    override suspend fun products() = StoreResult.Ok(listOf(ProductInfo(Products.PRO, "US$19.99")))

    override suspend fun purchase(productId: String): StoreResult<PurchaseOutcome> {
        purchaseStarted.complete(Unit)
        purchaseGate?.await()
        return nextOutcome
    }

    override suspend fun acknowledge(token: String): StoreResult<Unit> {
        if (!acknowledgeWorks) return StoreResult.Failed("offline")
        acknowledged += token
        owned.replaceAll { if (it.token == token) it.copy(acknowledged = true) else it }
        return StoreResult.Ok(Unit)
    }

    override suspend fun consume(token: String): StoreResult<Unit> {
        if (!consumeWorks) return StoreResult.Failed("offline")
        consumed += token
        owned.removeAll { it.token == token }
        return StoreResult.Ok(Unit)
    }
}

class EntitlementsTest {
    private val billing = FakeBilling()
    private val store = InMemoryEntitlementStore()
    private var now = 1_000_000L
    private val clock = Clock { now }
    private val entitlements = Entitlements(billing, store, clock)

    private fun pro(state: PurchaseState = PurchaseState.PURCHASED, acknowledged: Boolean = true, token: String = "t-pro") =
        StorePurchase(Products.PRO, token, state, acknowledged, purchaseTimeMillis = 500)

    private fun tip(id: String = "tip_5", token: String = "t-tip") = StorePurchase(id, token, PurchaseState.PURCHASED, acknowledged = false, purchaseTimeMillis = 600)

    @Test
    fun aFreshInstallIsUnknownAndGrantsNothing() = runBlocking<Unit> {
        val s = entitlements.saved()
        assertEquals(ProStatus.UNKNOWN, s.status)
        assertNull(s.verifiedAtMillis)
        assertFalse(entitlements.hasPro(s))
    }

    @Test
    fun anAccountWithoutProVerifiesAsFree() = runBlocking<Unit> {
        val v = entitlements.verify()
        assertTrue(v.reachable)
        assertEquals(ProStatus.FREE, v.state.status)
        assertEquals(1_000_000L, v.state.verifiedAtMillis)
        assertFalse(entitlements.hasPro(v.state))
        assertEquals(v.state, store.current)
    }

    @Test
    fun anAcknowledgedPurchaseVerifiesAsProWithoutAcknowledgingAgain() = runBlocking<Unit> {
        billing.owned += pro(acknowledged = true)
        val s = entitlements.verify().state
        assertEquals(ProStatus.PRO, s.status)
        assertTrue(s.acknowledged)
        assertTrue(entitlements.hasPro(s))
        assertEquals(emptyList(), billing.acknowledged)
    }

    @Test
    fun aPendingPurchaseGrantsNothingUntilTheStoreConfirms() = runBlocking<Unit> {
        billing.owned += pro(state = PurchaseState.PENDING, acknowledged = false)
        val s = entitlements.verify().state
        assertEquals(ProStatus.PENDING, s.status)
        assertFalse(entitlements.hasPro(s))
        assertEquals(emptyList(), billing.acknowledged)
        // Payment completes.
        billing.owned.clear(); billing.owned += pro(acknowledged = false)
        assertTrue(entitlements.hasPro(entitlements.verify().state))
    }

    @Test
    fun aPendingOrderThatVanishesFromTheStoreIsFreeAgain() = runBlocking<Unit> {
        billing.owned += pro(state = PurchaseState.PENDING, acknowledged = false)
        entitlements.verify()
        billing.owned.clear()
        assertEquals(ProStatus.FREE, entitlements.verify().state.status)
    }

    @Test
    fun anUnacknowledgedPurchaseIsAcknowledgedOnVerification() = runBlocking<Unit> {
        billing.owned += pro(acknowledged = false)
        val s = entitlements.verify().state
        assertEquals(listOf("t-pro"), billing.acknowledged)
        assertEquals(ProStatus.PRO, s.status)
        assertTrue(s.acknowledged)
    }

    @Test
    fun aFailedAcknowledgementIsRetriedOnEveryStartUntilTheStoreReportsIt() = runBlocking<Unit> {
        billing.owned += pro(acknowledged = false)
        billing.acknowledgeWorks = false
        val first = entitlements.verify().state
        assertEquals(ProStatus.PRO, first.status)
        assertFalse(first.acknowledged)
        assertTrue(entitlements.hasPro(first), "an unacknowledged purchase is still Pro until the store says otherwise")
        // The next start, still offline for the acknowledgement.
        assertFalse(Entitlements(billing, store, clock).verify().state.acknowledged)
        // Back online: the retry lands and is recorded.
        billing.acknowledgeWorks = true
        val third = Entitlements(billing, store, clock).verify().state
        assertEquals(listOf("t-pro"), billing.acknowledged)
        assertTrue(third.acknowledged)
        assertTrue(store.current.acknowledged)
    }

    @Test
    fun aVerificationFailureKeepsTheLastKnownStateAndItsTime() = runBlocking<Unit> {
        billing.owned += pro()
        entitlements.verify()
        val before = store.current
        billing.reachable = false
        now += 10 * 24 * 3600 * 1000L
        val v = entitlements.verify()
        assertFalse(v.reachable)
        assertEquals(before, v.state, "no silent downgrade")
        assertEquals(before, store.current)
        assertTrue(entitlements.hasPro(v.state))
        val summary = EntitlementPresenter.summary(v.state, now, unlockedBuild = false, storeReachable = false)
        assertTrue(summary.stale)
        assertTrue("10 days ago" in summary.detail, summary.detail)
    }

    @Test
    fun anUnavailableStoreAlsoKeepsTheState() = runBlocking<Unit> {
        billing.owned += pro()
        entitlements.verify()
        val before = store.current
        val e = Entitlements(NoBilling, store, clock)
        val v = e.verify()
        assertFalse(v.reachable)
        assertEquals(before, v.state)
    }

    @Test
    fun aRefundedPurchaseIsWithdrawnWithTheRefundReason() = runBlocking<Unit> {
        billing.owned += pro()
        entitlements.verify()
        billing.owned.clear()
        val s = entitlements.verify().state
        assertEquals(ProStatus.REVOKED, s.status)
        assertEquals(RevokeReason.REFUNDED, s.revokeReason)
        assertFalse(entitlements.hasPro(s))
        // Stays revoked, with a fresh verification time, until bought again.
        now += 1000
        val again = entitlements.verify().state
        assertEquals(ProStatus.REVOKED, again.status)
        assertEquals(now, again.verifiedAtMillis)
    }

    @Test
    fun aPurchaseTheStoreRefundedForWantOfAnAcknowledgementSaysSo() = runBlocking<Unit> {
        billing.owned += pro(acknowledged = false)
        billing.acknowledgeWorks = false
        entitlements.verify()
        billing.owned.clear() // three days later Play refunded it
        val s = entitlements.verify().state
        assertEquals(ProStatus.REVOKED, s.status)
        assertEquals(RevokeReason.UNCONFIRMED, s.revokeReason)
        assertTrue("could not be confirmed with Google Play in time" in EntitlementPresenter.summary(s, now, false).detail)
    }

    @Test
    fun aRevokedPurchaseBoughtAgainIsProAgain() = runBlocking<Unit> {
        billing.owned += pro(); entitlements.verify(); billing.owned.clear(); entitlements.verify()
        billing.owned += pro(token = "t2")
        val s = entitlements.verify().state
        assertEquals(ProStatus.PRO, s.status)
        assertNull(s.revokeReason)
    }

    @Test
    fun anUnlockedBuildHasProWithoutAPurchaseAndStillNeverAsksNoStore() = runBlocking<Unit> {
        val e = Entitlements(NoBilling, InMemoryEntitlementStore(), clock, unlockedBuild = true)
        assertTrue(e.hasPro(e.saved()))
        assertFalse(e.sellsPro)
        assertEquals("Everything is unlocked", EntitlementPresenter.summary(e.saved(), now, unlockedBuild = true).headline)
    }

    @Test
    fun buyingProRecordsAndAcknowledgesTheOrderTheStoreReported() = runBlocking<Unit> {
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.Purchased(pro(acknowledged = false)))
        val r = entitlements.buyPro()
        assertIs<BuyResult.Done>(r)
        assertEquals(ProStatus.PRO, r.state.status)
        assertTrue(r.state.acknowledged)
        assertEquals(listOf("t-pro"), billing.acknowledged)
        assertEquals(0, billing.purchasesCalls, "the reported order is the store's answer; no second query")
    }

    @Test
    fun buyingProWhileThePaymentIsPendingGrantsNothing() = runBlocking<Unit> {
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.Pending(pro(state = PurchaseState.PENDING, acknowledged = false)))
        val r = entitlements.buyPro()
        assertIs<BuyResult.Pending>(r)
        assertEquals(ProStatus.PENDING, r.state.status)
        assertFalse(entitlements.hasPro(r.state))
        assertEquals(emptyList(), billing.acknowledged)
    }

    @Test
    fun cancellingOrFailingChangesNothing() = runBlocking<Unit> {
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.Cancelled)
        assertEquals(BuyResult.Cancelled, entitlements.buyPro())
        billing.nextOutcome = StoreResult.Failed("busy")
        assertEquals(BuyResult.Failed("busy"), entitlements.buyPro())
        billing.nextOutcome = StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
        assertEquals(BuyResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE), entitlements.buyPro())
        assertEquals(EntitlementState(), store.current)
    }

    @Test
    fun alreadyOwnedIsSettledByTheStoresPurchaseList() = runBlocking<Unit> {
        billing.owned += pro()
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
        val r = entitlements.buyPro()
        assertIs<BuyResult.Done>(r)
        assertEquals(ProStatus.PRO, r.state.status)
        billing.reachable = false
        assertIs<BuyResult.Failed>(entitlements.buyPro())
    }

    @Test
    fun alreadyOwnedWhileThePaymentIsStillPendingIsPendingNotDone() = runBlocking<Unit> {
        // The buyer taps Buy again while a slow payment is open; Play answers ITEM_ALREADY_OWNED for the pending order.
        billing.owned += pro(state = PurchaseState.PENDING, acknowledged = false)
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
        val r = entitlements.buyPro()
        assertIs<BuyResult.Pending>(r)
        assertEquals(ProStatus.PENDING, r.state.status)
        assertFalse(entitlements.hasPro(entitlements.saved()))
        assertEquals(emptyList(), billing.acknowledged)
    }

    @Test
    fun alreadyOwnedWithNoProInTheStoresListIsAFailureThatPointsToRestore() = runBlocking<Unit> {
        // The store says owned but lists nothing for `pro` (an order the list mapping dropped): nothing is unlocked, so nothing is thanked.
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
        val r = entitlements.buyPro()
        assertIs<BuyResult.Failed>(r)
        assertTrue("Restore" in r.message, r.message)
        assertEquals(ProStatus.FREE, entitlements.saved().status)
        assertFalse(entitlements.hasPro(entitlements.saved()))
    }

    @Test
    fun alreadyOwnedAfterARefundIsAFailureToo() = runBlocking<Unit> {
        billing.owned += pro(); entitlements.verify(); billing.owned.clear()
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
        assertIs<BuyResult.Failed>(entitlements.buyPro())
        assertEquals(ProStatus.REVOKED, entitlements.saved().status)
    }

    @Test
    fun restoreOnASecondDeviceFindsThePurchaseThroughTheStoreAccount() = runBlocking<Unit> {
        billing.owned += pro()
        val secondPhone = Entitlements(billing, InMemoryEntitlementStore(), clock)
        assertEquals(ProStatus.UNKNOWN, secondPhone.saved().status)
        val v = secondPhone.restore()
        assertEquals(ProStatus.PRO, v.state.status)
        assertTrue(secondPhone.hasPro(secondPhone.saved()))
    }

    @Test
    fun aTipIsConsumedOnlyAfterTheThankYouSoTheSameTipCanBeBoughtAgain() = runBlocking<Unit> {
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.Purchased(tip()))
        billing.owned += tip()
        val thanks = entitlements.buyTip("tip_5")
        assertEquals(TipResult.Thanks("t-tip"), thanks)
        assertEquals(emptyList(), billing.consumed, "not consumed before the thank-you is shown")
        assertTrue(entitlements.tipThanked("t-tip"))
        assertEquals(listOf("t-tip"), billing.consumed)
        // Bought a second time: the store would refuse it had it not been consumed.
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.Purchased(tip(token = "t-tip-2")))
        assertEquals(TipResult.Thanks("t-tip-2"), entitlements.buyTip("tip_5"))
    }

    @Test
    fun aTipWhoseConsumeFailedIsListedAgainAtTheNextVerification() = runBlocking<Unit> {
        billing.owned += tip()
        billing.consumeWorks = false
        assertFalse(entitlements.tipThanked("t-tip"))
        val v = entitlements.verify()
        assertEquals(listOf("t-tip"), v.unconsumedTips.map { it.token })
        assertEquals(ProStatus.FREE, v.state.status, "a tip never changes Pro")
        billing.consumeWorks = true
        assertTrue(entitlements.tipThanked("t-tip"))
        assertEquals(emptyList(), entitlements.verify().unconsumedTips)
    }

    @Test
    fun aTipBoughtWhileAnEarlierOneIsUnconsumedIsHandedBackForConsumption() = runBlocking<Unit> {
        billing.owned += tip()
        billing.nextOutcome = StoreResult.Ok(PurchaseOutcome.AlreadyOwned)
        assertEquals(TipResult.Thanks("t-tip"), entitlements.buyTip("tip_5"))
    }

    @Test
    fun buyingNonTipThroughTheTipPathIsRefused() = runBlocking<Unit> {
        val failure = runCatching { entitlements.buyTip(Products.PRO) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)
    }

    @Test
    fun savedDoesNotWaitForAVerificationTheStoreNeverAnswers() = runBlocking<Unit> {
        // The widget refresh reads saved() on a background thread; a hung Play callback must not take that read with it.
        val before = EntitlementState(ProStatus.PRO, verifiedAtMillis = 900_000L, acknowledged = true, purchaseTimeMillis = 500)
        store.current = before
        billing.owned += pro()
        val gate = CompletableDeferred<Unit>().also { billing.purchasesGate = it }
        val inFlight = launch(Dispatchers.Default) { entitlements.verify() }
        try {
            billing.purchasesStarted.await()
            assertEquals(before, withTimeout(2_000) { entitlements.saved() }, "the last committed state, while the store call is in flight")
            gate.complete(Unit)
            inFlight.join()
            assertEquals(1_000_000L, entitlements.saved().verifiedAtMillis, "the verification's result is what saved() returns once it lands")
        } finally {
            gate.complete(Unit)
            inFlight.cancel()
        }
    }

    @Test
    fun savedDoesNotWaitForAPurchaseSheetThatStaysOpen() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>().also { billing.purchaseGate = it }
        val inFlight = launch(Dispatchers.Default) { entitlements.buyPro() }
        try {
            billing.purchaseStarted.await()
            assertEquals(EntitlementState(), withTimeout(2_000) { entitlements.saved() })
        } finally {
            gate.complete(Unit)
            inFlight.cancel()
        }
    }

    @Test
    fun storeCallsStillRunOneAtATime() = runBlocking<Unit> {
        // The file lock is separate from the lock that serializes store calls; a second store call still waits for the first.
        billing.owned += pro()
        val gate = CompletableDeferred<Unit>().also { billing.purchasesGate = it }
        val first = launch(Dispatchers.Default) { entitlements.verify() }
        try {
            billing.purchasesStarted.await()
            val second = launch(Dispatchers.Default) { entitlements.verify() }
            try {
                delay(100)
                assertEquals(1, billing.purchasesCalls, "the second verification waits for the first")
            } finally {
                gate.complete(Unit)
                second.join()
            }
            first.join()
            assertEquals(2, billing.purchasesCalls)
        } finally {
            gate.complete(Unit)
            first.cancel()
        }
    }

    @Test
    fun aBuildThatCannotVerifyAPurchaseHonoursNone() = runBlocking<Unit> {
        // entitlement.json left behind by a play install of the same application id: a foss build never re-verifies, so it must not honour it.
        val leftover = EntitlementState(ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true, purchaseTimeMillis = 1L)
        val foss = Entitlements(NoBilling, InMemoryEntitlementStore(leftover), clock)
        assertEquals(ProStatus.PRO, foss.saved().status)
        assertFalse(foss.hasPro(foss.saved()))
        val noSale = Entitlements(FakeBilling(sellsPro = false), InMemoryEntitlementStore(leftover), clock)
        assertFalse(noSale.hasPro(noSale.saved()))
        val play = Entitlements(FakeBilling(sellsPro = true), InMemoryEntitlementStore(leftover), clock)
        assertTrue(play.hasPro(play.saved()))
    }

    @Test
    fun anUnlockedBuildHasProWhateverTheStateAndWhetherOrNotItSells() = runBlocking<Unit> {
        for (sells in listOf(false, true)) for (status in ProStatus.entries) {
            val e = Entitlements(FakeBilling(sellsPro = sells), InMemoryEntitlementStore(EntitlementState(status)), clock, unlockedBuild = true)
            assertTrue(e.hasPro(e.saved()), "sells=$sells status=$status")
        }
    }

    @Test
    fun theGrantRuleIsOneFunctionTheWidgetProcessCanCallWithoutAnInstance() {
        for (status in ProStatus.entries) {
            val state = EntitlementState(status)
            assertEquals(status == ProStatus.PRO, Entitlements.hasPro(state, unlockedBuild = false, sellsPro = true), "sells, $status")
            assertFalse(Entitlements.hasPro(state, unlockedBuild = false, sellsPro = false), "does not sell, $status")
            assertTrue(Entitlements.hasPro(state, unlockedBuild = true, sellsPro = false), "unlocked, $status")
            assertTrue(Entitlements.hasPro(state, unlockedBuild = true, sellsPro = true), "unlocked and sells, $status")
        }
    }
}
