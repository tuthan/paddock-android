package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.reconcile.Installed
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

class AnswerControllerTest {
    private var now = 5_000_000L
    private val store = InMemoryJournalStore()
    private val journal = OperationJournal(store) { now }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var installed: Installed? = Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1"))), now, 1)
    @Volatile private var status: AgentStatus? = AgentStatus.Blocked
    @After fun stop() { scope.cancel() }

    private class Decided(val pane: String, val session: String, val id: String, val behavior: Behavior)

    private inner class FakePort : AnswerPort {
        @Volatile var listing: RequestListing = listingOf(received = now, candidate = request(ID_A))
        val lists = CopyOnWriteArrayList<String>()
        val decides = CopyOnWriteArrayList<Decided>()
        @Volatile var onDecide: suspend () -> Unit = {}
        @Volatile var failList: Throwable? = null
        /** While set, every read waits on it: a link that went dead after the write. */
        @Volatile var listGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun list(paneId: String): RequestListing { lists += paneId; listGate?.await(); failList?.let { throw it }; return listing.copy(receivedAtMillis = now) }
        override suspend fun decide(paneId: String, claudeSessionId: String, requestId: String, behavior: Behavior, beforeWrite: suspend () -> Unit) {
            beforeWrite(); decides += Decided(paneId, claudeSessionId, requestId, behavior); onDecide()
        }
    }

    private val port = FakePort()
    private fun controller(poll: Long = 20, settle: Long = 10) = AnswerController(scope, port, journal, { installed }, { status }, { now }, poll, settle)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private suspend fun AnswerController.opened(): AnswerController { refresh(key); until("the request to be shown") { views.value["term_1"]?.shown != null }; return this }
    private fun view(c: AnswerController) = c.views.value.getValue("term_1")
    private suspend fun AnswerController.answered(b: Behavior) { answer(key, b, view(this).shown!!.requestId); until("the answer") { view(this).result != null && "term_1" !in running.value } }

    @Test fun theFirstRequestSeenIsTheOneOnTheSheetAndItIsAnswerable() = runBlocking<Unit> {
        val c = controller().opened()
        assertEquals(ID_A, view(c).shown?.requestId)
        assertIs<Answerability.Yes>(view(c).answerability)
        assertEquals(RequestOutcome.Waiting, view(c).outcome)
    }

    @Test fun aYesIsOneJournaledWriteBoundToTheRequestOnTheSheet() = runBlocking<Unit> {
        val c = controller().opened()
        c.answered(Behavior.Allow)
        val d = port.decides.single()
        assertEquals(listOf("w1:p1", "claude-1", ID_A, Behavior.Allow), listOf(d.pane, d.session, d.id, d.behavior))
        val r = assertIs<OperationResult.Acknowledged<*>>(view(c).result!!.result)
        assertEquals(OperationKind.Allow, r.record.kind)
        assertEquals(ID_A, r.record.requestId)
        val row = journal.records.value.single()
        assertEquals(listOf(OperationKind.Allow, OperationOutcome.Acknowledged, ID_A, "w1:p1"), listOf(row.kind, row.outcome, row.requestId, row.paneIdAtSend))
    }

    @Test fun aNoIsTheSameAndAlsoJournaled() = runBlocking<Unit> {
        val c = controller().opened()
        c.answered(Behavior.Deny)
        assertEquals(Behavior.Deny, port.decides.single().behavior)
        assertEquals(OperationKind.Deny, journal.records.value.single().kind)
    }

    @Test fun theRowIsOnDiskAsSentBeforeTheWriterIsCalled() = runBlocking<Unit> {
        var atWrite: OperationOutcome? = null
        port.onDecide = { atWrite = store.load().records.single().outcome }
        val c = controller().opened()
        c.answered(Behavior.Allow)
        assertEquals(OperationOutcome.Sent, atWrite)
    }

    @Test fun sendingEndsWhenTheOutcomeIsKnownEvenIfTheFollowUpReadHangs() = runBlocking<Unit> {
        val c = controller().opened()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        port.onDecide = { port.listGate = gate }
        c.answer(key, Behavior.Allow, ID_A)
        until("the result") { view(c).result != null }
        until("sending to end while the follow-up read is still stuck") { "term_1" !in c.running.value }
        assertEquals(1, port.decides.size)
        gate.complete(Unit)
    }

    @Test fun aTapDrawnForAnotherRequestThanTheOneNowOnTheSheetSendsNothingAndIsJournaledAsReplaced() = runBlocking<Unit> {
        val c = controller().opened()
        assertEquals(ID_A, view(c).shown?.requestId)
        c.answer(key, Behavior.Allow, ID_B)          // the screen had drawn B; the sheet is on A now
        until("the refusal") { view(c).result != null && "term_1" !in c.running.value }
        assertTrue(port.decides.isEmpty(), "nothing was written")
        val result = view(c).result!!
        assertEquals(ID_A, result.requestId, "the refusal is told on the sheet that is up now, so the user sees why nothing was sent")
        val notSent = assertIs<OperationResult.NotSent>(result.result)
        assertEquals(NotAnswerable.Replaced.code, notSent.reason)
        assertEquals(listOf(ID_B), journal.records.value.map { it.requestId }, "the row names the request the tap was for")
    }

    @Test fun aRequestRecordsWhenItBecameTheOneOnTheSheet() = runBlocking<Unit> {
        now = 5_000_000L
        val c = controller().opened()
        assertEquals(5_000_000L, view(c).shownSince)
        now = 5_020_000L
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Pending), entry(ID_B, RequestState.Pending, created = 9_000)), candidate = request(ID_B, created = 9_000))
        c.refresh(key)
        until("the newer request to be offered") { view(c).newer?.requestId == ID_B }
        assertEquals(5_000_000L, view(c).shownSince, "offering a newer request changes nothing about the one on the sheet")
        now = 5_030_000L
        c.review("term_1")
        assertEquals(ID_B, view(c).shown?.requestId)
        assertEquals(5_030_000L, view(c).shownSince, "reviewing starts the clock again")
    }

    @Test fun aSecondTapWhileTheFirstRunsNeverMakesASecondWrite() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        port.onDecide = { gate.await() }
        val c = controller().opened()
        c.answer(key, Behavior.Allow, ID_A)
        until("the write to start") { port.decides.isNotEmpty() }
        c.answer(key, Behavior.Allow, ID_A)
        c.answer(key, Behavior.Deny, ID_A)
        delay(100)
        assertEquals(1, port.decides.size)
        gate.complete(Unit)
        until("the answer") { view(c).result != null }
        assertEquals(1, port.decides.size)
        assertEquals(1, journal.records.value.size)
    }

    @Test fun aTapAfterTheAnswerIsRefusedByTheHostsOwnStateNotSentAgain() = runBlocking<Unit> {
        val c = controller().opened()
        c.answered(Behavior.Allow)
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Consumed, decision = Behavior.Allow)), candidate = null)
        c.answered(Behavior.Allow)
        assertEquals(1, port.decides.size)
        val ns = assertIs<OperationResult.NotSent>(view(c).result!!.result)
        assertEquals(NotAnswerable.NothingPending.code, ns.reason)
    }

    @Test fun aNewerRequestIsOfferedNeverSwappedInAndAnAnswerToTheOldOneIsRefused() = runBlocking<Unit> {
        val c = controller().opened()
        port.listing = listingOf(
            entries = listOf(entry(ID_A, RequestState.Pending, created = 1_000), entry(ID_B, RequestState.Pending, created = 5_000)), candidate = request(ID_B, created = 5_000),
        )
        c.refresh(key)
        until("the newer request") { view(c).newer != null }
        assertEquals(ID_A, view(c).shown?.requestId, "the request on the sheet did not change under the user")
        assertEquals(RequestOutcome.Replaced, view(c).outcome)
        c.answered(Behavior.Allow)
        assertTrue(port.decides.isEmpty(), "nothing is sent for a request that was replaced")
        val ns = assertIs<OperationResult.NotSent>(view(c).result!!.result)
        assertEquals(NotAnswerable.Replaced.code, ns.reason)
        c.review("term_1")
        assertEquals(ID_B, view(c).shown?.requestId)
        assertNull(view(c).newer)
        assertNull(view(c).result)
        c.answered(Behavior.Allow)
        assertEquals(ID_B, port.decides.single().id)
    }

    @Test fun aRequestThatExpiredOrWasAnsweredOnTheDesktopIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        val c = controller().opened()
        port.listing = listingOf(hostNow = 61_000, candidate = request(ID_A))
        c.answered(Behavior.Allow)
        assertEquals(NotAnswerable.Expired.code, assertIs<OperationResult.NotSent>(view(c).result!!.result).reason)
        c.dismiss("term_1")
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Expired)), candidate = null)
        c.answered(Behavior.Allow)
        assertEquals(NotAnswerable.Expired.code, assertIs<OperationResult.NotSent>(view(c).result!!.result).reason)
        assertTrue(port.decides.isEmpty())
    }

    @Test fun tooLittleTimeIsRefusedToo() = runBlocking<Unit> {
        val c = controller().opened()
        port.listing = listingOf(hostNow = 61_000 - 100 - 300, rtt = 100, candidate = request(ID_A))
        c.answered(Behavior.Allow)
        assertEquals(NotAnswerable.TooLate.code, assertIs<OperationResult.NotSent>(view(c).result!!.result).reason)
        assertTrue(port.decides.isEmpty())
    }

    @Test fun aWriterThatSaysGoneIsALostRaceNotAnUnknown() = runBlocking<Unit> {
        port.onDecide = { throw DecisionRefused(AnswerCodes.GONE, "gone") }
        val c = controller().opened()
        c.answered(Behavior.Allow)
        val rej = assertIs<OperationResult.Rejected>(view(c).result!!.result)
        assertEquals(AnswerCodes.GONE, rej.code)
        assertEquals(OperationOutcome.Rejected, journal.records.value.single().outcome)
        assertTrue(journal.unresolvedUnknown(key).isEmpty(), "a refusal is certain: nothing waits for a re-read")
    }

    @Test fun aLinkLostAfterTheWriteIsUnknownAndIsSettledFromTheHostsFiles() = runBlocking<Unit> {
        port.onDecide = { throw IOException("link lost") }
        val c = controller().opened()
        c.answered(Behavior.Allow)
        assertIs<OperationResult.Unknown>(view(c).result!!.result)
        val row = journal.records.value.single()
        assertEquals(listOf(OperationOutcome.Unknown, ID_A), listOf(row.outcome, row.requestId))
        assertTrue(row.awaitsReread)
        // nothing more may go out for this terminal until the outcome is read
        port.onDecide = {}
        c.dismiss("term_1")
        c.answered(Behavior.Allow)
        assertIs<OperationResult.NeedsReread>(view(c).result!!.result)
        assertEquals(1, port.decides.size)
        // on reconnect the files say what became of it
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Consumed, decision = Behavior.Allow)), candidate = null)
        val settled = c.settle(key)
        assertEquals(RequestOutcome.Consumed(Behavior.Allow), settled.single().second)
        assertFalse(journal.get(row.id)!!.awaitsReread)
        assertEquals(OperationOutcome.Unknown, journal.get(row.id)!!.outcome, "the row stays unknown; it only stops blocking")
        assertTrue(journal.get(row.id)!!.note.contains("taken by the hook"))
    }

    @Test fun settlingAnExpiredOrVanishedRequestSaysSo() = runBlocking<Unit> {
        port.onDecide = { throw IOException("link lost") }
        val c = controller().opened()
        c.answered(Behavior.Deny)
        port.listing = listingOf(entries = emptyList(), candidate = null)
        assertEquals(RequestOutcome.Gone, c.settle(key).single().second)
        assertTrue(c.settle(key).isEmpty(), "nothing left waiting")
    }

    @Test fun anUnreadableJournalSendsNothing() = runBlocking<Unit> {
        store.unreadable = true
        val j = OperationJournal(store) { now }
        val c = AnswerController(scope, port, j, { installed }, { status }, { now }, 20, 10)
        c.refresh(key); until("shown") { c.views.value["term_1"]?.shown != null }
        c.answer(key, Behavior.Allow, ID_A)
        until("the answer") { c.views.value["term_1"]?.result != null }
        assertIs<OperationResult.JournalUnreadable>(c.views.value.getValue("term_1").result!!.result)
        assertTrue(port.decides.isEmpty())
    }

    @Test fun aPaneThatMovedOnToAnotherEpochIsStaleAndNothingIsSent() = runBlocking<Unit> {
        val c = controller().opened()
        installed = installed!!.copy(epoch = 2)
        c.answered(Behavior.Allow)
        assertIs<OperationResult.Stale>(view(c).result!!.result)
        assertTrue(port.decides.isEmpty())
        assertTrue(journal.records.value.isEmpty(), "nothing was journaled for a send that could not be aimed")
    }

    @Test fun aSheetWithNothingShownSendsNothing() = runBlocking<Unit> {
        port.listing = listingOf(entries = emptyList(), candidate = null)
        val c = controller()
        c.refresh(key); until("a view") { c.views.value["term_1"]?.listing != null }
        c.answer(key, Behavior.Allow, ID_A)
        delay(100)
        assertTrue(port.decides.isEmpty())
        assertNull(view(c).shown)
        assertEquals(NotAnswerable.NothingPending, (view(c).answerability as Answerability.No).why)
    }

    @Test fun aFailedReadIsShownAsAnErrorAndKeepsTheLastGoodListing() = runBlocking<Unit> {
        val c = controller().opened()
        port.failList = IOException("host unreachable")
        c.refresh(key)
        until("the error") { view(c).error != null }
        assertEquals("host unreachable", view(c).error)
        assertEquals(ID_A, view(c).shown?.requestId)
        port.failList = null
        c.refresh(key)
        until("recovery") { view(c).error == null }
    }

    @Test fun anExpiredRequestWithNothingOfTheUsersOnItGivesWayToTheNewOne() = runBlocking<Unit> {
        val c = controller().opened()
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Expired), entry(ID_B, RequestState.Pending, created = 9_000)), candidate = request(ID_B, created = 9_000))
        c.refresh(key)
        until("adoption") { view(c).shown?.requestId == ID_B }
    }

    @Test fun anAnsweredRequestKeepsItsOutcomeOnTheSheetUntilTheUserMovesOn() = runBlocking<Unit> {
        val c = controller().opened()
        c.answered(Behavior.Allow)
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Consumed, decision = Behavior.Allow), entry(ID_B, RequestState.Pending, created = 9_000)), candidate = request(ID_B, created = 9_000))
        c.refresh(key)
        until("the newer one") { view(c).newer != null }
        assertEquals(ID_A, view(c).shown?.requestId)
        assertEquals(RequestOutcome.Consumed(Behavior.Allow), view(c).outcome)
    }

    @Test fun workingIsObservedOnlyAfterTheHookConsumedTheAnswerAndHerdrMovedOn() = runBlocking<Unit> {
        val c = controller().opened()
        c.answered(Behavior.Allow)
        assertFalse(view(c).workingObserved)
        port.listing = listingOf(entries = listOf(entry(ID_A, RequestState.Consumed, decision = Behavior.Allow)), candidate = null)
        status = AgentStatus.Blocked
        c.refresh(key); until("consumed") { view(c).outcome is RequestOutcome.Consumed }
        assertFalse(view(c).workingObserved, "still blocked: not observed")
        status = AgentStatus.Working
        c.refresh(key); until("working observed") { view(c).workingObserved }
    }

    @Test fun watchingReadsOnlyWhileTheAgentIsBlockedOrAnAnswerIsSettling() = runBlocking<Unit> {
        val c = controller(poll = 15)
        c.watch(key)
        until("the first read") { port.lists.isNotEmpty() }
        val blockedReads = port.lists.size
        delay(150)
        assertTrue(port.lists.size > blockedReads + 3, "polls while blocked")
        status = AgentStatus.Idle
        port.listing = listingOf(entries = emptyList(), candidate = null)
        delay(80)
        val idleStart = port.lists.size
        delay(200)
        assertEquals(idleStart, port.lists.size, "stops reading when the agent is no longer blocked and nothing is settling")
        c.unwatch("term_1")
    }

    @Test fun aHostThatFailsEveryReadIsAskedSlowlyEvenWhileTheAgentIsBlocked() = runBlocking<Unit> {
        port.failList = IOException("not set up")
        val c = AnswerController(scope, port, journal, { installed }, { status }, { now }, 10, 10, errorPollMillis = 150)
        c.watch(key)
        until("the error") { c.views.value["term_1"]?.error != null }
        delay(500)
        val reads = port.lists.size
        assertTrue(reads in 2..8, "read $reads times in half a second at a 10 ms poll")
        c.unwatch("term_1")
    }

    @Test fun twoScreensWatchingOneTerminalShareOnePoll() = runBlocking<Unit> {
        val c = controller(poll = 20)
        c.watch(key); c.watch(key)
        delay(120)
        c.unwatch("term_1")
        val before = port.lists.size
        delay(100)
        assertTrue(port.lists.size > before, "one watcher left, still polling")
        c.unwatch("term_1")
        delay(60)
        val stopped = port.lists.size
        delay(100)
        assertEquals(stopped, port.lists.size, "the last unwatch ends the poll")
    }

    @Test fun theControllerHoldsNoHerdrClientSoItCannotSendAKey() {
        val params = AnswerController::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }.map { it.name }
        for (banned in listOf("RelayClient", "AgentOperations", "SendController", "SshSession", "HerdrCli", "ControlHelper"))
            assertTrue(params.none { it.contains(banned) }, "AnswerController takes a $banned")
        val fields = AnswerController::class.java.declaredFields.map { it.type.name }
        for (banned in listOf("RelayClient", "AgentOperations", "SendController", "SshSession")) assertTrue(fields.none { it.contains(banned) }, "AnswerController holds a $banned")
    }
}
