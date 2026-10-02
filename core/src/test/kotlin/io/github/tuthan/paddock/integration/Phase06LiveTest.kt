package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityPresenter
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.ManualInputRules
import io.github.tuthan.paddock.ops.NotReadyReason
import io.github.tuthan.paddock.ops.Operation
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.herdr.AgentInfoResult
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.reconcile.Freshness
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 06 against the disposable `paddock-test` session: the prompt, Esc, Ctrl+C and focus operations through the
 * real relay and the real herdr, with `tools/fake-agent.py` running as `claude` in a pane this test creates (herdr
 * accepts `agent prompt` only for a pane whose foreground process is a known agent). The fake agent logs every
 * submission outside the pane, so "exactly once" is a count, not a reading of a screen. AC-06.1, .2 (live half), .3
 * (JVM tier), .4, .5, .6 and .8. Run with `PADDOCK_TEST_SOCKET=<.../sessions/paddock-test/herdr.sock>`.
 */
class Phase06LiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: PaddockTest
    private lateinit var pane: String
    private lateinit var log: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val foreground = MutableStateFlow(true)
    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    private val store = InMemoryJournalStore()
    private val journal = OperationJournal(store, clock)
    private val profile = HostProfile("local", "Local", "127.0.0.1", 22, "tester")
    private val started = CopyOnWriteArrayList<MonitoredHost>()

    @Before fun setUp() = runBlocking<Unit> {
        env = PaddockTest.orSkip()
        pane = env.split(env.basePane())
        log = File(tmp.root, "agent.log")
        val bin = File(tmp.root, "bin").also { it.mkdirs() }
        Files.createSymbolicLink(File(bin, "claude").toPath(), File(System.getProperty("paddock.repoRoot"), "tools/fake-agent.py").toPath())
        env.runInPane(pane, "FAKE_AGENT_LOG=${log.absolutePath} ${bin.absolutePath}/claude")
        until("the fake agent to start") { "fake agent ready" in env.paneText(pane) }
        env.reportAgent(pane, "idle", agent = "claude")
    }

    @After fun tearDown() {
        if (!::env.isInitialized) return
        runBlocking {
            started.forEach { runCatching { it.stop() } }
            runCatching { env.sendKeys(pane, "ctrl+c") }
            runCatching { env.releaseAgent(pane, "claude") }
            runCatching { env.close(pane) }
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun until(what: String, ms: Long = 10_000, cond: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!cond()) delay(25) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private suspend fun host(session: SshSession = env.session): MonitoredHost =
        MonitoredHost(scope, profile, env.sessionName, session, env.relayScript, env.socket, ledger, clock, foreground, env.herdr, journal = journal)
            .also { started += it; it.start(); withTimeout(15_000) { it.freshness.first { f -> f == Freshness.Live } }
                until("the agent pane in the home") { it.home.value?.rows?.any { r -> r.paneId == pane } == true } }

    private fun MonitoredHost.keyOfPane(): TerminalKey = home.value!!.rows.first { it.paneId == pane }.key

    private fun events(kind: String): List<kotlinx.serialization.json.JsonObject> =
        if (!log.exists()) emptyList() else log.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.filter { it["event"]!!.jsonPrimitive.content == kind }

    private fun submissions() = events("submit").map { it["text"]!!.jsonPrimitive.content }
    private fun promptRequests() = env.session.stdinLog.count { "\"agent.prompt\"" in it }

    // ---- AC-06.1 -----------------------------------------------------------------------------------------------

    @Test fun aPromptLandsAsOneSubmissionWithItsLineBreakInsideAndExactlyOnce() = runBlocking<Unit> {
        val h = host()
        val text = "alpha paddock ${System.nanoTime()}\nbeta paddock"
        val r = h.operations!!.prompt(h.keyOfPane(), text)
        assertIs<OperationResult.Acknowledged<*>>(r)
        until("the submission") { submissions().isNotEmpty() }
        delay(1_500)                                                           // a late duplicate would show by now
        assertEquals(listOf(text), submissions(), "one submission carrying the whole text, line break inside")
        val screen = env.paneText(pane)
        assertEquals(1, Regex("GOT \\d+:").findAll(screen).count(), "the pane shows the submission once:\n$screen")
        assertTrue("GOT 1:" in screen && "beta paddock" in screen)
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertEquals(OperationJournal.sha256Hex(text), row.payloadSha256)
        assertEquals(null, row.promptText, "the journal keeps a hash, not the text")
        println("AC-06.1: 1 submission, ${text.length} chars, journal row ${row.id} acknowledged")
    }

    // ---- AC-06.2: the live half ------------------------------------------------------------------------------------

    @Test fun everyStateThatIsNotReadyRefusesAndNothingLands() = runBlocking<Unit> {
        val h = host()
        val ops = h.operations!!
        val key = h.keyOfPane()
        for ((state, reason) in listOf("working" to NotReadyReason.Working, "blocked" to NotReadyReason.Blocked, "unknown" to NotReadyReason.StatusUnknown)) {
            env.reportAgent(pane, state, agent = "claude")
            val r = ops.prompt(key, "must not land while $state")
            assertEquals(reason.code, assertIs<OperationResult.NotSent>(r).reason, "state $state")
        }
        assertEquals(0, promptRequests(), "no prompt request reached herdr in any not-ready state")
        assertTrue(submissions().isEmpty())
        env.reportAgent(pane, "idle", agent = "claude")
        assertIs<OperationResult.Acknowledged<*>>(ops.prompt(key, "now it may land"))
        until("the one submission") { submissions().size == 1 }
        assertEquals(1, promptRequests())
    }

    @Test fun anAgentThatBlockedBetweenTheReadAndTheSendIsHerdrsRefusalAndNeverRetried() = runBlocking<Unit> {
        val h = host()
        val ops = h.operations!!
        val key = h.keyOfPane()
        env.reportAgent(pane, "blocked", agent = "claude")
        // The phone's own check would catch this, so the send runs without it: the state a read missed, as herdr sees it.
        val r = Operation.run(journal, key, OperationKind.Prompt, "must not land", resolveTarget = { ops.resolve(key) }, send = { p, before ->
            env.relay.call("agent.prompt", buildJsonObject { put("target", p); put("text", "must not land") }, beforeWrite = before).decode<AgentInfoResult>("agent_prompted").agent
        })
        val rej = assertIs<OperationResult.Rejected>(r)
        assertEquals("agent_blocked", rej.code)
        assertTrue("blocked" in rej.message, rej.message)
        delay(800)
        assertTrue(submissions().isEmpty(), "herdr sent no input")
        assertEquals(OperationOutcome.Rejected, journal.records.value.single().outcome)
        assertEquals(1, promptRequests(), "asked once, never retried")
        println("AC-06.2 live: herdr says ${rej.code}: ${rej.message}")
    }

    // ---- AC-06.3 on the JVM tier ------------------------------------------------------------------------------------

    private class CuttingSession(private val inner: SshSession) : SshSession by inner {
        val armed = AtomicBoolean(false)
        val cuts = AtomicBoolean(false)
        override suspend fun openStream(argv: List<String>): StreamChannel {
            val ch = inner.openStream(argv)
            return object : StreamChannel by ch {
                override suspend fun write(bytes: ByteArray) {
                    ch.write(bytes)
                    if (bytes.toString(Charsets.UTF_8).contains("\"agent.prompt\"") && armed.compareAndSet(true, false)) { cuts.set(true); ch.close() }
                }
            }
        }
    }

    @Test fun aLinkCutRightAfterTheWriteIsUnknownWithNoSecondWriteAndTheTerminalWaitsForAReRead() = runBlocking<Unit> {
        val cutting = CuttingSession(env.session)
        val h = host(cutting)
        val ops = h.operations!!
        val key = h.keyOfPane()
        cutting.armed.set(true)
        val r = ops.prompt(key, "sent into a dying link")
        val unknown = assertIs<OperationResult.Unknown>(r)
        assertTrue(cutting.cuts.get())
        assertEquals(OperationOutcome.Unknown, store.load().records.single().outcome)
        delay(1_500)
        val landed = submissions().size
        assertTrue(landed <= 1, "at most the original write reached the pane: $landed")
        assertIs<OperationResult.NeedsReread>(ops.prompt(key, "sent into a dying link"))
        assertEquals(1, promptRequests(), "nothing was written a second time")
        // The user re-reads, and only then can they decide what to do.
        val report = ops.rereadAll(key)
        assertEquals(listOf(unknown.record.id), report.resolved.map { it.id }, "one read frees the one waiting row")
        assertTrue(journal.records.value.first { it.id == unknown.record.id }.let { it.outcome == OperationOutcome.Unknown && it.resolvedAt != null }, "it stays unknown, re-read")
        assertIs<OperationResult.Acknowledged<*>>(ops.prompt(key, "a deliberate second prompt"))
        until("the second prompt") { submissions().size == landed + 1 }
        println("AC-06.3 (JVM tier): unknown after the cut; the first prompt ${if (landed == 1) "had reached" else "had not reached"} the pane; no automatic second write")
    }

    // ---- AC-06.4 -----------------------------------------------------------------------------------------------

    @Test fun fiveTapsAtOnceWriteOnceAndTheRestAreRefusedWithTheFirstsStatus() = runBlocking<Unit> {
        val h = host()
        val ops = h.operations!!
        val key = h.keyOfPane()
        val results = (1..5).map { async(Dispatchers.Default) { ops.prompt(key, "tap tap tap") } }.awaitAll()
        val acked = results.count { it is OperationResult.Acknowledged<*> }
        val busy = results.filterIsInstance<OperationResult.Busy>()
        assertEquals(1, acked)
        assertEquals(4, busy.size, "the others were refused: $results")
        assertTrue(busy.all { it.first.kind == OperationKind.Prompt && it.first.inFlight })
        until("the submission") { submissions().isNotEmpty() }
        delay(1_500)
        assertEquals(1, submissions().size, "exactly one write reached the pane")
        assertEquals(1, promptRequests())
        assertEquals(1, journal.records.value.size)
    }

    // ---- AC-06.5 ---------------------------------------------------------------------------------------------------

    @Test fun escAndCtrlCReachTheAgentAsKeysEachOneJournaled() = runBlocking<Unit> {
        val h = host()
        val ops = h.operations!!
        val key = h.keyOfPane()
        env.reportAgent(pane, "blocked", agent = "claude")                       // manual input is for exactly this state
        assertIs<OperationResult.Acknowledged<*>>(ops.sendKey(key, OperationKind.Esc))
        until("the Esc") { events("esc").size == 1 }
        assertIs<OperationResult.Acknowledged<*>>(ops.sendKey(key, OperationKind.CtrlC))
        until("the Ctrl+C") { events("ctrl-c").size == 1 }
        assertEquals(listOf(OperationKind.Esc, OperationKind.CtrlC), journal.records.value.map { it.kind })
        assertTrue(journal.records.value.all { it.outcome == OperationOutcome.Acknowledged })
        assertTrue(submissions().isEmpty(), "a key is not a submission")
    }

    @Test fun manualInputOpensTheKeysOnlyOnAReadMadeAfterEnteringAndADoubleTapIsOneWrite() = runBlocking<Unit> {
        val h = host()
        val sends = h.sends!!
        val key = h.keyOfPane()
        val terminal = key.target.terminalId
        env.reportAgent(pane, "working", agent = "claude")                       // a working agent takes keys: a key says nothing about readiness
        until("the working state") { h.home.value?.rows?.firstOrNull { it.paneId == pane }?.state == StateWord.Working }
        delay(200)
        val mode = ManualInputMode()
        mode.enter(terminal, clock.nowMillis(), h.reconciler.installed.value!!.epoch)

        fun gate(): OperationGate {
            val session = mode.current.value!!
            val installed = h.reconciler.installed.value
            val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminal }
            return ManualInputRules.gate(agent, installed?.readAtMillis, session.enteredAtMillis, h.freshness.value == Freshness.Live, h.operationRecords.value,
                TerminalKey(key.target, session.epoch), currentEpoch = installed?.epoch)
        }
        assertEquals(SendBlock.Reading, assertIs<OperationGate.Closed>(gate()).block, "the read from before entering is not the fresh read")
        h.refresh()
        until("a read made after entering") { gate() == OperationGate.Open }

        val sessionKey = TerminalKey(key.target, mode.current.value!!.epoch)
        sends.sendKey(sessionKey, OperationKind.Esc)
        sends.sendKey(sessionKey, OperationKind.Esc)                             // the second tap of a double tap: refused by the controller
        until("the Esc outcome") { sends.outcomes.value[terminal]?.result is OperationResult.Acknowledged<*> }
        assertEquals(OperationKind.Esc, sends.outcomes.value[terminal]!!.kind)
        until("the Esc") { events("esc").size == 1 }
        delay(1_000)
        assertEquals(1, events("esc").size, "a double tap is one write")
        assertEquals(listOf(OperationKind.Esc), journal.records.value.map { it.kind })
        assertEquals(OperationGate.Open, gate(), "an acknowledged key does not hold the terminal")

        sends.sendKey(sessionKey, OperationKind.CtrlC)
        until("the Ctrl+C outcome") { sends.outcomes.value[terminal]?.let { it.kind == OperationKind.CtrlC && it.result is OperationResult.Acknowledged<*> } == true }
        until("the Ctrl+C") { events("ctrl-c").size == 1 }
        assertEquals(listOf(OperationKind.Esc, OperationKind.CtrlC), journal.records.value.map { it.kind })
        assertTrue(submissions().isEmpty(), "keys are not submissions")
    }

    // ---- AC-06.6 (the herdr half) --------------------------------------------------------------------------------------

    @Test fun focusMovesTheDesktopFocusToTheAgentAndIsJournaled() = runBlocking<Unit> {
        val h = host()
        val key = h.keyOfPane()
        val other = env.basePane()
        env.session.exec(listOf(env.herdr, "--session", env.sessionName, "agent", "focus", other))     // somewhere else first
        val r = h.operations!!.focus(key)
        val focused = assertIs<OperationResult.Acknowledged<io.github.tuthan.paddock.herdr.Agent>>(r).value
        assertTrue(focused.focused)
        assertEquals(OperationKind.Focus, journal.records.value.single().kind)
    }

    @Test fun aFocusFromThePhoneAppearsInActivityAsAPhoneActionAndNeverShowsATextItWasNotGiven() = runBlocking<Unit> {
        val h = host()
        val key = h.keyOfPane()
        val title = h.home.value!!.rows.first { it.paneId == pane }.title
        assertIs<OperationResult.Acknowledged<*>>(h.operations!!.focus(key))
        val items = Activity.build(emptyList(), emptyList(), ActivityFilter.PhoneActions, journal.records.value)
        val rows = ActivityPresenter(titleOf = { _, _, t -> if (t == key.target.terminalId) title else null }).present(items, clock.nowMillis()).flatMap { it.rows }
        val row = rows.single()
        assertEquals("You focused $title on the desktop", row.text)
        assertTrue(row.detail!!.startsWith("The desktop now has this agent focused"), row.detail)
        assertEquals(1, journal.records.value.count { it.kind == OperationKind.Focus })
        assertTrue(Activity.build(emptyList(), emptyList(), ActivityFilter.Connection, journal.records.value).isEmpty(), "not a connection event")
    }

    // ---- AC-06.8 ---------------------------------------------------------------------------------------------------

    @Test fun freeTextNeverAppearsInArgvOfAnyProcessWhileThePromptIsSent() = runBlocking<Unit> {
        val h = host()
        val marker = "paddock-secret-${System.nanoTime()}"
        val control = "paddock-control-${System.nanoTime()}"
        val seen = CopyOnWriteArrayList<String>()
        val stop = AtomicBoolean(false)
        val sampler = Thread {
            while (!stop.get()) {
                runCatching { ProcessBuilder("ps", "-eo", "args=").redirectErrorStream(true).start().let { p -> val out = p.inputStream.bufferedReader().readText(); p.waitFor(); seen += out } }
            }
        }.also { it.isDaemon = true; it.start() }
        // Positive control: the sampler can see a marker that is in argv, so its silence about the prompt means something.
        val sleeper = ProcessBuilder("python3", "-c", "import time; time.sleep(3)", control).start()      // keeps the marker in its own argv
        delay(300)
        val key = h.keyOfPane()
        val r = h.operations!!.prompt(key, "please remember $marker")
        until("the submission") { submissions().isNotEmpty() }
        delay(300)
        stop.set(true); sampler.join(5_000); sleeper.destroy()
        assertIs<OperationResult.Acknowledged<*>>(r)
        assertTrue(seen.size > 20, "the sampler ran ${seen.size} times")
        assertTrue(seen.any { control in it }, "the sampler can see a process argument")
        assertFalse(seen.any { marker in it }, "the prompt text appeared in a process listing")
        assertTrue(env.session.commands.none { argv -> argv.any { marker in it } }, "the prompt text appeared in a command's argv")
        assertEquals(1, env.session.stdinLog.count { marker in it }, "it travelled once, on the relay's stdin")
        assertTrue(submissions().single().contains(marker))
        println("AC-06.8: ${seen.size} ps samples, none showed the text; positive control seen; text on stdin once")
    }
}
