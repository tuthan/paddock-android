package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.DownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectOutcomeTest {
    @Test fun connectingPhasesAreWaitingAndDecideNothing() {
        for (p in listOf(null, HostPhase.Connecting, HostPhase.InstallingRelay)) {
            assertTrue("$p", ConnectOutcomes.waiting(p))
            assertNull("$p", ConnectOutcomes.outcome(p, sawConnecting = true))
        }
    }

    @Test fun aFailureSeenBeforeAnyConnectingIsTheOldAttemptsAndIsIgnored() {
        assertNull(ConnectOutcomes.outcome(HostPhase.Failed(DownReason.AuthFailed, null), sawConnecting = false))
        assertEquals(ConnectOutcome.Failed(DownReason.AuthFailed), ConnectOutcomes.outcome(HostPhase.Failed(DownReason.AuthFailed, null), sawConnecting = true))
    }

    @Test fun aSetupProblemMeansTheConnectionCameUpOnceItIsThisAttempts() {
        assertNull(ConnectOutcomes.outcome(HostPhase.Problem("herdr was not found"), sawConnecting = false))
        assertEquals(ConnectOutcome.Connected, ConnectOutcomes.outcome(HostPhase.Problem("herdr was not found"), sawConnecting = true))
        val ask = HostPhase.NeedsRelayInstall("/home/jdoe/.paddock/relay.py", "abc", replacing = false)
        assertEquals(ConnectOutcome.Connected, ConnectOutcomes.outcome(ask, sawConnecting = true))
        assertNull(ConnectOutcomes.outcome(ask, sawConnecting = false))
    }

    @Test fun aWaitingPhaseIsNotWaitingOnceDecided() {
        assertFalse(ConnectOutcomes.waiting(HostPhase.Failed(DownReason.Timeout, null)))
        assertFalse(ConnectOutcomes.waiting(HostPhase.Problem("x")))
    }

    @Test fun anAttemptIsConnectingUntilItHasAnError() {
        assertTrue(ConnectAttempt("a").connecting)
        assertFalse(ConnectAttempt("a", "nope", ConnectFix.Retry).connecting)
    }
}
