package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.relay.HerdrError
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** A pane as the saga's own creates leave it: in the tab and workspace they returned, unless a test says it moved. */
private fun facts(terminal: String, revision: Long, cwd: String, hasAgent: Boolean = false, atShellPrompt: Boolean = false, tab: String? = null, ws: String? = null) =
    PaneFacts(true, terminal, tab ?: (if (terminal == "term_wt") "w9:t1" else "w1:t7"), ws ?: (if (terminal == "term_wt") "w9" else "w1"), revision, cwd, hasAgent, atShellPrompt)

/** The start-agent saga with a failure injected at every step: what is created, what is persisted, what is never repeated. */
class StartAgentSagaTest {
    private var now = 8_000_000L
    private val journal = OperationJournal(InMemoryJournalStore()) { now }
    private val store = InMemorySagaStore()
    private val h = HostProfileId("h1")

    /** A host whose every member is scripted and counted. A mutation calls `before` first, as the relay does. */
    private class Host : SagaHost {
        val calls = mutableListOf<String>()
        var available: (List<String>) -> Boolean = { true }
        /** What the host makes of a typed folder: its real path, or null when it is not a folder there. */
        var folder: (String) -> String? = { it }
        var worktree: () -> Created = { Created("w9", "w9:t1", "w9:p1", "term_wt", "/wt/feat", worktreePath = "/wt/feat", repository = "repo") }
        var tab: () -> Created = { Created(null, "w1:t7", "w1:p7", "term_tab", "/work") }
        var pane: (n: Int) -> PaneFacts = { facts("term_tab", 1, "/work", hasAgent = false, atShellPrompt = true) }
        var start: (name: String) -> StartedAgent = { StartedAgent("term_tab", "w1:p7") }
        var close: () -> Unit = {}
        var inspects = 0
        var beforeFails = false
        override suspend fun executableAvailable(candidates: List<String>): Boolean { calls += "available ${candidates.joinToString("|")}"; return available(candidates) }
        override suspend fun createWorktree(parentWorkspaceId: String, branch: String, trust: Boolean, before: suspend () -> Unit): Created { calls += "worktree $parentWorkspaceId $branch trust=$trust"; before(); return worktree() }
        override suspend fun createTab(workspaceId: String, cwd: String?, before: suspend () -> Unit): Created { calls += "tab $workspaceId" + (cwd?.let { " in $it" } ?: ""); before(); return tab() }
        override suspend fun resolveFolder(path: String): String? { calls += "folder $path"; return folder(path) }
        override suspend fun inspectPane(paneId: String): PaneFacts { calls += "inspect $paneId"; return pane(inspects++) }
        override suspend fun startAgent(name: String, kind: String, paneId: String, timeoutMs: Int, before: suspend () -> Unit): StartedAgent { calls += "start $name $kind $paneId"; before(); return start(name) }
        override suspend fun closeWorkspace(workspaceId: String, before: suspend () -> Unit) { calls += "closeWorkspace $workspaceId"; before(); close() }
        override suspend fun closeTab(tabId: String, before: suspend () -> Unit) { calls += "closeTab $tabId"; before(); close() }
        override suspend fun closePane(paneId: String, before: suspend () -> Unit) { calls += "closePane $paneId"; before(); close() }
        fun mutations() = calls.count { it.startsWith("worktree") || it.startsWith("tab ") || it.startsWith("start ") || it.startsWith("close") }
    }

    private val host = Host()
    private var prompter: SagaPrompter? = null
    private fun saga(sagaStore: SagaStore = store, window: Long = 10_000) =
        StartAgentSaga(host, prompter, journal, sagaStore, { now }, verifyWindowMillis = window, pollMillis = 500, pause = { now += it })

    private fun req(branch: String? = null, name: String = "worker", kind: String = "claude", prompt: String? = null, skip: Boolean = false, trust: Boolean = false, folder: String? = null) =
        SagaRequest(h, "paddock-test-2", name, kind, "w1", branch, trust, prompt, skip, repository = "repo", folder = folder)

    private fun rows(kind: OperationKind) = journal.records.value.filter { it.kind == kind }

    // ---- the happy paths ------------------------------------------------------------------------------------------------------

    @Test fun withoutAWorktreeATabIsCreatedCheckedAndTheAgentStartsInItsPane() = runBlocking<Unit> {
        val rec = saga().run(req())
        assertEquals(SagaState.Succeeded, rec.state)
        assertEquals(listOf("available claude", "tab w1", "inspect w1:p7", "start worker claude w1:p7"), host.calls)
        assertEquals("w1:t7", rec.createdTabId); assertEquals("w1:p7", rec.createdPaneId); assertEquals("term_tab", rec.terminalId)
        assertNull(rec.createdWorkspaceId)
        // Each mutation is a journaled operation on the saga's own subject, Acknowledged.
        assertEquals(listOf(OperationKind.TabCreate, OperationKind.AgentStart), journal.records.value.map { it.kind })
        assertTrue(journal.records.value.all { it.outcome == OperationOutcome.Acknowledged && it.terminalId == Subject.saga(rec.id) })
        assertEquals("w1:p7", rows(OperationKind.AgentStart).single().paneIdAtSend)
    }

    @Test fun withAWorktreeTheAgentStartsInTheRootPaneThatCameBackAndItsCwdIsTheWorktreePath() = runBlocking<Unit> {
        host.pane = { facts("term_wt", 1, "/wt/feat", atShellPrompt = true) }
        host.start = { StartedAgent("term_wt", "w9:p1") }
        val rec = saga().run(req(branch = "feat"))
        assertEquals(SagaState.Succeeded, rec.state)
        // AC-09.8: the pane for `agent start` is the worktree's root pane, not the parent workspace's pane.
        assertEquals(listOf("available claude", "worktree w1 feat trust=false", "inspect w9:p1", "start worker claude w9:p1"), host.calls)
        assertEquals("w9", rec.createdWorkspaceId); assertEquals("/wt/feat", rec.worktreePath)
        assertEquals(listOf(OperationKind.WorktreeCreate, OperationKind.AgentStart), journal.records.value.map { it.kind })
    }

    @Test fun everyReturnedIdIsOnDiskBeforeTheNextStepStarts() = runBlocking<Unit> {
        var seenAtStart: SagaRecord? = null
        host.start = { seenAtStart = store.load().records.single(); StartedAgent("term_tab", "w1:p7") }
        saga().run(req())
        assertEquals("w1:p7", seenAtStart!!.createdPaneId); assertEquals("w1:t7", seenAtStart!!.createdTabId); assertEquals("term_tab", seenAtStart!!.createdTerminalId)
        assertEquals(SagaStep.Start, seenAtStart!!.step)
    }

    @Test fun theFirstPromptGoesThroughThePromptPathOnlyOnceTheAgentRuns() = runBlocking<Unit> {
        val sent = mutableListOf<Pair<String, String>>()
        prompter = SagaPrompter { t, text -> sent += t to text; PromptStep.Sent }
        val rec = saga().run(req(prompt = "do the thing"))
        assertEquals(listOf("term_tab" to "do the thing"), sent)
        assertEquals(SagaState.Succeeded, rec.state)
        assertNull(rec.promptNote)
        // The text is never stored; its hash is.
        assertEquals(OperationJournal.sha256Hex("do the thing"), rec.promptSha256)
        assertFalse("do the thing" in store.load().records.toString())
    }

    @Test fun aPromptThatCannotGoYetLeavesTheAgentStartedWithANote() = runBlocking<Unit> {
        prompter = SagaPrompter { _, _ -> PromptStep.Held("The agent is not ready for a prompt yet. Nothing was sent.") }
        val rec = saga().run(req(prompt = "hi"))
        assertEquals(SagaState.Succeeded, rec.state)
        assertTrue("not ready" in rec.promptNote!!)
        // The saga never sends a prompt without one asked for, and never one to an agent that did not start.
        prompter = SagaPrompter { _, _ -> error("must not be called") }
        assertEquals(SagaState.Succeeded, saga().run(req(name = "two")).state)
    }

    // ---- the folder ---------------------------------------------------------------------------------------------------------------

    @Test fun aFolderIsResolvedFirstAndTheTabIsCreatedThereAndCheckedAgainstTheRealPath() = runBlocking<Unit> {
        host.folder = { "/srv/real/api" }
        host.tab = { Created(null, "w1:t7", "w1:p7", "term_tab", "/srv/real/api") }
        host.pane = { facts("term_tab", 1, "/srv/real/api", hasAgent = false, atShellPrompt = true) }
        val rec = saga().run(req(folder = "~/api"))
        assertEquals(SagaState.Succeeded, rec.state)
        // The host is asked about what the user typed; the tab is made in what it answered; both are in the record.
        assertEquals(listOf("available claude", "folder ~/api", "tab w1 in /srv/real/api", "inspect w1:p7", "start worker claude w1:p7"), host.calls)
        assertEquals("~/api", rec.folder); assertEquals("/srv/real/api", rec.expectedCwd)
    }

    @Test fun aFolderThatIsNotThereStopsBeforeAnythingIsCreated() = runBlocking<Unit> {
        host.folder = { null }
        val rec = saga().run(req(folder = "/srv/nothing"))
        assertEquals(SagaState.Failed, rec.state); assertEquals(SagaFailure.FOLDER_MISSING, rec.failure); assertEquals(SagaStep.Folder, rec.step)
        assertEquals(listOf("available claude", "folder /srv/nothing"), host.calls)
        assertFalse(rec.createdSomething)
        assertTrue(journal.records.value.isEmpty())
        assertTrue("/srv/nothing is not a folder on the host" in rec.message && "Nothing was created" in rec.message)
    }

    @Test fun aHostThatCannotBeAskedAboutTheFolderStopsBeforeAnythingIsCreated() = runBlocking<Unit> {
        host.folder = { throw IOException("reset") }
        val rec = saga().run(req(folder = "/srv/api"))
        assertEquals(SagaFailure.HOST_UNREACHABLE, rec.failure)
        assertTrue(host.calls.none { it.startsWith("tab") })
        assertTrue(journal.records.value.isEmpty())
    }

    /** What 0.9.1 really does with a folder it cannot use: it opens the tab in its own default folder and says so only in the pane's cwd. */
    @Test fun aTabThatOpenedSomewhereElseStopsBeforeAnAgentStartsAndKeepsItsIds() = runBlocking<Unit> {
        host.folder = { "/srv/api" }
        host.tab = { Created(null, "w1:t7", "w1:p7", "term_tab", "/home/dev") }
        val rec = saga().run(req(folder = "/srv/api"))
        assertEquals(SagaFailure.WRONG_CWD, rec.failure)
        assertEquals("w1:t7", rec.createdTabId)
        assertTrue(host.calls.none { it.startsWith("start") || it.startsWith("inspect") })
        assertTrue("/home/dev" in rec.message && "/srv/api" in rec.message)
        assertTrue(rec.needsRecovery)
    }

    @Test fun withoutAFolderTheHostIsNeverAskedAndTheTabHasNoCwd() = runBlocking<Unit> {
        saga().run(req())
        assertTrue(host.calls.none { it.startsWith("folder") })
        assertTrue("tab w1" in host.calls)
    }

    @Test fun aFolderIsKeptForStartingAgainFromTheCard() = runBlocking<Unit> {
        host.available = { false }
        val s = saga()
        val rec = s.run(req(folder = "/srv/api", kind = "maki"))
        assertEquals("/srv/api", s.requestOf(rec.id)!!.folder)
        // After a restart the request is rebuilt from the record, and the folder is still in it.
        assertEquals("/srv/api", saga().requestOf(rec.id)!!.folder)
    }

    @Test fun theFolderRulesAreTheFormsAndTheSagasAlike() {
        assertNull(SagaRules.folderProblem("/srv/api")); assertNull(SagaRules.folderProblem("~")); assertNull(SagaRules.folderProblem("~/my project"))
        for (bad in listOf("", "  ", "api", "./api", "~user/x", "/a\nb", "/" + "x".repeat(1100))) assertNotNull("[$bad]", SagaRules.folderProblem(bad))
        assertNotNull(SagaRules.problem(req(folder = "/srv/api", branch = "feat")))
        assertNull(SagaRules.problem(req(folder = "/srv/api")))
        assertFailsWith<IllegalArgumentException> { runBlocking { saga().run(req(folder = "relative")) } }
    }

    // ---- step 1: availability -------------------------------------------------------------------------------------------------

    @Test fun aMissingExecutableStopsBeforeAnythingIsCreated() = runBlocking<Unit> {
        host.available = { false }
        val rec = saga().run(req(kind = "maki"))
        assertEquals(SagaFailure.EXECUTABLE_MISSING, rec.failure)
        assertEquals(listOf("available maki"), host.calls)
        assertFalse(rec.createdSomething)
        assertTrue(journal.records.value.isEmpty())
        val card = SagaCard.of(rec)
        assertTrue(card.actions.contains(CardAction.StartAnyway))
        assertTrue(card.lines.any { "Nothing was created" in it })
    }

    /** The app showed no card at all for a start that stopped before creating anything, so "not installed" and "no such folder" were silent. */
    @Test fun aStartThatCreatedNothingShowsACardOnlyForTheAttemptJustMade() = runBlocking<Unit> {
        host.available = { false }
        val rec = saga().run(req(kind = "maki"))
        assertFalse(rec.needsRecovery)
        assertTrue(rec.showsCard(attemptSince = rec.startedAt))
        assertFalse(rec.showsCard(attemptSince = null))
        assertFalse(rec.showsCard(attemptSince = rec.updatedAt + 1), "an older failure stays in Activity")
        saga().leave(rec.id)
        assertFalse(store.load().records.single().showsCard(attemptSince = rec.startedAt), "dismissed")
    }

    @Test fun startAnywaySkipsOnlyTheAvailabilityCheck() = runBlocking<Unit> {
        host.available = { false }
        val rec = saga().run(req(skip = true))
        assertEquals(SagaState.Succeeded, rec.state)
        assertTrue(host.calls.none { it.startsWith("available") })
    }

    @Test fun aHostThatCannotBeAskedStopsBeforeAnythingIsCreated() = runBlocking<Unit> {
        host.available = { throw IOException("reset") }
        val rec = saga().run(req())
        assertEquals(SagaFailure.HOST_UNREACHABLE, rec.failure)
        assertEquals(0, host.mutations())
    }

    @Test fun cursorAndKiroAlsoLookForTheirVendorsSpelling() {
        assertEquals(listOf("cursor", "cursor-agent"), SagaRules.executables("cursor"))
        assertEquals(listOf("kiro", "kiro-cli"), SagaRules.executables("kiro"))
        assertEquals(listOf("claude"), SagaRules.executables("claude"))
    }

    // ---- step 2: the place ----------------------------------------------------------------------------------------------------

    @Test fun aRefusedWorktreeCreatesNothingAndOffersTrustOnlyWhenHerdrAsksForIt() = runBlocking<Unit> {
        host.worktree = { throw HerdrError("worktree_create_failed", "repository is not trusted: dubious ownership") }
        val rec = saga().run(req(branch = "feat"))
        assertEquals(SagaFailure.PLACE_REFUSED, rec.failure)
        assertFalse(rec.createdSomething)
        assertEquals(OperationOutcome.Rejected, rows(OperationKind.WorktreeCreate).single().outcome)
        assertTrue(SagaCard.of(rec).actions.any { it is CardAction.TrustRepository })
        // Another refusal gets no trust button: trust is not a routine retry.
        host.worktree = { throw HerdrError("worktree_create_failed", "branch feat already exists") }
        val other = saga().run(req(branch = "feat"))
        assertTrue(SagaCard.of(other).actions.none { it is CardAction.TrustRepository })
    }

    @Test fun trustIsPassedOnlyWhenTheRequestCarriesIt() = runBlocking<Unit> {
        saga().run(req(branch = "feat", trust = true))
        assertTrue("worktree w1 feat trust=true" in host.calls)
    }

    @Test fun aLinkLostAfterTheCreateRequestLeftIsUnknownWithNoIdsAndNothingIsRepeated() = runBlocking<Unit> {
        host.tab = { throw IOException("connection reset") }
        val s = saga()
        val rec = s.run(req())
        assertEquals(SagaFailure.PLACE_UNKNOWN, rec.failure)
        assertFalse(rec.createdSomething)
        assertEquals(OperationOutcome.Unknown, rows(OperationKind.TabCreate).single().outcome)
        assertEquals(1, host.mutations())
        assertEquals(listOf("available claude", "tab w1"), host.calls)
        assertTrue("may exist" in rec.message)
    }

    @Test fun aSagaThatCannotWriteItsFirstRecordCreatesNothing() = runBlocking<Unit> {
        val failing = InMemorySagaStore().also { it.failSaves = true }
        val rec = saga(failing).run(req())
        assertEquals(SagaFailure.SAGA_UNWRITABLE, rec.failure)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun idsThatCannotBeSavedAreStillShownAndNothingIsStarted() = runBlocking<Unit> {
        val flaky = object : SagaStore {
            var saves = 0
            override fun load() = SagaData()
            override fun save(data: SagaData) { if (++saves >= 3) throw IOException("disk full") }
        }
        val s = saga(flaky)
        val rec = s.run(req())
        assertEquals(SagaFailure.IDS_NOT_SAVED, rec.failure)
        assertEquals("w1:p7", rec.createdPaneId)
        assertTrue("w1:p7" in rec.message)
        assertTrue(host.calls.none { it.startsWith("start") })
        assertEquals("w1:p7", s.sagas.value.single().createdPaneId)
    }

    // ---- step 3: the check of the new pane -----------------------------------------------------------------------------------

    @Test fun aPaneThatNeverReachesAPromptStopsTheSagaAfterTheWindowAndRecordsItsIds() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val rec = saga(window = 10_000).run(req())
        assertEquals(SagaFailure.PANE_NOT_READY, rec.failure)
        assertTrue(host.inspects >= 20, "polled for the window, not once: ${host.inspects}")
        assertTrue(host.calls.none { it.startsWith("start") })
        assertEquals("w1:p7", rec.createdPaneId)
    }

    @Test fun aShellThatTakesAMomentIsWaitedFor() = runBlocking<Unit> {
        host.pane = { n -> if (n < 3) facts("term_tab", 0, "/work", atShellPrompt = false) else facts("term_tab", 1, "/work", atShellPrompt = true) }
        assertEquals(SagaState.Succeeded, saga().run(req()).state)
        assertEquals(4, host.inspects)
    }

    @Test fun aPaneInTheWrongFolderIsNotStartedIn() = runBlocking<Unit> {
        host.worktree = { Created("w9", "w9:t1", "w9:p1", "term_wt", "/wt/feat", worktreePath = "/wt/feat") }
        host.pane = { facts("term_wt", 1, "/home/u", atShellPrompt = true) }
        val rec = saga().run(req(branch = "feat"))
        assertEquals(SagaFailure.WRONG_CWD, rec.failure)
        assertTrue("/wt/feat" in rec.message && "/home/u" in rec.message)
        assertTrue(host.calls.none { it.startsWith("start") })
        assertTrue(SagaCard.of(rec).actions.any { it is CardAction.CloseCreated && it.what == CloseTarget.Workspace("w9") })
    }

    @Test fun aPaneRunningSomethingElseOrAlreadyHoldingAnAgentIsNotStartedIn() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 1, "/work", atShellPrompt = false) }
        assertEquals(SagaFailure.PANE_BUSY, saga().run(req(name = "a")).failure)
        host.pane = { facts("term_tab", 3, "/work", hasAgent = true, atShellPrompt = false) }
        assertEquals(SagaFailure.PANE_BUSY, saga().run(req(name = "b")).failure)
        assertEquals(0, host.calls.count { it.startsWith("start") })
    }

    @Test fun aPaneMovedToAnotherTabKeepsItsIdButNotItsTabSoOnlyThePaneIsOfferedForClosing() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 1, "/work", atShellPrompt = true, tab = "w1:t99") }
        val s = saga()
        val rec = s.run(req())
        assertEquals(SagaFailure.PANE_MOVED, rec.failure)
        assertTrue("w1:t99" in rec.message)
        assertNull(rec.createdTabId); assertNull(rec.createdWorkspaceId)
        assertEquals("w1:p7", rec.createdPaneId)
        assertEquals(CloseTarget.Pane("w1:p7"), s.closeTarget(rec))
        assertTrue(host.calls.none { it.startsWith("start") })
        // A worktree pane that left its workspace is the same: the workspace recorded is no longer its own, so it is never closed.
        host.pane = { facts("term_wt", 1, "/wt/feat", atShellPrompt = true, ws = "w1") }
        val wt = saga().run(req(branch = "feat", name = "wt"))
        assertEquals(SagaFailure.PANE_MOVED, wt.failure)
        assertEquals(CloseTarget.Pane("w9:p1"), saga().closeTarget(wt))
    }

    @Test fun aPaneThatChangedHandsOrWentAwayIsRefusedEvenWhenItsIdIsStillThere() = runBlocking<Unit> {
        host.pane = { facts("term_somebody_else", 1, "/work", atShellPrompt = true) }
        assertEquals(SagaFailure.PANE_MOVED, saga().run(req(name = "a")).failure)
        host.pane = { PaneFacts(exists = false) }
        assertEquals(SagaFailure.PANE_MOVED, saga().run(req(name = "b")).failure)
        assertEquals(0, host.calls.count { it.startsWith("start") })
    }

    @Test fun aPaneThatCannotBeReadForTheWholeWindowFailsWithoutStarting() = runBlocking<Unit> {
        host.pane = { throw IOException("no answer") }
        val rec = saga().run(req())
        assertEquals(SagaFailure.PANE_NOT_READY, rec.failure)
        assertTrue("no answer" in rec.message)
        assertEquals(0, host.calls.count { it.startsWith("start") })
    }

    // ---- step 4: the start ----------------------------------------------------------------------------------------------------

    @Test fun aDuplicateNameKeepsThePlaceAndAllowsOneNewStartWithAnotherNameWithoutRecreatingTopology() = runBlocking<Unit> {
        var taken = true
        host.start = { name -> if (taken && name == "worker") throw HerdrError("agent_name_taken", "agent name worker is already used") else StartedAgent("term_tab", "w1:p7") }
        val s = saga()
        val first = s.run(req())
        assertEquals(SagaFailure.NAME_TAKEN, first.failure)
        assertEquals("w1:p7", first.createdPaneId)
        assertTrue(SagaCard.of(first).actions.contains(CardAction.ChooseAnotherName))
        val creates = host.calls.count { it.startsWith("tab ") }

        val second = s.retryWithName(first.id, "worker2")
        assertEquals(SagaState.Succeeded, second.state)
        assertEquals("worker2", second.agentName)
        assertEquals(creates, host.calls.count { it.startsWith("tab ") }, "the topology is not created again")
        assertEquals(1, host.calls.count { it == "start worker2 claude w1:p7" })
        assertEquals(2, host.inspects, "the pane was checked again before the second start")
        assertEquals(listOf(OperationKind.TabCreate, OperationKind.AgentStart, OperationKind.AgentStart), journal.records.value.map { it.kind })
    }

    @Test fun onlyARefusedNameCanBeRetriedAndTheNewNameIsChecked() = runBlocking<Unit> {
        host.start = { throw HerdrError("agent_name_taken", "taken") }
        val s = saga()
        val rec = s.run(req())
        assertFailsWith<IllegalArgumentException> { s.retryWithName(rec.id, "bad name") }
        host.start = { StartedAgent("term_tab", "w1:p7") }
        val ok = s.retryWithName(rec.id, "fresh")
        assertEquals(SagaState.Succeeded, ok.state)
        // A saga that succeeded, or failed some other way, cannot be "retried".
        assertFailsWith<IllegalArgumentException> { s.retryWithName(rec.id, "again") }
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val stuck = s.run(req(name = "n2"))
        assertFailsWith<IllegalArgumentException> { s.retryWithName(stuck.id, "n3") }
    }

    @Test fun aBlockedOrTimedOutStartKeepsTheNameAndShowsThePane() = runBlocking<Unit> {
        host.start = { throw HerdrError("agent_not_ready", "agent did not become ready within 30000ms") }
        val s = saga()
        val rec = s.run(req())
        assertEquals(SagaFailure.START_REFUSED, rec.failure)
        assertEquals("worker", rec.agentName)
        assertTrue("agent_not_ready" in rec.message)
        val card = SagaCard.of(rec)
        assertTrue(card.actions.any { it is CardAction.OpenPane && it.paneId == "w1:p7" })
        assertTrue(card.actions.none { it == CardAction.ChooseAnotherName })
        assertEquals(1, host.calls.count { it.startsWith("start") })
    }

    @Test fun aLinkLostAfterTheStartRequestLeftIsUnknownAndIsNeverReplayed() = runBlocking<Unit> {
        host.start = { throw IOException("connection reset") }
        val rec = saga().run(req())
        assertEquals(SagaFailure.START_UNKNOWN, rec.failure)
        assertEquals(1, host.calls.count { it.startsWith("start") })
        assertEquals(OperationOutcome.Unknown, rows(OperationKind.AgentStart).single().outcome)
        assertEquals("w1:p7", rec.createdPaneId)
        assertTrue(SagaCard.of(rec).actions.any { it is CardAction.OpenPane })
    }

    @Test fun anAgentStartedInAnotherTerminalThanTheOneCreatedIsReportedNotAcknowledged() = runBlocking<Unit> {
        host.start = { StartedAgent("term_someone_else", "w1:p7") }
        val rec = saga().run(req())
        assertEquals(SagaFailure.START_MISDELIVERED, rec.failure)
        assertEquals(OperationOutcome.Unknown, rows(OperationKind.AgentStart).single().outcome)
        assertTrue(rows(OperationKind.AgentStart).single().note.startsWith(Misdelivered.PREFIX))
    }

    // ---- recovery -------------------------------------------------------------------------------------------------------------

    @Test fun closingWhatAWorktreeSagaCreatedIsOneConfirmedCloseOfItsWorkspaceAndLeavesTheCheckout() = runBlocking<Unit> {
        host.pane = { facts("term_wt", 0, "/wt/feat", atShellPrompt = false) }
        val s = saga(window = 1_000)
        val rec = s.run(req(branch = "feat"))
        assertTrue(rec.needsRecovery)
        val card = SagaCard.of(rec)
        assertTrue(card.lines.any { "worktree folder" in it && "stay on the host" in it })
        assertEquals(listOf("Workspace" to "w9", "Tab" to "w9:t1", "Pane" to "w9:p1", "Worktree" to "/wt/feat"), card.ids)
        assertEquals(CloseTarget.Workspace("w9"), s.closeTarget(rec))

        assertIs<OperationResult.Acknowledged<*>>(s.closeCreated(rec.id))
        assertEquals(listOf("closeWorkspace w9"), host.calls.filter { it.startsWith("close") })
        assertTrue(s.get(rec.id)!!.recovered)
        assertFalse(s.get(rec.id)!!.needsRecovery)
        assertEquals(OperationKind.CloseWorkspace, journal.records.value.last().kind)
        assertEquals("workspace:w9", journal.records.value.last().terminalId)
        assertNull(s.closeTarget(s.get(rec.id)!!))
    }

    @Test fun withoutAWorktreeTheTabIsClosedAndAGoneTargetCountsAsDone() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val s = saga(window = 1_000)
        val rec = s.run(req())
        host.close = { throw HerdrError("tab_not_found", "no such tab") }
        val r = s.closeCreated(rec.id)
        assertIs<OperationResult.Rejected>(r)
        assertEquals(listOf("closeTab w1:t7"), host.calls.filter { it.startsWith("close") })
        assertTrue(s.get(rec.id)!!.recovered, "a tab that is already gone is what was asked for")
    }

    @Test fun aCloseThatFailsOtherwiseLeavesTheCardAndAnUnknownCloseIsNotRepeated() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val s = saga(window = 1_000)
        val rec = s.run(req())
        host.close = { throw IOException("reset") }
        assertIs<OperationResult.Unknown>(s.closeCreated(rec.id))
        assertFalse(s.get(rec.id)!!.recovered)
        assertIs<OperationResult.NeedsReread>(s.closeCreated(rec.id))
        assertEquals(1, host.calls.count { it.startsWith("close") })
    }

    @Test fun leavingItDismissesTheCardButKeepsTheIds() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val s = saga(window = 1_000)
        val rec = s.run(req())
        s.leave(rec.id)
        val kept = s.get(rec.id)!!
        assertTrue(kept.recovered && !kept.needsRecovery)
        assertEquals("w1:p7", kept.createdPaneId)
    }

    @Test fun aSagaThatWasRunningWhenTheAppEndedBecomesAFailureWithItsIdsAndIsNeverResumed() = runBlocking<Unit> {
        val running = SagaRecord("s1", "h1", "paddock-test-2", "worker", "claude", "w1", null, null, SagaStep.Verify, SagaState.Running,
            createdTabId = "w1:t7", createdPaneId = "w1:p7", createdTerminalId = "term_tab", startedAt = 1, updatedAt = 2)
        val restarted = saga(InMemorySagaStore(SagaData(listOf(running))))
        val rec = restarted.get("s1")!!
        assertEquals(SagaState.Failed, rec.state)
        assertEquals(SagaFailure.APP_ENDED, rec.failure)
        assertTrue(rec.needsRecovery)
        assertEquals(0, host.mutations())
    }

    @Test fun aRequestThatBreaksTheRulesNeverReachesTheHost() = runBlocking<Unit> {
        for (bad in listOf(req(name = "has space"), req(name = ""), req(kind = "notakind"), req(branch = "a..b"), req(branch = "-x"), req(branch = "x y"), req(branch = "end/"), req(branch = "a.lock"))) {
            assertFailsWith<IllegalArgumentException>("for $bad") { saga().run(bad) }
        }
        assertTrue(host.calls.isEmpty())
    }

    @Test fun theKindListMatchesTheCapturedStartKinds() {
        val file = java.io.File("../fixtures/herdr-0.9.1/agent-start-kinds.txt")
        if (file.exists()) assertEquals(file.readLines().filter { it.isNotBlank() }, SagaRules.KINDS)
        assertEquals(24, SagaRules.KINDS.size)
    }

    @Test fun everyFailureHasACardThatNamesWhatExists() = runBlocking<Unit> {
        host.pane = { facts("term_tab", 0, "/work", atShellPrompt = false) }
        val s = saga(window = 1_000)
        val rec = s.run(req())
        val card = SagaCard.of(rec, "laptop")
        assertTrue("stopped" in card.title && "worker" in card.title)
        assertTrue(card.lines.any { "laptop" in it && "w1:p7" in it && "w1:t7" in it })
        assertEquals(listOf("Tab" to "w1:t7", "Pane" to "w1:p7"), card.ids)
        assertEquals("Leave it", (card.actions.last() as CardAction.Leave).label)
        assertEquals("Close the new tab", (card.actions.first { it is CardAction.CloseCreated } as CardAction.CloseCreated).label)
        // A saga that created nothing says so and offers Dismiss only.
        host.available = { false }
        val none = SagaCard.of(saga().run(req(name = "z")))
        assertEquals("Dismiss", (none.actions.last() as CardAction.Leave).label)
        assertTrue(none.ids.isEmpty())
    }
}
