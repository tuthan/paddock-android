package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeTapTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val sent = WakeSendResult.Sent(listOf(Ipv4Subnet.literal("192.168.1.255")!!))
    private val answeredNotLive = WakeLink(answered = true, reachable = false)
    private val live = WakeLink(answered = true, reachable = true)

    @Volatile private var now = 1_000L
    private val clock = Clock { now }
    private val sends = AtomicInteger()
    private val reconnects = AtomicInteger()
    private val link = MutableStateFlow(WakeLink.DOWN)

    private fun tap(followMillis: Long = 5_000, send: suspend () -> WakeSendResult? = { sends.incrementAndGet(); sent }) =
        WakeTap(scope, clock, send, { link.value }, link, { reconnects.incrementAndGet() }, followMillis)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(3_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun twoQuickTapsSendOneBurst() = runBlocking<Unit> {
        val t = tap { sends.incrementAndGet(); delay(150); sent }
        t.tap(); t.tap()
        until("the facts") { t.facts.value != null }
        delay(100)
        assertEquals(1, sends.get())
        // and the guard that follows holds: a third tap inside 30 s sends nothing either
        t.tap(); delay(100)
        assertEquals(1, sends.get())
    }

    @Test fun aTapThatSentNothingLeavesTheActionWhereItWas() = runBlocking<Unit> {
        val failed = WakeSendResult.Failed(WakeSendFailure.Permission)
        val t = tap { sends.incrementAndGet(); failed }
        t.tap()
        until("the first facts") { t.facts.value != null }
        assertEquals(0, reconnects.get(), "nothing was sent, so there is nothing to reconnect for")
        delay(50)
        t.tap()
        until("the second send") { sends.get() == 2 }
        assertEquals(failed, t.facts.value!!.result)
    }

    @Test fun nothingToWakeForShowsNothing() = runBlocking<Unit> {
        val t = tap { null }
        t.tap(); delay(100)
        assertNull(t.facts.value)
        t.tap(); delay(100)
        assertNull(t.facts.value, "and the flag is released, so a later tap is heard")
    }

    @Test fun aSentPacketAsksForAReconnectAndFollowsTheConnectionFromAChange() = runBlocking<Unit> {
        val t = tap()
        t.tap()
        until("the facts and the reconnect") { t.facts.value != null && reconnects.get() == 1 }
        assertNull(t.facts.value!!.answeredAtMillis)
        now = 4_000; link.value = answeredNotLive
        until("answered") { t.facts.value!!.answeredAtMillis != null }
        assertEquals(4_000, t.facts.value!!.answeredAtMillis)
        assertNull(t.facts.value!!.reachableAtMillis)
        now = 9_000; link.value = live
        until("reachable") { t.facts.value!!.reachableAtMillis != null }
        assertEquals(9_000, t.facts.value!!.reachableAtMillis)
        assertTrue(t.facts.value!!.settled)
    }

    @Test fun whatWasAlreadyTrueAtTheTapIsNotRecordedAsAnAnswerToIt() = runBlocking<Unit> {
        // The connection reached the SSH step (a setup problem) before the tap: that is not the machine answering this packet.
        link.value = answeredNotLive
        val t = tap()
        t.tap()
        until("the facts") { t.facts.value != null }
        delay(150)
        assertNull(t.facts.value!!.answeredAtMillis)
        assertNull(t.facts.value!!.reachableAtMillis)
        now = 7_000; link.value = live
        until("both facts") { t.facts.value!!.settled }
        assertEquals(7_000, t.facts.value!!.answeredAtMillis)
        assertEquals(7_000, t.facts.value!!.reachableAtMillis)
    }

    @Test fun aMachineAlreadyLiveIsToldSoAndNoFactIsInventedAtTheTap() = runBlocking<Unit> {
        link.value = live
        val t = tap()
        t.tap()
        until("the facts") { t.facts.value != null }
        val f = t.facts.value!!
        assertTrue(f.alreadyLive)
        assertNull(f.answeredAtMillis)
        assertNull(f.reachableAtMillis)
        assertEquals(
            listOf("Wake packet sent to 192.168.1.255.", "The machine was already connected, and herdr already live, when you tapped."),
            f.lines { "t$it" },
        )
    }

    @Test fun aViewThatDropsAfterTheTapAndComesBackCountsOnlyTheComingBack() = runBlocking<Unit> {
        // A connection that looked live but is dead: the tap, then the drop, then a real reconnect.
        link.value = answeredNotLive
        val t = tap()
        t.tap()
        until("the facts") { t.facts.value != null }
        now = 2_000; link.value = WakeLink.DOWN
        delay(100)
        assertNull(t.facts.value!!.answeredAtMillis)
        now = 6_000; link.value = live
        until("both facts") { t.facts.value!!.settled }
        assertEquals(6_000, t.facts.value!!.reachableAtMillis)
    }

    @Test fun forgettingClearsTheFactsAndStopsFollowing() = runBlocking<Unit> {
        val t = tap()
        t.tap()
        until("the facts") { t.facts.value != null }
        t.forget()
        assertNull(t.facts.value)
        link.value = live
        delay(150)
        assertNull(t.facts.value, "a connection coming up later must not bring the old machine's facts back")
    }

    @Test fun followingEndsAfterItsTime() = runBlocking<Unit> {
        val t = tap(followMillis = 100)
        t.tap()
        until("the facts") { t.facts.value != null }
        delay(300)
        link.value = live
        delay(150)
        val f = assertNotNull(t.facts.value)
        assertNull(f.answeredAtMillis)
        assertFalse(f.settled)
    }
}

class WakeLinkTest {
    @Test fun anyPhasePastConnectingIsTheMachineAnsweringAndNothingElseIsAnAnswer() {
        assertEquals(WakeLink(true, false), WakeLink.of(HostPhase.Problem("x"), null))
        assertEquals(WakeLink(true, false), WakeLink.of(HostPhase.InstallingRelay, null))
        assertEquals(WakeLink(true, false), WakeLink.of(HostPhase.NeedsRelayInstall("~/.local/bin/x", "0".repeat(64), replacing = false), null))
        assertEquals(WakeLink.DOWN, WakeLink.of(HostPhase.Connecting, null))
        assertEquals(WakeLink.DOWN, WakeLink.of(HostPhase.Failed(DownReason.Timeout, null), null))
        assertEquals(WakeLink.DOWN, WakeLink.of(null, null))
    }
}
