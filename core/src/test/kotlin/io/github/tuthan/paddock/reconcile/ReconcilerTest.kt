package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilerTest {
    private val now = AtomicLong(1_000_000)
    private val clock = Clock { now.get() }
    private val host = HostProfileId("laptop")

    private fun pane(term: String, status: AgentStatus = AgentStatus.Idle, title: String? = null, id: String = "w1:$term") =
        Pane(paneId = id, terminalId = term, workspaceId = "w1", tabId = "w1:t1", agentStatus = status, terminalTitleStripped = title)
    private fun snap(vararg panes: Pane, version: String = "0.9.1") = Snapshot(version, 22, panes = panes.toList())

    private suspend fun awaitReads(r: Reconciler, n: Long) = withTimeout(5_000) { while (r.reads < n) delay(5) }

    // ---- a read that fails with a cancellation it did not ask for ------------------------------------------

    @Test fun aCancellationThatLeaksOutOfAReadIsAFailedReadAndTheLoopKeepsGoing() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val r = Reconciler(clock, host, "main", sleep = { delay(1) }, read = {
            if (calls.incrementAndGet() == 1) throw leakedCancellation() else snap(pane("a"))
        })
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        r.invalidate("first")
        withTimeout(5_000) { while (r.installed.value == null) delay(5) }
        assertEquals(2, calls.get(), "the failed read was retried, not the end of the loop")
        assertTrue(loop.isActive)
        loop.cancel(); loop.join()
        assertTrue(loop.isCancelled, "cancelling the loop's own coroutine still stops it")
    }

    @Test fun readAtIsWhenTheReadStartedSoAgeIsNeverUnderstated() = runBlocking<Unit> {
        val r = Reconciler(clock, host, "main", read = { val start = now.get(); now.addAndGet(5_000); snap(pane("a")).also { check(now.get() == start + 5_000) } })
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        val startedAt = now.get()
        r.invalidate("x")
        withTimeout(5_000) { while (r.installed.value == null) delay(5) }
        assertEquals(startedAt, r.installed.value!!.readAtMillis)
        assertEquals(5_000, r.ageMillis())
        loop.cancel()
    }

    // ---- AC-03.4: single flight and repeat-after-dirty -------------------------------------------------------

    @Test fun noReadIsEverConcurrentWithAnotherUnderAStormOfInvalidations() = runBlocking<Unit> {
        val concurrent = AtomicInteger(); val peak = AtomicInteger(); val total = AtomicInteger()
        val r = Reconciler(clock, host, "main", read = {
            val c = concurrent.incrementAndGet(); peak.updateAndGet { maxOf(it, c) }
            delay(10); total.incrementAndGet(); concurrent.decrementAndGet(); snap(pane("a"))
        })
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        coroutineScopeStorm(r)
        withTimeout(5_000) { while (r.installed.value == null) delay(5) }
        delay(200)
        assertEquals(1, peak.get(), "reads overlapped")
        assertTrue(total.get() in 1..20, "a storm of 500 invalidations collapses to a few reads, was ${total.get()}")
        loop.cancel()
    }

    private suspend fun coroutineScopeStorm(r: Reconciler) {
        val jobs = (1..10).map { CoroutineScope(Dispatchers.Default).launch { repeat(50) { r.invalidate("storm"); delay(1) } } }
        jobs.forEach { it.join() }
    }

    @Test fun anInvalidationDuringAReadCausesExactlyOneMoreRead() = runBlocking<Unit> {
        val started = Channel<Int>(Channel.UNLIMITED)
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val n = AtomicInteger()
        val r = Reconciler(clock, host, "main", read = { val i = n.getAndIncrement(); started.send(i); gates[i].await(); snap(pane("a")) })
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        r.invalidate("first")
        assertEquals(0, withTimeout(2_000) { started.receive() })
        r.invalidate("during"); r.invalidate("during again")      // two events inside one read still mean one more read
        gates[0].complete(Unit)
        assertEquals(1, withTimeout(2_000) { started.receive() })
        gates[1].complete(Unit)
        delay(300)
        assertTrue(started.tryReceive().isFailure, "no third read")
        assertEquals(2, r.reads)
        loop.cancel()
    }

    // ---- AC-03.5: age comes from readAt ---------------------------------------------------------------------------

    @Test fun ageIsMeasuredFromTheReadAndNotFromAnEvent() = runBlocking<Unit> {
        val r = Reconciler(clock, host, "main", read = { snap(pane("a")) })
        assertNull(r.ageMillis())
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        r.invalidate("start"); awaitReads(r, 1); withTimeout(2_000) { r.installed.first { it != null } }
        assertEquals(0L, r.ageMillis())
        now.addAndGet(60_000)                                    // a stream that stays silent for 60 s
        assertEquals(60_000L, r.ageMillis())
        loop.cancel()
    }

    // ---- failure, backoff, heartbeat -----------------------------------------------------------------------------

    @Test fun aFailedReadKeepsTheOldSnapshotBacksOffAndRetries() = runBlocking<Unit> {
        val sleeps = mutableListOf<Long>(); val n = AtomicInteger()
        val r = Reconciler(clock, host, "main", sleep = { sleeps += it }, read = { if (n.incrementAndGet() <= 2) error("link down") else snap(pane("a")) })
        val loop = CoroutineScope(Dispatchers.Default).launch { r.run() }
        r.invalidate("start")
        withTimeout(2_000) { r.installed.first { it != null } }
        assertEquals(listOf(1_000L, 2_000L), sleeps)
        // The install and the clearing of the failure are two writes; the test can run between them under load.
        withTimeout(2_000) { r.lastFailure.first { it == null } }
        loop.cancel()
    }

    @Test fun backoffSchedule() {
        val max = Backoff(random = { 1.0 }); var t = 0L
        val delays = (1..9).map { max.nextDelayMillis(t).also { t += it } }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 120_000, 120_000), delays)
        val min = Backoff(random = { 0.0 }); t = 0
        val low = (1..9).map { min.nextDelayMillis(t).also { t += 1 } }
        assertEquals(listOf(1_000L, 2_000, 4_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), low)
    }

    @Test fun backoffRestartsOnlyAfterSixtySecondsOfContinuousSuccess() {
        val b = Backoff(random = { 1.0 })
        assertEquals(1_000, b.nextDelayMillis(0)); assertEquals(2_000, b.nextDelayMillis(1_000)); assertEquals(4_000, b.nextDelayMillis(3_000))
        b.succeeded(10_000)
        assertEquals(8_000, b.nextDelayMillis(10_000 + 30_000), "30 s of health is not enough")
        b.succeeded(50_000); b.succeeded(70_000)                 // the streak started at 50 s, later successes do not restart it
        assertEquals(1_000, b.nextDelayMillis(50_000 + 60_000))
        assertEquals(2_000, b.nextDelayMillis(111_000))
        // a long wait between failures is not health
        val c = Backoff(random = { 1.0 }); var t = 0L
        repeat(8) { t += c.nextDelayMillis(t) }
        assertEquals(120_000, c.nextDelayMillis(t))
    }

    @Test fun heartbeatInvalidatesOnlyWhileInTheForeground() = runBlocking<Unit> {
        val ticks = Channel<Unit>(Channel.UNLIMITED); val reads = AtomicInteger()
        val fg = MutableStateFlow(true)
        val r = Reconciler(clock, host, "main", sleep = { ticks.receive() }, read = { reads.incrementAndGet(); snap(pane("a")) })
        val scope = CoroutineScope(Dispatchers.Default)
        val loop = scope.launch { r.run() }; val hb = r.heartbeat(scope, fg)
        ticks.send(Unit); withTimeout(2_000) { while (reads.get() < 1) delay(5) }
        fg.value = false; ticks.send(Unit); ticks.send(Unit); delay(200)
        assertEquals(1, reads.get(), "a background tick is not a read")
        fg.value = true; ticks.send(Unit); withTimeout(2_000) { while (reads.get() < 2) delay(5) }
        hb.cancel(); loop.cancel()
    }

    // ---- Diff ---------------------------------------------------------------------------------------------------

    private fun diff(a: Snapshot, b: Snapshot, epoch: Long = 1) = Diff.observe(a, b, 7, host, "main", epoch)

    @Test fun diffTable() {
        // new blocked, new done, plain change
        val s = diff(snap(pane("a", AgentStatus.Working), pane("b", AgentStatus.Working)), snap(pane("a", AgentStatus.Blocked), pane("b", AgentStatus.Done)))
        val changes = s.filterIsInstance<Observation.StatusChanged>().associateBy { it.key.target.terminalId }
        assertTrue(changes.getValue("a").newlyBlocked); assertTrue(changes.getValue("b").newlyDone)
        // a repeated sample is nothing
        assertEquals(emptyList(), diff(snap(pane("a", AgentStatus.Blocked)), snap(pane("a", AgentStatus.Blocked))))
        // appeared and vanished are matched by terminal id, so a renumbered pane is neither
        val move = diff(snap(pane("a", id = "w1:p1")), snap(pane("a", id = "w1:p9")))
        assertEquals(emptyList(), move)
        val av = diff(snap(pane("a")), snap(pane("b")))
        assertTrue(av.any { it is Observation.PaneVanished && it.key.target.terminalId == "a" })
        assertTrue(av.any { it is Observation.PaneAppeared && it.key.target.terminalId == "b" })
        // titles
        val t = diff(snap(pane("a", title = "x")), snap(pane("a", title = "y"))).single() as Observation.TitleChanged
        assertEquals("x" to "y", t.from to t.to)
        // stamped with the phone's clock and the epoch's key
        assertTrue(s.all { it.at == 7L && it.key.epoch == 1L && it.key.target.host == host && it.key.target.session == "main" })
    }

    @Test fun theBaselineAndTheFirstReadAfterAReconnectProduceNoObservations() = runBlocking<Unit> {
        val reads = ArrayDeque(listOf(snap(pane("a", AgentStatus.Working)), snap(pane("a", AgentStatus.Blocked)), snap(pane("a", AgentStatus.Done))))
        val r = Reconciler(clock, host, "main", read = { reads.removeFirst() })
        val seen = mutableListOf<Observation>()
        val scope = CoroutineScope(Dispatchers.Default)
        val collector = scope.launch { r.observations.collect { seen += it } }
        val loop = scope.launch { r.run() }
        delay(50)
        r.invalidate("baseline"); awaitReads(r, 1); delay(100)
        assertTrue(seen.isEmpty(), "a baseline must not manufacture history")
        r.invalidate("change"); awaitReads(r, 2); delay(100)
        assertEquals(1, seen.size); assertTrue((seen[0] as Observation.StatusChanged).newlyBlocked)
        r.onReconnect(); awaitReads(r, 3); delay(100)
        assertEquals(1, seen.size, "the read after a reconnect is a new baseline, not a completion")
        assertEquals(2L, r.installed.value!!.epoch)
        collector.cancel(); loop.cancel()
    }
}

/** Stands in for a transport timeout that surfaces as a CancellationException the reconciler did not ask for. */
private fun leakedCancellation(): kotlin.coroutines.cancellation.CancellationException =
    kotlin.coroutines.cancellation.CancellationException("timed out inside the transport")

