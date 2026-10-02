package io.github.tuthan.paddock.ops

import kotlin.test.Test
import kotlin.test.assertEquals

/** The composer's unsent text: kept for its terminal across leaving the screen, emptied only by a newly accepted prompt. */
class PromptDraftTest {
    @Test fun theTextBelongsToTheTerminalItWasTypedFor() {
        val d = PromptDraft().typed("term_1", "hello")
        assertEquals("hello", d.textFor("term_1"))
        assertEquals("", d.textFor("term_2"), "another agent never inherits it")
        assertEquals("", d.textFor(null))
        assertEquals("", PromptDraft().textFor("term_1"))
    }

    @Test fun typingForAnotherTerminalReplacesIt() {
        val d = PromptDraft().typed("term_1", "hello").typed("term_2", "other")
        assertEquals("other", d.textFor("term_2"))
        assertEquals("", d.textFor("term_1"))
    }

    @Test fun anAcceptedPromptTakesTheTextWithIt() {
        val d = PromptDraft().typed("term_1", "hello").accepted("term_1", recordId = 7)
        assertEquals("", d.textFor("term_1"))
        assertEquals(mapOf("term_1" to 7L), d.acceptedThrough)
    }

    @Test fun anOldAcceptedOutcomeSeenAgainDoesNotTakeTextTypedAfterIt() {
        // The composer reopens (or turns) with the earlier accepted prompt still the terminal's last outcome.
        val afterSend = PromptDraft().typed("term_1", "first").accepted("term_1", recordId = 7)
        val retyped = afterSend.typed("term_1", "second draft")
        assertEquals("second draft", retyped.accepted("term_1", recordId = 7).textFor("term_1"))
        assertEquals("second draft", retyped.accepted("term_1", recordId = 3).textFor("term_1"), "an older row is no news either")
    }

    @Test fun aNewerAcceptedPromptTakesTheTextAgain() {
        val d = PromptDraft().typed("term_1", "first").accepted("term_1", 7).typed("term_1", "second").accepted("term_1", 9)
        assertEquals("", d.textFor("term_1"))
        assertEquals(mapOf("term_1" to 9L), d.acceptedThrough)
    }

    @Test fun anAcceptedPromptForAnotherTerminalLeavesTheDraftAlone() {
        val d = PromptDraft().typed("term_1", "mine").accepted("term_2", recordId = 5)
        assertEquals("mine", d.textFor("term_1"))
        assertEquals(mapOf("term_2" to 5L), d.acceptedThrough)
    }

    @Test fun twoSendsInFlightOnDifferentAgentsAreEachAppliedWhicheverAnswersLast() {
        // Row 7 was sent on term_1, row 8 on term_2; term_2's answer comes first.
        val d = PromptDraft().typed("term_1", "for one").accepted("term_2", recordId = 8).accepted("term_1", recordId = 7)
        assertEquals("", d.textFor("term_1"), "the older row is still news for its own terminal")
        assertEquals(mapOf("term_2" to 8L, "term_1" to 7L), d.acceptedThrough)
    }

    @Test fun anOldAcceptedOutcomeAfterVisitingAnotherAgentStillClearsNothing() {
        val d = PromptDraft().typed("term_1", "first").accepted("term_1", 7).typed("term_2", "elsewhere").typed("term_1", "new draft")
        assertEquals("new draft", d.accepted("term_1", 7).textFor("term_1"))
    }
}
