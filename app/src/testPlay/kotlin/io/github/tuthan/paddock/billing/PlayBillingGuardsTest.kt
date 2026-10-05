package io.github.tuthan.paddock.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What PlayBilling does around the library, without the library: nothing it throws escapes, nothing it leaves unanswered holds a caller
 * or the connection lock, and a purchase update nobody waits for is reported. The library calls themselves (a real BillingClient on a
 * phone with Play) are not reachable from a unit test; those lines are held by compilation and the device checks in docs/billing.md.
 */
class PlayBillingGuardsTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "billing-test").apply { isDaemon = true } }
    @After fun stop() { pool.shutdownNow() }

    private fun blocking(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { body() } }

    /** The main thread, stood in for: it runs what it is given on the caller's own thread. */
    private val inline: (() -> Unit) -> Unit = { it() }

    /** A main thread that never gets to the call. */
    private val never: (() -> Unit) -> Unit = { }

    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).setDebugMessage("").build()

    // awaitCallback

    @Test fun theAnswerOfTheLibraryIsReturned() = blocking {
        assertEquals(7, awaitCallback<Int>(1_000, inline) { done -> done(7) })
    }

    @Test fun anAnswerFromAnotherThreadIsReturned() = blocking {
        assertEquals(7, awaitCallback<Int>(5_000, inline) { done -> pool.execute { done(7) } })
    }

    @Test fun aCallbackThatNeverFiresEndsTheWaitInsteadOfHoldingTheCallerForever() = blocking {
        val started = System.nanoTime()
        try {
            awaitCallback<Int>(50, inline) { }
            fail("a library that never answers must not be waited for")
        } catch (e: StoreNoAnswer) {
            assertTrue("the wait ran far past its bound", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
        }
    }

    @Test fun aCallThatTheMainThreadNeverRunsEndsTheWaitToo() = blocking {
        try {
            awaitCallback<Int>(50, never) { done -> done(1) }
            fail("a call that never ran has no answer")
        } catch (e: StoreNoAnswer) {
            // expected
        }
    }

    @Test fun anAnswerAfterTheWaitIsOverIsDroppedAndCannotThrow() = blocking {
        var late: ((Int) -> Unit)? = null
        try {
            awaitCallback<Int>(50, inline) { done -> late = done }
            fail("no answer yet")
        } catch (e: StoreNoAnswer) {
            // expected
        }
        late!!(1)
        late!!(2)
    }

    @Test fun aSecondAnswerIsIgnored() = blocking {
        assertEquals(1, awaitCallback<Int>(1_000, inline) { done -> done(1); done(2) })
    }

    @Test fun aThrowFromTheLibraryCallReachesTheCaller() = blocking {
        try {
            awaitCallback<Int>(1_000, inline) { throw IllegalStateException("Client is closed") }
            fail("the throw was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("Client is closed", e.message)
        }
    }

    @Test fun aThrowOnTheMainThreadReachesTheCallerToo() = blocking {
        try {
            awaitCallback<Int>(5_000, { pool.execute(it) }) { throw IllegalStateException("main") }
            fail("the throw was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("main", e.message)
        }
    }

    // guarded

    @Test fun guardedLeavesAnAnswerAlone() = blocking {
        val ok = StoreResult.Ok(3)
        assertSame(ok, guarded { ok })
        val unavailable = StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
        assertSame(unavailable, guarded<Int> { unavailable })
    }

    @Test fun aLibraryThatThrowsIsAFailedCallNotACrash() = blocking {
        assertEquals(
            StoreResult.Failed("Google Play threw IllegalStateException: Client is already in the process of connecting to billing service."),
            guarded<Int> { throw IllegalStateException("Client is already in the process of connecting to billing service.") },
        )
        assertEquals(StoreResult.Failed("Google Play threw SecurityException"), guarded<Int> { throw SecurityException() })
    }

    @Test fun noAnswerInTimeIsAFailedCall() = blocking {
        assertEquals(StoreResult.Failed("Google Play did not answer in time"), guarded<Int> { awaitCallback<Int>(50, inline) { }.let { StoreResult.Ok(it) } })
    }

    @Test fun theCallersOwnCancellationStillPasses() = blocking {
        try {
            guarded<Int> { throw CancellationException("the caller went away") }
            fail("a cancellation must not become a result")
        } catch (e: CancellationException) {
            assertEquals("the caller went away", e.message)
        }
        val started = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { guarded<Int> { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    // connectedThen

    @Test fun aConnectionThatNeverAnswersFailsTheCallAndDoesNotKeepTheLock() = blocking {
        val lock = Mutex()
        val ran = AtomicInteger()
        val r = guarded {
            connectedThen(lock, { false }, { awaitCallback<Int>(50, inline) { }; null }) { ran.incrementAndGet(); StoreResult.Ok(Unit) }
        }
        assertEquals(StoreResult.Failed("Google Play did not answer in time"), r)
        assertEquals("the call must not run on a connection it never got", 0, ran.get())
        assertTrue("the connection lock is still held after the bound ran out", lock.tryLock())
    }

    @Test fun aConnectionThatThrowsFailsTheCallAndDoesNotKeepTheLock() = blocking {
        val lock = Mutex()
        val r = guarded<Unit> { connectedThen(lock, { false }, { throw IllegalStateException("closed") }) { StoreResult.Ok(Unit) } }
        assertEquals(StoreResult.Failed("Google Play threw IllegalStateException: closed"), r)
        assertTrue(lock.tryLock())
    }

    @Test fun aSecondCallIsNotStuckBehindAConnectionThatTimedOut() = blocking {
        val lock = Mutex()
        var ready = false
        val first = guarded<Unit> { connectedThen(lock, { ready }, { awaitCallback<Int>(50, inline) { }; null }) { StoreResult.Ok(Unit) } }
        assertTrue(first is StoreResult.Failed)
        val second = guarded { connectedThen(lock, { ready }, { ready = true; null }) { StoreResult.Ok("ran") } }
        assertEquals(StoreResult.Ok("ran"), second)
    }

    @Test fun aConnectionThatFailsReturnsWhyAndSkipsTheCall() = blocking {
        val why = StoreResult.Unavailable(UnavailableKind.STORE_UNAVAILABLE)
        val ran = AtomicInteger()
        val r = connectedThen(Mutex(), { false }, { why }) { ran.incrementAndGet(); StoreResult.Ok(Unit) }
        assertSame(why, r)
        assertEquals(0, ran.get())
    }

    @Test fun aReadyClientIsNotConnectedAgainAndTheCallRunsOutsideTheLock() = blocking {
        val lock = Mutex()
        val connects = AtomicInteger()
        val r = connectedThen(lock, { true }, { connects.incrementAndGet(); null }) { StoreResult.Ok(lock.tryLock().also { if (it) lock.unlock() }) }
        assertEquals(0, connects.get())
        assertEquals(StoreResult.Ok(true), r)
    }

    // PurchaseUpdates

    /** An owner collecting [PurchaseUpdates.changes], counting what it is told. */
    private fun CoroutineScope.owner(updates: PurchaseUpdates, seen: AtomicInteger) =
        launch(start = CoroutineStart.UNDISPATCHED) { updates.changes.collect { seen.incrementAndGet() } }

    @Test fun anUpdateNobodyIsWaitingForReachesTheOwner() = blocking {
        // The pending purchase the store finished while the app was open.
        val updates = PurchaseUpdates()
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        updates.onUpdate(result(BillingResponseCode.OK), null)
        yield()
        assertEquals(1, seen.get())
        job.cancel()
    }

    @Test fun anUpdateBeforeTheOwnerCollectsIsDroppedBecauseTheOwnerVerifiesAtStart() = blocking {
        val updates = PurchaseUpdates()
        updates.onUpdate(result(BillingResponseCode.OK), null)
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        yield()
        assertEquals(0, seen.get())
        job.cancel()
    }

    @Test fun theFlowGetsItsUpdateAndTheOwnerIsNotToldAsWell() = blocking {
        val updates = PurchaseUpdates()
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        val waiting = updates.expect()
        updates.onUpdate(result(BillingResponseCode.OK), null)
        yield()
        assertEquals(BillingResponseCode.OK, waiting.await().first.responseCode)
        assertEquals("the flow took it; telling the owner too would verify twice", 0, seen.get())
        job.cancel()
    }

    @Test fun anUpdateAfterTheFlowGaveUpIsAnnounced() = blocking {
        // The sheet was left open past its bound, the flow released its slot, and the purchase finishes later.
        val updates = PurchaseUpdates()
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        updates.release(updates.expect())
        updates.onUpdate(result(BillingResponseCode.OK), null)
        yield()
        assertEquals(1, seen.get())
        job.cancel()
    }

    @Test fun aSecondUpdateForTheSameFlowIsAnnounced() = blocking {
        val updates = PurchaseUpdates()
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        val waiting = updates.expect()
        updates.onUpdate(result(BillingResponseCode.OK), null)
        updates.onUpdate(result(BillingResponseCode.OK), null)
        yield()
        assertTrue(waiting.isCompleted)
        assertEquals(1, seen.get())
        job.cancel()
    }

    @Test fun releasingAFlowDoesNotDisturbALaterOne() = blocking {
        val updates = PurchaseUpdates()
        val first = updates.expect()
        val second = updates.expect()
        updates.release(first)
        updates.onUpdate(result(BillingResponseCode.OK), null)
        assertTrue(second.isCompleted)
        assertFalse(first.isCompleted)
    }

    @Test fun anItemTheAccountAlreadyOwnsIsAnnouncedButACancelOrAnErrorIsNot() = blocking {
        val updates = PurchaseUpdates()
        val seen = AtomicInteger()
        val job = owner(updates, seen)
        updates.onUpdate(result(BillingResponseCode.USER_CANCELED), null)
        updates.onUpdate(result(BillingResponseCode.ERROR), null)
        updates.onUpdate(result(BillingResponseCode.SERVICE_UNAVAILABLE), null)
        yield()
        assertEquals(0, seen.get())
        updates.onUpdate(result(BillingResponseCode.ITEM_ALREADY_OWNED), null)
        yield()
        assertEquals(1, seen.get())
        job.cancel()
    }

    @Test fun changesThatArriveWhileTheOwnerIsVerifyingCoalesceIntoOneMore() = blocking {
        val updates = PurchaseUpdates()
        val gate = CompletableDeferred<Unit>()
        val verifications = AtomicInteger()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            updates.changes.collect { if (verifications.incrementAndGet() == 1) gate.await() }
        }
        updates.onUpdate(result(BillingResponseCode.OK), null)
        yield()
        assertEquals(1, verifications.get())
        repeat(5) { updates.onUpdate(result(BillingResponseCode.OK), null) }
        gate.complete(Unit)
        yield()
        yield()
        assertEquals("one verification for the first change and one for everything that came while it ran", 2, verifications.get())
        job.cancel()
    }
}
