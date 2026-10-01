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
        assertEquals(Recovery.Retry, r.recovery)
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
}
