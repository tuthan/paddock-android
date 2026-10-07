package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wake for a saved machine that is not watched (decision D2): it only sends, says no answer is observed, and keeps its own guard. */
class MachineWakeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val sent = WakeSendResult.Sent(listOf(Ipv4Subnet.literal("192.168.1.255")!!))

    @Volatile private var now = 1_000L
    private val clock = Clock { now }
    private val reconnects = AtomicInteger()
    private val watchedSends = AtomicInteger()
    /** Sends per machine, by profile id. */
    private val sends = ConcurrentHashMap<String, AtomicInteger>()
    private val link = MutableStateFlow(WakeLink.DOWN)
    @Volatile private var watchedId: String? = "alpha"

    private val watchedTap = WakeTap(scope, clock, send = { watchedSends.incrementAndGet(); sent }, link = { link.value }, links = link, reconnect = { reconnects.incrementAndGet() }, followMillis = 5_000)

    private fun wake(send: suspend (String) -> WakeSendResult? = { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); sent }) =
        MachineWake(scope, clock, watchedTap, { watchedId }, send)

    private fun sendsTo(id: String) = sends[id]?.get() ?: 0

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(3_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun aMachineThatIsNotWatchedGetsThePacketAndTheNotWatchedLineAndNoReconnect() = runBlocking<Unit> {
        val w = wake()
        w.tap("beta")
        until("beta's facts") { w.others.value["beta"] != null }
        val f = w.others.value.getValue("beta")
        assertTrue(f.notWatched)
        assertEquals(listOf("Wake packet sent to 192.168.1.255.", WakeFacts.NOT_WATCHED), f.lines { "t$it" })
        delay(100)
        assertEquals(0, reconnects.get(), "the watched machine's connection is not asked to reconnect for another machine")
        assertEquals(0, watchedSends.get(), "and the watched machine's tap did not run")
        assertNull(watchedTap.facts.value, "nor did it record anything")
        assertEquals(1, sendsTo("beta"))
        // A connection coming up later is the watched machine's, never an answer from beta.
        link.value = WakeLink(answered = true, reachable = true)
        delay(100)
        assertEquals(f, w.others.value["beta"])
        assertEquals(f, w.facts("beta"))
    }

    @Test fun theWatchedMachineStillGetsTheFullTap() = runBlocking<Unit> {
        val w = wake()
        w.tap("alpha")
        until("the reconnect") { reconnects.get() == 1 && watchedTap.facts.value != null }
        assertEquals(1, watchedSends.get())
        assertEquals(0, sendsTo("alpha"), "the send-only path is not used for the watched machine")
        assertTrue(w.others.value.isEmpty())
        assertEquals(watchedTap.facts.value, w.facts("alpha"))
    }

    @Test fun theGuardHoldsPerMachine() = runBlocking<Unit> {
        val w = wake()
        w.tap("beta")
        until("beta's facts") { w.others.value["beta"] != null }
        w.tap("beta"); delay(100)
        assertEquals(1, sendsTo("beta"), "a second tap inside 30 s sends nothing")
        w.tap("gamma")
        until("gamma's facts") { w.others.value["gamma"] != null }
        assertEquals(1, sendsTo("gamma"), "another machine has its own guard")
        now += WakeFacts.GUARD_MILLIS
        w.tap("beta")
        until("beta's second send") { sendsTo("beta") == 2 }
    }

    @Test fun aTapThatSentNothingStartsNoGuard() = runBlocking<Unit> {
        val failed = WakeSendResult.Failed(WakeSendFailure.RelayRequired)
        val w = wake { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); failed }
        w.tap("beta")
        until("the facts") { w.others.value["beta"] != null }
        assertEquals(1, w.others.value.getValue("beta").lines { "" }.size)
        w.tap("beta")
        until("the second send") { sendsTo("beta") == 2 }
    }

    @Test fun twoQuickTapsSendOneBurst() = runBlocking<Unit> {
        val w = wake { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); delay(150); sent }
        w.tap("beta"); w.tap("beta")
        until("the facts") { w.others.value["beta"] != null }
        delay(100)
        assertEquals(1, sendsTo("beta"))
    }

    @Test fun nothingToWakeRecordsNothingAndReleasesTheTap() = runBlocking<Unit> {
        val w = wake { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); null }
        w.tap("beta"); delay(100)
        assertNull(w.others.value["beta"])
        w.tap("beta")
        until("a later tap is heard") { sendsTo("beta") == 2 }
    }

    @Test fun switchingTheWatchedMachineKeepsAnotherMachinesFactsAndDropsTheNewWatchedOnes() = runBlocking<Unit> {
        val w = wake()
        w.tap("beta"); w.tap("gamma")
        until("both facts") { w.others.value.keys == setOf("beta", "gamma") }
        // The user watches beta: its "not watching this machine" line is no longer true, gamma's still is.
        watchedId = "beta"
        w.watching("beta")
        assertNull(w.others.value["beta"])
        assertNotNull(w.others.value["gamma"], "watching one machine says nothing about a packet sent to another")
        assertNull(w.facts("beta"), "beta's facts are now the watched tap's, and there has been no tap yet")
    }

    @Test fun watchingAMachineForgetsWhatTheWatchedTapSaidAboutThePreviousOne() = runBlocking<Unit> {
        val w = wake()
        w.tap("alpha")
        until("alpha's facts") { watchedTap.facts.value != null }
        watchedId = "beta"
        w.watching("beta")
        assertNull(watchedTap.facts.value)
        assertNull(w.facts("alpha"), "alpha is not watched now and never had a send-only tap")
    }

    @Test fun aSendStillRunningWhenItsMachineIsForgottenRecordsNothing() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val w = wake { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); release.await(); sent }
        w.tap("beta")
        until("the send started") { sendsTo("beta") == 1 }
        w.forget("beta")
        release.complete(Unit)
        delay(150)
        assertNull(w.others.value["beta"], "a removed machine does not come back with a line about a packet")
    }

    @Test fun aForgetBetweenTheTapAndItsFirstStepRecordsNothing() {
        // A dispatcher that runs nothing until the test says so: the tap's coroutine is queued, the machine is forgotten, then the coroutine runs.
        val queued = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val held = CoroutineScope(SupervisorJob() + java.util.concurrent.Executor { queued.add(it) }.asCoroutineDispatcher())
        try {
            val w = MachineWake(held, clock, watchedTap, { watchedId }) { id -> sends.getOrPut(id) { AtomicInteger() }.incrementAndGet(); sent }
            w.tap("beta")
            assertEquals(0, sendsTo("beta"), "nothing has run yet")
            w.forget("beta")
            while (true) queued.poll()?.run() ?: break
            assertEquals(1, sendsTo("beta"), "the send itself ran")
            assertNull(w.others.value["beta"], "the generation is the one at the tap, so a removal before the send started still records nothing")
        } finally { held.cancel() }
    }
}
