package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.Begin
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.Subject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the gate calls "the user is busy" (review F6). A row that can still change holds the gate shut and a deferred tap says why; a row whose call
 * never came back does not hold it for the life of the process, because every locked control would then be a dead tap with no way to buy Pro.
 */
class GateContextTest {
    private val now = 10_000_000L
    private val minute = 60_000L

    private fun row(outcome: OperationOutcome, ageMillis: Long, terminalId: String = "term_1", id: Long = 1) =
        OperationRecord(id, "h1", "paddock-test", terminalId, 1, OperationKind.Prompt, requestedAt = now - ageMillis, outcome = outcome, sentAt = (now - ageMillis + 200).takeIf { outcome != OperationOutcome.Requested })

    private fun context(records: List<OperationRecord>, pending: Boolean = false, manual: Boolean = false, at: Long = now) =
        ProGate.context(pendingAnswerOnScreen = pending, manualInputOpen = manual, records = records, nowMillis = at)

    // ---- the context from the facts -----------------------------------------------------------------------------------

    @Test
    fun withNothingRunningTheAppIsIdle() {
        assertEquals(GateContext.IDLE, context(emptyList()))
    }

    @Test
    fun aRowThatIsStillRequestedOrSentHoldsTheGate() {
        assertEquals(GateContext.OPERATION_IN_FLIGHT, context(listOf(row(OperationOutcome.Requested, 2_000))))
        assertEquals(GateContext.OPERATION_IN_FLIGHT, context(listOf(row(OperationOutcome.Sent, 2_000))))
    }

    @Test
    fun aRowThatIsSettledNeverHoldsTheGateHoweverYoungItIs() {
        for (outcome in listOf(OperationOutcome.Acknowledged, OperationOutcome.Rejected, OperationOutcome.NotSent, OperationOutcome.Unknown)) {
            assertEquals(GateContext.IDLE, context(listOf(row(outcome, 100))), "$outcome")
        }
    }

    @Test
    fun aSentRowWhoseCallNeverCameBackStopsHoldingTheGate() {
        // The connection dropped after the write and nothing settled the row; recover() makes it Unknown only at the next start.
        // With the old rule (any Requested or Sent row) every locked control stayed a dead tap for the whole process.
        val stuck = row(OperationOutcome.Sent, ageMillis = 10 * minute)
        assertEquals(GateContext.IDLE, context(listOf(stuck)))
        assertEquals(GateDecision.SHOW_GATE, ProGate.decide(ProCapabilities.START_AGENT.id, hasPro = false, context = context(listOf(stuck))))
    }

    @Test
    fun aRequestedRowThatNeverBecameSentStopsHoldingTheGateToo() {
        assertEquals(GateContext.IDLE, context(listOf(row(OperationOutcome.Requested, ageMillis = 10 * minute))))
    }

    @Test
    fun theBoundIsInclusiveAndANamedConstant() {
        val bound = ProGate.OPERATION_STUCK_AFTER_MILLIS
        assertEquals(2 * minute, bound)
        assertEquals(GateContext.OPERATION_IN_FLIGHT, context(listOf(row(OperationOutcome.Sent, bound))), "at the bound the call can still be answered")
        assertEquals(GateContext.IDLE, context(listOf(row(OperationOutcome.Sent, bound + 1))), "one millisecond past it, it cannot")
    }

    @Test
    fun aStuckRowDoesNotHideAYoungOneBesideIt() {
        val rows = listOf(row(OperationOutcome.Sent, 10 * minute, id = 1), row(OperationOutcome.Sent, 3_000, terminalId = "term_2", id = 2))
        assertEquals(GateContext.OPERATION_IN_FLIGHT, context(rows))
    }

    @Test
    fun aClockThatMovedBackLeavesTheGateWaitingNotOpen() {
        val future = row(OperationOutcome.Sent, ageMillis = -5 * minute)
        assertEquals(GateContext.OPERATION_IN_FLIGHT, context(listOf(future)))
    }

    @Test
    fun aSessionOrSagaRowCountsLikeATerminalRow() {
        for (subject in listOf(Subject.session("main"), Subject.saga("s1"), Subject.workspace("w_1"))) {
            assertEquals(GateContext.OPERATION_IN_FLIGHT, context(listOf(row(OperationOutcome.Sent, 1_000, terminalId = subject))), subject)
        }
    }

    @Test
    fun whatTheUserCanSeeIsNamedFirst() {
        val running = listOf(row(OperationOutcome.Sent, 1_000))
        assertEquals(GateContext.PENDING_ANSWER, context(running, pending = true, manual = true))
        assertEquals(GateContext.MANUAL_INPUT, context(running, manual = true))
        assertEquals(GateContext.PENDING_ANSWER, context(emptyList(), pending = true))
        assertEquals(GateContext.MANUAL_INPUT, context(emptyList(), manual = true))
    }

    @Test
    fun theRealJournalKeepsAStuckRowInFlightWhileTheGateLetsItGo() {
        // The rule reads the journal and changes nothing in it: the composer and Spaces still see the row as running.
        var clock = now
        val journal = OperationJournal(InMemoryJournalStore()) { clock }
        val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), 1)
        val started = (journal.begin(key, OperationKind.Prompt, "hello") as Begin.Started).record
        journal.markSent(started.id, "p_1")
        assertEquals(GateContext.OPERATION_IN_FLIGHT, ProGate.context(false, false, journal.records.value, clock))
        clock += 5 * minute
        assertEquals(GateContext.IDLE, ProGate.context(false, false, journal.records.value, clock))
        assertTrue(journal.records.value.single().inFlight, "the journal itself is untouched")
        assertEquals(OperationOutcome.Sent, journal.get(started.id)?.outcome)
    }

    // ---- a deferred tap says why ---------------------------------------------------------------------------------------

    @Test
    fun everyDeferralHasASentenceAndOnlyADeferralDoes() {
        for (c in ProCapabilities.ALL) for (context in GateContext.entries) {
            val decision = ProGate.decide(c.id, hasPro = false, context = context)
            val notice = ProGate.deferNotice(context)
            if (decision == GateDecision.DEFER) assertNotNull(notice, "${c.id} deferred in $context with nothing to say") else assertNull(notice, "$context")
        }
    }

    @Test
    fun theSentencesNameWhatIsWaitedFor() {
        assertEquals("Pro is offered once the request on screen is answered.", ProGate.deferNotice(GateContext.PENDING_ANSWER))
        assertEquals("Pro is offered once Manual input is closed.", ProGate.deferNotice(GateContext.MANUAL_INPUT))
        assertEquals("Pro is offered once the operation in progress finishes.", ProGate.deferNotice(GateContext.OPERATION_IN_FLIGHT))
        assertNull(ProGate.deferNotice(GateContext.IDLE))
    }

    @Test
    fun theSentencesAreShortDistinctAndSingle() {
        val all = GateContext.entries.mapNotNull { ProGate.deferNotice(it) }
        assertEquals(3, all.size)
        assertEquals(3, all.toSet().size, "each context says its own thing")
        for (s in all) {
            assertTrue(s.length <= 80, s)
            assertTrue(s.endsWith(".") && s.count { it == '.' } == 1, "one sentence: $s")
            assertFalse(s.contains("Buy", ignoreCase = true), "a deferral sells nothing: $s")
        }
    }
}
