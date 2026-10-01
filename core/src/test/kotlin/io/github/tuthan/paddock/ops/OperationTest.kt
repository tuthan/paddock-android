package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.PaneResolver
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.relay.HerdrError
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OperationTest {
    private var now = 5_000_000L
    private val store = InMemoryJournalStore()
    private val journal = OperationJournal(store) { now }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 3)
    private val writes = AtomicInteger()

    private fun snapshot(vararg panes: Pair<String, String>) = Snapshot("0.9.1", 22,
        panes = panes.map { (pane, term) -> Pane(paneId = pane, terminalId = term, workspaceId = "w1", tabId = "w1:t1") })

    private suspend fun run(
        k: TerminalKey = key,
        resolve: () -> String = { "w1:p1" },
        preflight: suspend (String) -> Preflight = { Preflight.Go(seq = 7) },
        send: suspend (String, suspend () -> Unit) -> String = { _, before -> before(); writes.incrementAndGet(); "ok" },
    ) = Operation.run(journal, k, OperationKind.Prompt, payload = "hello", resolveTarget = resolve, preflight = preflight, send = send)

    @Test fun anAcknowledgedSendIsJournaledAndWritesOnce() = runBlocking<Unit> {
        val r = run()
        val ack = assertIs<OperationResult.Acknowledged<String>>(r)
        assertEquals("ok", ack.value)
        assertEquals(OperationOutcome.Acknowledged, ack.record.outcome)
        assertEquals(1, writes.get())
        val row = store.load().records.single()
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertEquals("w1:p1", row.paneIdAtSend)
        assertEquals(7L, row.seqAtSend)
        assertEquals(OperationJournal.sha256Hex("hello"), row.payloadSha256)
    }

    @Test fun theRowIsSentAndOnDiskBeforeTheFirstByteIsWritten() = runBlocking<Unit> {
        var seenAtWrite: OperationOutcome? = null
        run(send = { _, before -> before(); seenAtWrite = store.load().records.single().outcome; "ok" })
        assertEquals(OperationOutcome.Sent, seenAtWrite)
    }

    @Test fun theRowIsRequestedAndOnDiskBeforeTheReadinessRead() = runBlocking<Unit> {
        var seen: OperationOutcome? = null
        run(preflight = { seen = store.load().records.single().outcome; Preflight.Go() })
        assertEquals(OperationOutcome.Requested, seen)
    }

    // ---- the guards before anything is written ---------------------------------------------------------------------

    @Test fun aChangedEpochIsRefusedBeforeAnyRowIsWritten() = runBlocking<Unit> {
        val resolver = PaneResolver(snapshot("w1:p1" to "term_1"), epoch = 4)   // the screen opened in epoch 3
        val r = run(resolve = { resolver.require(key) })
        assertEquals(OperationResult.Stale(key), r)
        assertTrue(journal.records.value.isEmpty())
        assertEquals(0, writes.get())
    }

    @Test fun aTerminalThatLeftTheSnapshotIsStale() = runBlocking<Unit> {
        val resolver = PaneResolver(snapshot("w1:p1" to "term_other"), epoch = 3)
        assertIs<OperationResult.Stale>(run(resolve = { resolver.require(key) }))
        assertEquals(0, writes.get())
    }

    @Test fun aRenumberedPaneIsFollowedByTerminalId() = runBlocking<Unit> {
        val resolver = PaneResolver(snapshot("w1:p9" to "term_1"), epoch = 3)
        var sentTo: String? = null
        run(resolve = { resolver.require(key) }, send = { pane, before -> before(); sentTo = pane; "ok" })
        assertEquals("w1:p9", sentTo)
    }

    @Test fun aRefusingPreflightIsNotSentWithItsCodeAndTheSendNeverRuns() = runBlocking<Unit> {
        val r = run(preflight = { Preflight.Refuse("agent_working", "the agent is working") })
        val n = assertIs<OperationResult.NotSent>(r)
        assertEquals("agent_working", n.reason)
        assertEquals(OperationOutcome.NotSent, journal.records.value.single().outcome)
        assertEquals(0, writes.get())
    }

    @Test fun aFailedReadBeforeTheSendIsNotSent() = runBlocking<Unit> {
        val r = run(preflight = { throw IOException("read timed out") })
        val n = assertIs<OperationResult.NotSent>(r)
        assertEquals("preflight_failed", n.reason)
        assertEquals(0, writes.get())
    }

    @Test fun cancellingDuringThePreflightLeavesNotSent() = runBlocking<Unit> {
        val inPreflight = CompletableDeferred<Unit>()
        val job = launch { run(preflight = { inPreflight.complete(Unit); awaitCancellation() }) }
        inPreflight.await()
        job.cancelAndJoin()
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.NotSent, row.outcome)
        assertEquals("cancelled", row.code)
    }

    // ---- one at a time ---------------------------------------------------------------------------------------------

    @Test fun aSecondTapWhileTheFirstIsInFlightIsRefusedAndExactlyOneWriteHappens() = runBlocking<Unit> {
        val inSend = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { run(send = { _, before -> before(); writes.incrementAndGet(); inSend.complete(Unit); release.await(); "ok" }) }
        inSend.await()
        val second = run()
        val busy = assertIs<OperationResult.Busy>(second)
        assertEquals(OperationOutcome.Sent, busy.first.outcome)
        release.complete(Unit)
        assertIs<OperationResult.Acknowledged<String>>(first.await())
        assertEquals(1, writes.get())
        assertEquals(1, journal.records.value.size)
    }

    @Test fun afterAcknowledgementTheNextOperationRuns() = runBlocking<Unit> {
        run(); run()
        assertEquals(2, writes.get())
    }

    // ---- what comes back -------------------------------------------------------------------------------------------

    @Test fun herdrsRefusalIsRejectedWithItsCodeAndNeverRetried() = runBlocking<Unit> {
        val r = run(send = { _, before -> before(); writes.incrementAndGet(); throw HerdrError("agent_blocked", "agent w1:p1 is blocked and requires interactive input") })
        val rej = assertIs<OperationResult.Rejected>(r)
        assertEquals("agent_blocked", rej.code)
        assertEquals(OperationOutcome.Rejected, journal.records.value.single().outcome)
        assertEquals("agent_blocked", journal.records.value.single().code)
        assertEquals(1, writes.get())
    }

    @Test fun aFailureBeforeTheWriteIsNotSent() = runBlocking<Unit> {
        val r = run(send = { _, _ -> throw IOException("no channel") })
        val n = assertIs<OperationResult.NotSent>(r)
        assertEquals("transport_failed", n.reason)
        assertNull(journal.records.value.single().sentAt)
    }

    @Test fun aFailureAfterTheWriteIsUnknownAndNothingIsEverResent() = runBlocking<Unit> {
        val r = run(send = { _, before -> before(); writes.incrementAndGet(); throw IOException("stream closed") })
        val u = assertIs<OperationResult.Unknown>(r)
        assertEquals(OperationOutcome.Unknown, u.record.outcome)
        assertEquals(OperationOutcome.Unknown, store.load().records.single().outcome)
        // The same tap again, and a different prompt, both stop at the re-read; no write follows.
        assertIs<OperationResult.NeedsReread>(run())
        assertIs<OperationResult.NeedsReread>(Operation.run(journal, key, OperationKind.Esc, resolveTarget = { "w1:p1" }, send = { _, before -> before(); writes.incrementAndGet(); "ok" }))
        assertEquals(1, writes.get())
    }

    @Test fun afterTheUserReReadsTheTerminalIsFreeAgain() = runBlocking<Unit> {
        run(send = { _, before -> before(); throw IOException("stream closed") })
        journal.resolve(journal.records.value.single().id, "re-read")
        assertIs<OperationResult.Acknowledged<String>>(run())
        assertEquals(1, writes.get())
    }

    @Test fun cancellingAfterTheWriteStillLeavesUnknownOnDisk() = runBlocking<Unit> {
        val written = CompletableDeferred<Unit>()
        val job = launch { run(send = { _, before -> before(); written.complete(Unit); awaitCancellation() }) }
        written.await()
        job.cancelAndJoin()
        assertEquals(OperationOutcome.Unknown, store.load().records.single().outcome)
    }

    @Test fun aCancellationIsStillACancellationForTheCaller() = runBlocking<Unit> {
        val written = CompletableDeferred<Unit>()
        val job = async { run(send = { _, before -> before(); written.complete(Unit); awaitCancellation() }) }
        written.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }

    // ---- the journal as the gate ---------------------------------------------------------------------------------------

    @Test fun ifTheRowCannotBeWrittenNothingIsSent() = runBlocking<Unit> {
        store.failSaves = true
        val r = run()
        assertIs<OperationResult.JournalFailed>(r)
        assertEquals(0, writes.get())
        assertTrue(journal.records.value.isEmpty())
    }

    @Test fun ifSentCannotBeWrittenTheWriteDoesNotHappen() = runBlocking<Unit> {
        val r = run(preflight = { store.failSaves = true; Preflight.Go() })
        assertIs<OperationResult.JournalFailed>(r)
        assertEquals(0, writes.get())
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.NotSent, row.outcome)
        assertEquals("journal_unwritable", row.code)
    }

    @Test fun aSendThatNeverReportedItsWriteIsAnErrorAndLeavesNotSent() = runBlocking<Unit> {
        val r = run(send = { _, _ -> "ok" })
        assertIs<OperationResult.NotSent>(r)
        assertEquals(OperationOutcome.NotSent, journal.records.value.single().outcome)
    }
}
