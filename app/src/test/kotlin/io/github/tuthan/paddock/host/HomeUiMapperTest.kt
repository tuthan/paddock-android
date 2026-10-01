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

    @Test fun ageNeverGoesNegativeIfTheClockMovesBack() {
        val s = map(HostView(phase = HostPhase.Connecting, lastHome = model, lastReadAtMillis = now + 5_000)).state as HomeUiState.Degraded
        assertEquals(0L, s.ageMillis)
    }
}
