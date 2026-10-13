package io.github.tuthan.paddock.demo

import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.cli.ReadSource
import io.github.tuthan.paddock.hostprofile.FileHostProfileStore
import io.github.tuthan.paddock.hostprofile.HostNameProbe
import io.github.tuthan.paddock.hostprofile.HostOs
import io.github.tuthan.paddock.hostprofile.HostOsProbe
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.live.FakeLease
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.output.AgentOutputReader
import io.github.tuthan.paddock.output.OutputRead
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.RelayClient
import io.github.tuthan.paddock.relay.sha256Hex
import io.github.tuthan.paddock.terminal.FrameDecoder
import io.github.tuthan.paddock.terminal.TerminalRecord
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import org.junit.Test

/**
 * The demo machine against the app's own code: the real bring-up and monitor run over a [DemoSession] and reach a live herd with every command
 * answered, so a step the app takes that the demo does not know fails here and not on a reviewer's phone (Phase 16, spike S1 as a contract test).
 */
class DemoSessionTest {
    private val now = AtomicLong(1_000_000L)
    private val clock = Clock { now.get() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val script = "print('relay')\n".toByteArray()
    private val sha = sha256Hex(script)
    private val cli = HerdrCli(Demo.HERDR_PATH, Demo.SESSION)

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun session() = DemoSession(DemoHerd(clock), sha, clock)

    private suspend fun monitored(session: DemoSession): Pair<HostSessionController, MonitoredHost> {
        val c = HostSessionController(
            scope, Demo.profile, { FakeLease(Connection.Connected(session, 1)) }, Ledger(InMemoryLedgerStore()) { now.get() }, clock, MutableStateFlow(true), script, sha,
            journal = OperationJournal(InMemoryJournalStore(), clock),
            osProbe = { HostOsProbe.read(it) }, nameProbe = { HostNameProbe.read(it) },
        ).also { it.start() }
        until("monitoring") { c.phase.value is HostPhase.Monitoring }
        val host = (c.phase.value as HostPhase.Monitoring).host
        until("the first read") { host.home.value != null }
        return c to host
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(8_000) { while (!cond()) delay(10) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private fun MonitoredHost.row(name: String) = home.value?.rows?.firstOrNull { it.context.startsWith(name) }

    private fun MonitoredHost.state(name: String): StateWord? = row(name)?.state

    @Test fun theRealBringUpReachesAHerdOfFourAndEveryCommandItSentWasAnswered() = runBlocking<Unit> {
        val session = session()
        val (c, host) = monitored(session)
        val rows = host.home.value!!.rows
        assertEquals(setOf("Refactor order validation", "Invoice status migration", "Write the install guide", "Keep the test suite green"), rows.map { it.title }.toSet())
        assertEquals(StateWord.Working, host.state("refactor"))
        assertEquals(StateWord.Done, host.state("migrate"))
        assertEquals(StateWord.Ready, host.state("docs"))
        assertEquals(StateWord.Ready, host.state("tests"))
        assertEquals(HostOs.Linux, c.os.also { until("os") { it.value != null } }.value)
        until("hostname") { c.hostname.value != null }
        assertEquals("demo", c.hostname.value)
        assertTrue(session.unanswered.isEmpty(), "commands the demo did not know: ${session.unanswered}")
        c.stop()
    }

    @Test fun theScriptMovesTheHerdOnTheClockAndAStatusEventCarriesIt() = runBlocking<Unit> {
        val session = session()
        val (c, host) = monitored(session)
        now.addAndGet(DemoHerd.BLOCK_AT + 1_000)
        until("docs blocked") { host.state("docs") == StateWord.Blocked }
        assertEquals(StateWord.Working, host.state("refactor"))
        now.addAndGet(DemoHerd.FINISH_AT)
        until("refactor done") { host.state("refactor") == StateWord.Done }
        assertTrue(session.unanswered.isEmpty(), "commands the demo did not know: ${session.unanswered}")
        c.stop()
    }

    @Test fun aPromptIsAcknowledgedAndTheAgentWorksAndThenFinishes() = runBlocking<Unit> {
        val session = session()
        val (c, host) = monitored(session)
        val key = host.row("tests")!!.key
        val result = host.operations!!.prompt(key, "Add a test for the empty basket")
        assertIs<OperationResult.Acknowledged<*>>(result)
        until("tests working") { host.state("tests") == StateWord.Working }
        val working = (AgentOutputReader(session, cli).read("w2:p2") as OutputRead.Text).text
        assertTrue("Add a test for the empty basket" in working && "Working" in working && "nothing ran" !in working, working)
        now.addAndGet(DemoHerd.PROMPT_WORK + 1_000)
        until("tests done") { host.state("tests") == StateWord.Done }
        assertTrue("nothing ran" in (AgentOutputReader(session, cli).read("w2:p2") as OutputRead.Text).text)
        c.stop()
    }

    @Test fun aBlockedAgentCannotBePromptedAndSaysSo() = runBlocking<Unit> {
        val session = session()
        val (c, host) = monitored(session)
        now.addAndGet(DemoHerd.BLOCK_AT + 1_000)
        until("docs blocked") { host.state("docs") == StateWord.Blocked }
        val key = host.row("docs")!!.key
        // The readiness check refuses before anything is sent; the same rule as for any real agent.
        assertTrue(host.operations!!.prompt(key, "go on") !is OperationResult.Acknowledged<*>)
        c.stop()
    }

    @Test fun outputIsReadThroughTheCliAndABlockedAgentsDetectionTextIsItsQuestion() = runBlocking<Unit> {
        val session = session()
        val recent = (AgentOutputReader(session, cli).read("w1:p1") as OutputRead.Text).text
        assertTrue("validateOrder" in recent && "\u001b[32m" in recent, recent)
        now.addAndGet(DemoHerd.BLOCK_AT + 1_000)
        val detected = (AgentOutputReader(session, cli, lines = 12, source = ReadSource.Detection).read("w2:p1") as OutputRead.Text).text
        assertTrue("Allow Codex to run" in detected, detected)
        assertEquals(OutputRead.AgentMissing, AgentOutputReader(session, cli).read("w9:p9"))
    }

    @Test fun theTerminalObservesOneFullFrameOfTheRequestedSize() = runBlocking<Unit> {
        val session = session()
        val channel = session.openStream(cli.terminalObserve("w1:p1", 80, 24))
        try {
            val line = withTimeout(5_000) { channel.stdout.first() }.toString(Charsets.UTF_8).trim()
            val frame = assertIs<TerminalRecord.Frame>(FrameDecoder.decode(line))
            assertTrue(frame.full)
            assertEquals(80 to 24, frame.width to frame.height)
            assertTrue("validateOrder" in frame.bytes.toString(Charsets.UTF_8))
        } finally { channel.close() }
    }

    @Test fun thePaneSizeIsKnownSoControlIsAskedForWithoutAResizeQuestion() = runBlocking<Unit> {
        val session = session()
        assertEquals(io.github.tuthan.paddock.terminal.PtySize(DemoHerd.COLS, DemoHerd.ROWS), io.github.tuthan.paddock.terminal.PtyProbe.probe(session, cli, "w1:p1"))
        assertTrue(session.unanswered.isEmpty(), session.unanswered.toString())
    }

    @Test fun whatTheDemoDoesNotDoIsRefusedByNameAndAnythingUnknownIsRefusedAndRemembered() = runBlocking<Unit> {
        val session = session()
        for (argv in listOf(listOf(Demo.HERDR_PATH, "session", "stop", "demo", "--json"), listOf(Demo.HERDR_PATH, "session", "delete", "demo", "--json"), cli.agentStart("fixer", "claude", "w1:p1", 20_000))) {
            val failure = assertIs<CliOutcome.Failure>(CliResult.classify(session.exec(argv)), argv.toString())
            assertEquals(DemoCopy.REFUSAL_CODE, failure.code)
        }
        val relay = RelayClient(session, Demo.RELAY_PATH, Demo.SOCKET_PATH)
        val refused = assertFailsWith<HerdrError> { relay.call("agent.send_keys") }
        assertEquals(DemoCopy.REFUSAL_CODE, refused.code)
        assertTrue(session.unanswered.isEmpty())
        assertEquals(127, session.exec(listOf("python3", "/home/demo/paddock-control.py")).exit)
        assertEquals(1, session.exec(listOf("sha256sum", "--", "/home/demo/.local/share/paddock/paddock-control.py")).exit)
        assertEquals(listOf(listOf("python3", "/home/demo/paddock-control.py")), session.unanswered)
    }

    @Test fun theDemoSourceHasNoNetworkAndNoFileAccess() {
        val dir = File(System.getProperty("paddock.repoRoot"), "core/src/main/kotlin/io/github/tuthan/paddock/demo")
        val files = dir.listFiles { f -> f.extension == "kt" }!!.toList()
        assertTrue(files.size >= 4, files.toString())
        for (f in files) {
            val imports = f.readLines().filter { it.startsWith("import ") }
            assertTrue(imports.none { it.startsWith("import java.net") || it.startsWith("import java.io.File") || it.startsWith("import java.nio") || it.startsWith("import android.") || ".ssh." in it }, "${f.name}: $imports")
            assertTrue("Socket(" !in f.readText() && "File(" !in f.readText(), "${f.name} opens a socket or a file")
        }
    }

    @Test fun theDemoProfileLivesInMemoryAndANewProcessStartsWithNoMachine() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("paddock-demo-store").toFile()
        try {
            val file = File(dir, "host-profiles.json")
            val mode = DemoMode(clock)
            val store = DemoProfileStore(FileHostProfileStore(file), mode)
            assertTrue(store.list().isEmpty())
            store.put(Demo.profile)
            assertTrue(mode.active)
            assertEquals(listOf(Demo.PROFILE_ID), store.list().map { it.id })
            assertEquals(Demo.profile, store.get(Demo.PROFILE_ID))
            val named = store.update(Demo.PROFILE_ID) { it.copy(detectedName = "demo") }
            assertEquals("demo", named?.detectedName)
            assertEquals("demo", store.get(Demo.PROFILE_ID)?.detectedName)
            assertTrue(!file.exists(), "the demo's profile is never written")
            // A restart: a new store over the same file, a new mode.
            assertTrue(DemoProfileStore(FileHostProfileStore(file), DemoMode(clock)).list().isEmpty())
            store.remove(Demo.PROFILE_ID)
            assertTrue(!mode.active && store.list().isEmpty() && store.get(Demo.PROFILE_ID) == null)
        } finally { dir.deleteRecursively() }
    }

    @Test fun addingARealMachineEndsTheDemoAndIsTheOnlyThingWritten() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("paddock-demo-store").toFile()
        try {
            val file = File(dir, "host-profiles.json")
            val mode = DemoMode(clock)
            val store = DemoProfileStore(FileHostProfileStore(file), mode)
            store.put(Demo.profile)
            val real = HostProfile("devbox", "devbox", "192.168.42.10", 22, "jdoe")
            store.put(real)
            assertTrue(!mode.active)
            assertEquals(listOf("devbox"), store.list().map { it.id })
            assertNotNull(FileHostProfileStore(file).get("devbox"))
            assertTrue(!file.readText().contains(Demo.HOST))
        } finally { dir.deleteRecursively() }
    }
}
