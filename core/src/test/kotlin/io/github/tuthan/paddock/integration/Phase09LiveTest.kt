package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.AgentInfoResult
import io.github.tuthan.paddock.herdr.SessionCatalog
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.herdr.decodeResult
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.HostSpaces
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ops.AgentOperations
import io.github.tuthan.paddock.ops.CardAction
import io.github.tuthan.paddock.ops.CloseTarget
import io.github.tuthan.paddock.ops.Created
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.InMemorySagaStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.PaneFacts
import io.github.tuthan.paddock.ops.RelaySagaHost
import io.github.tuthan.paddock.ops.SagaCard
import io.github.tuthan.paddock.ops.SagaFailure
import io.github.tuthan.paddock.ops.SagaHost
import io.github.tuthan.paddock.ops.SagaRequest
import io.github.tuthan.paddock.ops.SagaState
import io.github.tuthan.paddock.ops.SavedLayoutParser
import io.github.tuthan.paddock.ops.SavedLayoutReader
import io.github.tuthan.paddock.ops.SavedLayoutResult
import io.github.tuthan.paddock.ops.SessionCopy
import io.github.tuthan.paddock.ops.SessionOperations
import io.github.tuthan.paddock.ops.SessionRules
import io.github.tuthan.paddock.ops.SessionRun
import io.github.tuthan.paddock.ops.SpaceOperations
import io.github.tuthan.paddock.ops.StartAgentSaga
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.relay.RelayClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 09 against a real herdr. Each test starts a throw-away herdr server of its own, in an isolated HOME (short, because herdr's
 * client socket name is longer than the API socket's and a Unix socket path holds about 100 bytes) and a session named
 * `paddock-test-p9<n>`, so stop and delete only ever act on a session this test created and never on `default` or `paddock-test`.
 * Stand-ins for `claude` and `cline` come first on the server's PATH, so a pane's `agent start` runs `tools/fake-agent.py`, never an
 * installed agent. AC-09.1 to .4, .7 and .8 live halves. Gated by `PADDOCK_TEST_SOCKET`, like every integration test, so CI skips it.
 */
class Phase09LiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repoRoot = File(System.getProperty("paddock.repoRoot"))
    private lateinit var home: File
    private lateinit var name: String
    private lateinit var work: File
    private lateinit var server: Process
    private lateinit var env: Map<String, String>
    private lateinit var session: LocalProcessSession
    private lateinit var cli: HerdrCli
    private lateinit var relay: RelayClient
    private val herdr = System.getenv("PADDOCK_HERDR") ?: "/usr/bin/herdr"
    private val clock = Clock { System.currentTimeMillis() }
    private val host = HostProfileId("local")
    private var serverUp = false

    @Before fun setUp() {
        Assume.assumeTrue("PADDOCK_TEST_SOCKET not set; integration test skipped", !System.getenv("PADDOCK_TEST_SOCKET").isNullOrBlank())
        val n = (System.nanoTime() / 1000 % 100_000).toString()
        name = "paddock-test-p9$n"
        home = File("/tmp/p9h$n").also { it.mkdirs() }
        work = File(home, "work").also { it.mkdirs() }
        val bin = File(home, "bin").also { it.mkdirs() }
        for (kind in listOf("claude", "kiro")) Files.createSymbolicLink(File(bin, kind).toPath(), File(repoRoot, "tools/fake-agent.py").toPath())
        // herdr reads XDG_CONFIG_HOME before HOME, and a pane of the developer's own herdr exports HERDR_SOCKET_PATH (the default session's
        // socket), so both are pointed at this test's own directory: a command without --session lands here, never on `default`.
        val sock0 = File(home, ".config/herdr/sessions/$name/herdr.sock")
        env = mapOf("HOME" to home.absolutePath, "XDG_CONFIG_HOME" to File(home, ".config").absolutePath, "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
            "XDG_STATE_HOME" to File(home, ".local/state").absolutePath, "HERDR_SOCKET_PATH" to sock0.absolutePath,
            "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}", "FAKE_AGENT_LOG" to File(home, "agent.log").absolutePath)
        // A git repository to make worktrees from.
        git("init", "-q", "-b", "main"); git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "init")
        session = LocalProcessSession(env)
        cli = HerdrCli(herdr, name)
        server = ProcessBuilder(herdr, "--session", name, "server").directory(work).redirectErrorStream(true).redirectOutput(File(home, "server.log"))
            .also { it.environment().putAll(env) }.start()
        val sock = sock0
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !(sock.exists() && cliOk("status"))) Thread.sleep(200)
        check(sock.exists()) { "the disposable herdr did not come up: ${File(home, "server.log").readText().take(300)}" }
        PaddockTest.guard(sock.absolutePath)
        serverUp = true
        relay = RelayClient(session, File(repoRoot, "host/paddock-relay.py").absolutePath, sock.absolutePath)
        // The headless server seeds a workspace at HOME: put one at the repository and drop the other.
        cliText("workspace", "create", "--cwd", work.absolutePath, "--label", "work", "--no-focus")
        runBlocking { snapshot().workspaces.filter { it.label != "work" }.forEach { cliText("workspace", "close", it.workspaceId) } }
    }

    @After fun tearDown() {
        if (!::server.isInitialized) return
        // Stop whatever is left of this test's own session, then remove its directory. Nothing else is ever named.
        runCatching { ProcessBuilder(herdr, "session", "stop", name, "--json").also { it.environment().putAll(env) }.start().waitFor(15, TimeUnit.SECONDS) }
        server.destroy(); server.waitFor(5, TimeUnit.SECONDS)
        // Worktrees live under this HOME, which goes away with the rest; the repository is a temp directory of this test.
        if (home.absolutePath.startsWith("/tmp/p9h") && name.startsWith("paddock-test-p9")) home.deleteRecursively()
    }

    private fun git(vararg args: String) {
        val p = ProcessBuilder(listOf("git") + args).directory(work).redirectErrorStream(true).start()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: ${p.inputStream.readBytes().decodeToString()}" }
    }

    private fun scoped(vararg rest: String) = listOf(herdr, "--session", name) + rest
    private fun cliOk(vararg args: String) = ProcessBuilder(scoped(*args)).also { it.environment().putAll(env) }.redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 }
    private fun cliText(vararg args: String): String {
        val p = ProcessBuilder(scoped(*args)).also { it.environment().putAll(env) }.redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        check(p.waitFor(20, TimeUnit.SECONDS)) { "herdr ${args.toList()} timed out" }
        return out
    }

    private suspend fun snapshot(): Snapshot = SessionMonitor.snapshotReader(relay)()

    private suspend fun catalog(): SessionCatalog {
        val ok = CliResult.classify(session.exec(HerdrCli(herdr, "default").sessionList())) as CliOutcome.Ok
        return PaddockJson.decodeResult<SessionCatalog>(ok.stdout).getOrThrow()
    }
    private suspend fun entry(): SessionEntry? = catalog().sessions.firstOrNull { it.name == name }

    private fun journal() = OperationJournal(InMemoryJournalStore(), clock)

    // ---- AC-09.1, AC-09.2, AC-09.7: stop, saved layout, delete ----------------------------------------------------------------

    @Test fun stopAndDeleteActOnlyOnTheNamedSessionAndTheSavedLayoutIsReadBetween() = runBlocking<Unit> {
        val j = journal()
        val spaces = HostSpaces(session, herdr, j, host, clock)
        spaces.refresh()
        val before = (spaces.state.value as SpacesState.Ready).rows
        val mine = before.single { it.entry.name == name }
        assertTrue(mine.entry.running && mine.layout == null, "a running session shows its live herd, not a file")
        // Only the isolated HOME's own session exists: the real default session of the machine is not in this catalogue at all.
        assertEquals(setOf(name), before.map { it.entry.name }.toSet() - "default")

        // Delete of a running session is not offered, and herdr's own refusal is the second line of defence.
        assertEquals("Stop $name before deleting it.", SessionRules.deleteRefusal(mine.entry))
        val forced = CliResult.classify(session.exec(HerdrCli(herdr, "default").sessionDelete(name)))
        assertEquals("session_delete_failed", assertIs<CliOutcome.Failure>(forced).code)
        assertNotNull(entry(), "the refused delete left the session alone")

        val stopped = assertIs<SessionRun.Ran>(SessionOperations(session, herdr, j, host).stop(mine.entry)).result
        assertIs<OperationResult.Acknowledged<*>>(stopped)
        val after = entry()!!
        assertFalse(after.running)

        // AC-09.2: the saved layout of the stopped session, basenames and the file's date.
        val layout = assertIs<SavedLayoutResult.Layout>(SavedLayoutReader(session).read(after)).layout
        assertEquals(listOf(work.name), layout.workspaces)
        assertTrue(System.currentTimeMillis() - layout.savedAtMillis in 0..120_000, "dated by the file: ${layout.savedAtMillis}")

        // Stopping what is stopped is herdr's refusal, shown as it is.
        val again = assertIs<SessionRun.Ran>(SessionOperations(session, herdr, j, host).stop(after.copy(running = true))).result
        assertEquals("session_stop_failed", assertIs<OperationResult.Rejected>(again).code)

        val deleted = assertIs<SessionRun.Ran>(SessionOperations(session, herdr, j, host).delete(after)).result
        assertIs<OperationResult.Acknowledged<*>>(deleted)
        assertTrue(entry() == null, "the session is gone from the catalogue")
        assertEquals(listOf(OperationKind.SessionStop, OperationKind.SessionStop, OperationKind.SessionDelete), j.records.value.map { it.kind })
        assertEquals(listOf(OperationOutcome.Acknowledged, OperationOutcome.Rejected, OperationOutcome.Acknowledged), j.records.value.map { it.outcome })
        // Never a command for any other session name, and never a signal.
        val named = session.commands.filter { "stop" in it || "delete" in it }.flatten().filter { it.startsWith("paddock-test") || it == "default" }.toSet()
        assertEquals(setOf(name), named)
        assertTrue(session.commands.none { it.firstOrNull() in setOf("kill", "pkill", "killall") })
        assertTrue("herdr says session $name is deleted" in SessionCopy.outcome(OperationKind.SessionDelete, name, deleted))
    }

    @Test fun deletingTheDefaultSessionIsRefusedBeforeAnyCall() = runBlocking<Unit> {
        val j = journal()
        val fakeDefault = SessionEntry("default", true, false, "/nowhere", "/nowhere/herdr.sock")
        val before = session.commands.size
        val run = SessionOperations(session, herdr, j, host).delete(fakeDefault)
        assertEquals(SessionRules.DEFAULT_DELETE, assertIs<SessionRun.Refused>(run).reason)
        assertEquals(before, session.commands.size, "no command left the phone's side")
    }

    // ---- AC-09.3, AC-09.8: the saga against real herdr -----------------------------------------------------------------------

    private fun saga(store: InMemorySagaStore = InMemorySagaStore(), startTimeoutMs: Int = 15_000, wrap: (SagaHost) -> SagaHost = { it }, j: OperationJournal = journal()): Pair<StartAgentSaga, OperationJournal> =
        StartAgentSaga(wrap(RelaySagaHost(relay, session, cli)), null, j, store, clock, startTimeoutMs = startTimeoutMs, verifyWindowMillis = 10_000, pollMillis = 250) to j

    private suspend fun workspaceId() = snapshot().workspaces.first { it.label == "work" }.workspaceId
    private fun request(workspaceId: String, agent: String, kind: String = "claude", branch: String? = null, folder: String? = null) = SagaRequest(host, name, agent, kind, workspaceId, branch, repository = work.name, folder = folder)

    @Test fun aTabSagaStartsTheAgentInThePaneThatCameBack() = runBlocking<Unit> {
        val (saga, j) = saga()
        val rec = saga.run(request(workspaceId(), "tabby"))
        assertEquals(SagaState.Succeeded, rec.state, rec.message)
        val snap = snapshot()
        val agent = snap.agents.single { it.terminalId == rec.terminalId }
        assertEquals(rec.createdPaneId, agent.paneId)
        assertEquals("claude", agent.agent, "agent as herdr lists it: ${cliText("agent", "list")}")
        assertEquals(listOf(OperationKind.TabCreate, OperationKind.AgentStart), j.records.value.map { it.kind })
        assertTrue(j.records.value.all { it.outcome == OperationOutcome.Acknowledged })
    }

    /** The folder the user types goes through `~` and a symlink, and the pane really is in the real folder (herdr resolves it; the check compares with it). */
    @Test fun aFolderOfTheUsersChoiceIsWhereTheShellAndTheAgentStart() = runBlocking<Unit> {
        val real = File(home, "projects/api service").also { it.mkdirs() }.canonicalFile
        val link = File(home, "api-link").also { Files.createSymbolicLink(it.toPath(), real.toPath()) }
        val (saga, j) = saga()
        for ((agent, typed) in listOf("viahome" to "~/projects/api service", "vialink" to link.path)) {
            val rec = saga.run(request(workspaceId(), agent, folder = typed))
            assertEquals(SagaState.Succeeded, rec.state, rec.message)
            assertEquals(real.path, snapshot().panes.single { it.paneId == rec.createdPaneId }.cwd)
            assertEquals(real.path, rec.expectedCwd)
        }
        assertEquals(4, j.records.value.size, "two tab creates and two agent starts")
    }

    /** herdr 0.9.1 opens the tab in its default folder for a folder that is not there; the saga asks first, so no tab is made. */
    @Test fun aFolderThatIsNotThereCreatesNothing() = runBlocking<Unit> {
        val (saga, j) = saga()
        val tabs = snapshot().tabs.size
        for (bad in listOf("/tmp/p9h-does-not-exist", "~/missing", File(work, ".git/HEAD").path)) {
            val rec = saga.run(request(workspaceId(), "nowhere", folder = bad))
            assertEquals(SagaFailure.FOLDER_MISSING, rec.failure, rec.message)
            assertEquals(false, rec.createdSomething)
        }
        assertEquals(tabs, snapshot().tabs.size)
        assertTrue(j.records.value.isEmpty(), "nothing was sent to herdr")
    }

    @Test fun aWorktreeSagaStartsTheAgentInTheWorktreesRootPaneAndItsCwdIsTheWorktreePath() = runBlocking<Unit> {
        val (saga, _) = saga()
        val rec = saga.run(request(workspaceId(), "wt", branch = "feat-live"))
        assertEquals(SagaState.Succeeded, rec.state, rec.message)
        // AC-09.8: the pane is the root pane `worktree create` returned, and `pane get` shows the worktree path as its cwd.
        val path = assertNotNull(rec.worktreePath)
        assertTrue(path.endsWith("/feat-live"), path)
        val pane = snapshot().panes.single { it.paneId == rec.createdPaneId }
        assertEquals(path, pane.cwd)
        assertEquals(rec.createdWorkspaceId, pane.workspaceId)
        assertNotNull(snapshot().agents.singleOrNull { it.terminalId == rec.terminalId && it.paneId == rec.createdPaneId })
        // The branch and the checkout are real.
        assertTrue(File(path).isDirectory)
        // "Close what was created" closes the workspace and leaves the checkout, as the card says.
        val closed = saga.closeCreated(rec.id)
        assertIs<OperationResult.Acknowledged<*>>(closed)
        assertTrue(snapshot().workspaces.none { it.workspaceId == rec.createdWorkspaceId })
        assertTrue(File(path).isDirectory, "the worktree folder stays on the host")
    }

    @Test fun aDuplicateNameIsHerdrsRefusalAndTheNewNameStartsInTheSamePaneWithoutANewTab() = runBlocking<Unit> {
        val (saga, j) = saga()
        val ws = workspaceId()
        assertEquals(SagaState.Succeeded, saga.run(request(ws, "dup")).state)
        val second = saga.run(request(ws, "dup"))
        assertEquals(SagaFailure.NAME_TAKEN, second.failure, second.message)
        assertTrue(SagaCard.of(second).actions.contains(CardAction.ChooseAnotherName))
        val tabsBefore = snapshot().tabs.size
        val retried = saga.retryWithName(second.id, "dup2")
        assertEquals(SagaState.Succeeded, retried.state, retried.message)
        assertEquals(tabsBefore, snapshot().tabs.size, "no topology was created for the retry")
        assertEquals(second.createdPaneId, retried.createdPaneId)
        assertEquals(2, j.records.value.count { it.kind == OperationKind.AgentStart && it.outcome == OperationOutcome.Acknowledged } + j.records.value.count { it.kind == OperationKind.AgentStart && it.outcome == OperationOutcome.Rejected } - 1)
    }

    @Test fun anExecutableThatIsNotInstalledStopsBeforeAnythingIsCreated() = runBlocking<Unit> {
        // `maki` is a kind herdr can start; no stand-in is on this server's PATH for it.
        Assume.assumeFalse("maki is installed on this machine", cliOk("status") && ProcessBuilder("sh", "-c", "command -v maki").also { it.environment().putAll(env) }.start().waitFor() == 0)
        val before = snapshot().tabs.size
        val (saga, j) = saga()
        val rec = saga.run(request(workspaceId(), "nomaki", kind = "maki"))
        assertEquals(SagaFailure.EXECUTABLE_MISSING, rec.failure, rec.message)
        assertEquals(before, snapshot().tabs.size)
        assertTrue(j.records.value.isEmpty())
        assertTrue(SagaCard.of(rec).actions.contains(CardAction.StartAnyway))
    }

    @Test fun aStartThatNeverBecomesReadyKeepsTheNameAndTheCardNamesThePaneAndItsIds() = runBlocking<Unit> {
        // herdr never detects the `kiro` stand-in (Phase 12 capture), so `agent start` times out with the pane already created.
        val (saga, j) = saga(startTimeoutMs = 4_000)
        val rec = saga.run(request(workspaceId(), "slow", kind = "kiro"))
        assertEquals(SagaState.Failed, rec.state)
        assertEquals(SagaFailure.START_REFUSED, rec.failure, rec.message)
        assertTrue("timeout" in rec.message, rec.message)
        assertEquals("slow", rec.agentName)
        assertNotNull(rec.createdPaneId)
        val card = SagaCard.of(rec)
        assertTrue(card.actions.any { it is CardAction.OpenPane && it.paneId == rec.createdPaneId })
        assertEquals(listOf("Tab" to rec.createdTabId!!, "Pane" to rec.createdPaneId!!), card.ids)
        assertEquals(1, j.records.value.count { it.kind == OperationKind.AgentStart })
        // Close what was created: the tab goes, and the pane with it.
        assertIs<OperationResult.Acknowledged<*>>(saga.closeCreated(rec.id))
        assertTrue(snapshot().tabs.none { it.tabId == rec.createdTabId })
    }

    @Test fun aPaneThatMovedAfterItWasCreatedIsRefusedAndNothingIsStarted() = runBlocking<Unit> {
        // Between the create and the check the pane is moved to a tab of its own. It keeps its pane id (measured on 0.9.1) and the tab
        // it was created in is closed, so the id alone would have passed the check.
        val moving = { inner: SagaHost -> object : SagaHost by inner {
            override suspend fun createTab(workspaceId: String, cwd: String?, before: suspend () -> Unit): Created =
                inner.createTab(workspaceId, cwd, before).also { cliText("pane", "move", it.paneId, "--new-tab") }
        } }
        val (saga, j) = saga(wrap = moving)
        val rec = saga.run(request(workspaceId(), "mover"))
        assertEquals(SagaFailure.PANE_MOVED, rec.failure, rec.message)
        // The pane kept its id, the tab it was created in is gone, and only the pane is offered for closing.
        assertEquals(CloseTarget.Pane(rec.createdPaneId!!), saga.closeTarget(rec))
        assertEquals(0, j.records.value.count { it.kind == OperationKind.AgentStart })
        assertTrue(snapshot().agents.none { it.terminalId == rec.createdTerminalId })
    }

    @Test fun aWorktreeRefusedByHerdrCreatesNothing() = runBlocking<Unit> {
        val (saga, j) = saga()
        val ws = workspaceId()
        assertEquals(SagaState.Succeeded, saga.run(request(ws, "first", branch = "dupe-branch")).state)
        val workspaces = snapshot().workspaces.size
        // The branch exists now, so herdr refuses a second worktree on it.
        val again = saga.run(request(ws, "second", branch = "dupe-branch"))
        assertEquals(SagaFailure.PLACE_REFUSED, again.failure, again.message)
        assertFalse(again.createdSomething)
        assertEquals(workspaces, snapshot().workspaces.size)
        assertEquals(OperationOutcome.Rejected, j.records.value.last { it.kind == OperationKind.WorktreeCreate }.outcome)
    }

    // ---- rename and focus --------------------------------------------------------------------------------------------------

    @Test fun renameAndFocusRunThroughTheRealRelay() = runBlocking<Unit> {
        val (saga, j) = saga()
        val rec = saga.run(request(workspaceId(), "before"))
        assertEquals(SagaState.Succeeded, rec.state, rec.message)
        var installed = Installed(snapshot(), clock.nowMillis(), 1)
        val ops = AgentOperations(relay, j, { installed }, clock)
        val key = TerminalKey(TargetRef(host, name, rec.terminalId!!), 1)
        val renamed = ops.rename(key, "after")
        assertIs<OperationResult.Acknowledged<*>>(renamed)
        assertEquals("after", (renamed as OperationResult.Acknowledged).value.name)
        installed = Installed(snapshot(), clock.nowMillis(), 1)
        val spaces = SpaceOperations(relay, j, { installed }, host, name)
        assertIs<OperationResult.Acknowledged<*>>(spaces.focusWorkspace(rec.createdPaneId!!.substringBefore(':')))
        assertIs<OperationResult.Acknowledged<*>>(spaces.focusTab(rec.createdTabId!!))
        assertTrue(snapshot().tabs.single { it.tabId == rec.createdTabId }.focused)
    }
}
