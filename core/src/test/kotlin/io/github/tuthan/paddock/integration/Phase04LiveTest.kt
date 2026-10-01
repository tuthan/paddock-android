package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.Section
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentListResult
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.ActionKind
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.ledger.ObservationKind
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Freshness
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Phase 04 against the disposable `paddock-test` session: AC-04.1 (home equals `agent list` by set and status), AC-04.2
 * (output advances with no title change; scrolling up pauses, resuming reads again), AC-04.3 (a Done tap is local: no
 * `agent focus` or `pane focus` reaches herdr). Run with `PADDOCK_TEST_SOCKET=<.../sessions/paddock-test/herdr.sock>`.
 */
class Phase04LiveTest {
    private lateinit var env: PaddockTest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val foreground = MutableStateFlow(true)
    private val extraPanes = CopyOnWriteArrayList<String>()
    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    private lateinit var base: String

    @Before fun setUp() = runBlocking<Unit> { env = PaddockTest.orSkip(); base = env.basePane() }

    @After fun tearDown() {
        if (::env.isInitialized) runBlocking { runCatching { env.releaseAgent(base) }; extraPanes.forEach { runCatching { env.close(it) } } }
        scope.coroutineContext[Job]?.cancel()
    }

    private val profile = HostProfile("local", "Local", "127.0.0.1", 22, "tester")

    private suspend fun host(transform: (io.github.tuthan.paddock.herdr.Snapshot) -> io.github.tuthan.paddock.herdr.Snapshot = { it }): MonitoredHost =
        MonitoredHost(scope, profile, env.sessionName, env.session, env.relayScript, env.socket, ledger, clock, foreground, env.herdr, transform)
            .also { it.start(); withTimeout(15_000) { it.freshness.first { f -> f == Freshness.Live } } }

    private suspend fun until(what: String, ms: Long = 10_000, cond: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!cond()) delay(25) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private suspend fun agentList(): List<Agent> {
        val ok = CliResult.classify(env.session.exec(env.cli.agentList())) as CliOutcome.Ok
        return CliResult.decode<AgentListResult>(ok, "agent_list").agents
    }

    private fun wordFor(a: Agent) = AttentionModel.section(a, { null }).first

    // ---- AC-04.1 -----------------------------------------------------------------------------------------------

    @Test fun homeEqualsAgentListByPaneSetAndStatusAfterTenRandomDrives() = runBlocking<Unit> {
        val h = host()
        val panes = listOf(base) + (1..2).map { env.split(base).also { p -> extraPanes += p } }
        // herdr accepts only these from a reporter; `done` is detection-only (Phase 00 finding), so AC-04.3 stands it in below.
        val states = listOf("idle", "working", "blocked", "unknown")
        val rnd = Random(20261001)
        val log = StringBuilder()
        repeat(10) { round ->
            val pane = panes[rnd.nextInt(panes.size)]
            val state = states[rnd.nextInt(states.size)]
            env.reportAgent(pane, state)
            // Converged: the home's pane set and words equal what herdr itself lists. Order is Paddock's own, so it is not compared.
            until("round $round ($pane -> $state) to converge") {
                val expected = agentList().associate { it.paneId to wordFor(it) }
                val rows = h.home.value?.rows.orEmpty()
                rows.associate { it.paneId to it.state } == expected && expected.isNotEmpty()
            }
            val rows = h.home.value!!.rows
            val order = h.home.value!!.sections.map { it.first }
            assertEquals(order.sortedBy { it.ordinal }, order, "sections follow the documented order")
            assertTrue(rows.all { r -> r.section == AttentionModel.section(agentList().first { it.paneId == r.paneId }, { null }).second })
            log.appendLine("round $round: $pane -> $state; home = ${rows.associate { it.paneId to it.state.word }}")
        }
        println("AC-04.1 ten drives, all converged:\n$log")
        h.stop()
    }

    // ---- AC-04.2 -----------------------------------------------------------------------------------------------

    @Test fun outputAdvancesWhenThePaneTypesWithoutATitleChangeAndPausesWhileScrolledUp() = runBlocking<Unit> {
        val h = host()
        env.reportAgent(base, "working")
        until("base is an agent") { h.home.value?.rows?.any { it.paneId == base } == true }
        val row = h.home.value!!.rows.first { it.paneId == base }
        val titleBefore = h.reconciler.installed.value!!.snapshot.panes.first { it.paneId == base }.terminalTitleStripped

        val feed = h.outputFeed(row.key.target.terminalId)
        feed.start(); feed.setVisible(true)
        fun text() = (feed.state.value as? OutputState.Showing)?.lines?.joinToString("\n") { it.text }.orEmpty()
        until("first read") { feed.state.value is OutputState.Showing }

        val marker1 = "paddock-marker-${System.nanoTime()}"
        env.runInPane(base, "echo $marker1")
        until("output to show the first marker", 8_000) { marker1 in text() }

        val titleAfter = h.reconciler.installed.value!!.snapshot.panes.first { it.paneId == base }.terminalTitleStripped
        assertEquals(titleBefore, titleAfter, "this scenario must not change the title, or Output is not what moved")

        // Scrolling up pauses: nothing is read while paused, and the text stays.
        feed.userScrolledUp()
        delay(300)
        val readsAtPause = feed.reads
        val marker2 = "paddock-marker-${System.nanoTime()}"
        env.runInPane(base, "echo $marker2")
        delay(2_500)
        assertEquals(readsAtPause, feed.reads, "no reads while the user is reading older lines")
        assertFalse(marker2 in text())

        // Tap to follow: reads resume at once and the newest line arrives.
        feed.resumeFollowing()
        until("output to catch up after resuming", 8_000) { marker2 in text() }
        feed.stop(); h.stop()
    }

    // ---- AC-04.3 -----------------------------------------------------------------------------------------------

    @Test fun aDoneTapIsLocalAndNothingFocusesAnAgentOnTheDesktop() = runBlocking<Unit> {
        // `done` is detection-only in herdr (it cannot be reported), so the read says done for the base pane while the exec
        // log, the ledger and the relay are all real.
        val h = host { snap ->
            fun done(status: io.github.tuthan.paddock.herdr.AgentStatus) = if (status == io.github.tuthan.paddock.herdr.AgentStatus.Working) io.github.tuthan.paddock.herdr.AgentStatus.Done else status
            snap.copy(agents = snap.agents.map { it.copy(agentStatus = done(it.agentStatus)) }, panes = snap.panes.map { it.copy(agentStatus = done(it.agentStatus)) })
        }
        env.reportAgent(base, "working")
        until("a Done row") { h.home.value?.rows?.any { it.paneId == base && it.state == StateWord.Done } == true }
        val row = h.home.value!!.rows.first { it.paneId == base }
        val before = env.session.commands.size

        h.markSeen(row)

        until("the row to read Ready locally") { h.home.value?.rows?.any { it.paneId == base && it.state == StateWord.Ready } == true }
        val during = env.session.commands.drop(before)
        assertTrue(during.none { argv -> "focus" in argv }, "no focus command of any kind was sent: $during")
        assertTrue(ledger.actions().any { it.kind == ActionKind.MarkSeen && it.terminalId == row.key.target.terminalId })
        // herdr itself still says working: the acknowledgement is the phone's and nothing was written back.
        assertEquals("working", agentList().first { it.paneId == base }.agentStatus.wire)
        h.stop()
    }

    // ---- ledger from real observations -------------------------------------------------------------------------

    @Test fun theLedgerRecordsConnectionAndStateChangesAndNeverATitleOrText() = runBlocking<Unit> {
        val h = host()
        env.reportAgent(base, "working")
        until("base is an agent") { h.home.value?.rows?.any { it.paneId == base } == true }
        env.reportAgent(base, "blocked")
        until("a blocked row") { h.home.value?.rows?.any { it.paneId == base && it.state == StateWord.Blocked } == true }
        until("the change in the ledger") { ledger.observations().any { it.kind == ObservationKind.StateChanged && it.detail == "working -> blocked" } }
        assertTrue(ledger.observations().any { it.kind == ObservationKind.Connected })
        assertTrue(ledger.observations().all { o -> o.detail.length <= 40 && "paddock" !in o.detail }, "details are state words only")
        h.stop()
    }
}
