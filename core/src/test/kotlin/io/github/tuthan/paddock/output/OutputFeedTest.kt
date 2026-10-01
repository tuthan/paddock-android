package io.github.tuthan.paddock.output

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutputFeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val target = TargetRef(HostProfileId("laptop"), "main", "term_a")
    private val installed = MutableStateFlow<Installed?>(snapshotWith(Pane("w1:p1", "term_a", "w1", "w1:t1")))
    private val paneIds = CopyOnWriteArrayList<String>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    @Volatile private var next: (Int) -> OutputRead = { n -> OutputRead.Text("line $n") }

    private fun snapshotWith(vararg panes: Pane) = Installed(Snapshot("0.9.1", 22, panes = panes.toList()), 0, 1)

    private fun feed(interval: Long = 1_000) =
        OutputFeed(scope, target, installed, { id -> paneIds += id; next(paneIds.size) }, clock, intervalMillis = interval, sleep = { sleeps += it; delay(5) })

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun nothingIsReadUntilTheScreenIsVisible() = runBlocking<Unit> {
        val f = feed(); f.start()
        delay(100)
        assertEquals(0, paneIds.size)
        assertEquals(OutputState.Loading, f.state.value)
        f.setVisible(true)
        until("first read") { f.state.value is OutputState.Showing }
        assertEquals("line 1", (f.state.value as OutputState.Showing).lines.single().text)
        f.stop()
    }

    @Test fun itPollsEverySecondByDefaultWhileVisibleAndFollowing() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("three reads") { paneIds.size >= 3 }
        assertTrue(sleeps.all { it == 1_000L }, "interval was $sleeps")
        f.stop()
    }

    @Test fun hidingTheScreenStopsPolling() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("reading") { paneIds.size >= 2 }
        f.setVisible(false); delay(60)
        val n = paneIds.size; delay(150)
        assertEquals(n, paneIds.size)
        f.setVisible(true)
        until("reading again") { paneIds.size > n }
        f.stop()
    }

    @Test fun scrollingUpStopsPollingKeepsTheTextAndTappingResumes() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("reading") { paneIds.size >= 2 }
        f.userScrolledUp(); delay(60)
        val n = paneIds.size
        val shown = f.state.value
        delay(200)
        assertEquals(n, paneIds.size, "no reads while the user is reading history")
        assertEquals(shown, f.state.value)
        assertFalse(f.following.value)
        f.resumeFollowing()
        until("resumed") { paneIds.size > n }
        assertTrue(f.following.value)
        f.stop()
    }

    @Test fun aRenumberedPaneIsFollowedByTerminalId() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("first read") { paneIds.isNotEmpty() }
        installed.value = snapshotWith(Pane("w2:p7", "term_a", "w2", "w2:t1"))
        until("read on the new id") { paneIds.contains("w2:p7") }
        f.stop()
    }

    @Test fun whenTheTerminalLeavesTheIdIsNeverReadAgainEvenIfAnotherPaneTakesIt() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("first read") { paneIds.isNotEmpty() }
        // term_a is gone and a different terminal now holds pane id w1:p1.
        installed.value = snapshotWith(Pane("w1:p1", "term_b", "w1", "w1:t1"))
        until("gone") { f.state.value == OutputState.PaneGone }
        val n = paneIds.size
        delay(200)
        assertEquals(n, paneIds.size, "no further reads once the terminal is gone")
        f.stop()
    }

    @Test fun aVanishedPaneIsReportedEvenWhilePollingIsPaused() = runBlocking<Unit> {
        val f = feed(); f.start(); f.setVisible(true)
        until("first read") { paneIds.isNotEmpty() }
        f.userScrolledUp(); delay(60)
        installed.value = snapshotWith()
        until("gone") { f.state.value == OutputState.PaneGone }
        f.stop()
    }

    @Test fun herdrSayingNotFoundEndsTheFeed() = runBlocking<Unit> {
        next = { OutputRead.PaneMissing }
        val f = feed(); f.start(); f.setVisible(true)
        until("gone") { f.state.value == OutputState.PaneGone }
        val n = paneIds.size; delay(120)
        assertEquals(n, paneIds.size)
        f.stop()
    }

    @Test fun aFailedReadKeepsTheLastTextMarkedStaleAndRecovers() = runBlocking<Unit> {
        next = { n -> if (n == 2) OutputRead.Failed("boom") else OutputRead.Text("ok $n") }
        val f = feed(); f.start(); f.setVisible(true)
        until("stale") { (f.state.value as? OutputState.Showing)?.stale == true }
        assertEquals("ok 1", (f.state.value as OutputState.Showing).lines.single().text)
        until("recovered") { (f.state.value as? OutputState.Showing)?.stale == false && paneIds.size >= 3 }
        f.stop()
    }

    @Test fun aFirstReadThatFailsIsUnavailableNotEmpty() = runBlocking<Unit> {
        next = { OutputRead.Failed("no route") }
        val f = feed(); f.start(); f.setVisible(true)
        until("unavailable") { f.state.value is OutputState.Unavailable }
        assertEquals("no route", (f.state.value as OutputState.Unavailable).message)
        f.stop()
    }

    @Test fun aThrowingReaderDoesNotKillTheFeed() = runBlocking<Unit> {
        next = { n -> if (n == 1) error("link dropped") else OutputRead.Text("later") }
        val f = feed(); f.start(); f.setVisible(true)
        until("recovered") { (f.state.value as? OutputState.Showing)?.lines?.singleOrNull()?.text == "later" }
        f.stop()
    }

    @Test fun nothingIsReadBeforeTheFirstSnapshotIsInstalled() = runBlocking<Unit> {
        installed.value = null
        val f = feed(); f.start(); f.setVisible(true)
        delay(100)
        assertEquals(0, paneIds.size)
        assertEquals(OutputState.Loading, f.state.value, "starting is not the same as gone")
        installed.value = snapshotWith(Pane("w1:p1", "term_a", "w1", "w1:t1"))
        until("read") { paneIds.isNotEmpty() }
        f.stop()
    }

    @Test fun theReaderAsksForTwoHundredUnwrappedAnsiLinesAndMapsTheAnswers() = runBlocking<Unit> {
        val cli = HerdrCli("/usr/local/bin/herdr", "main")
        val answers = ArrayDeque(listOf(
            FakeSession.result(0, out = "hello\n"),
            FakeSession.result(1, err = """{"error":{"code":"agent_not_found","message":"agent target w1:p1 not found"},"id":"x"}"""),
            FakeSession.result(2, err = "usage"),
            FakeSession.result(1, err = "segfault"),
        ))
        val session = FakeSession(onExec = { _, _ -> answers.removeFirst() })
        val reader = AgentOutputReader(session, cli)
        assertEquals(OutputRead.Text("hello\n"), reader.read("w1:p1"))
        assertEquals(OutputRead.PaneMissing, reader.read("w1:p1"))
        assertIs<OutputRead.Failed>(reader.read("w1:p1"))
        assertIs<OutputRead.Failed>(reader.read("w1:p1"))
        val argv = session.execs.first().first
        assertEquals(
            listOf("/usr/local/bin/herdr", "--session", "main", "agent", "read", "w1:p1", "--source", "recent-unwrapped", "--lines", "200", "--format", "ansi"),
            argv,
        )
    }
}
