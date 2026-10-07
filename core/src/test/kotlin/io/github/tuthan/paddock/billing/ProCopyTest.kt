package io.github.tuthan.paddock.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertTrue("seeing which saved machines need you on Home" in text, "the chip counts are built and named: $text")
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

    // ---- D6: the gate sheet's list and the free path ------------------------------------------------------------------------------

    private val builtLabels = listOf(
        "Answering permission requests with Yes and No", "Starting an agent", "Stopping and deleting sessions", "Home-screen widgets", "Switching between saved machines",
        "Seeing which saved machines need you on Home",
    )

    @Test
    fun theListNamesEveryBuiltCapabilityInTheOrderOfTheOneListWithTheirCapitals() {
        assertEquals(builtLabels, ProCopy.coverageLines(ProGate.GATED))
        assertEquals(builtLabels, ProCopy.coverageLines(ProGate.GATED, first = null))
        assertFalse(ProCopy.coverageLines(ProGate.GATED).any { "one list" in it || "queue" in it }, "nothing merges machines into one list, so no label says it does")
    }

    @Test
    fun theChosenCapabilityLeadsTheListAndTheRestKeepTheirOrder() {
        assertEquals(
            listOf("Stopping and deleting sessions", "Answering permission requests with Yes and No", "Starting an agent", "Home-screen widgets", "Switching between saved machines", "Seeing which saved machines need you on Home"),
            ProCopy.coverageLines(ProGate.GATED, first = "operations.manage"),
        )
        assertEquals(
            listOf("Switching between saved machines", "Answering permission requests with Yes and No", "Starting an agent", "Stopping and deleting sessions", "Home-screen widgets", "Seeing which saved machines need you on Home"),
            ProCopy.coverageLines(ProGate.GATED, first = "hosts.switch"),
        )
        assertEquals("Seeing which saved machines need you on Home", ProCopy.coverageLines(ProGate.GATED, first = "hosts.merged").first())
        // Already first, or unknown: the order of the one list stands.
        assertEquals(builtLabels, ProCopy.coverageLines(ProGate.GATED, first = "answers.guarded"))
        assertEquals(builtLabels, ProCopy.coverageLines(ProGate.GATED, first = "not.a.capability"))
    }

    @Test
    fun theListHoldsOnlyWhatIsGated() {
        assertEquals(listOf("Home-screen widgets", "Starting an agent"), ProCopy.coverageLines(setOf("operations.start", "widgets"), first = "widgets"))
        assertEquals(emptyList(), ProCopy.coverageLines(emptySet(), first = "widgets"))
        assertEquals(listOf("Seeing which saved machines need you on Home"), ProCopy.coverageLines(setOf("hosts.merged")))
        assertEquals(emptyList(), ProCopy.coverageLines(setOf("not.a.capability")))
        assertEquals("Pro covers:", ProCopy.COVERS)
    }

    @Test
    fun theSentenceFormIsUnchanged() {
        assertEquals(
            "Pro covers answering permission requests with Yes and No, starting an agent, stopping and deleting sessions, home-screen widgets, switching between saved machines, and seeing which saved machines need you on Home. " +
                ProCopy.STAYS_FREE,
            ProCopy.coverage(ProGate.GATED),
        )
    }

    @Test
    fun everyBuiltCapabilityNamesItsFreePath() {
        val expected = mapOf(
            "answers.guarded" to "Free: type the answer in the Terminal tab or in Manual input; every alert still arrives.",
            "operations.start" to "Free: start the agent in herdr on the machine; Paddock shows it as soon as it runs.",
            "operations.manage" to "Free: stop or delete the session in herdr on the machine; reading and re-reading sessions stays free.",
            "widgets" to "Free: the app itself shows the same herd.",
            "hosts.switch" to "Free: return to the machine you chose yourself (an alert never changes that), add the machine again from Machines, or open it from an alert.",
            "hosts.merged" to "Free: alerts still arrive for every machine; open one from its alert, or watch it to see its agents.",
        )
        assertEquals(ProCapabilities.ALL.filter { it.built }.map { it.id }.toSet(), expected.keys, "every built capability has a pinned free path")
        for ((id, sentence) in expected) {
            assertEquals(sentence, ProCapabilities.freePath(id), id)
            assertEquals(sentence, ProCapabilities.byId(id)!!.freePath, id)
        }
    }

    // ---- D5: the overview Settings opens (the "What Pro covers" row and its sheet) -----------------------------------------------------

    @Test
    fun theShortFormListsTheBuiltLabelsInOrderWithOnlyTheFirstCapitalAndNoAnd() {
        assertEquals(
            "Answering permission requests with Yes and No, starting an agent, stopping and deleting sessions, home-screen widgets, switching between saved machines, seeing which saved machines need you on Home",
            ProCopy.coverageShort(ProGate.GATED),
        )
        assertEquals("Home-screen widgets, switching between saved machines", ProCopy.coverageShort(setOf("hosts.switch", "widgets")))
        assertEquals("Starting an agent", ProCopy.coverageShort(setOf("operations.start")))
        // An unknown id names nothing; nothing gated is an empty line, never a promise.
        assertEquals("", ProCopy.coverageShort(setOf("not.a.capability")))
        assertEquals("", ProCopy.coverageShort(emptySet()))
    }

    @Test
    fun theOverviewHasItsOwnTitleAndIsTheRowsName() {
        assertEquals("pro.overview", ProCopy.OVERVIEW_ID)
        assertEquals("What Pro covers", ProCopy.OVERVIEW_ROW)
        assertEquals("What Pro covers", ProCopy.gateTitle(ProCopy.OVERVIEW_ID))
        assertNull(ProCapabilities.byId(ProCopy.OVERVIEW_ID))
        assertNull(ProCapabilities.freePath(ProCopy.OVERVIEW_ID))
    }

    @Test
    fun theOverviewIsNotACapabilitySoTheGateWouldLetItThroughAndTheHostDecidesItself() {
        // This is why the app's gate host special-cases the id: ProGate.decide would never ask for a sheet for it.
        assertFalse(ProCopy.OVERVIEW_ID in ProGate.GATED)
        assertFalse(ProGate.locked(ProCopy.OVERVIEW_ID, hasPro = false))
        for (context in GateContext.entries) assertEquals(GateDecision.PROCEED, ProGate.decide(ProCopy.OVERVIEW_ID, hasPro = false, context = context), "$context")
    }

    @Test
    fun anUnknownCapabilityHasNoFreePath() {
        assertNull(ProCapabilities.freePath("not.a.capability"))
    }
}
