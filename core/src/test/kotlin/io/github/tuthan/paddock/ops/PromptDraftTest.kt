package io.github.tuthan.paddock.ops

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun sha(text: String) = OperationJournal.sha256Hex(text)

    @Test fun aDelayedAcknowledgementDoesNotTakeTextEditedAfterTheSend() {
        // Sent "A"; the field stayed editable; the user changed it to "B"; then herdr's answer for "A" arrives.
        val d = PromptDraft().typed("term_1", "A").typed("term_1", "B").accepted("term_1", recordId = 7, sentSha256 = sha("A"))
        assertEquals("B", d.textFor("term_1"))
        assertEquals(mapOf("term_1" to 7L), d.acceptedThrough, "the row is still recorded as seen")
    }

    @Test fun theAcknowledgementTakesTheTextThatWasSent() {
        val d = PromptDraft().typed("term_1", "A").accepted("term_1", recordId = 7, sentSha256 = sha("A"))
        assertEquals("", d.textFor("term_1"))
    }

    @Test fun textEditedAwayAndBackToWhatWasSentIsTheSentText() {
        val d = PromptDraft().typed("term_1", "A").typed("term_1", "B").typed("term_1", "A").accepted("term_1", 7, sha("A"))
        assertEquals("", d.textFor("term_1"))
    }

    @Test fun theKeptTextSurvivesTheSameAnswerShownAgainAndIsTakenByItsOwnSend() {
        val kept = PromptDraft().typed("term_1", "A").typed("term_1", "B").accepted("term_1", 7, sha("A"))
        assertEquals("B", kept.accepted("term_1", 7, sha("A")).textFor("term_1"), "the same row shown again changes nothing")
        assertEquals("", kept.accepted("term_1", 8, sha("B")).textFor("term_1"), "B's own acceptance takes B")
    }

    @Test fun textClearedByTheUserAfterTheSendStaysClear() {
        val d = PromptDraft().typed("term_1", "A").typed("term_1", "").accepted("term_1", 7, sha("A"))
        assertEquals("", d.textFor("term_1"))
        assertEquals(mapOf("term_1" to 7L), d.acceptedThrough)
    }

    // ---- Phase 15: the attachment, and the user's own text hash (X12) ---------------------------------------------------------

    private val photo = DraftAttachment("snap-1", "2026-10-11-143205-shot.png", 48_211, "image/png")

    private fun promptRow(id: Long, userText: String, withPhrase: Boolean = true) = OperationRecord(
        id, "h1", "s", "term_1", 1, OperationKind.Prompt, 1, OperationOutcome.Acknowledged,
        payloadSha256 = sha(if (withPhrase) "Attached file: .paddock/inbox/shot.png\n$userText" else userText),
        textSha256 = sha(userText).takeIf { withPhrase },
    )

    @Test fun theAttachmentBelongsToTheTerminalAndIsAttachedAndDetachedByIt() {
        val d = PromptDraft().typed("term_1", "look").attached("term_1", photo)
        assertEquals(photo, d.attachmentFor("term_1"))
        assertEquals("look", d.textFor("term_1"), "attaching leaves the text")
        assertNull(d.attachmentFor("term_2"), "another agent never inherits it")
        assertNull(d.attachmentFor(null))
        assertEquals(photo, d.detached("term_2").attachmentFor("term_1"), "detaching for another terminal changes nothing")
        val removed = d.detached("term_1")
        assertNull(removed.attachmentFor("term_1"))
        assertEquals("look", removed.textFor("term_1"), "and detaching leaves the text")
        // Attaching for another terminal replaces the draft, as typing there does.
        val other = d.attached("term_2", photo.copy(snapshotId = "snap-2"))
        assertEquals("", other.textFor("term_2"))
        assertEquals("snap-2", other.attachmentFor("term_2")?.snapshotId)
        assertNull(other.attachmentFor("term_1"))
        // Typing for another terminal drops the first one's file with its text.
        assertNull(d.typed("term_2", "x").attachmentFor("term_1"))
        assertNull(d.typed("term_2", "x").attachmentFor("term_2"))
        assertEquals(photo, d.typed("term_1", "more words").attachmentFor("term_1"), "typing for the same terminal keeps it")
    }

    @Test fun anAcceptedPromptWithAnAttachmentTakesTheTextAndTheFileWhenTheRowsOwnTextHashMatches() {
        val d = PromptDraft().typed("term_1", "what is this?").attached("term_1", photo)
        // The row's payload hash covers the phrase line, so it is the user's own text that is compared.
        val row = promptRow(7, "what is this?")
        assertEquals("", d.accepted("term_1", row).textFor("term_1"))
        assertNull(d.accepted("term_1", row).attachmentFor("term_1"))
        assertEquals(mapOf("term_1" to 7L), d.accepted("term_1", row).acceptedThrough)
        // The old comparison, by the whole payload, would have left the words and the file behind: this is why the row carries the other hash.
        assertEquals("what is this?", d.accepted("term_1", 7, row.payloadSha256).textFor("term_1"))
    }

    @Test fun aPhotoWithNoWordsIsAcceptedAndEmptiesTheDraft() {
        val d = PromptDraft().attached("term_1", photo)
        val row = promptRow(7, "")
        val after = d.accepted("term_1", row)
        assertNull(after.attachmentFor("term_1"))
        assertEquals("", after.textFor("term_1"))
    }

    @Test fun textEditedAfterAnAttachmentSendLeavesTheNewTextAndItsFile() {
        val d = PromptDraft().typed("term_1", "A").attached("term_1", photo).typed("term_1", "B")
        val after = d.accepted("term_1", promptRow(7, "A"))
        assertEquals("B", after.textFor("term_1"))
        assertEquals(photo, after.attachmentFor("term_1"), "the file stays with the words that stayed")
        assertEquals(mapOf("term_1" to 7L), after.acceptedThrough, "the row is still seen")
    }

    @Test fun aRowWithoutAnOwnTextHashIsComparedByItsPayloadAsBefore() {
        val d = PromptDraft().typed("term_1", "A")
        assertEquals("", d.accepted("term_1", promptRow(7, "A", withPhrase = false)).textFor("term_1"))
        assertEquals("B", d.typed("term_1", "B").accepted("term_1", promptRow(8, "A", withPhrase = false)).textFor("term_1"))
        assertEquals("", d.accepted("term_1", 7, sha("A")).textFor("term_1"), "the three-argument form is unchanged")
    }

    @Test fun anOldAcceptedOutcomeSeenAgainChangesNeitherTheTextNorTheFile() {
        val d = PromptDraft().typed("term_1", "A").accepted("term_1", promptRow(7, "A")).typed("term_1", "B").attached("term_1", photo)
        val again = d.accepted("term_1", promptRow(7, "A"))
        assertEquals(d, again)
    }

    @Test fun theSavedStateRoundTripsTheAttachmentTheTextAndWhatWasAccepted() {
        val d = PromptDraft().typed("term_1", "hello").attached("term_1", photo).accepted("term_9", recordId = 4)
        assertEquals(d, PromptDraft.restore(d.saved()))
        val noFile = PromptDraft().typed("term_1", "hello").accepted("term_1", 3)
        assertEquals(noFile, PromptDraft.restore(noFile.saved()))
        assertEquals(PromptDraft(), PromptDraft.restore(PromptDraft().saved()))
        // Saved state is plain strings and longs, nothing else, and carries no bytes.
        assertTrue(d.saved().all { it is String || it is Long })
        // What a process restore cannot read is an empty draft, never a crash.
        assertEquals(PromptDraft(), PromptDraft.restore(emptyList()))
        assertEquals(PromptDraft(), PromptDraft.restore(listOf("term_1", "x", 5, 6, 7, 8)))
    }
}
