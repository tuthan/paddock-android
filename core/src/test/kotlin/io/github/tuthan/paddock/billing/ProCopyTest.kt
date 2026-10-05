package io.github.tuthan.paddock.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProCopyTest {
    @Test
    fun withNothingGatedTheCopyPromisesNothing() {
        val text = ProCopy.coverage(emptySet())
        assertTrue("No capability is Pro yet" in text)
        assertTrue("unlock nothing" in text)
    }

    @Test
    fun theCopyNamesWhatExistsAndNeverWhatDoesNot() {
        val text = ProCopy.coverage(ProGate.GATED)
        for (c in ProCapabilities.ALL.filter { it.built }) assertTrue(c.label.replaceFirstChar { it.lowercase() } in text, "${c.id}: $text")
        assertFalse("several machines" in text, "the reserved multi-host capability is not offered until something merges machines: $text")
        assertFalse("No capability is Pro yet" in text, text)
    }

    @Test
    fun freeStillCoversWhatTheVaultSaysFreeNeverHides() {
        val text = ProCopy.coverage(ProGate.GATED)
        assertTrue("every view of your agents" in text && "every alert" in text && "typing into any terminal" in text && "snippets" in text, text)
    }

    @Test
    fun theGateTitleNamesTheChosenCapability() {
        assertEquals("Starting an agent is a Pro capability", ProCopy.gateTitle("operations.start"))
        assertEquals("This is a Pro capability", ProCopy.gateTitle("not.a.capability"))
    }

    @Test
    fun aSingleCapabilityReadsAsASentence() {
        assertEquals("Pro covers starting an agent. ${ProCopy.STAYS_FREE}", ProCopy.coverage(setOf("operations.start")))
    }

    @Test
    fun refundsAreGooglePlaysAndNoPaddockAccountExists() {
        assertTrue("Google Play" in ProCopy.REFUNDS && "no account" in ProCopy.REFUNDS)
    }
}
