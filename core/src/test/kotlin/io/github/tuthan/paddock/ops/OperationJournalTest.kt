package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 10_000_000L
    private val store = InMemoryJournalStore()
    private fun journal(s: JournalStore = store) = OperationJournal(s) { now }
    private fun key(id: String = "t1", epoch: Long = 1) = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", id), epoch)

    private fun OperationJournal.started(key: TerminalKey = key(), kind: OperationKind = OperationKind.Prompt, payload: String? = "hello") =
        (begin(key, kind, payload) as Begin.Started).record

    // ---- the state machine ---------------------------------------------------------------------------------------

    @Test fun theRowIsOnDiskBeforeBeginReturns() {
        val j = journal()
        val row = j.started()
        assertEquals(OperationOutcome.Requested, row.outcome)
        assertEquals(listOf(row), store.load().records)
    }

    @Test fun aPromptGoesRequestedSentAcknowledgedWithItsTimes() {
        val j = journal()
        val r = j.started()
        now += 40
        val sent = j.markSent(r.id, "w2:p1")
        assertEquals(OperationOutcome.Sent, sent.outcome)
        assertEquals("w2:p1", sent.paneIdAtSend)
        assertEquals(now, sent.sentAt)
        assertEquals(r.requestedAt, sent.requestedAt)
        assertEquals(OperationOutcome.Acknowledged, j.acknowledged(r.id).outcome)
        assertEquals(OperationOutcome.Acknowledged, store.load().records.single().outcome)
    }

    @Test fun herdrsRefusalKeepsItsCodeAndALinkLossIsUnknown() {
        val j = journal()
        val a = j.started(key("a")); j.markSent(a.id, "w2:p1")
        val rejected = j.rejected(a.id, "agent_blocked", "agent w2:p1 is blocked")
        assertEquals(OperationOutcome.Rejected, rejected.outcome)
        assertEquals("agent_blocked", rejected.code)
        val b = j.started(key("b")); j.markSent(b.id, "w2:p2")
        assertEquals(OperationOutcome.Unknown, j.unknown(b.id, "link lost").outcome)
    }

    @Test fun aRefusalBeforeTheWriteIsNotSentWithItsReason() {
        val j = journal()
        val r = j.started()
        val n = j.notSent(r.id, "not_ready", "agent is working")
        assertEquals(OperationOutcome.NotSent, n.outcome)
        assertEquals("not_ready", n.code)
        assertNull(n.sentAt)
    }

    @Test fun illegalMovesAreRefused() {
        val j = journal()
        val r = j.started()
        assertThrowsTransition { j.acknowledged(r.id) }            // never sent
        assertThrowsTransition { j.unknown(r.id) }
        j.markSent(r.id, "w2:p1")
        assertThrowsTransition { j.notSent(r.id, "late") }         // a write may be out: not "not sent"
        assertThrowsTransition { j.markSent(r.id, "w2:p1") }
        j.acknowledged(r.id)
        assertThrowsTransition { j.unknown(r.id) }                 // terminal states never move
        assertThrowsTransition { j.rejected(r.id, "x") }
        assertThrowsTransition { j.resolve(r.id) }                 // only an unknown row is resolved
    }

    private fun assertThrowsTransition(block: () -> Unit) {
        try { block(); fail("expected IllegalOutcomeTransition") } catch (_: IllegalOutcomeTransition) {}
    }

    // ---- one at a time per terminal --------------------------------------------------------------------------------

    @Test fun aSecondOperationOnATerminalIsRefusedWithTheFirstsStatus() {
        val j = journal()
        val first = j.started()
        val second = j.begin(key(), OperationKind.Esc)
        assertEquals(Begin.InFlight(first), second)
        j.markSent(first.id, "w2:p1")
        assertEquals(Begin.InFlight(j.get(first.id)!!), j.begin(key(), OperationKind.Prompt, "again"))
        assertEquals(1, j.records.value.size)               // the refusal wrote nothing
        j.acknowledged(first.id)
        assertTrue(j.begin(key(), OperationKind.Prompt, "next") is Begin.Started)
    }

    @Test fun otherTerminalsAreNotHeldUp() {
        val j = journal()
        j.started(key("a"))
        assertTrue(j.begin(key("b"), OperationKind.Prompt, "x") is Begin.Started)
    }

    @Test fun anUnknownOutcomeBlocksTheTerminalUntilTheUserHasReReadAndNeverResends() {
        val j = journal()
        val r = j.started(); j.markSent(r.id, "w2:p1"); j.unknown(r.id, "link lost")
        val blocked = j.begin(key(), OperationKind.Prompt, "hello")
        assertTrue(blocked is Begin.NeedsReread)
        assertEquals(r.id, (blocked as Begin.NeedsReread).unknown.id)
        assertEquals(listOf(r.id), j.unresolvedUnknown(key()).map { it.id })
        now += 5_000
        val resolved = j.resolve(r.id, "re-read")
        assertEquals(OperationOutcome.Unknown, resolved.outcome)       // the outcome is never rewritten into a guess
        assertEquals(now, resolved.resolvedAt)
        assertTrue(j.unresolvedUnknown(key()).isEmpty())
        assertTrue(j.begin(key(), OperationKind.Prompt, "hello") is Begin.Started)
        assertEquals(1, j.records.value.count { it.outcome == OperationOutcome.Unknown })
    }

    @Test fun resolvingTwiceKeepsTheFirstTime() {
        val j = journal()
        val r = j.started(); j.markSent(r.id, "w2:p1"); j.unknown(r.id)
        now += 1; val first = j.resolve(r.id)
        now += 1
        assertEquals(first.resolvedAt, j.resolve(r.id).resolvedAt)
    }

    @Test fun twoThreadsBeginningOnOneTerminalStartExactlyOne() {
        val j = journal()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val started = AtomicInteger()
        val tasks = (1..8).map { pool.submit { go.await(); if (j.begin(key(), OperationKind.Prompt, "x") is Begin.Started) started.incrementAndGet() } }
        go.countDown(); tasks.forEach { it.get() }; pool.shutdown()
        assertEquals(1, started.get())
        assertEquals(1, j.records.value.size)
    }

    // ---- what is stored --------------------------------------------------------------------------------------------

    @Test fun thePayloadIsAHashAndTheTextIsNotKeptByDefault() {
        val j = journal()
        val r = (j.begin(key(), OperationKind.Prompt, "abc") as Begin.Started).record
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r.payloadSha256)
        assertNull(r.promptText)
        val raw = store.load().records.single()
        assertNull(raw.promptText)
    }

    @Test fun theTextIsKeptOnlyWhenAskedAndTheHashIsStillThere() {
        val j = journal()
        val r = (j.begin(key(), OperationKind.Prompt, "abc", keepText = true) as Begin.Started).record
        assertEquals("abc", r.promptText)
        assertNotNull(r.payloadSha256)
    }

    @Test fun keysAndFocusCarryNoPayload() {
        val j = journal()
        val r = (j.begin(key(), OperationKind.Esc) as Begin.Started).record
        assertNull(r.payloadSha256); assertNull(r.promptText)
    }

    @Test fun theRowNamesTheTerminalAndEpoch() {
        val j = journal()
        val r = j.started(key("term_9", epoch = 4))
        assertEquals(Triple("h1", "paddock-test", "term_9"), Triple(r.host, r.session, r.terminalId))
        assertEquals(4L, r.epoch)
    }

    // ---- write-ahead -----------------------------------------------------------------------------------------------

    @Test fun ifTheRowCannotBeWrittenNothingStartsAndNothingIsKept() {
        val j = journal()
        store.failSaves = true
        try { j.begin(key(), OperationKind.Prompt, "x"); fail("expected JournalWriteFailed") } catch (_: JournalWriteFailed) {}
        assertTrue(j.records.value.isEmpty())
        store.failSaves = false
        val r = j.started()
        assertEquals(1L, r.id)            // the failed attempt did not use up an id
    }

    @Test fun ifSentCannotBeWrittenTheRowStaysRequestedAndTheCallerDoesNotSend() {
        val j = journal()
        val r = j.started()
        store.failSaves = true
        try { j.markSent(r.id, "w2:p1"); fail("expected JournalWriteFailed") } catch (_: JournalWriteFailed) {}
        assertEquals(OperationOutcome.Requested, j.get(r.id)!!.outcome)
    }

    @Test fun aLaterWriteFailureKeepsMemoryRightAndTheDiskTheMoreCautious() {
        val j = journal()
        val r = j.started(); j.markSent(r.id, "w2:p1")
        store.failSaves = true
        assertEquals(OperationOutcome.Acknowledged, j.acknowledged(r.id).outcome)
        assertTrue(j.saveFailed)
        assertEquals(OperationOutcome.Sent, store.load().records.single().outcome)     // disk still says Sent
        assertEquals(OperationOutcome.Unknown, journal().records.value.single().outcome) // a restart reads it as unknown
    }

    // ---- restart ---------------------------------------------------------------------------------------------------

    @Test fun aRestartReadsRequestedAsNotSentAndSentAsUnknown() {
        val j = journal()
        val requested = j.started(key("a"))
        val sent = j.started(key("b")); j.markSent(sent.id, "w2:p2")
        val acked = j.started(key("c")); j.markSent(acked.id, "w2:p3"); j.acknowledged(acked.id)

        val again = journal()
        val byId = again.records.value.associateBy { it.id }
        assertEquals(OperationOutcome.NotSent, byId.getValue(requested.id).outcome)
        assertEquals("app_restarted", byId.getValue(requested.id).code)
        assertEquals(OperationOutcome.Unknown, byId.getValue(sent.id).outcome)
        assertEquals(OperationOutcome.Acknowledged, byId.getValue(acked.id).outcome)
        // The recovered state is itself saved, so a second restart does not reinterpret it.
        assertEquals(byId.values.toList(), store.load().records)
    }

    @Test fun anUnknownRowSurvivesAFileRestartWithItsResolutionAndStillBlocksUntilResolved() {
        val file = File(tmp.root, "operations.json")
        val j = journal(FileJournalStore(file))
        val r = j.started(); j.markSent(r.id, "w2:p1"); j.unknown(r.id, "link lost")

        val after = journal(FileJournalStore(file))
        val row = after.records.value.single()
        assertEquals(OperationOutcome.Unknown, row.outcome)
        assertNull(row.resolvedAt)
        assertTrue(after.begin(key(), OperationKind.Prompt, "x") is Begin.NeedsReread)
        after.resolve(r.id)
        assertNotNull(journal(FileJournalStore(file)).records.value.single().resolvedAt)
    }

    @Test fun aNewRowAfterARestartDoesNotReuseAnId() {
        val file = File(tmp.root, "operations.json")
        val first = journal(FileJournalStore(file)).started()
        val second = journal(FileJournalStore(file)).started(key("other"))
        assertTrue(second.id > first.id)
    }

    @Test fun anUnreadableFileIsKeptAsideAndTheJournalStartsEmpty() {
        val file = File(tmp.root, "operations.json")
        file.writeText("{ not json")
        val j = journal(FileJournalStore(file))
        assertTrue(j.records.value.isEmpty())
        assertEquals("{ not json", File(tmp.root, "operations.json.corrupt").readText())
    }

    // ---- retention -------------------------------------------------------------------------------------------------

    @Test fun oldRowsGoButAnUnknownRowIsNeverDropped() {
        val j = journal()
        val old = j.started(key("a")); j.markSent(old.id, "w2:p1"); j.acknowledged(old.id)
        val lost = j.started(key("b")); j.markSent(lost.id, "w2:p2"); j.unknown(lost.id); j.resolve(lost.id)
        now += OperationJournal.MAX_AGE_MILLIS + 1
        val fresh = journal().records.value
        assertEquals(listOf(lost.id), fresh.map { it.id })
    }

    @Test fun theRowCapDropsTheOldestButKeepsUnknownRows() {
        val seeded = (1..OperationJournal.MAX_ROWS + 10).map {
            OperationRecord(it.toLong(), "h1", "paddock-test", "t$it", 1, OperationKind.Esc, now - 1_000L + it,
                outcome = if (it == 3) OperationOutcome.Unknown else OperationOutcome.Acknowledged)
        }
        val j = journal(InMemoryJournalStore(JournalData(nextId = 6_000, records = seeded)))
        val ids = j.records.value.map { it.id }
        assertTrue(3L in ids)
        assertEquals(OperationJournal.MAX_ROWS + 1, ids.size)       // the cap plus the protected unknown row
        assertFalse(1L in ids); assertFalse(2L in ids)
        assertTrue((OperationJournal.MAX_ROWS + 10L) in ids)
    }
}
