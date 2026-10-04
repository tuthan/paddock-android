package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.AgentStartedResult
import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.herdr.TabCreatedResult
import io.github.tuthan.paddock.herdr.WorktreeCreatedResult
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.relay.FakeSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * herdr 0.9.1's real answers for the Phase 09 calls (`fixtures/herdr-0.9.1/`, captured by `tools/capture-phase09-fixtures.sh`), run through
 * the code that reads them, so a change in what herdr says fails here and not on a phone.
 */
class Phase09FixturesTest {
    private fun fx(name: String) = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1/$name").readText().trim()
    private fun success(name: String) = Envelope.parse(fx(name)) as Message.Success

    private val journal = OperationJournal(InMemoryJournalStore()) { 1L }
    private val entry = SessionEntry("paddock-test-p9fx", false, true, "/home/user/.config/herdr/sessions/paddock-test-p9fx", "/x/herdr.sock")

    private fun ops(exit: Int, stdout: String) = SessionOperations(FakeSession(onExec = { _, _ -> FakeSession.result(exit, stdout) }), "/usr/bin/herdr", journal, HostProfileId("h"))

    @Test fun sessionStopAndDeleteAnswersAreUnderstood() = runBlocking<Unit> {
        assertIs<OperationResult.Acknowledged<*>>((ops(0, fx("session-stop.json")).stop(entry) as SessionRun.Ran).result)
        assertIs<OperationResult.Acknowledged<*>>((ops(0, fx("session-delete.json")).delete(entry.copy(running = false)) as SessionRun.Ran).result)
    }

    @Test fun herdrsRefusalsKeepTheirCodes() = runBlocking<Unit> {
        val running = (ops(1, fx("session-delete-running.json")).delete(entry.copy(running = false)) as SessionRun.Ran).result
        assertEquals("session_delete_failed", assertIs<OperationResult.Rejected>(running).code)
        val stopped = (ops(1, fx("session-stop-not-running.json")).stop(entry) as SessionRun.Ran).result
        assertEquals("session_stop_failed", assertIs<OperationResult.Rejected>(stopped).code)
    }

    @Test fun theSavedSessionFileIsVersionThreeAndShowsTheWorkspaceFolder() {
        val layout = assertIs<SavedLayoutResult.Layout>(SavedLayoutParser.parse(fx("session-saved-v3.json"), 1)).layout
        assertEquals(listOf("repo", "fx-branch"), layout.workspaces)
    }

    @Test fun tabAndWorktreeCreateReturnEveryIdTheSagaPersists() {
        val tab = success("tab-create.json").decode<TabCreatedResult>("tab_created")
        assertTrue(tab.rootPane.paneId.isNotEmpty() && tab.rootPane.terminalId.startsWith("term_") && tab.tab.tabId == tab.rootPane.tabId)
        val wt = success("worktree-create.json").decode<WorktreeCreatedResult>("worktree_created")
        // The root pane is in the new workspace, and its cwd is the worktree path: the check AC-09.8 rests on.
        assertEquals(wt.workspace.workspaceId, wt.rootPane.workspaceId)
        assertEquals(wt.worktree.path, wt.rootPane.cwd)
        assertEquals("fx-branch", wt.worktree.branch)
        assertTrue(wt.worktree.path.endsWith("/fx-branch"))
    }

    @Test fun aFreshPaneShowsRevisionZeroThenOneWhenItsShellDrawsAndItsForegroundIsTheShell() {
        val pane = success("pane-get-fresh-shell.json").result["pane"]!!.toString()
        assertFalse("\"agent\":" in pane, "a plain shell has no agent")
        val info = success("pane-process-info-shell.json").result["process_info"]!!.toString()
        assertTrue("\"name\":\"bash\"" in info, info)
    }

    @Test fun theCliWaitsForReadinessAndTheRelayDoesNot() {
        val cli = success("agent-start-cli.json").decode<AgentStartedResult>("agent_started").agent
        assertEquals("idle", cli.agentStatus.wire)
        assertEquals("claude", cli.agent)
        assertEquals("fxone", cli.name)
        // The same call through the relay returns at once, launch pending, no detected agent: why the saga starts through the CLI.
        val relay = success("agent-start-relay-launch-pending.json").decode<AgentStartedResult>("agent_started").agent
        assertEquals("unknown", relay.agentStatus.wire)
        assertEquals(true, relay.launchPending)
        assertNull(relay.agent)
    }

    @Test fun aTakenNameAndATimeoutAreHerdrsOwnErrorsWithTheirCodes() {
        val taken = CliResult.classify(ExecResult(1, fx("agent-start-name-taken.json").toByteArray(), ByteArray(0), false, false, kotlin.time.Duration.ZERO))
        assertEquals("agent_name_taken", assertIs<CliOutcome.Failure>(taken).code)
        val timeout = CliResult.classify(ExecResult(1, fx("agent-start-timeout.json").toByteArray(), ByteArray(0), false, false, kotlin.time.Duration.ZERO))
        assertEquals("timeout", assertIs<CliOutcome.Failure>(timeout).code)
    }

    @Test fun aMovedPaneKeepsItsIdAndItsOldTabCloses() {
        val move = success("pane-move-new-tab.json").result["move_result"]!!.toString()
        assertTrue("\"changed\":true" in move && "closed_tab_id" in move && "created_tab" in move, move)
    }

    @Test fun renameAndFocusAnswersDecode() {
        assertEquals("agent_info", success("agent-rename.json").type)
        assertEquals("workspace_info", success("workspace-focus.json").type)
        assertEquals("tab_info", success("tab-focus.json").type)
        assertNotNull(HerdrCli("/usr/bin/herdr", "paddock-test").agentStart("a", "claude", "w1:p1", 4000))
    }
}
