package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.live.SessionRow
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ops.CardAction
import io.github.tuthan.paddock.ops.CloseTarget
import io.github.tuthan.paddock.ops.SagaCard
import io.github.tuthan.paddock.ops.SagaCardModel
import io.github.tuthan.paddock.ops.SagaFailure
import io.github.tuthan.paddock.ops.SagaRecord
import io.github.tuthan.paddock.ops.SagaState
import io.github.tuthan.paddock.ops.SagaStep
import io.github.tuthan.paddock.ops.SavedLayout
import io.github.tuthan.paddock.ops.SavedLayoutResult
import io.github.tuthan.paddock.ops.SessionCopy
import io.github.tuthan.paddock.ops.SessionRules
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.components.PRO_LOCK_TAG
import io.github.tuthan.paddock.ui.screens.RecoveryCard
import io.github.tuthan.paddock.ui.screens.RenameDialog
import io.github.tuthan.paddock.ui.screens.RowActionsDialog
import io.github.tuthan.paddock.ui.screens.SagaProgress
import io.github.tuthan.paddock.ui.screens.SpacesActions
import io.github.tuthan.paddock.ui.screens.SpacesScreen
import io.github.tuthan.paddock.ui.screens.SpacesScreenState
import io.github.tuthan.paddock.ui.screens.StartAgentForm
import io.github.tuthan.paddock.ui.screens.StartAvailability
import io.github.tuthan.paddock.ui.screens.StartForm
import io.github.tuthan.paddock.ui.screens.WorkspaceChoice
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Spaces screen of Phase 09: the dialogs name the session and the machine and open on Cancel, default is never deletable, Restart says why it is off. */
class SpacesTest {
    @get:Rule val rule = createComposeRule()

    private fun entry(name: String, running: Boolean, default: Boolean = false) =
        SessionEntry(name, default, running, "/home/u/.config/herdr/sessions/$name", "/home/u/.config/herdr/sessions/$name/herdr.sock")

    private val layout = SavedLayoutResult.Layout(SavedLayout(listOf("repo", "docs"), 1_790_000_000_000))
    private fun clock(ms: Long) = "12:34"

    private class Calls {
        val stopped = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val reread = mutableListOf<String>()
        val started = mutableListOf<StartForm>()
        val cards = mutableListOf<Pair<String, CardAction>>()
        val retried = mutableListOf<Pair<String, String>>()
        var refreshed = 0
    }

    private fun state(
        rows: List<SessionRow>, cards: List<SagaCardModel> = emptyList(), progress: SagaProgress? = null, notice: String? = null,
        start: StartAvailability = StartAvailability.Available(listOf(WorkspaceChoice("w_1", "paddock"), WorkspaceChoice("w_2", "notes"))),
        list: SpacesState = SpacesState.Ready(rows, 1_790_000_000_000), locked: Set<String> = emptySet(),
    ) = SpacesScreenState("Laptop", "Connected", HostHealth.Live, list, notice, "main", cards, progress, start, locked = locked)

    /** Every Pro capability this screen offers, locked: Start an agent, Stop and Delete draw the lock and the tag (D6). */
    private val allLocked = setOf(ProCapabilities.START_AGENT.id, ProCapabilities.MANAGE_SESSIONS.id)

    private fun show(s: SpacesScreenState, fontScale: Float? = null, dark: Boolean = true): Calls {
        val calls = Calls()
        val actions = SpacesActions(
            onRefresh = { calls.refreshed++ }, onStop = { calls.stopped += it.name }, onDelete = { calls.deleted += it.name }, onReread = { calls.reread += it },
            onStart = { calls.started += it }, onCard = { id, a -> calls.cards += id to a }, onRetryName = { id, n -> calls.retried += id to n },
        )
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) { SpacesScreen(s, actions, clock = ::clock) }
            }
        }
        return calls
    }

    private fun button(text: String): SemanticsNodeInteraction = rule.onNode(hasText(text) and hasClickAction())

    private val main = SessionRow(entry("main", running = true))
    private val stoppedDefault = SessionRow(entry("default", running = false, default = true), layout)
    private val stopped = SessionRow(entry("work-2", running = false), layout)

    @Test fun aRunningSessionStopsOnlyAfterADialogThatNamesTheSessionAndTheMachineAndOpensOnCancel() {
        val calls = show(state(listOf(main)))
        button("Stop…").performScrollTo().performClick()
        rule.onNodeWithText(SessionCopy.stopTitle("main")).assertIsDisplayed()
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        rule.onNodeWithText("On Laptop.", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("Session: main")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Machine: Laptop")).assertIsDisplayed()
        assertTrue("nothing is sent before the confirm", calls.stopped.isEmpty())
        button("Cancel").performClick()
        assertTrue(calls.stopped.isEmpty())
        button("Stop…").performScrollTo().performClick()
        button(SessionCopy.STOP_CONFIRM).performClick()
        assertEquals(listOf("main"), calls.stopped)
    }

    @Test fun theDefaultSessionDialogSaysItIsTheDefaultOne() {
        show(state(listOf(SessionRow(entry("default", running = true, default = true)))))
        button("Stop…").performScrollTo().performClick()
        rule.onNodeWithText("This is the default session", substring = true).assertIsDisplayed()
    }

    @Test fun aStoppedSessionDeletesOnlyAfterADialogThatOpensOnCancelAndTheDefaultCannotBeDeleted() {
        val calls = show(state(listOf(stopped, stoppedDefault)))
        // Two stopped rows: the default's Delete is disabled with its reason, the other's is live.
        val deletes = rule.onAllNodesWithTextCompat("Delete…")
        assertEquals(2, deletes.size)
        rule.onNodeWithText(SessionRules.deleteRefusal(stoppedDefault.entry)!!, substring = true).performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Delete…") and hasClickAction())[1].performScrollTo().assertIsNotEnabled()
        rule.onAllNodes(hasText("Delete…") and hasClickAction())[0].performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText(SessionCopy.deleteTitle("work-2")).assertIsDisplayed()
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        button("Cancel").performClick()
        assertTrue(calls.deleted.isEmpty())
        rule.onAllNodes(hasText("Delete…") and hasClickAction())[0].performScrollTo().performClick()
        button(SessionCopy.DELETE_CONFIRM).performClick()
        assertEquals(listOf("work-2"), calls.deleted)
    }

    @Test fun restartIsDisabledAndSaysWhy() {
        show(state(listOf(stopped)))
        button("Restart").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(SessionCopy.RESTART_DISABLED).performScrollTo().assertIsDisplayed()
    }

    @Test fun aStoppedSessionShowsWhatHerdrSavedWithTheFileDateOrSaysItIsUnavailable() {
        show(state(listOf(stopped, SessionRow(entry("odd", running = false), SavedLayoutResult.Unavailable("version 4, only 3 is read")))))
        rule.onNodeWithText("Saved layout: repo, docs · file dated 12:34").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("saved contents unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test fun theListIsLabelledAsThatMomentsReadNotALiveView() {
        show(state(listOf(main)))
        rule.onNodeWithText("List read at 12:34", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("watched by this phone", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun aSessionWithAnUnknownOutcomeOffersOnlyReread() {
        val calls = show(state(listOf(SessionRow(entry("main", running = true), awaitsReread = true))))
        rule.onNodeWithText("The last operation on this session has an unknown outcome", substring = true).performScrollTo().assertIsDisplayed()
        button("Stop…").assertDoesNotExist()
        button("Re-read").performScrollTo().performClick()
        assertEquals(listOf("main"), calls.reread)
    }

    @Test fun aBusySessionCannotBeStoppedAgain() {
        show(state(listOf(SessionRow(entry("main", running = true), busy = true))))
        button("Stop…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aFailedListReadsAsABannerWithTryAgain() {
        val calls = show(state(emptyList(), list = SpacesState.Failed("The host did not answer")))
        rule.onNodeWithText("The host did not answer").assertIsDisplayed()
        button("Try again").performClick()
        assertEquals(1, calls.refreshed)
    }

    @Test fun whenNoAgentCanBeStartedTheReasonReplacesTheButton() {
        show(state(listOf(main), start = StartAvailability.Unavailable("Connect to the machine first.")))
        rule.onNodeWithText("Connect to the machine first.").assertIsDisplayed()
        button("Start an agent…").assertDoesNotExist()
    }

    @Test fun theStartFormNeedsAValidNameAndABranchWhenAWorktreeIsAskedFor() {
        val calls = show(state(listOf(main)))
        button("Start an agent…").performScrollTo().performClick()
        rule.onNodeWithText("Start an agent").assertIsDisplayed()
        button("Start agent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextInput("worker 1")
        button("Start agent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Name").performTextReplacement("worker-1")
        button("Start agent").performScrollTo().assertIsEnabled()
        // A worktree needs a branch, and the branch is checked before anything is sent.
        rule.onNodeWithText("Start in a new worktree").performScrollTo().performClick()
        button("Start agent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Branch").performScrollTo().performTextInput("-bad")
        button("Start agent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Branch").performTextReplacement("feature-x")
        button("Start agent").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(StartForm("w_1", "claude", "worker-1", "feature-x", null)), calls.started)
    }

    @Test fun aRecoveryCardListsEveryCreatedIdAndNothingRunsWithoutATap() {
        val card = SagaCard.of(failed(SagaFailure.START_REFUSED, "agent_pane_busy: the pane runs something else", workspace = "w_9", tab = "t_9", pane = "p_9", terminal = "term_9", branch = "feat-x", worktree = "/home/u/wt/feat-x"), "Laptop")
        val calls = show(state(listOf(main), cards = listOf(card)))
        rule.onNodeWithText(card.title).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("Workspace" to "w_9", "Tab" to "t_9", "Pane" to "p_9", "Worktree" to "/home/u/wt/feat-x"), card.ids)
        for ((k, v) in card.ids) rule.onNode(hasContentDescription("$k: $v")).performScrollTo().assertIsDisplayed()
        assertTrue(calls.cards.isEmpty())
        // Closing the created workspace is destructive: a dialog first, Cancel focused, then the action carries the exact target.
        button("Close the new workspace…").performScrollTo().performClick()
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        assertTrue(calls.cards.isEmpty())
        button("Cancel").performClick()
        button("Close the new workspace…").performScrollTo().performClick()
        button("Close it").performClick()
        assertEquals(1, calls.cards.size)
        assertEquals(CloseTarget.Workspace("w_9"), (calls.cards.single().second as CardAction.CloseCreated).what)
    }

    @Test fun aNameTakenCardLetsTheUserChooseAnotherNameAndValidatesIt() {
        val card = SagaCard.of(failed(SagaFailure.NAME_TAKEN, "agent_name_taken: worker-1", tab = "t_4", pane = "p_4", terminal = "term_4"), "Laptop")
        val calls = show(state(listOf(main), cards = listOf(card)))
        button("Choose another name").performScrollTo().performClick()
        button("Start with this name").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("New name").performScrollTo().performTextInput("bad name!")
        button("Start with this name").performScrollTo().assertIsNotEnabled()
        assertTrue(calls.retried.isEmpty())
    }

    @Test fun aSuccessfulStartIsShownWithAnOpenButton() {
        show(state(listOf(main), progress = SagaProgress("s1", "worker-1", "claude", "Start", done = true, terminalId = "term_7", note = null)))
        rule.onNodeWithText("worker-1 (claude) is running.").performScrollTo().assertIsDisplayed()
        button("Open it").performScrollTo().assertIsEnabled()
    }

    @Test fun aRunningStartNamesTheStepAndTheRecordKeepingRule() {
        show(state(listOf(main), progress = SagaProgress("s1", "worker-1", "claude", "Verify the pane", done = false, terminalId = null, note = null)))
        rule.onNodeWithText("Step: Verify the pane", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun at200PercentFontOnASmallPhoneEverySessionControlStaysReachable() {
        show(state(listOf(main, stopped)), fontScale = 2f)
        button("Stop…").performScrollTo().assertIsDisplayed()
        button("Delete…").performScrollTo().assertIsDisplayed()
        button("Restart").performScrollTo().assertIsDisplayed()
    }

    @Test fun lockedControlsShowTheirPlainLabelWithTheProTagAndAreReadAsLabelThenPro() {
        show(state(listOf(main, stopped), locked = allLocked))
        for (label in listOf("Start an agent…", "Stop…", "Delete…")) {
            rule.onNode(hasContentDescription("$label · Pro") and hasClickAction()).performScrollTo().assertIsDisplayed()
            rule.onNode(hasText(label) and hasText("Pro") and hasClickAction()).assertExists()
            // AC-4: the glyph as well as the tag, inside this button.
            rule.onNode(hasTestTag(PRO_LOCK_TAG) and hasAnyAncestor(hasContentDescription("$label · Pro")), useUnmergedTree = true).assertExists()
        }
        // The " · Pro" suffix is for text rows; no button shows it.
        assertEquals(0, rule.onAllNodes(hasText(" · Pro", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun at200PercentFontLockedControlsStayReachableWithTheirTag() {
        show(state(listOf(main, stopped), locked = allLocked), fontScale = 2f)
        for (label in listOf("Start an agent…", "Stop…", "Delete…")) {
            rule.onNode(hasContentDescription("$label · Pro") and hasClickAction()).performScrollTo().assertIsDisplayed()
            // The tag is never clipped or squeezed out: with no room beside the label it moves, whole, under it.
            rule.onNode(hasText(label) and hasText("Pro") and hasClickAction()).assertExists()
            rule.onNode(hasText("Pro") and hasAnyAncestor(hasContentDescription("$label · Pro")), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun theRowActionsDialogOpensOnCancelAndEachChoiceIsItsOwnButton() {
        var renamed = 0; var ws = 0; var tab = 0; var dismissed = 0
        rule.setContent { PaddockTheme(darkTheme = true) { RowActionsDialog("fix the build", { renamed++ }, { ws++ }, { tab++ }, { dismissed++ }) } }
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        button("Rename agent…").performClick()
        button("Show its workspace on the desktop").performClick()
        button("Show its tab on the desktop").performClick()
        assertEquals(Triple(1, 1, 1), Triple(renamed, ws, tab))
        assertEquals(0, dismissed)
        button("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun theRenameDialogValidatesTheNameAndOffersToClearIt() {
        val got = mutableListOf<String?>()
        rule.setContent { PaddockTheme(darkTheme = true) { RenameDialog("worker-1", { got += it }, {}) } }
        button("Rename").assertIsEnabled()
        button("Clear the name").performClick()
        assertEquals(listOf<String?>(null), got)
        rule.onNodeWithText("Name").performTextInput(" bad")
        button("Rename").assertIsNotEnabled()
    }

    @Test fun theRenameDialogOffersNoClearWhenThereIsNoName() {
        rule.setContent { PaddockTheme(darkTheme = true) { RenameDialog(null, {}, {}) } }
        button("Clear the name").assertDoesNotExist()
        button("Rename").assertIsNotEnabled()
    }

    @Test fun theStartFormReturnsExactlyWhatWasFilledIn() {
        var form: StartForm? = null
        rule.setContent { PaddockTheme(darkTheme = true) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), emptyList(), onCancel = {}, onStart = { form = it }) } }
        rule.onNodeWithText("Name").performTextInput("worker-1")
        button("Start agent").performScrollTo().assertIsEnabled().performClick()
        assertEquals(StartForm("w_1", "claude", "worker-1", null, null), form)
        assertNull(form!!.branch)
    }

    private val openFolders = listOf("/home/u/paddock", "/home/u/notes")

    @Test fun aFolderOfTheUsersOwnNeedsAFullPathAndIsReturnedTrimmed() {
        var form: StartForm? = null
        rule.setContent { PaddockTheme(darkTheme = true) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), openFolders, onCancel = {}, onStart = { form = it }) } }
        rule.onNodeWithText("Name").performTextInput("worker-1")
        // The default is the workspace's own folder, and no path field is drawn for it.
        rule.onNodeWithText("The workspace's folder").performScrollTo().assertIsSelected()
        rule.onNodeWithText("Folder path").assertDoesNotExist()
        rule.onNodeWithText("Another folder on the machine").performScrollTo().performClick().assertIsSelected()
        button("Start agent").performScrollTo().assertIsNotEnabled()
        // A relative path means nothing on the machine: said in words, and Start stays off.
        rule.onNodeWithText("Folder path").performScrollTo().performTextInput("api")
        rule.onNodeWithText("Type the full path, starting with / or ~/ (the home folder).").assertIsDisplayed()
        button("Start agent").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Folder path").performTextReplacement("  ~/projects/api  ")
        button("Start agent").performScrollTo().assertIsEnabled().performClick()
        assertEquals(StartForm("w_1", "claude", "worker-1", null, null, "~/projects/api"), form)
    }

    @Test fun aFolderAlreadyOpenOnTheMachineFillsThePathWithOneTap() {
        var form: StartForm? = null
        rule.setContent { PaddockTheme(darkTheme = true) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), openFolders, onCancel = {}, onStart = { form = it }) } }
        rule.onNodeWithText("Name").performTextInput("worker-1")
        rule.onNodeWithText("Another folder on the machine").performScrollTo().performClick()
        rule.onNodeWithText("Folders open on this machine").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("/home/u/notes") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        // Once it is the typed path it is no longer offered again, and the field holds it.
        rule.onNode(hasText("/home/u/notes") and hasClickAction() and !hasSetTextAction()).assertDoesNotExist()
        rule.onNode(hasText("/home/u/notes") and hasSetTextAction()).assertExists()
        button("Start agent").performScrollTo().assertIsEnabled().performClick()
        assertEquals("/home/u/notes", form!!.folder)
    }

    @Test fun aWorktreeHasNoFolderChoiceAndSendsNoFolder() {
        var form: StartForm? = null
        rule.setContent { PaddockTheme(darkTheme = true) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), openFolders, onCancel = {}, onStart = { form = it }) } }
        rule.onNodeWithText("Name").performTextInput("worker-1")
        rule.onNodeWithText("Another folder on the machine").performScrollTo().performClick()
        rule.onNodeWithText("Folder path").performScrollTo().performTextInput("/srv/api")
        // A worktree is a folder of its own: choosing one drops the folder, and the choice is gone from the form.
        rule.onNodeWithText("Start in a new worktree").performScrollTo().performClick()
        rule.onNodeWithText("Another folder on the machine").assertDoesNotExist()
        rule.onNodeWithText("Branch").performScrollTo().performTextInput("feature-x")
        button("Start agent").performScrollTo().assertIsEnabled().performClick()
        assertEquals(StartForm("w_1", "claude", "worker-1", "feature-x", null, null), form)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCompat(t: String) =
        onAllNodes(hasText(t) and hasClickAction()).fetchSemanticsNodes()

    private fun failed(
        failure: String, message: String, workspace: String? = null, tab: String? = null, pane: String? = null, terminal: String? = null,
        branch: String? = null, worktree: String? = null,
    ) = SagaRecord(
        id = "s_1", host = "laptop", session = "main", agentName = "worker-1", kind = "claude", workspaceId = "w_1", branch = branch, repository = "paddock",
        step = SagaStep.Start, state = SagaState.Failed, failure = failure, message = message,
        createdWorkspaceId = workspace, createdTabId = tab, createdPaneId = pane, createdTerminalId = terminal, worktreePath = worktree,
        startedAt = 1, updatedAt = 2,
    )

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditSpacesDark() { show(state(listOf(main, stopped, stoppedDefault))); SemanticsAudit.expectClean(rule, "Spaces, dark") }
    @Test fun auditSpacesLight() { show(state(listOf(main, stopped, stoppedDefault)), dark = false); SemanticsAudit.expectClean(rule, "Spaces, light") }
    // Locked (D6): the tag's word is measured as drawn on the Primary and Danger buttons, and every locked button keeps its 48 dp and its spoken name.
    @Test fun auditSpacesLockedDark() { show(state(listOf(main, stopped), locked = allLocked)); SemanticsAudit.expectClean(rule, "Spaces locked, dark") }
    @Test fun auditSpacesLockedLight() { show(state(listOf(main, stopped), locked = allLocked), dark = false); SemanticsAudit.expectClean(rule, "Spaces locked, light") }
    @Test fun auditStartFormWithAFolderDark() {
        rule.setContent { PaddockTheme(darkTheme = true) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), openFolders, onCancel = {}, onStart = {}) } }
        rule.onNodeWithText("Another folder on the machine").performScrollTo().performClick()
        rule.onNodeWithText("Folder path").performScrollTo().performTextInput("api")
        // The keyboard is not part of the screen's own drawing, and the audit samples pixels of the window.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        SemanticsAudit.expectClean(rule, "Start form with a folder, dark")
    }
    @Test fun auditStartFormWithAFolderLight() {
        rule.setContent { PaddockTheme(darkTheme = false) { StartAgentForm(listOf(WorkspaceChoice("w_1", "paddock")), openFolders, onCancel = {}, onStart = {}) } }
        rule.onNodeWithText("Another folder on the machine").performScrollTo().performClick()
        SemanticsAudit.expectClean(rule, "Start form with a folder, light")
    }
    @Test fun auditRowActionsDialog() { rule.setContent { PaddockTheme(darkTheme = true) { RowActionsDialog("fix the build", {}, {}, {}, {}) } }; SemanticsAudit.expectClean(rule, "Row actions dialog, dark", SemanticsAudit.Options(heading = false)) }
    @Test fun auditRenameDialog() { rule.setContent { PaddockTheme(darkTheme = true) { RenameDialog("worker-1", {}, {}) } }; SemanticsAudit.expectClean(rule, "Rename dialog, dark", SemanticsAudit.Options(heading = false)) }

}
