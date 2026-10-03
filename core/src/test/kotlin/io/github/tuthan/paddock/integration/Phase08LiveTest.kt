package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.answers.AnswerCodes
import io.github.tuthan.paddock.answers.AnswerController
import io.github.tuthan.paddock.answers.AnswerHost
import io.github.tuthan.paddock.answers.Answerability
import io.github.tuthan.paddock.answers.Behavior
import io.github.tuthan.paddock.answers.NotAnswerable
import io.github.tuthan.paddock.answers.RequestOutcome
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Phase 08's phone side against the real scripts and the real herdr: [AnswerHost] and [AnswerController] run `paddock-decide.py`
 * (installed into a throwaway HOME by the host's own `install()`) while the real `paddock-claude-permission-hook.py` waits in a pane
 * of the disposable `paddock-test` session whose status the test drives with `pane report-agent`. Nothing is sent to any pane.
 * Run with `PADDOCK_TEST_SOCKET=<.../sessions/paddock-test/herdr.sock>`.
 */
class Phase08LiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var env: PaddockTest
    private lateinit var pane: String
    private lateinit var key: TerminalKey
    private lateinit var local: LocalProcessSession
    private lateinit var hostApi: AnswerHost
    private lateinit var runtime: File
    private lateinit var config: File
    private val hooks = mutableListOf<Process>()
    /** How many commands and stdin writes the install itself made: the scripts' own text is on those, and is not what the phone ran. */
    private var afterInstall = 0 to 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val journal = OperationJournal(InMemoryJournalStore(), clock)
    private val repo = File(System.getProperty("paddock.repoRoot"))
    private val hookScript = File(repo, "host/paddock-claude-permission-hook.py")
    private val decideScript = File(repo, "host/paddock-decide.py")
    private val claudeSession = "live-claude-${System.nanoTime()}"

    @Before fun setUp() = runBlocking<Unit> {
        env = PaddockTest.orSkip()
        pane = env.split(env.basePane())
        val home = File(tmp.root, "home").also { it.mkdirs() }
        runtime = File(tmp.root, "run").also { it.mkdirs(); java.nio.file.Files.setPosixFilePermissions(it.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        config = File(tmp.root, "cfg/paddock").also { it.mkdirs() }
        val toml = File(config, "hook.toml")
        toml.writeText("window_seconds = 20\n")
        java.nio.file.Files.setPosixFilePermissions(toml.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        local = LocalProcessSession(mapOf("HOME" to home.absolutePath, "XDG_RUNTIME_DIR" to runtime.absolutePath))
        hostApi = AnswerHost(
            local, RelayInstaller(local, decideScript.readBytes(), sha256Hex(decideScript.readBytes()), fileName = "paddock-decide.py"),
            RelayInstaller(local, hookScript.readBytes(), sha256Hex(hookScript.readBytes()), fileName = "paddock-claude-permission-hook.py"), clock, env.sessionName, "live test",
        )
        hostApi.install()
        afterInstall = local.commands.size to local.stdinLog.size
        val snap = env.snapshot()
        key = TerminalKey(TargetRef(HostProfileId("local"), env.sessionName, snap.panes.first { it.paneId == pane }.terminalId), 1)
        env.reportAgent(pane, "blocked", agent = "claude")
    }

    @After fun tearDown() {
        if (!::env.isInitialized) return
        hooks.forEach { runCatching { it.destroyForcibly() } }
        runBlocking { runCatching { env.releaseAgent(pane, "claude") }; runCatching { env.close(pane) } }
        scope.coroutineContext[Job]?.cancel()
    }

    private var lastView: () -> String = { "" }

    private suspend fun until(what: String, ms: Long = 10_000, cond: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!cond()) delay(25) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what ${lastView()}") }
    }

    private fun startHook(input: String = """{"command":"touch /tmp/paddock-live-marker","description":"live"}""", toolName: String = "Bash"): Process {
        val payload = buildJsonObject {
            put("session_id", claudeSession); put("cwd", "/tmp"); put("permission_mode", "default"); put("hook_event_name", "PermissionRequest"); put("tool_name", toolName)
            put("tool_input", Json.parseToJsonElement(input))
        }.toString()
        val p = ProcessBuilder("python3", hookScript.absolutePath).also {
            it.environment().putAll(mapOf(
                "HERDR_ENV" to "1", "HERDR_PANE_ID" to pane, "HERDR_SESSION" to env.sessionName, "HERDR_SOCKET_PATH" to env.socket,
                "XDG_RUNTIME_DIR" to runtime.absolutePath, "XDG_CONFIG_HOME" to File(config, "..").canonicalPath,
            ))
            it.redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        p.outputStream.use { it.write(payload.toByteArray()) }
        hooks += p
        return p
    }

    private fun Process.stdoutText(): String = inputStream.readBytes().toString(Charsets.UTF_8)

    private fun controller(status: () -> AgentStatus? = { AgentStatus.Blocked }): AnswerController {
        val installedFn: () -> Installed? = { runBlocking { Installed(env.snapshot(), System.currentTimeMillis(), 1) } }
        return AnswerController(scope, hostApi, journal, installedFn, { status() }, clock, pollMillis = 200, settlingPollMillis = 100)
    }

    private fun AnswerController.view() = views.value[key.target.terminalId]

    /** The sheet opening on a blocked agent: the controller watches it and polls until the hook's request appears. */
    private suspend fun AnswerController.opened(): AnswerController {
        watch(key)
        lastView = { "view=${view()?.copy(listing = null)} requests=${view()?.listing?.requests} files=${runtime.walkTopDown().filter { it.isFile }.map { it.relativeTo(runtime).path }.toList()}" }
        until("the request on the sheet") { view()?.shown != null }
        return this
    }

    private fun answerNow(c: AnswerController, b: Behavior) = runBlocking {
        c.answer(key, b, c.view()!!.shown!!.requestId)
        until("the answer to finish") { c.view()?.result != null && key.target.terminalId !in c.running.value }
        c.view()!!.result!!
    }

    @Test fun aYesThroughTheKotlinSideIsConsumedByTheRealHookAndPrintedAsClaudeCodesDecision() = runBlocking<Unit> {
        val hook = startHook()
        val c = controller().opened()
        val shown = c.view()!!.shown!!
        assertEquals(listOf("Bash", claudeSession, pane, env.sessionName), listOf(shown.toolName, shown.claudeSessionId, shown.paneId, shown.herdrSession))
        assertTrue("touch /tmp/paddock-live-marker" in shown.toolInputText && "description:" in shown.toolInputText)
        assertIs<Answerability.Yes>(c.view()!!.answerability)
        val result = answerNow(c, Behavior.Allow)
        assertIs<OperationResult.Acknowledged<*>>(result.result)
        assertTrue(hook.waitFor(10, TimeUnit.SECONDS), "the hook ends once it has the decision")
        assertEquals(0, hook.exitValue())
        assertEquals("""{"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}}}""", hook.stdoutText())
        until("the request to be consumed") { c.refresh(key); c.view()?.outcome is RequestOutcome.Consumed }
        assertEquals(RequestOutcome.Consumed(Behavior.Allow), c.view()!!.outcome)
        val row = journal.records.value.single()
        assertEquals(listOf(OperationKind.Allow, OperationOutcome.Acknowledged, shown.requestId, pane), listOf(row.kind, row.outcome, row.requestId, row.paneIdAtSend))
        // AC-08.3 live: nothing in any command this phone ran named herdr, a key or an agent send
        val all = local.commands.drop(afterInstall.first).joinToString("\n") { it.joinToString(" ") } + "\n" + local.stdinLog.drop(afterInstall.second).joinToString("\n")
        for (banned in listOf("herdr", "send-keys", "send_keys", "agent.prompt", "agent.send")) assertFalse(banned in all, "$banned in what the phone ran")
        println("AC-08.1 live: Yes bound to ${shown.requestId.take(8)}, consumed, hook printed allow")
    }

    @Test fun aNoIsAnsweredTheSameWayAndPrintsADeny() = runBlocking<Unit> {
        val hook = startHook()
        val c = controller().opened()
        assertIs<OperationResult.Acknowledged<*>>(answerNow(c, Behavior.Deny).result)
        assertTrue(hook.waitFor(10, TimeUnit.SECONDS))
        assertEquals("""{"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "deny"}}}""", hook.stdoutText())
    }

    @Test fun theDesktopAnsweringFirstIsNoticedFromHerdrAndTheSheetSaysLost() = runBlocking<Unit> {
        val hook = startHook()
        val c = controller().opened()
        // herdr has shown the pane blocked while the hook waited; now the agent moves on (the desktop answered)
        delay(1_000)
        env.reportAgent(pane, "working", agent = "claude")
        assertTrue(hook.waitFor(8, TimeUnit.SECONDS), "the hook notices within a few polls and ends")
        assertEquals("", hook.stdoutText())
        val before = local.commands.count { "decide" in it }
        val result = answerNow(c, Behavior.Allow)
        val refused = assertIs<OperationResult.NotSent>(result.result)
        assertEquals(NotAnswerable.Expired.code, refused.reason)
        assertEquals(before, local.commands.count { "decide" in it }, "no decision was even attempted")
        // the read that follows an answer runs after sending has ended (see AnswerController.answer), so the outcome is awaited, not assumed
        until("the follow-up read to show the request expired") { c.view()?.outcome == RequestOutcome.Expired }
    }

    @Test fun aWriterThatLostTheRaceSaysGoneAndTheSheetSaysLost() = runBlocking<Unit> {
        val hook = startHook()
        val c = controller().opened()
        // The request is settled on the host behind the phone's back: the hook is told to end while the sheet still shows it.
        val shown = c.view()!!.shown!!
        hook.destroy()
        hook.waitFor(5, TimeUnit.SECONDS)
        File(runtime, "paddock/${env.sessionName}/$pane/${shown.requestId}.pending.json").let { it.renameTo(File(it.parentFile, "${shown.requestId}.expired.json")) }
        val result = answerNow(c, Behavior.Allow)
        // the preflight reads the settled files and refuses before sending
        assertIs<OperationResult.NotSent>(result.result)
        // and the writer itself, asked directly for a request that is gone, answers exit 3
        val direct = runCatching { hostApi.decide(pane, shown.claudeSessionId, shown.requestId, Behavior.Allow) {} }.exceptionOrNull()
        assertEquals(AnswerCodes.GONE, (direct as io.github.tuthan.paddock.answers.DecisionRefused).code)
    }

    @Test fun aTruncatedRequestIsShownWithItsReasonAndNeverAnswered() = runBlocking<Unit> {
        val big = "x".repeat(300_000)
        startHook(input = """{"content":"$big"}""", toolName = "Write")
        val c = controller().opened()
        val shown = c.view()!!.shown!!
        assertTrue(shown.truncated)
        assertEquals(NotAnswerable.TooLarge, (c.view()!!.answerability as Answerability.No).why)
        val before = local.commands.count { "decide" in it }
        val result = answerNow(c, Behavior.Allow)
        assertEquals(NotAnswerable.TooLarge.code, assertIs<OperationResult.NotSent>(result.result).reason)
        assertEquals(before, local.commands.count { "decide" in it })
    }

    @Test fun aLinkLostAfterTheWriteIsUnknownAndLaterSettledFromTheFilesAsConsumed() = runBlocking<Unit> {
        val hook = startHook()
        val cutting = object : SshSession by local {
            override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
                val r = local.exec(argv, stdin, limits)
                if ("decide" in argv) throw IOException("link lost after the command ran")
                return r
            }
        }
        val flaky = AnswerHost(
            cutting, RelayInstaller(local, decideScript.readBytes(), sha256Hex(decideScript.readBytes()), fileName = "paddock-decide.py"),
            RelayInstaller(local, hookScript.readBytes(), sha256Hex(hookScript.readBytes()), fileName = "paddock-claude-permission-hook.py"), clock, env.sessionName, "live test",
        )
        val c = AnswerController(scope, flaky, journal, { runBlocking { Installed(env.snapshot(), System.currentTimeMillis(), 1) } }, { AgentStatus.Blocked }, clock, 200, 100).opened()
        val result = answerNow(c, Behavior.Allow)
        assertIs<OperationResult.Unknown>(result.result)
        assertTrue(hook.waitFor(10, TimeUnit.SECONDS), "the decision was written even though the answer was lost")
        assertTrue(journal.unresolvedUnknown(key).isNotEmpty())
        val settled = c.settle(key)
        assertEquals(RequestOutcome.Consumed(Behavior.Allow), settled.single().second)
        assertTrue(journal.unresolvedUnknown(key).isEmpty())
    }
}
