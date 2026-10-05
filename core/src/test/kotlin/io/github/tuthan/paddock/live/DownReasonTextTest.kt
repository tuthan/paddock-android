package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.ports.DownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownReasonTextTest {
    private val all: List<DownReason> = listOf(
        DownReason.Closed, DownReason.Timeout, DownReason.LocalNetworkTimeout, DownReason.Refused, DownReason.AuthFailed,
        DownReason.HostKeyChanged, DownReason.PermissionDenied, DownReason.KeyUnavailable, DownReason.HostKeysUnreadable,
        DownReason.Network("no route to host"),
    )

    @Test fun everyReasonHasASentenceAndAFix() {
        for (r in all) {
            assertTrue(DownReasonText.sentence(r).isNotBlank(), "$r")
            DownReasonText.fix(r)
        }
    }

    @Test fun theFixMatchesWhatTheFailureNeeds() {
        assertEquals(ConnectFix.ShowCommand, DownReasonText.fix(DownReason.AuthFailed))
        assertEquals(ConnectFix.ReviewKey, DownReasonText.fix(DownReason.HostKeyChanged))
        assertEquals(ConnectFix.SetUpKey, DownReasonText.fix(DownReason.KeyUnavailable))
        for (r in listOf(DownReason.PermissionDenied, DownReason.LocalNetworkTimeout, DownReason.HostKeysUnreadable))
            assertEquals(ConnectFix.OpenSettings, DownReasonText.fix(r), "$r")
        for (r in listOf(DownReason.Timeout, DownReason.Refused, DownReason.Closed, DownReason.Network("x")))
            assertEquals(ConnectFix.Retry, DownReasonText.fix(r), "$r")
    }

    @Test fun theSentencesAreTheOnesHomeAlwaysShowed() {
        assertEquals("The host did not accept this phone's key. Authorize it on the host, then try again.", DownReasonText.sentence(DownReason.AuthFailed))
        assertEquals("The host's key changed. Nothing was signed in.", DownReasonText.sentence(DownReason.HostKeyChanged))
        assertEquals("The connection was not accepted.", DownReasonText.sentence(DownReason.Refused))
        assertEquals("Local-network access is off, so Paddock cannot reach this address.", DownReasonText.sentence(DownReason.PermissionDenied))
    }

    @Test fun theRetryWaitIsAppendedOnlyWhereItMeans() {
        assertEquals("The host did not answer in time. Trying again in 5 s.", DownReasonText.sentence(DownReason.Timeout, 5))
        assertEquals("Disconnected. Trying again in 1 s.", DownReasonText.sentence(DownReason.Closed, 1))
        assertEquals("Cannot reach the host: no route to host. Trying again in 3 s.", DownReasonText.sentence(DownReason.Network("no route to host"), 3))
        assertEquals("Cannot reach the host: no route to host.", DownReasonText.sentence(DownReason.Network("no route to host")))
        assertEquals("Cannot reach the host: closed.", DownReasonText.sentence(DownReason.Network("closed.")))
    }
}
