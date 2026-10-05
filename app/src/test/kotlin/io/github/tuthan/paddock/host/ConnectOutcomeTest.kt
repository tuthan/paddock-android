package io.github.tuthan.paddock.host

import androidx.compose.runtime.saveable.SaverScope
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.DownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectOutcomeTest {
    private val id = "m1"

    @Test fun connectingPhasesAreWaitingAndDecideNothing() {
        for (p in listOf(null, HostPhase.Connecting, HostPhase.InstallingRelay)) {
            assertTrue("$p", ConnectOutcomes.waiting(p))
            assertNull("$p", ConnectOutcomes.outcome(p, id, before = null))
        }
    }

    @Test fun aFailureThatIsTheVeryPhaseOnScreenWhenConnectWasPressedIsTheOldAttemptsAndIsIgnored() {
        val old = HostPhase.Failed(DownReason.AuthFailed, null)
        assertNull(ConnectOutcomes.outcome(old, id, before = old))
        // The same words, a new failure: an instance of its own is this attempt's (a refused key that is refused again must be shown again).
        val again = HostPhase.Failed(DownReason.AuthFailed, null)
        assertEquals(ConnectOutcome.Failed(DownReason.AuthFailed), ConnectOutcomes.outcome(again, id, before = old))
    }

    @Test fun aFailureThatArrivedBeforeAnyoneWasLookingIsStillAnAnswerWithoutEverSeeingConnecting() {
        // The effect starts a frame after Connect and a rotation restarts it: it may never see "connecting" at all. That used to drop the answer.
        assertEquals(ConnectOutcome.Failed(DownReason.AuthFailed), ConnectOutcomes.outcome(HostPhase.Failed(DownReason.AuthFailed, null), id, before = HostPhase.Connecting))
        assertEquals(ConnectOutcome.Failed(DownReason.AuthFailed), ConnectOutcomes.outcome(HostPhase.Failed(DownReason.AuthFailed, null), id, before = null))
    }

    @Test fun aSetupProblemMeansTheConnectionCameUpOnceItIsNotTheOldOne() {
        val old = HostPhase.Problem("herdr was not found")
        assertNull(ConnectOutcomes.outcome(old, id, before = old))
        assertEquals(ConnectOutcome.Connected, ConnectOutcomes.outcome(HostPhase.Problem("herdr was not found"), id, before = old))
        val ask = HostPhase.NeedsRelayInstall("/home/jdoe/.paddock/relay.py", "abc", replacing = false)
        assertEquals(ConnectOutcome.Connected, ConnectOutcomes.outcome(ask, id, before = null))
        assertNull(ConnectOutcomes.outcome(ask, id, before = ask))
    }

    @Test fun aWaitingPhaseIsNotWaitingOnceDecided() {
        assertFalse(ConnectOutcomes.waiting(HostPhase.Failed(DownReason.Timeout, null)))
        assertFalse(ConnectOutcomes.waiting(HostPhase.Problem("x")))
    }

    @Test fun anAttemptIsConnectingUntilItHasAnError() {
        assertTrue(ConnectAttempt("a").connecting)
        assertFalse(ConnectAttempt("a", "nope", ConnectFix.Retry).connecting)
    }

    private fun <T> roundTrip(saver: androidx.compose.runtime.saveable.Saver<T?, Any>, value: T?): T? {
        val saved = with(saver) { SaverScope { true }.save(value) }
        return saved?.let { saver.restore(it) }
    }

    @Test fun anAttemptSurvivesRotationWithItsErrorAndFix() {
        assertNull(roundTrip(ConnectAttempt.Saver, null))
        assertEquals(ConnectAttempt("a"), roundTrip(ConnectAttempt.Saver, ConnectAttempt("a")))
        val failed = ConnectAttempt("a", "The host did not accept this phone's key.", ConnectFix.ShowCommand)
        assertEquals(failed, roundTrip(ConnectAttempt.Saver, failed))
    }

    @Test fun aPairingLinkSurvivesRotationAndKeepsItsHashSoTheFormKeepsItsTypedFields() {
        val fp = "SHA256:" + "A".repeat(43)
        for (link in listOf(
            PairingLink("192.168.1.20", 22, "jdoe", listOf(fp), session = null),
            PairingLink("box.local", 2222, "jdoe", listOf(fp, "SHA256:" + "B".repeat(43)), session = "work", pairPort = 41234, sid = "abcdefghijklmnopqrstuv"),
            PairingLink("box", 22, "u", listOf(fp), session = ""),
        )) {
            val back = roundTrip(PairingLinkSaver, link)
            assertNotNull(link.toString(), back)
            assertEquals(link, back)
            assertEquals("the form's saved state is keyed by this", link.hashCode(), back.hashCode())
        }
        assertNull(roundTrip(PairingLinkSaver, null))
    }

    @Test fun aSavedLinkThatIsNotOneIsNoLink() {
        assertNull(PairingLinkSaver.restore(listOf("only", "three", "fields")))
        assertNull(PairingLinkSaver.restore(listOf("h", "notaport", "u", "fp", null, null, null)))
    }
}
