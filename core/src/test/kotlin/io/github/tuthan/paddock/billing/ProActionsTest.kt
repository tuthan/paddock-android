package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** The pure decisions behind the Pro card and the gate: one store action at a time, a throw is a sentence, which sentence, and when to verify again. */
class ProActionsTest {
    /** A regression that blocks (an action queued instead of refused) must fail the test, not hang the build. */
    private fun timed(body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking<Unit> { withTimeout(10_000) { body() } }

    // StoreActions (F9)

    @Test fun aSecondActionWhileOneIsRunningIsRefusedAndDoesNothing() = timed {
        val actions = StoreActions()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = launch { actions.tryRun { started.complete(Unit); release.await() } }
        started.await()
        assertTrue(actions.inFlight)
        var ran = false
        assertFalse(actions.tryRun { ran = true }, "a second tap while a purchase is in flight must be refused")
        assertFalse(ran)
        release.complete(Unit)
        first.join()
        assertFalse(actions.inFlight)
        assertTrue(actions.tryRun { ran = true })
        assertTrue(ran)
    }

    @Test fun twoTapsAtTheSameInstantRunOneActionNotTwo() = timed {
        val actions = StoreActions()
        val runs = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val taps = (1..2).map { async(Dispatchers.Default) { actions.tryRun { runs.incrementAndGet(); release.await() } } }
        withTimeout(5_000) { while (runs.get() == 0) yield() }
        // Let the loser give up before the winner is released: a loser that were queued instead of refused would run now.
        kotlinx.coroutines.delay(100)
        release.complete(Unit)
        assertEquals(listOf(false, true), taps.awaitAll().sorted())
        assertEquals(1, runs.get())
    }

    @Test fun anActionTheAppStartsWaitsForTheOneInFlightAndThenRuns() = timed {
        val actions = StoreActions()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = launch { actions.tryRun { started.complete(Unit); release.await() } }
        started.await()
        val ran = AtomicInteger()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { actions.run { ran.incrementAndGet() } }
        yield()
        assertEquals(0, ran.get(), "a verification must not run beside a purchase")
        release.complete(Unit)
        withTimeout(5_000) { first.join(); waiting.join() }
        assertEquals(1, ran.get())
    }

    @Test fun theLockIsReleasedWhenTheActionThrows() = timed {
        val actions = StoreActions()
        assertFailsWith<IllegalStateException> { actions.tryRun { throw IllegalStateException("boom") } }
        assertFalse(actions.inFlight)
        assertTrue(actions.tryRun { })
        assertFailsWith<IllegalStateException> { actions.run { throw IllegalStateException("boom") } }
        assertFalse(actions.inFlight)
    }

    // storeGuarded (F2)

    @Test fun aThrowFromAStoreCallEndsAsTheSentenceNotAsAThrow() = timed {
        val said = storeGuarded(onFailure = { it }) { throw IllegalStateException("Client is closed") }
        assertEquals("Google Play could not be used: IllegalStateException", said)
    }

    @Test fun anErrorFromTheLibraryIsHandledToo() = timed {
        // A class the Play library needs but the phone's Play services lack is a LinkageError, not an Exception, and it ends the process just the same.
        val said = storeGuarded(onFailure = { it }) { throw NoClassDefFoundError("com/android/billingclient/api/Foo") }
        assertEquals("Google Play could not be used: NoClassDefFoundError", said)
    }

    @Test fun anAnswerPassesThroughUntouched() = timed {
        assertEquals(7, storeGuarded(onFailure = { -1 }) { 7 })
    }

    @Test fun theCallersOwnCancellationIsNotAStoreFailure() = timed {
        var failed = false
        assertFailsWith<CancellationException> { storeGuarded(onFailure = { failed = true }) { throw CancellationException("the caller went away") } }
        assertFalse(failed, "a cancelled action must not show 'Google Play could not be used'")
    }

    @Test fun theSentenceNamesTheKindAndNeverTheMessage() {
        // A library's message can carry anything, including a token.
        assertEquals("Google Play could not be used: SecurityException", ProMessages.storeThrew(SecurityException("purchaseToken=abc123")))
        val anonymous = object : RuntimeException() {}
        assertEquals("Google Play could not be used: ${anonymous.javaClass.name}", ProMessages.storeThrew(anonymous))
    }

    // ProMessages (F10)

    @Test fun aRestoreThatRestoredProIsNotReplacedByTheTipThankYou() {
        // The old first branch said only "Thank you for your earlier tip." here.
        assertEquals("Pro restored from this Google account. Thank you for your earlier tip.", ProMessages.verification(restore = true, reachable = true, status = ProStatus.PRO, tipThanked = true))
    }

    @Test fun aRestoreThatFoundNothingStillSaysSoBesideTheTipThankYou() {
        assertEquals("Google Play lists no Pro purchase for this account. Thank you for your earlier tip.", ProMessages.verification(true, true, ProStatus.FREE, tipThanked = true))
        assertEquals("Google Play has not finished the payment. Thank you for your earlier tip.", ProMessages.verification(true, true, ProStatus.PENDING, tipThanked = true))
    }

    @Test fun aRestoreSaysItsOutcomeAlone() {
        assertEquals(ProMessages.RESTORED, ProMessages.verification(true, true, ProStatus.PRO, false))
        assertEquals(ProMessages.NOT_FINISHED, ProMessages.verification(true, true, ProStatus.PENDING, false))
        for (s in listOf(ProStatus.FREE, ProStatus.UNKNOWN, ProStatus.REVOKED)) assertEquals(ProMessages.RESTORE_NONE, ProMessages.verification(true, true, s, false), "$s")
        assertEquals(ProMessages.NOT_REACHED, ProMessages.verification(true, false, ProStatus.PRO, false))
    }

    @Test fun aVerificationNobodyAskedForSaysNothingButTheTipThankYou() {
        for (s in ProStatus.entries) {
            assertNull(ProMessages.verification(restore = false, reachable = true, status = s, tipThanked = false), "$s")
            assertNull(ProMessages.verification(restore = false, reachable = false, status = s, tipThanked = false), "$s")
            assertEquals(ProMessages.TIP_EARLIER, ProMessages.verification(false, true, s, tipThanked = true), "$s")
        }
    }

    @Test fun everyTipAndPurchaseOutcomeHasItsOwnSentence() {
        assertEquals("Thank you. A tip unlocks nothing and is not Pro.", ProMessages.tip(TipResult.Thanks("t")))
        assertEquals(ProMessages.NOT_FINISHED, ProMessages.tip(TipResult.Pending))
        assertNull(ProMessages.tip(TipResult.Cancelled))
        assertEquals(ProMessages.STORE_UNAVAILABLE, ProMessages.tip(TipResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)))
        assertEquals("The tip did not go through: offline.", ProMessages.tip(TipResult.Failed("offline")))
        val state = EntitlementState(ProStatus.PRO)
        assertEquals("Pro is on. Thank you.", ProMessages.purchase(BuyResult.Done(state)))
        assertEquals("Google Play has not finished the payment. Pro turns on when it does.", ProMessages.purchase(BuyResult.Pending(state)))
        assertNull(ProMessages.purchase(BuyResult.Cancelled))
        assertEquals(ProMessages.STORE_UNAVAILABLE, ProMessages.purchase(BuyResult.Unavailable(UnavailableKind.BUILD_WITHOUT_BILLING)))
        assertEquals("The purchase did not go through: offline.", ProMessages.purchase(BuyResult.Failed("offline")))
    }

    // ProView: which surface a sentence is on (F10)

    @Test fun aRestoreSentenceIsNeverOnTheGate() {
        val afterRestore = ProView().said(forGate = false, ProMessages.RESTORE_NONE)
        assertEquals(ProMessages.RESTORE_NONE, afterRestore.message)
        assertNull(afterRestore.gateMessage, "the gate sheet shows gateMessage; a Restore sentence under its title would read as being about this purchase")
    }

    @Test fun aFailedPurchaseSaysItsSentenceOnTheGateAndLeavesTheSettingsCardAlone() {
        val before = ProView().said(forGate = false, ProMessages.RESTORE_NONE)
        val after = before.afterPurchase(BuyResult.Failed("offline"))
        assertEquals("The purchase did not go through: offline.", after.gateMessage)
        assertEquals(ProMessages.RESTORE_NONE, after.message)
    }

    @Test fun aBoughtProIsOnTheStateAndKeptOnTheCardWhereTheHeadlineSaysPro() {
        val state = EntitlementState(ProStatus.PRO, acknowledged = true)
        val after = ProView().afterPurchase(BuyResult.Done(state))
        assertEquals(state, after.state)
        assertEquals(ProMessages.BOUGHT, after.message)
        assertEquals(ProMessages.BOUGHT, after.gateMessage)
    }

    @Test fun aPendingOrderIsOnTheStateAndOnlyTheGateSaysSo() {
        val state = EntitlementState(ProStatus.PENDING, 1L)
        val after = ProView().afterPurchase(BuyResult.Pending(state))
        assertEquals(state, after.state)
        assertNull(after.message, "the card's headline already says the purchase is pending")
        assertEquals(ProMessages.BOUGHT_PENDING, after.gateMessage)
    }

    @Test fun anActionClearsOnlyTheSentenceOfTheSurfaceItWritesTo() {
        val both = ProView(message = "card", gateMessage = "gate")
        assertEquals(ProView(busy = true, message = null, gateMessage = "gate"), both.begun(forGate = false))
        assertEquals(ProView(busy = true, message = "card", gateMessage = null), both.begun(forGate = true))
    }

    // Entitlements additions

    @Test fun theForegroundVerifiesAgainOnlyOnceTheLastAttemptIsOldEnough() {
        val bound = Entitlements.REVERIFY_AFTER_MILLIS
        assertEquals(15L * 60 * 1000, bound)
        assertTrue(Entitlements.reverifyDue(null, 1_000), "never asked in this process")
        assertFalse(Entitlements.reverifyDue(1_000, 1_000), "just asked")
        assertFalse(Entitlements.reverifyDue(1_000, 1_000 + bound - 1), "a tab switch or a quick glance away must not cost a store round trip")
        assertTrue(Entitlements.reverifyDue(1_000, 1_000 + bound))
        assertTrue(Entitlements.reverifyDue(1_000, 1_000 + 3L * 24 * 60 * 60 * 1000))
    }

    @Test fun aClockThatWentBackwardsCountsAsDue() {
        assertTrue(Entitlements.reverifyDue(lastAttemptMillis = 5_000_000, nowMillis = 1_000))
    }

    /** A store that reports changes on a flow the test controls. */
    private class Reporting : Billing {
        val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val sellsPro = true
        override val changes get() = changeFlow
        override suspend fun purchases() = StoreResult.Ok(emptyList<StorePurchase>())
        override suspend fun products() = StoreResult.Ok(emptyList<ProductInfo>())
        override suspend fun purchase(productId: String) = StoreResult.Ok(PurchaseOutcome.Cancelled)
        override suspend fun acknowledge(token: String) = StoreResult.Ok(Unit)
        override suspend fun consume(token: String) = StoreResult.Ok(Unit)
    }

    @Test fun entitlementsHandsOnTheStoresChanges() = timed {
        val billing = Reporting()
        val entitlements = Entitlements(billing, InMemoryEntitlementStore(), Clock { 1L })
        val seen = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { entitlements.storeChanges.collect { seen.incrementAndGet() } }
        billing.changeFlow.tryEmit(Unit)
        withTimeout(5_000) { while (seen.get() == 0) yield() }
        assertEquals(1, seen.get())
        job.cancel()
    }

    @Test fun aBuildWithoutAStoreHasNoChangesToHandOn() = timed {
        assertEquals(emptyList(), withTimeout(5_000) { Entitlements(NoBilling, InMemoryEntitlementStore(), Clock { 1L }).storeChanges.toList() })
    }
}
