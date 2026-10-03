package io.github.tuthan.paddock.answers

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/** A listing for the rules, built directly: [hostNow] is the host's clock, [received] and [rtt] are the phone's. */
fun listingOf(
    hostNow: Long = 2_000, received: Long = 10_000, rtt: Long = 100,
    entries: List<RequestEntry> = listOf(entry(ID_A, RequestState.Pending)),
    candidate: PendingRequest? = request(ID_A), complete: Boolean = true,
) = RequestListing(hostNow, entries, candidate?.let { Candidate(it.requestId, complete, it) } ?: if (!complete) Candidate(ID_A, false, null) else null, received, rtt)

fun request(id: String = ID_A, session: String = "claude-1", created: Long = 1_000, expires: Long = 61_000, truncated: Boolean = false) =
    PendingRequest(id, session, "paddock-test", "w1:p1", "Bash", "command:\n  ls", "default", created, expires, truncated)

fun entry(id: String, state: RequestState, created: Long? = 1_000, expires: Long? = 61_000, session: String? = "claude-1", decision: Behavior? = null, alive: Boolean? = null) =
    RequestEntry(id, state, 400, created, expires, session, false, decision, "Bash", true, alive)

class AnswerRulesTest {
    private fun a(l: RequestListing, now: Long = l.receivedAtMillis) = AnswerRules.answerability(l, now)

    @Test fun aReadWholeUntruncatedRequestWithTimeLeftIsAnswerable() {
        val yes = assertIs<Answerability.Yes>(a(listingOf()))
        assertEquals(ID_A, yes.request.requestId)
        assertEquals(61_000 - (2_000 + 100), yes.millisLeft)
    }

    @Test fun nothingPendingIsSaidSoNotGuessedAround() {
        assertEquals(NotAnswerable.NothingPending, assertIs<Answerability.No>(a(listingOf(entries = emptyList(), candidate = null))).why)
    }

    @Test fun aRequestTooBigToReadWholeOrThatDidNotParseIsNeverAnswerable() {
        assertEquals(NotAnswerable.TooLarge, assertIs<Answerability.No>(a(listingOf(candidate = null, complete = false))).why)
        val unreadable = listingOf(candidate = null).copy(candidate = Candidate(ID_A, true, null))
        assertEquals(NotAnswerable.Unreadable, assertIs<Answerability.No>(a(unreadable)).why)
    }

    @Test fun aTruncatedRequestIsNeverAnswerableWhateverTheTime() {
        val no = assertIs<Answerability.No>(a(listingOf(candidate = request(truncated = true))))
        assertEquals(NotAnswerable.TooLarge, no.why)
        assertEquals(ID_A, no.request?.requestId, "it is still shown, with the reason")
    }

    @Test fun theHostsClockDecidesNotThePhones() {
        // The host says it is 2 000 ms; the phone's clock reads a day later. The request has 59 s left either way.
        val skewed = listingOf(hostNow = 2_000, received = 86_400_000)
        assertIs<Answerability.Yes>(a(skewed))
        // Only the time since the listing arrived counts, measured on the phone's own clock.
        assertIs<Answerability.Yes>(a(skewed, now = 86_400_000 + 50_000))
        assertEquals(NotAnswerable.Expired, assertIs<Answerability.No>(a(skewed, now = 86_400_000 + 60_000)).why)
    }

    @Test fun theAnswersOwnRoundTripAndAMarginAreKeptInHand() {
        val rtt = 400L
        // left = 61 000 - (hostNow + rtt); answerable only while left > rtt + 500
        val ok = listingOf(hostNow = 61_000 - 400 - 400 - 501, rtt = rtt)
        assertIs<Answerability.Yes>(a(ok))
        val late = listingOf(hostNow = 61_000 - 400 - 400 - 500, rtt = rtt)
        assertEquals(NotAnswerable.TooLate, assertIs<Answerability.No>(a(late)).why)
        val gone = listingOf(hostNow = 61_000 - 400, rtt = rtt)
        assertEquals(NotAnswerable.Expired, assertIs<Answerability.No>(a(gone)).why)
    }

    @Test fun anotherLiveSessionOnTheSamePaneMakesItAmbiguous() {
        val other = entry(ID_B, RequestState.Pending, session = "someone-else", expires = 61_000)
        val l = listingOf(entries = listOf(entry(ID_A, RequestState.Pending), other))
        assertEquals(NotAnswerable.TwoSessions, assertIs<Answerability.No>(a(l)).why)
    }

    @Test fun anOldRequestOfAnotherSessionThatHasLongExpiredDoesNotMatter() {
        val stale = entry(ID_B, RequestState.Pending, session = "someone-else", expires = 1_500)
        assertIs<Answerability.Yes>(a(listingOf(entries = listOf(entry(ID_A, RequestState.Pending), stale))))
    }

    @Test fun anOlderPendingRequestOfTheSameSessionIsJustReplacedNotAmbiguous() {
        val older = entry(ID_B, RequestState.Pending, created = 500, session = "claude-1")
        assertIs<Answerability.Yes>(a(listingOf(entries = listOf(entry(ID_A, RequestState.Pending), older))))
    }

    @Test fun settledRequestsOfOtherSessionsDoNotMakeItAmbiguous() {
        val done = entry(ID_B, RequestState.Consumed, session = "someone-else")
        assertIs<Answerability.Yes>(a(listingOf(entries = listOf(entry(ID_A, RequestState.Pending), done))))
    }

    // ---- outcomes

    private fun o(l: RequestListing, id: String = ID_A, now: Long = l.receivedAtMillis) = AnswerRules.outcome(l, id, now)

    @Test fun aPendingRequestIsWaitingUntilItsDeadline() {
        assertEquals(RequestOutcome.Waiting, o(listingOf()))
        assertEquals(RequestOutcome.Expired, o(listingOf(hostNow = 61_000)))
    }

    @Test fun aPendingRequestThatIsNotTheNewestWasReplaced() {
        val l = listingOf(entries = listOf(entry(ID_A, RequestState.Pending, created = 500), entry(ID_B, RequestState.Pending, created = 900)), candidate = request(ID_B))
        assertEquals(RequestOutcome.Replaced, o(l, ID_A))
        assertEquals(RequestOutcome.Waiting, o(l, ID_B))
    }

    @Test fun theHooksOwnStatesAreReadAsTheyAre() {
        val l = listingOf(entries = listOf(entry(ID_A, RequestState.Consumed, decision = Behavior.Allow), entry(ID_B, RequestState.Expired)), candidate = null)
        assertEquals(RequestOutcome.Consumed(Behavior.Allow), o(l, ID_A))
        assertEquals(RequestOutcome.Expired, o(l, ID_B))
        assertEquals(RequestOutcome.Gone, o(l, ID_C))
    }

    @Test fun aClaimedRequestIsSentUntilTheHookTakesItAndExpiredWhenTheHookPlainlyNeverWill() {
        val claimed = listingOf(entries = listOf(entry(ID_A, RequestState.Claimed, decision = Behavior.Deny)), candidate = null)
        assertEquals(RequestOutcome.Sent(Behavior.Deny), o(claimed))
        assertEquals(RequestOutcome.Sent(Behavior.Deny), o(claimed.copy(hostNowMillis = 61_000 + 2_000 + 2_000)), "inside the grace and the staleness allowance")
        assertEquals(RequestOutcome.Expired, o(claimed.copy(hostNowMillis = 61_000 + 2_000 + 3_000 + 1)))
        val noDecision = listingOf(entries = listOf(entry(ID_A, RequestState.Claimed)), candidate = null)
        assertEquals(RequestOutcome.Waiting, o(noDecision), "claimed a moment ago, the decision file is about to appear")
    }

    @Test fun aRequestWhoseHookHasEndedIsNeverAnswerableAndIsExpired() {
        val dead = listingOf(entries = listOf(entry(ID_A, RequestState.Pending, alive = false)))
        assertEquals(NotAnswerable.HookGone, assertIs<Answerability.No>(a(dead)).why)
        assertEquals(RequestOutcome.Expired, o(dead))
        assertIs<Answerability.Yes>(a(listingOf(entries = listOf(entry(ID_A, RequestState.Pending, alive = true)))))
        assertIs<Answerability.Yes>(a(listingOf(entries = listOf(entry(ID_A, RequestState.Pending, alive = null)))), "not known is not dead")
    }

    @Test fun aClaimedRequestWhoseHookHasEndedWillNeverBeConsumed() {
        val l = listingOf(entries = listOf(entry(ID_A, RequestState.Claimed, decision = Behavior.Allow, alive = false)), candidate = null)
        assertEquals(RequestOutcome.Expired, o(l))
    }

    @Test fun theCandidateOfAnEmptyListingIsNull() {
        assertNull(listingOf(entries = emptyList(), candidate = null).candidate)
    }
}
