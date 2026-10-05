package io.github.tuthan.paddock.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTextTest {
    private val fp = "SHA256:abcdefgh1234567890ABCDEFGHIJKLMNOPQRSTUVWXY"
    private val pending = PendingPairing("a".repeat(22), "192.168.42.86", 41234, fp, "ecdsa-sha2-nistp256 AAAA", 1_000, 121_000, 1)
    private val now = 61_000L

    @Test fun theHeadIsTheFirstEightCharactersAfterTheAlgorithmName() {
        assertEquals("abcdefgh", PairingText.fingerprintHead(fp))
        assertEquals("abcdefgh", PairingText.fingerprintHead("abcdefghijk"))
    }

    @Test fun idleAndCancelledShowNothing() {
        assertNull(PairingText.view(PairingState.Idle, now))
        assertNull(PairingText.view(PairingState.Cancelled, now))
    }

    @Test fun anActiveRequestCountsDownAndAlwaysSaysToCompareTheFingerprint() {
        for (s in listOf(PairingState.Sending(pending), PairingState.Waiting(pending), PairingState.Unreachable(pending, "x"))) {
            val v = assertNotNull(PairingText.view(s, now), "$s")
            assertTrue(v.active, "$s")
            assertEquals(60, v.secondsLeft, "$s")
        }
        assertTrue(PairingText.COMPARE in PairingText.view(PairingState.Waiting(pending), now)!!.detail)
    }

    @Test fun theCountdownNeverGoesBelowZero() {
        assertEquals(0, PairingText.view(PairingState.Waiting(pending), 500_000)!!.secondsLeft)
    }

    @Test fun everyTerminalStateIsInactiveWithItsOwnSentence() {
        val states = listOf(
            PairingState.Approved(pending), PairingState.Rejected(pending), PairingState.Expired(pending), PairingState.Refused(pending),
            PairingState.Busy(pending), PairingState.NotConfirmed(pending), PairingState.CannotReach(pending, null),
        )
        val headlines = states.map { s -> PairingText.view(s, now)!!.also { assertFalse(it.active, "$s"); assertNull(it.secondsLeft, "$s") }.headline }
        assertEquals(headlines.size, headlines.toSet().size)
    }

    @Test fun onlyApprovedConnectsByItselfAndOnlyAnUnheardWindowOffersConnect() {
        assertTrue(PairingText.view(PairingState.Approved(pending), now)!!.approved)
        assertTrue(PairingText.view(PairingState.NotConfirmed(pending), now)!!.offerConnect)
        for (s in listOf(PairingState.Rejected(pending), PairingState.Expired(pending), PairingState.Refused(pending), PairingState.Busy(pending), PairingState.CannotReach(pending, null))) {
            val v = PairingText.view(s, now)!!
            assertFalse(v.approved, "$s")
            assertFalse(v.offerConnect, "$s")
        }
    }

    @Test fun anUnreachableDesktopNamesTheHostPortAndTheHostFlag() {
        val v = PairingText.view(PairingState.Unreachable(pending, "x"), now)!!
        assertTrue("192.168.42.86:41234" in v.headline)
        assertTrue("--host" in v.detail)
    }

    @Test fun theClockIsMinutesAndSeconds() {
        assertEquals("1:59", PairingText.clock(119))
        assertEquals("0:05", PairingText.clock(5))
        assertEquals("2:00", PairingText.clock(120))
    }
}
