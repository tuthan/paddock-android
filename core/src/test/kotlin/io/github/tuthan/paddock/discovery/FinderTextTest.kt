package io.github.tuthan.paddock.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinderTextTest {
    @Test fun aBlankPortMeansSshsOwnAlone() = assertEquals(TypedPort(null, null), FinderText.port("  "))

    @Test fun aPortInRangeIsKeptAndTrimmed() {
        assertEquals(TypedPort(2233, null), FinderText.port(" 2233 "))
        assertEquals(TypedPort(65535, null), FinderText.port("65535"))
        assertEquals(TypedPort(1, null), FinderText.port("1"))
    }

    @Test fun anythingElseIsAnErrorAndNeverAPort() {
        for (bad in listOf("0", "65536", "-1", "22a", "2 2", "1e3", "٢٢", "+22", "999999999999", "0x16")) {
            assertEquals(TypedPort(null, FinderText.PORT_ERROR), FinderText.port(bad), bad)
        }
    }

    @Test fun theEndedScanSaysWhatWasFoundAndWhatWasAndWasNotDoneToTheServers() {
        // The probe does open a TCP connection to every answering address, so the sentence must not say nothing was connected to.
        val host = FoundHost("10.0.0.5", 22, null, "OpenSSH 9.9")
        val tail = "Nothing was signed in to, trusted or saved; Paddock only read the first line each server sent."
        assertEquals("Found 1 machine. Tap one to fill in its address and port. $tail", FinderText.result(FinderState(FinderPhase.Done, rows = listOf(host))))
        assertEquals("Found 2 machines. Tap one to fill in its address and port. $tail", FinderText.result(FinderState(FinderPhase.Done, rows = listOf(host, host.copy(address = "10.0.0.6")))))
        assertEquals(false, "connected" in FinderText.result(FinderState(FinderPhase.Done, rows = listOf(host)))!!)
    }

    @Test fun anEmptyResultSaysSoAndOffersTheTypedAddress() {
        assertEquals(true, FinderText.result(FinderState(FinderPhase.Done))!!.contains("type its address"))
    }

    @Test fun aStoppedScanKeepsItsCount() {
        assertEquals("Stopped. Found so far: none.", FinderText.result(FinderState(FinderPhase.Cancelled)))
        assertEquals("Stopped. Found so far: 1 machine.", FinderText.result(FinderState(FinderPhase.Cancelled, rows = listOf(FoundHost("10.0.0.5", 22, null, null)))))
    }

    @Test fun nothingIsSaidBeforeOrDuringAScan() {
        assertNull(FinderText.result(FinderState(FinderPhase.Idle)))
        assertNull(FinderText.result(FinderState(FinderPhase.Scanning)))
        assertEquals("Tried 12 of 508 addresses", FinderText.progress(FinderState(FinderPhase.Scanning, done = 12, total = 508)))
        assertNull(FinderText.progress(FinderState(FinderPhase.Done, done = 508, total = 508)))
    }
}
