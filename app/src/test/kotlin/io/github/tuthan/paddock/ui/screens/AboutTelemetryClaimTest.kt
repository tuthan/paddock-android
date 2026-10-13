package io.github.tuthan.paddock.ui.screens

import io.github.tuthan.paddock.billing.Distribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * About's version row says what is known about telemetry (Phase 16, slice 4). The `play` build's billing library has not been measured on a phone, so
 * that flavor claims only Paddock's own code; the foss sentence is unchanged.
 */
class AboutTelemetryClaimTest {
    @Test fun aBuildThatDoesNotSellProKeepsTheOriginalSentence() =
        assertEquals("no analytics, no crash uploads", aboutTelemetryClaim(sellsPro = false))

    @Test fun aBuildThatSellsProClaimsPaddocksOwnCodeOnly() =
        assertEquals("no analytics or crash uploads of Paddock's own", aboutTelemetryClaim(sellsPro = true))

    @Test fun theSentenceDiffersOnlyWhenTheBuildSellsPro() {
        assertNotEquals(aboutTelemetryClaim(sellsPro = false), aboutTelemetryClaim(sellsPro = true))
        // Whichever flavor this test runs in: its claim differs from the foss one exactly when it sells Pro.
        assertEquals(Distribution.SELLS_PRO, aboutTelemetryClaim(Distribution.SELLS_PRO) != aboutTelemetryClaim(sellsPro = false))
    }

    @Test fun neitherSentenceClaimsAnythingAboutTheNetwork() {
        for (sellsPro in listOf(false, true)) {
            val claim = aboutTelemetryClaim(sellsPro)
            assertFalse(claim, claim.contains("nothing leaves") || claim.contains("only SSH"))
            assertTrue(claim, claim.startsWith("no analytics"))
        }
    }

    @Test fun thePolicyAddressIsOneHttpsUrl() {
        assertTrue(PRIVACY_POLICY_URL.startsWith("https://"))
        assertFalse(PRIVACY_POLICY_URL.any { it.isWhitespace() })
    }
}
