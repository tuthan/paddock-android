package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ops.SendBlock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DecisionPresenterTest {
    private val now = 10_000L
    private fun view(
        listing: RequestListing? = listingOf(received = now), shown: PendingRequest? = request(ID_A), outcome: RequestOutcome? = RequestOutcome.Waiting,
        answerability: Answerability? = null, result: AnswerResult? = null, newer: PendingRequest? = null, working: Boolean = false, error: String? = null,
        shownSince: Long? = null,
    ) = AnswerView(
        listing, shown, outcome, answerability ?: listing?.let { AnswerRules.answerability(it, now) }, result, newer, error, working, shownSince = shownSince,
    )
    private fun m(v: AnswerView?, gate: OperationGate = OperationGate.Open, sending: Boolean = false) = DecisionPresenter.model(v, now, gate, sending)
    private fun row(kind: OperationKind, outcome: OperationOutcome) = OperationRecord(1, "h", "s", "t", 1, kind, requestedAt = 1, outcome = outcome, sentAt = 2, requestId = ID_A)

    @Test fun beforeAnythingHasBeenReadTheSheetIsLoading() {
        assertEquals(DecisionModel.Kind.Loading, m(null).kind)
        assertEquals(DecisionModel.Kind.Loading, m(AnswerView()).kind)
    }

    @Test fun nothingPendingSaysSoAndOffersNoAnswer() {
        val v = view(listing = listingOf(entries = emptyList(), candidate = null), shown = null, outcome = null)
        val model = m(v)
        assertEquals(DecisionModel.Kind.NoRequest, model.kind)
        assertEquals(NotAnswerable.NothingPending.sentence, model.whyNot)
        assertFalse(model.canAnswer)
    }

    @Test fun anAnswerableRequestShowsEverythingAndTheTimeItHasLeft() {
        val model = m(view())
        assertEquals(DecisionModel.Kind.Request, model.kind)
        assertEquals("Bash", model.toolName)
        assertEquals("command:\n  ls", model.inputText)
        assertEquals("11111111", model.requestShort)
        assertTrue(model.canAnswer)
        assertNull(model.whyNot)
        assertNull(model.inputNote)
        // host now 2 000 + rtt 100 + 0 elapsed; expires 61 000 -> 58.9 s -> "59 s"
        assertEquals("Answer within 59 s", model.timeLeft)
    }

    @Test fun aTruncatedRequestIsShownWithTheReasonAndBothButtonsOff() {
        val v = view(listing = listingOf(candidate = request(truncated = true)), shown = request(truncated = true))
        val model = m(v)
        assertFalse(model.canAnswer)
        assertEquals(NotAnswerable.TooLarge.sentence, model.whyNot)
        assertNotNull(model.inputNote)
        assertEquals("command:\n  ls", model.inputText, "what was kept is still shown")
    }

    @Test fun aClosedGateNamesItsOwnReasonFirst() {
        val model = m(view(), gate = OperationGate.Closed(SendBlock.NotLive, "Not connected to the machine right now. Sending is off until the link is back."))
        assertFalse(model.canAnswer)
        assertEquals("Not connected to the machine right now. Sending is off until the link is back.", model.whyNot)
    }

    @Test fun anExpiredRequestSaysExpiredAndTheDesktopHasIt() {
        val v = view(listing = listingOf(hostNow = 62_000), outcome = RequestOutcome.Expired)
        val model = m(v)
        assertFalse(model.canAnswer)
        assertEquals("Expired. Answer on the desktop.", model.status)
        assertNull(model.timeLeft)
        assertEquals(ResultTone.Refused, model.statusTone)
    }

    @Test fun aReplacedRequestIsSaidToBeReplacedAndTheNewOneIsOffered() {
        val v = view(outcome = RequestOutcome.Replaced, newer = request(ID_B, created = 5_000).copy(toolName = "Edit"))
        val model = m(v)
        assertFalse(model.canAnswer)
        assertNull(model.status, "the offer of the newer request says it, so the sheet does not say it twice")
        assertTrue(model.offersNewer)
        assertEquals("Edit", model.newerToolName)
    }

    @Test fun aSentAnswerWaitsForTheHookAndNeverClaimsToHaveBeenApplied() {
        val r = AnswerResult(ID_A, Behavior.Allow, OperationResult.Acknowledged(row(OperationKind.Allow, OperationOutcome.Acknowledged), Unit), now)
        val model = m(view(outcome = RequestOutcome.Sent(Behavior.Allow), result = r))
        assertFalse(model.canAnswer, "one answer per request")
        assertEquals("Yes written. Waiting for the hook to take it.", model.status)
        assertTrue(model.result!!.startsWith("Yes written"))
        assertNull(model.whyNot)
    }

    @Test fun aConsumedAnswerIsHandedToTheAgentWithTheDesktopCaveat() {
        val r = AnswerResult(ID_A, Behavior.Deny, OperationResult.Acknowledged(row(OperationKind.Deny, OperationOutcome.Acknowledged), Unit), now)
        val waiting = m(view(outcome = RequestOutcome.Consumed(Behavior.Deny), result = r))
        assertEquals("Handed to the agent as No. Waiting for the agent to move on. ${DecisionPresenter.CAVEAT}", waiting.status)
        val moved = m(view(outcome = RequestOutcome.Consumed(Behavior.Deny), result = r, working = true))
        assertEquals("Handed to the agent as No. The agent moved on. ${DecisionPresenter.CAVEAT}", moved.status)
        assertEquals(ResultTone.Ok, moved.statusTone)
    }

    @Test fun aLostRaceIsLostAndTheButtonsStayOff() {
        val r = AnswerResult(ID_A, Behavior.Allow, OperationResult.Rejected(row(OperationKind.Allow, OperationOutcome.Rejected), AnswerCodes.GONE, "gone"), now)
        val model = m(view(outcome = RequestOutcome.Waiting, result = r))
        assertTrue(model.result!!.startsWith("Lost:"))
        assertFalse(model.canAnswer)
    }

    @Test fun anUnknownAnswerIsDrawnAsUnknown() {
        val r = AnswerResult(ID_A, Behavior.Allow, OperationResult.Unknown(row(OperationKind.Allow, OperationOutcome.Unknown), "link lost"), now)
        val model = m(view(outcome = RequestOutcome.Waiting, result = r))
        assertEquals(ResultTone.Unknown, model.resultTone)
        assertTrue("outcome unknown" in model.result!!)
        assertFalse(model.canAnswer)
    }

    @Test fun aSendInProgressIsShownAndTheButtonsIgnoreTaps() {
        val model = m(view(), sending = true)
        assertTrue(model.sending)
        assertFalse(model.canAnswer)
        assertNull(model.whyNot)
    }

    @Test fun theJournalRowOfTheAnswerBeingSentDoesNotAlsoSayAnotherSendIsRunning() {
        val closed = OperationGate.Closed(io.github.tuthan.paddock.ops.SendBlock.InFlight, "Another send to this agent is still running.")
        val model = m(view(), gate = closed, sending = true)
        assertNull(model.whyNot)
        assertFalse(model.canAnswer)
        assertEquals("Another send to this agent is still running.", m(view(), gate = closed).whyNot)
    }

    @Test fun settlingStatesWhatTheFilesShowAndNeverThatTheAnswerWasApplied() {
        assertTrue("never reached" in DecisionPresenter.settled(RequestOutcome.Waiting))
        assertTrue(DecisionPresenter.settled(RequestOutcome.Consumed(Behavior.Allow)).startsWith("Read from the host's files: Handed to the agent as Yes."))
        assertTrue(DecisionPresenter.settled(RequestOutcome.Consumed(Behavior.Allow)).endsWith(DecisionPresenter.CAVEAT))
        assertTrue("Expired" in DecisionPresenter.settled(RequestOutcome.Expired))
        assertTrue("gone" in DecisionPresenter.settled(RequestOutcome.Gone))
    }

    @Test fun theEntryDescribesTheNewestPendingRequestNotTheOneOnTheSheet() {
        val shownOld = view(shown = request(ID_B), outcome = RequestOutcome.Consumed(Behavior.Allow))
        assertEquals(DecisionEntryModel("Bash", "Answer within 59 s"), DecisionPresenter.entry(shownOld, now))
    }

    @Test fun theEntryIsAbsentWhenNothingIsPendingOrTheRequestHasExpired() {
        assertNull(DecisionPresenter.entry(null, now))
        assertNull(DecisionPresenter.entry(AnswerView(), now))
        assertNull(DecisionPresenter.entry(view(listing = listingOf(entries = emptyList(), candidate = null), shown = null, outcome = null), now))
        val expired = view(listing = listingOf(candidate = request(expires = 1_500)))
        assertNull(DecisionPresenter.entry(expired, now))
    }

    @Test fun aQuestionThatIsNotAPermissionGetsNoEntry() {
        for (tool in listOf("AskUserQuestion", "ExitPlanMode")) assertNull(DecisionPresenter.entry(view(listing = listingOf(candidate = request(tool = tool))), now), tool)
        assertNotNull(DecisionPresenter.entry(view(listing = listingOf(candidate = request(tool = "Bash"))), now))
    }

    @Test fun aRequestTooLargeToShowStillGetsAnEntryWithNoToolName() {
        val big = view(listing = listingOf(candidate = null, complete = false), shown = null, outcome = null)
        val entry = DecisionPresenter.entry(big, now)
        assertNotNull(entry)
        assertNull(entry.toolName)
    }

    @Test fun aReplacedRequestIsSaidOnceByTheOfferOfTheNewerOne() {
        val withNewer = m(view(outcome = RequestOutcome.Replaced, newer = request(ID_B)))
        assertNull(withNewer.status)
        assertEquals("Bash", withNewer.newerToolName)
        assertEquals(NotAnswerable.Replaced.sentence, m(view(outcome = RequestOutcome.Replaced)).status, "without a newer one to offer the sentence stands in")
    }

    @Test fun aRequestThatJustAppearedCannotBeAnsweredForAMomentAndThenCan() {
        val fresh = m(view(shownSince = now - 500))
        assertFalse(fresh.canAnswer)
        assertEquals(DecisionPresenter.JUST_APPEARED, fresh.whyNot)
        assertEquals("Bash", fresh.toolName, "it is shown at once: only the answering waits")
        val settled = m(view(shownSince = now - DecisionPresenter.APPEAR_GUARD_MILLIS))
        assertTrue(settled.canAnswer)
        assertNull(settled.whyNot)
        assertTrue(m(view()).canAnswer, "no record of when it appeared means no guard (a view built by hand)")
    }

    @Test fun theModelCarriesTheWholeIdOfTheRequestOnTheSheetForTheTapToBeBoundTo() {
        assertEquals(ID_A, m(view()).requestId)
        assertNull(m(null).requestId)
    }

    @Test fun aFailedReadIsCarriedToTheScreen() {
        val model = m(view(error = "host unreachable"))
        assertEquals("host unreachable", model.readError)
    }

    @Test fun theTimeLeftCountsDownFromTheHostsClockNotThePhones() {
        val l = listingOf(hostNow = 2_000, received = 10_000, rtt = 100)
        val later = DecisionPresenter.model(view(listing = l), 10_000 + 30_000, OperationGate.Open, false)
        assertEquals("Answer within 29 s", later.timeLeft)
        val phoneClockWrongByADay = DecisionPresenter.model(view(listing = l.copy(receivedAtMillis = 86_400_000L)), 86_400_000L + 30_000, OperationGate.Open, false)
        assertEquals("Answer within 29 s", phoneClockWrongByADay.timeLeft)
    }
}
