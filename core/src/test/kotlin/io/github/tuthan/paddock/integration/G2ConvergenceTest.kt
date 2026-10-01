package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Reconciler
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.reconcile.Subscriptions
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * G2 against the disposable `paddock-test` session (AC-03.2, .3, .6, .7). Run with
 * `PADDOCK_TEST_SOCKET=<.../sessions/paddock-test/herdr.sock> ./gradlew :core:test --tests '*G2*'`.
 * Each scenario changes the real session, waits for the reconciler to go quiet, and requires the installed model to
 * equal a fresh `session.snapshot` read.
 */
class G2ConvergenceTest {
    private lateinit var env: PaddockTest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val foreground = MutableStateFlow(true)
    private val extraPanes = CopyOnWriteArrayList<String>()
    private lateinit var base: String

    @Before fun setUp() = runBlocking<Unit> { env = PaddockTest.orSkip(); base = env.basePane() }

    @After fun tearDown() { if (::env.isInitialized) runBlocking { runCatching { env.releaseAgent(base) }; extraPanes.forEach { runCatching { env.close(it) } } }; scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private fun monitor(): SessionMonitor {
        val rec = Reconciler(clock, HostProfileId("local"), env.sessionName, read = SessionMonitor.snapshotReader(env.relay))
        return SessionMonitor(scope, env.relay, rec, foreground, clock).also { it.start() }
    }

    private suspend fun live(m: SessionMonitor) = withTimeout(15_000) { m.freshness.first { it == Freshness.Live } }

    /** Waits until no read has started for [quietMs], then compares the installed model with a fresh read. */
    private suspend fun assertConverged(m: SessionMonitor, what: String, quietMs: Long = 600) {
        var last = -1L; var stableSince = System.currentTimeMillis()
        withTimeout(20_000) {
            while (true) {
                val r = m.reconciler.reads
                if (r != last) { last = r; stableSince = System.currentTimeMillis() } else if (System.currentTimeMillis() - stableSince >= quietMs) break
                delay(20)
            }
        }
        val fresh = env.snapshot()
        assertEquals(fresh, m.reconciler.installed.value!!.snapshot, "after: $what")
    }

    // ---- AC-03.2 -----------------------------------------------------------------------------------------------

    @Test fun lifecycleIsAcknowledgedSessionWideAndStatusPerPaneAndAPanelessStatusRequestIsRefused() = runBlocking<Unit> {
        val ack = CompletableDeferred<Unit>()
        val job = scope.launch { runCatching { env.relay.subscribe(Subscriptions.lifecycle(), onAcknowledged = { ack.complete(Unit) }).toList() } }
        withTimeout(10_000) { ack.await() }; job.cancel()

        val statusAck = CompletableDeferred<Unit>()
        val s = scope.launch { runCatching { env.relay.subscribe(Subscriptions.status(listOf(base)), onAcknowledged = { statusAck.complete(Unit) }).toList() } }
        withTimeout(10_000) { statusAck.await() }; s.cancel()

        val missing = listOf(buildJsonObject { put("type", "pane.agent_status_changed") })
        val e = assertFailsWith<HerdrError> { env.relay.subscribe(missing).toList() }
        assertEquals("invalid_request", e.code); assertTrue(e.message!!.contains("pane_id"), e.message)

        // One bad pane rejects the whole request, which is why StatusStreams retries with a fresh set.
        val bad = Subscriptions.status(listOf(base, "w99:p99"))
        println("G2 bad-pane request: " + runCatching { env.relay.subscribe(bad).toList() }.exceptionOrNull()?.message)
    }


    // ---- AC-03.3 -----------------------------------------------------------------------------------------------

    @Test fun theInstalledModelEqualsAFreshSnapshotAfterEveryKindOfChange() = runBlocking<Unit> {
        val m = monitor(); live(m)
        assertConverged(m, "baseline")

        val p2 = env.split(base).also { extraPanes += it }
        assertConverged(m, "new pane $p2")

        env.moveToNewTab(p2)
        assertConverged(m, "moved pane")

        env.reportAgent(base, "working")                          // first status sample: the pane becomes an agent
        assertConverged(m, "first status sample")
        withTimeout(10_000) { while (m.reconciler.installed.value!!.snapshot.agents.none { it.paneId == base }) delay(20) }
        env.reportAgent(base, "blocked")
        assertConverged(m, "status change")
        assertEquals(AgentStatus.Blocked, m.reconciler.installed.value!!.snapshot.panes.first { it.paneId == base }.agentStatus)

        env.close(p2); extraPanes.remove(p2)
        assertConverged(m, "closed pane")
        m.stop()
    }

    @Test fun aChangeMadeWhileAReadIsInFlightStillConverges() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>(); val inRead = CompletableDeferred<Unit>()
        var first = true
        val real = SessionMonitor.snapshotReader(env.relay)
        val rec = Reconciler(clock, HostProfileId("local"), env.sessionName, read = {
            val snap = real()                                   // taken before the change below...
            if (first) { first = false; inRead.complete(Unit); gate.await() }
            snap
        })
        val m = SessionMonitor(scope, env.relay, rec, foreground, clock).also { it.start() }
        withTimeout(15_000) { inRead.await() }
        val p2 = env.split(base).also { extraPanes += it }       // ...and this event lands while that read is held
        delay(500); gate.complete(Unit)
        live(m)
        assertConverged(m, "event during a read")
        assertTrue(m.reconciler.installed.value!!.snapshot.panes.any { it.paneId == p2 })
        m.stop()
    }

    @Test fun lostEventsAndAReconnectConvergeAfterOneReconciliation() = runBlocking<Unit> {
        val m = monitor(); live(m); assertConverged(m, "baseline")
        val epochBefore = m.reconciler.installed.value!!.epoch
        // Simulated events_lost: the relay streams are torn down by killing their processes, and the session
        // changes while nothing is listening.
        val relays = env.session.liveProcessesMatching("paddock-relay.py")
        assertTrue(relays.isNotEmpty(), "no live relay streams to drop")
        relays.forEach { it.destroyForcibly() }
        val p2 = env.split(base).also { extraPanes += it }
        withTimeout(15_000) { while (m.reconciler.installed.value!!.epoch == epochBefore || m.freshness.value != Freshness.Live) delay(20) }
        assertConverged(m, "reconnect")
        assertTrue(m.reconciler.installed.value!!.snapshot.panes.any { it.paneId == p2 }, "a pane created while streams were down must be found")
        assertTrue(m.reconciler.installed.value!!.epoch > epochBefore)
        m.stop()
    }

    /** Negative control: without invalidations the comparison above does fail, so a pass means something. */
    @Test fun withoutInvalidationsTheModelDoesNotConverge() = runBlocking<Unit> {
        val rec = Reconciler(clock, HostProfileId("local"), env.sessionName, read = SessionMonitor.snapshotReader(env.relay))
        val loop = scope.launch { rec.run() }
        rec.invalidate("once"); withTimeout(10_000) { rec.installed.first { it != null } }
        val p2 = env.split(base).also { extraPanes += it }
        delay(800)
        assertTrue(rec.installed.value!!.snapshot != env.snapshot(), "a changed session must differ from a stale install")
        loop.cancel()
    }

    // ---- AC-03.6 and AC-03.7 -----------------------------------------------------------------------------------

    @Test fun aTamperedRelayIsRefusedAndThePinnedOneIsAccepted() = runBlocking<Unit> {
        val script = File(System.getProperty("paddock.repoRoot"), "host/paddock-relay.py").readBytes()
        val pinned = sha256Hex(script)
        val home = java.nio.file.Files.createTempDirectory("paddock-relay-it").toFile()
        val dir = File(home, ".local/share/paddock").apply { mkdirs() }
        try {
            val installer = RelayInstaller(env.session, script, pinned)
            assertEquals(RelayState.Missing, installer.state(home.path))
            File(dir, "paddock-relay.py").writeBytes(script + "\n# tampered\n".toByteArray())
            assertIs<RelayState.Mismatch>(installer.state(home.path))
            assertFailsWith<io.github.tuthan.paddock.relay.RelayRefused> { installer.verifiedPath(home.path) }
            File(dir, "paddock-relay.py").writeBytes(script)
            assertEquals(File(dir, "paddock-relay.py").path, installer.verifiedPath(home.path))
        } finally { home.deleteRecursively() }
    }

    @Test fun freeTextReachesTheHostOnlyOnStdinNeverInArgv() = runBlocking<Unit> {
        val marker = "paddock-free-text-" + System.nanoTime()
        val text = "$marker \$(id) 'q' \"d\" ; rm -x"
        env.relay.call("pane.send_text", buildJsonObject { put("pane_id", base); put("text", text) })
        // The text is in the pane's input, and in no process's argument list at any moment we can see.
        val ps = ProcessBuilder("ps", "-eo", "args").start().inputStream.bufferedReader().readText()
        assertTrue(!ps.contains(marker), "free text appeared in a process argument list")
        assertTrue(env.session.commands.none { argv -> argv.any { marker in it } }, "free text appeared in an argv")
        env.relay.call("pane.send_keys", buildJsonObject { put("pane_id", base); put("keys", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("ctrl+u")))) })
    }
}
