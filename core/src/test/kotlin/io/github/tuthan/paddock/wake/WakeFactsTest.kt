package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeFactsTest {
    private fun ip(s: String) = Ipv4Subnet.literal(s)!!
    private val clock = { m: Long -> "t$m" }
    private val sent = WakeSendResult.Sent(listOf(ip("192.168.42.255"), ip("255.255.255.255")))

    @Test fun threeSeparateFactsAreShownAndTheLaterTwoStartAsNotYet() {
        val f = WakeFacts(1_000, sent)
        assertEquals(
            listOf("Wake packet sent to 192.168.42.255 and 255.255.255.255.", "The machine answered: not yet", "herdr reachable: not yet"),
            f.lines(clock),
        )
    }

    @Test fun eachLaterFactCarriesItsOwnTime() {
        val f = WakeFacts(1_000, sent, answeredAtMillis = 5_000, reachableAtMillis = 7_000)
        assertEquals(listOf("The machine answered: at t5000", "herdr reachable: at t7000"), f.lines(clock).drop(1))
        assertEquals(listOf("The machine answered: at t5000", "herdr reachable: not yet"), f.copy(reachableAtMillis = null).lines(clock).drop(1))
    }

    @Test fun aRelaySendSaysNothingReachedTheNetworkFromHere() {
        val line = WakeFacts(0, WakeSendResult.Sent(listOf(ip("192.168.1.1")), viaRelay = true)).lines(clock).first()
        assertEquals("Wake packet sent to the relay 192.168.1.1. The relay must re-broadcast it; nothing reached the machine's network from here.", line)
    }

    @Test fun aFailureShowsOneLineAndNoAnsweredFacts() {
        for (r in WakeSendFailure.entries) {
            val f = WakeFacts(0, WakeSendResult.Failed(r))
            assertEquals(1, f.lines(clock).size, "$r")
            assertTrue(f.lines(clock).single().startsWith("No wake packet was sent: "), "$r")
            assertFalse(f.transmitted)
        }
    }

    @Test fun aPartialSendStillShowsTheFactsAndSaysItStopped() {
        val f = WakeFacts(0, WakeSendResult.Partial(listOf(ip("192.168.42.255")), WakeSendFailure.SendFailed, "IOException: boom"))
        assertTrue(f.transmitted)
        assertEquals(3, f.lines(clock).size)
        assertTrue("then sending stopped: the network refused it (IOException: boom)" in f.lines(clock).first())
    }

    @Test fun theRelayRequiredSentenceNamesTheFix() {
        val f = WakeFacts(0, WakeSendResult.Failed(WakeSendFailure.RelayRequired))
        assertTrue(f.needsRelay)
        assertTrue("needs a relay on the machine's network" in f.lines(clock).single())
    }

    @Test fun anotherWakeIsOfferedOnlyAfterThirtySeconds() {
        val f = WakeFacts(10_000, sent)
        assertFalse(f.canWakeAgain(10_000))
        assertFalse(f.canWakeAgain(39_999))
        assertTrue(f.canWakeAgain(40_000))
        assertEquals(30, f.secondsUntilAgain(10_000))
        assertEquals(1, f.secondsUntilAgain(39_001))
        assertEquals(0, f.secondsUntilAgain(60_000))
    }

    @Test fun aSendThatTransmittedNothingDoesNotStartTheGuard() {
        // Nothing left the phone, so there is nothing for a second packet to repeat: the action stays.
        for (r in WakeSendFailure.entries) {
            val f = WakeFacts(10_000, WakeSendResult.Failed(r))
            assertTrue(f.canWakeAgain(10_000), "$r")
            assertEquals(0, f.secondsUntilAgain(10_000), "$r")
        }
    }

    @Test fun aPartialSendStartsTheGuardBecauseSomethingLeftThePhone() {
        val f = WakeFacts(10_000, WakeSendResult.Partial(listOf(ip("192.168.42.255")), WakeSendFailure.SendFailed))
        assertFalse(f.canWakeAgain(10_001))
        assertTrue(f.canWakeAgain(40_000))
    }
}
