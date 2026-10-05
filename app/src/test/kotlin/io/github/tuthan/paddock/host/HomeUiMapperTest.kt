package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ui.screens.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiMapperTest {
    private val model = HomeModel(emptyList(), "Nothing needs you.", quiet = true)
    private val now = 1_000_000L

    private fun map(v: HostView) = HomeUiMapper.map("Laptop", v, now)

    @Test fun nothingYetIsLoading() {
        assertEquals(HomeUiState.Loading("Laptop"), map(HostView()).state)
        assertEquals(HomeUiState.Loading("Laptop"), map(HostView(phase = HostPhase.Connecting)).state)
    }

    @Test fun reconnectingKeepsTheLastHomeDimmedWithItsAge() {
        val s = map(HostView(phase = HostPhase.Connecting, lastHome = model, lastReadAtMillis = now - 40_000)).state as HomeUiState.Degraded
        assertEquals(model, s.model)
        assertEquals(40_000L, s.ageMillis)
    }

    @Test fun aFailedAuthWaitsForTheUserWithATryAgainAction() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.AuthFailed, null)))
        assertEquals(Recovery.Retry, r.recovery)
        assertTrue((r.state as HomeUiState.Degraded).reason.contains("did not accept this phone's key"))
    }

    @Test fun aDeniedGrantOffersSettingsAndNamesTheReason() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.PermissionDenied, null)))
        assertEquals(Recovery.OpenSettings, r.recovery)
        val s = r.state as HomeUiState.Degraded
        assertTrue(s.reason.startsWith("Local-network access is off"))
        assertEquals("Open settings", s.recoveryLabel)
    }

    @Test fun aChangedKeyOffersReviewAndNeverRetriesOnItsOwn() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.HostKeyChanged, null)))
        assertEquals(Recovery.ReviewKey, r.recovery)
        assertTrue((r.state as HomeUiState.Degraded).reason.contains("Nothing was signed in"))
    }

    @Test fun aTransientFailureSaysWhenItRetriesAndOffersNoManualAction() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.Network("no route"), now + 4_200)))
        val s = r.state as HomeUiState.Degraded
        assertTrue(s.reason, s.reason.contains("Trying again in 5 s."))
        assertNull(r.recovery)
        assertNull(s.recoveryLabel)
    }

    @Test fun aFailureWithNoScheduledRetryOffersTryAgain() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.Timeout, null)))
        assertEquals(Recovery.Retry, r.recovery)
    }

    @Test fun aTimeoutWithoutTheGrantSuggestsLocalNetworkAccessAndOffersSettingsWhileItRetries() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.LocalNetworkTimeout, now + 1_500)))
        val s = r.state as HomeUiState.Degraded
        assertTrue(s.reason, s.reason.contains("check that Paddock has local-network access"))
        assertTrue(s.reason, s.reason.contains("Trying again in 2 s."))
        assertEquals(Recovery.OpenSettings, r.recovery)
        assertEquals("Open settings", s.recoveryLabel)
    }

    @Test fun anUnreadableKeySaysSoInPlainWordsAndNeverMentionsTheNetwork() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.KeyUnavailable, null)))
        val s = r.state as HomeUiState.Degraded
        assertTrue(s.reason, s.reason.startsWith("The key stored on this phone can't be read. Import it again or create a new phone key"))
        assertTrue(s.reason, "reach" !in s.reason)
        // Retrying cannot fix it: the action opens the key setup for this machine.
        assertEquals(Recovery.SetUpKey, r.recovery)
        assertEquals("Set up the key", s.recoveryLabel)
    }

    @Test fun unreadableHostKeysPointToTheStorageReset() {
        val r = map(HostView(phase = HostPhase.Failed(DownReason.HostKeysUnreadable, null)))
        val s = r.state as HomeUiState.Degraded
        assertTrue(s.reason, s.reason.startsWith("Saved host keys can't be read"))
        assertTrue(s.reason, s.reason.contains("Nothing was signed in"))
        assertEquals(Recovery.OpenSettings, r.recovery)
        assertEquals("Open settings", s.recoveryLabel)
    }

    @Test fun aMissingRelayCarriesThePromptAndOffersToReviewIt() {
        val ask = HostPhase.NeedsRelayInstall("/home/x/.local/share/paddock/paddock-relay.py", "ab".repeat(32), replacing = false)
        val r = map(HostView(phase = ask))
        assertEquals(Recovery.InstallRelay, r.recovery)
        assertEquals(ask, r.relayPrompt)
        assertNull(r.state.let { (it as HomeUiState.Degraded).model })
    }

    @Test fun aHostProblemIsStatedAsAHostFactWithTryAgain() {
        val r = map(HostView(phase = HostPhase.Problem("No herdr session is running on the host.")))
        assertEquals("No herdr session is running on the host.", (r.state as HomeUiState.Degraded).reason)
        assertEquals(Recovery.Retry, r.recovery)
    }

    @Test fun aStaleMonitorSaysWhatWentWrongAsAHostFactWithTheLastReadTime() {
        fun reason(loss: Throwable?) = HomeUiMapper.staleReason("Laptop", loss, "14:02")
        assertTrue(reason(io.github.tuthan.paddock.relay.RelayTimeout("session.snapshot", kotlin.time.Duration.parse("10s"))).startsWith("herdr on Laptop is not answering."))
        assertTrue(reason(io.github.tuthan.paddock.relay.HerdrError("server_busy", "x")).contains("refused a read (server_busy)"))
        assertTrue(reason(io.github.tuthan.paddock.herdr.ProtocolError.NotJson(RuntimeException())).contains("an answer Paddock can't read"))
        assertTrue(reason(java.io.EOFException()).startsWith("The connection to herdr on Laptop was lost."))
        assertTrue(reason(null).endsWith("Last read at 14:02."))
        assertEquals("The connection to herdr on Laptop was lost. Paddock is reconnecting.", HomeUiMapper.staleReason("Laptop", null, null))
    }

    @Test fun ageNeverGoesNegativeIfTheClockMovesBack() {
        val s = map(HostView(phase = HostPhase.Connecting, lastHome = model, lastReadAtMillis = now + 5_000)).state as HomeUiState.Degraded
        assertEquals(0L, s.ageMillis)
    }

    private fun mapWake(reason: DownReason, retryAt: Long? = null) = HomeUiMapper.map("Laptop", HostView(phase = HostPhase.Failed(reason, retryAt)), now, wakeAvailable = true)

    @Test fun aMachineThatMaySimplyBeAsleepOffersWakeFirstAndTryAgainBeside() {
        for (reason in listOf(DownReason.Timeout, DownReason.Closed, DownReason.Network("no route"))) {
            val r = mapWake(reason)
            val s = r.state as HomeUiState.Degraded
            assertEquals("$reason", Recovery.Wake, r.recovery)
            assertEquals("$reason", Recovery.Retry, r.secondary)
            assertEquals("Wake the machine", s.recoveryLabel)
            assertEquals("Try again", s.secondaryLabel)
        }
    }

    @Test fun wakeIsOfferedEvenWhilePaddockIsAboutToRetryItself() {
        val r = mapWake(DownReason.Timeout, now + 4_000)
        assertEquals(Recovery.Wake, r.recovery)
        assertTrue((r.state as HomeUiState.Degraded).reason.contains("Trying again in 4 s"))
    }

    @Test fun theMissingGrantKeepsItsFixFirstAndWakeIsTheSecondPossibility() {
        val r = mapWake(DownReason.LocalNetworkTimeout)
        assertEquals(Recovery.OpenSettings, r.recovery)
        assertEquals(Recovery.Wake, r.secondary)
        assertEquals("Wake the machine", (r.state as HomeUiState.Degraded).secondaryLabel)
    }

    @Test fun failuresThatAreNotSilenceNeverOfferWake() {
        for (reason in listOf(DownReason.AuthFailed, DownReason.HostKeyChanged, DownReason.Refused, DownReason.PermissionDenied, DownReason.KeyUnavailable, DownReason.HostKeysUnreadable)) {
            val r = mapWake(reason)
            assertTrue("$reason", r.recovery != Recovery.Wake && r.secondary != Recovery.Wake)
            assertNull("$reason", (r.state as HomeUiState.Degraded).secondaryLabel)
        }
    }

    @Test fun withoutWakeAvailableNothingChanges() {
        val r = HomeUiMapper.map("Laptop", HostView(phase = HostPhase.Failed(DownReason.Timeout, null)), now)
        assertEquals(Recovery.Retry, r.recovery)
        assertNull(r.secondary)
        assertNull((r.state as HomeUiState.Degraded).secondaryLabel)
    }
}
