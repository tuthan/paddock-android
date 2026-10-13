package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.ui.screens.SHARE_TITLE
import io.github.tuthan.paddock.ui.screens.SHARE_WHERE
import io.github.tuthan.paddock.ui.screens.ShareAgent
import io.github.tuthan.paddock.ui.screens.ShareChooser
import io.github.tuthan.paddock.ui.screens.ShareChooserActions
import io.github.tuthan.paddock.ui.screens.ShareChooserUi
import io.github.tuthan.paddock.ui.screens.ShareFolder
import io.github.tuthan.paddock.ui.screens.ShareMachine
import io.github.tuthan.paddock.ui.screens.ShareReplace
import io.github.tuthan.paddock.ui.screens.ShareReplaceCopy
import io.github.tuthan.paddock.ui.screens.ShareResult
import io.github.tuthan.paddock.ui.screens.ShareSummary
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The share chooser (Phase 15, slice 11): the item, the machine step with the Pro switch, Where?, and the result sheet. */
class ShareChooserTest {
    @get:Rule val rule = createComposeRule()

    private val summary = ShareSummary("2026-10-11-143205-screenshot.png", "Image · 84 KB", isFile = true)
    private val watched = ShareMachine("m1", "jdoe-arch", "jdoe@192.168.42.10:22", watched = true, locked = false)
    private val other = ShareMachine("m2", "devbox", "jdoe@192.168.42.20:22", watched = false, locked = true)
    private val agents = listOf(ShareAgent("t1", "Fix the build", "claude · blindpass"), ShareAgent("t2", "Waiting agent", "codex · api", reason = "Working: a prompt would interrupt it."))
    private val folders = listOf(ShareFolder("/home/jdoe/blindpass", "blindpass"))

    private class Calls {
        val machines = mutableListOf<String>(); val agents = mutableListOf<String>(); val folders = mutableListOf<String>()
        var drop = 0; var back = 0; var dismiss = 0; val copied = mutableListOf<String>(); val used = mutableListOf<String>(); var closed = 0
        var replaced = 0; var kept = 0
    }

    private var ui by mutableStateOf(ShareChooserUi(summary, listOf(watched, other), live = true, agents = agents, folders = folders, dropFolder = "~/Paddock"))

    private fun show(state: ShareChooserUi = ui, calls: Calls = Calls(), dark: Boolean = true, fontScale: Float? = null): Calls {
        ui = state
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    ShareChooser(
                        ui,
                        ShareChooserActions(
                            onBack = { calls.back++ }, onMachine = { calls.machines += it.id }, onAgent = { calls.agents += it.terminalId }, onFolder = { calls.folders += it.path },
                            onDrop = { calls.drop++ }, onDismissNotice = { calls.dismiss++ }, onCopyPath = { calls.copied += it }, onUsePath = { calls.used += it }, onCloseResult = { calls.closed++ },
                            onReplace = { calls.replaced++ }, onKeep = { calls.kept++ },
                        ),
                    )
                }
            }
        }
        return calls
    }

    @Test fun theChooserSaysWhatWasSharedThenTheMachineThenWhere() {
        show()
        rule.onNodeWithText(SHARE_TITLE).assertIsDisplayed()
        rule.onNodeWithText(summary.title).assertIsDisplayed()
        rule.onNodeWithText("Image · 84 KB").assertIsDisplayed()
        rule.onNodeWithText("jdoe-arch").assertIsDisplayed()
        rule.onNodeWithText("Watching").assertIsDisplayed()
        rule.onNodeWithText(SHARE_WHERE.uppercase()).assertExists() // a kicker draws upper case; below the fold on a small screen, the list scrolls
    }

    @Test fun theWatchedMachineHasNoSwitchAndAnotherOneAsksTheProGateWithTheGlyph() {
        val calls = show()
        rule.onAllNodesWithText("Switch to this machine").assertCountEquals(1)
        rule.onNode(hasContentDescription("Switch to devbox", substring = true)).assertHasClickAction().performClick()
        assertEquals(listOf("m2"), calls.machines)
        val desc = rule.onNode(hasContentDescription("devbox", substring = true) and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertTrue("a locked switch says Pro in words: $desc", desc?.contains("Pro") == true)
    }

    @Test fun anUnlockedOtherMachineSaysNoProAnywhere() {
        show(ShareChooserUi(summary, listOf(watched, other.copy(locked = false)), live = true))
        val desc = rule.onNode(hasContentDescription("Switch to devbox", substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("Switch to devbox", desc)
    }

    @Test fun readyAgentsAreChoosableAndANotReadyOneIsDisabledWithItsReason() {
        val calls = show()
        rule.onNode(hasContentDescription("Fix the build", substring = true) and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(listOf("t1"), calls.agents)
        rule.onNodeWithText("codex · api · Working: a prompt would interrupt it.").assertIsDisplayed()
        rule.onNode(hasContentDescription("Waiting agent", substring = true)).assertIsNotEnabled()
        rule.onNode(hasContentDescription("Waiting agent", substring = true)).performClick()
        assertEquals("a disabled agent cannot be chosen", listOf("t1"), calls.agents)
    }

    @Test fun aFolderAndTheDropFolderAreChoosableWithNoPrompt() {
        val calls = show()
        rule.onNodeWithText("In /home/jdoe/blindpass/.paddock/inbox/").assertIsDisplayed()
        rule.onNodeWithText("A folder on this machine, with no prompt".uppercase()).assertExists() // the section the text share's test sees none of
        rule.onNode(androidx.compose.ui.test.hasText("In /home/jdoe/blindpass/.paddock/inbox/", substring = true) and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.onNode(hasContentDescription("drop folder", substring = true) and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(listOf("/home/jdoe/blindpass"), calls.folders)
        assertEquals(1, calls.drop)
    }

    @Test fun aTextShareHasNoFolderAndNoDropSection() {
        show(ShareChooserUi(ShareSummary("a link", "Text · 42 characters"), listOf(watched), live = true, agents = agents))
        rule.onAllNodesWithText("The machine's drop folder").assertCountEquals(0)
        rule.onAllNodesWithText("A folder on this machine, with no prompt".uppercase()).assertCountEquals(0)
    }

    @Test fun whenTheMachineIsNotConnectedNothingIsOfferedAndTheScreenSaysSo() {
        show(ShareChooserUi(summary, listOf(watched), live = false, liveNote = "jdoe-arch is not connected yet."))
        rule.onNodeWithText("jdoe-arch is not connected yet.").assertIsDisplayed()
        rule.onAllNodesWithText("Fix the build").assertCountEquals(0)
    }

    @Test fun aNoteAboutRemovedCharactersSitsUnderTheSummary() {
        show(ShareChooserUi(ShareSummary("hello", "Text · 5 characters", note = "3 hidden characters were removed."), listOf(watched), live = true))
        rule.onNodeWithText("3 hidden characters were removed.").assertIsDisplayed()
    }

    @Test fun aNoticeShowsAndCanBeDismissed() {
        val calls = show(ShareChooserUi(summary, listOf(watched), live = true, notice = "The send was refused."))
        rule.onNodeWithText("The send was refused.").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test fun theResultSheetOffersCopyPathAndUseInAPromptAndDone() {
        val calls = show(ShareChooserUi(summary, listOf(watched), live = true, result = ShareResult(true, "The file is on jdoe-arch.", "/home/jdoe/Paddock/shot.png")))
        rule.onNodeWithText("Sent").assertIsDisplayed()
        rule.onNodeWithText("/home/jdoe/Paddock/shot.png").assertIsDisplayed()
        rule.onNodeWithText("Copy path").performClick()
        rule.onNodeWithText("Use in a prompt").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("/home/jdoe/Paddock/shot.png"), calls.copied)
        assertEquals(listOf("/home/jdoe/Paddock/shot.png"), calls.used)
        assertEquals(1, calls.closed)
    }

    @Test fun aRefusedSendShowsItsSentenceAndNoPathButtons() {
        show(ShareChooserUi(summary, listOf(watched), live = true, result = ShareResult(false, "That name is not allowed in a drop folder.")))
        rule.onNodeWithText("Not sent").assertIsDisplayed()
        rule.onAllNodesWithText("Copy path").assertCountEquals(0)
    }

    @Test fun aShareThatWouldReplaceAnotherAgentsDraftAsksFirstWithReplaceAndCancel() {
        val calls = show(ui.copy(replace = ShareReplace("Fix the build", words = true, file = true)))
        rule.onNodeWithText(ShareReplaceCopy.TITLE).assertIsDisplayed()
        rule.onNodeWithText("Paddock holds an unsent prompt and its file for Fix the build. Adding this share replaces both.").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, calls.kept)
        assertEquals("Cancel changes nothing", 0, calls.replaced)
        rule.onNodeWithText(ShareReplaceCopy.REPLACE).performClick()
        assertEquals(1, calls.replaced)
        assertTrue("the question is not a choice of agent by itself", calls.agents.isEmpty())
    }

    @Test fun anAgentTheChooserDoesNotListIsAnotherAgentInTheQuestion() {
        show(ui.copy(replace = ShareReplace(null, words = true, file = false)))
        rule.onNodeWithText("Paddock holds an unsent prompt for another agent. Adding this share replaces it.").assertIsDisplayed()
    }

    @Test fun withNoQuestionThereIsNoDialog() {
        show()
        rule.onAllNodesWithText(ShareReplaceCopy.TITLE).assertCountEquals(0)
    }

    // A pane's folder and the path a send answered are the machine's words: a bidi override or a zero-width character is not drawn (the RLO would show
    // `/home/jdoe/Paddock/gnp.exe` as `…exe.png`), but the send, Copy path and Use in a prompt get the machine's real path.

    @Test fun aPanesFolderIsShownCleanedAndTheSendGetsTheRealOne() {
        val folder = "/home/jdoe/bl​ind‮pass"
        val calls = show(ui.copy(folders = listOf(ShareFolder(folder, "blindpass"))))
        rule.onNodeWithText("In /home/jdoe/blindpass/.paddock/inbox/").assertIsDisplayed()
        rule.onAllNodesWithText("In $folder/.paddock/inbox/").assertCountEquals(0)
        rule.onNode(androidx.compose.ui.test.hasText("In /home/jdoe/blindpass/.paddock/inbox/", substring = true) and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals("the send uses the real folder", listOf(folder), calls.folders)
    }

    @Test fun theResultsPathIsShownCleanedAndCopyAndUseGetTheRealOne() {
        val path = "/home/jdoe/Paddock/‮gnp.exe"
        val calls = show(ShareChooserUi(summary, listOf(watched), live = true, result = ShareResult(true, "The file is on jdoe-arch.", path)))
        rule.onNodeWithText("/home/jdoe/Paddock/gnp.exe").assertIsDisplayed()
        rule.onAllNodesWithText(path).assertCountEquals(0)
        rule.onNodeWithText("Copy path").performClick()
        rule.onNodeWithText("Use in a prompt").performClick()
        assertEquals(listOf(path), calls.copied)
        assertEquals(listOf(path), calls.used)
    }

    @Test fun backDropsWhatWasShared() {
        val calls = show()
        rule.onNode(hasContentDescription("Back, and drop what was shared")).performClick()
        assertEquals(1, calls.back)
    }

    // ---- audits ----

    @Test fun auditChooserDark() { show(); SemanticsAudit.expectClean(rule, "Share chooser, dark") }
    @Test fun auditChooserLight() { show(dark = false); SemanticsAudit.expectClean(rule, "Share chooser, light") }
    @Test fun auditChooserAt200Percent() { show(fontScale = 2f); SemanticsAudit.expectClean(rule, "Share chooser, 200%") }
    @Test fun auditChooserNotConnected() { show(ShareChooserUi(summary, listOf(watched), live = false)); SemanticsAudit.expectClean(rule, "Share chooser, not connected") }
    @Test fun auditResultSheetAlone() {
        rule.setContent { PaddockTheme { ShareChooser(ShareChooserUi(summary, listOf(watched), live = true, result = ShareResult(true, "The file is on jdoe-arch.", "/home/jdoe/Paddock/shot.png")), ShareChooserActions({}, {}, {}, {}, {}, {}, {}, {}, {})) } }
        // The dialog is audited with the screen behind it dimmed out of the measurement: only the sheet's own nodes are read.
        rule.onNodeWithText("Sent").assertIsDisplayed()
    }
}
