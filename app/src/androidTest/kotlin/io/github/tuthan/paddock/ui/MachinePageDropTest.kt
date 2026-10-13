package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.ui.screens.DropFilesUi
import io.github.tuthan.paddock.ui.screens.DropFolderActions
import io.github.tuthan.paddock.ui.screens.DropFolderCopy
import io.github.tuthan.paddock.ui.screens.DropFolderUi
import io.github.tuthan.paddock.ui.screens.DropRowUi
import io.github.tuthan.paddock.ui.screens.DropTold
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.WakeWords
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Files from this phone go to" on a machine's page (Phase 15, slice 11, X10): the saved folder, the newest files, and the sheet that asks the machine what a value
 * is before it is saved. The page is stateless, so the machine's answers are given here; what `:core`'s [io.github.tuthan.paddock.files.DropFolderCheck] says is its own test.
 */
class MachinePageDropTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val alpha = HostProfile("alpha", "Alpha", "192.168.42.86", 22, "jdoe")
    private val row = MachineRow(alpha.id, alpha.name, "jdoe@192.168.42.86:22", null, watched = true, returnFree = false)

    private class Calls { val checks = mutableListOf<String>(); val saves = mutableListOf<String?>(); var reReads = 0; var closes = 0; var edits = 0 }

    private var drop by mutableStateOf(DropFolderUi("~/Paddock", isDefault = true, askable = true))

    private fun show(calls: Calls = Calls(), dark: Boolean = true, fontScale: Float? = null): Calls {
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides if (fontScale != null) androidx.compose.ui.unit.Density(base.density, fontScale) else base,
            ) {
                PaddockTheme(darkTheme = dark) {
                    MachinePage(
                        MachinePageState(row, "Watching · live", switchLocked = false, wake = WakeWords("Not read yet"), alerts = "No alerts", profile = alpha, drop = drop),
                        onBack = {}, onWatch = {}, onRemove = {}, onAlertRelay = {}, onCopyWakeCommand = {}, onSaveWakeRelay = {}, onWake = {},
                        dropActions = DropFolderActions(
                            onReRead = { calls.reReads++ }, onEdit = { calls.edits++; drop = drop.copy(editing = true, told = null) },
                            onCloseEdit = { calls.closes++; drop = drop.copy(editing = false, told = null) },
                            onCheck = { calls.checks += it },
                            onSave = { calls.saves += it; drop = drop.copy(editing = false) },
                        ),
                    )
                }
            }
        }
        return calls
    }

    private fun field() = rule.onNode(hasSetTextAction() and hasText(DropFolderCopy.FIELD))
    private fun save() = rule.onNodeWithText(DropFolderCopy.SAVE).performScrollTo()
    private fun check() = rule.onNodeWithText(DropFolderCopy.CHECK).performScrollTo()

    @Test fun theSectionNamesTheSavedFolderAndSaysWhyTheMachineJudgesIt() {
        show()
        rule.onNodeWithText(DropFolderCopy.KICKER.uppercase()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("drop-folder-value").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("~/Paddock (${DropFolderCopy.DEFAULT_TAG})").assertExists()
        rule.onNodeWithText(DropFolderCopy.NOTE, substring = true).assertExists()
        rule.onNodeWithText("Change…").performScrollTo().assertIsEnabled()
    }

    @Test fun aMachineThatIsNotWatchedCannotBeAskedAndSaysToWatchItFirst() {
        drop = drop.copy(askable = false)
        show()
        rule.onNodeWithText(DropFolderCopy.WATCH_FIRST, substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Change…").assertDoesNotExist()
        rule.onNodeWithText("Re-read").assertDoesNotExist()
    }

    @Test fun aSavedNonDefaultFolderIsShownAsTypedWithoutTheDefaultTag() {
        drop = DropFolderUi("~/Drop/phone", isDefault = false, askable = true)
        show()
        rule.onNodeWithText("~/Drop/phone").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("~/Drop/phone (${DropFolderCopy.DEFAULT_TAG})").assertDoesNotExist()
    }

    @Test fun theNewestFilesSitUnderTheFolderWithWhenTheListWasRead() {
        drop = drop.copy(files = DropFilesUi.Listed(listOf(DropRowUi("shot.png", "2 KB · changed 3 min ago"), DropRowUi("notes.txt", "5 B · changed 1 h ago")), 0, "/home/jdoe/Paddock", exists = true, readAtTime = "14:02:11"))
        show()
        rule.onNodeWithText(DropFolderCopy.listRead("14:02:11")).performScrollTo().assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithTag("drop-file-row").fetchSemanticsNodes().size)
        rule.onNodeWithText("shot.png").assertExists()
        rule.onNodeWithText(DropFolderCopy.MORE).assertDoesNotExist()
    }

    @Test fun moreFilesThanAreListedAreSaidNotCounted() {
        drop = drop.copy(files = DropFilesUi.Listed(List(10) { DropRowUi("f$it.txt", "1 B · changed now") }, 7, "/home/jdoe/Paddock", exists = true, readAtTime = "14:02:11"))
        show()
        rule.onNodeWithText(DropFolderCopy.MORE).performScrollTo().assertIsDisplayed()
    }

    @Test fun aFolderThatIsNotThereYetSaysTheFirstFileCreatesIt() {
        drop = drop.copy(files = DropFilesUi.Listed(emptyList(), 0, "/home/jdoe/Paddock", exists = false, readAtTime = "14:02:11"))
        show()
        rule.onNodeWithText(DropFolderCopy.notThereYet("/home/jdoe/Paddock")).performScrollTo().assertIsDisplayed()
    }

    @Test fun anEmptyFolderAndAFailedReadAreSaidInWords() {
        drop = drop.copy(files = DropFilesUi.Listed(emptyList(), 0, "/home/jdoe/Paddock", exists = true, readAtTime = "14:02:11"))
        show()
        rule.onNodeWithText(DropFolderCopy.EMPTY).performScrollTo().assertIsDisplayed()
        drop = drop.copy(files = DropFilesUi.Failed("Could not ask the machine: the link was lost."))
        rule.onNodeWithText("Could not ask the machine: the link was lost.").performScrollTo().assertIsDisplayed()
        drop = drop.copy(files = DropFilesUi.Reading)
        rule.onNodeWithText("Reading the folder…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Re-read").assertDoesNotExist()
    }

    @Test fun reReadAsksTheCallerOnce() {
        val calls = show()
        rule.onNodeWithText("Re-read").performScrollTo().performClick()
        assertEquals(1, calls.reReads)
    }

    // ---- the sheet ----

    @Test fun theSheetStartsAtTheSavedFolderAndSaveIsOffUntilTheMachineHasBeenAsked() {
        val calls = show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        assertEquals(1, calls.edits)
        rule.onNodeWithText(DropFolderCopy.EDIT_TITLE).assertIsDisplayed()
        field().assertExists().assert(hasText("~/Paddock"))
        save().assertIsNotEnabled()
        check().assertIsEnabled()
        assertTrue(calls.saves.isEmpty())
    }

    @Test fun checkSendsTheTrimmedValueAndSaveWaitsForAnOkAnswerAboutThatValue() {
        val calls = show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        field().performTextClearance(); field().performTextInput("  ~/Drop/phone ")
        check().performClick()
        assertEquals(listOf("~/Drop/phone"), calls.checks)
        save().assertIsNotEnabled()

        drop = drop.copy(told = DropTold("~/Drop/phone", "~/Drop/phone is /home/jdoe/Drop/phone on this machine and will be created when the first file arrives.", ok = true))
        rule.onNodeWithTag("drop-told").assertIsDisplayed()
        save().assertIsEnabled()

        // An edit after the answer makes the answer about another value: Save goes off until the machine is asked again.
        field().performTextInput("x")
        save().assertIsNotEnabled()
        rule.onNodeWithTag("drop-told").assertDoesNotExist()
        field().performTextClearance(); field().performTextInput("~/Drop/phone")
        save().assertIsEnabled()
        save().performClick()
        assertEquals(listOf<String?>("~/Drop/phone"), calls.saves)
        rule.onNodeWithText(DropFolderCopy.EDIT_TITLE).assertDoesNotExist()
    }

    @Test fun theMachinesRefusalIsTheSentenceUnderTheFieldAndSaveStaysOff() {
        show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        field().performTextClearance(); field().performTextInput("~")
        check().performClick()
        drop = drop.copy(told = DropTold("~", "That is the whole home folder, which Paddock does not write files into.", ok = false))
        rule.onNodeWithText("That is the whole home folder", substring = true).assertExists()
        save().assertIsNotEnabled()
    }

    @Test fun aValueThePhoneRefusesItselfIsNeverSentToTheMachine() {
        val calls = show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        for (bad in listOf("Paddock", "~other/x", "relative/path")) {
            field().performTextClearance(); field().performTextInput(bad)
            rule.onNodeWithText("Start with ~/ or /", substring = true).assertExists()
            check().assertIsNotEnabled()
            save().assertIsNotEnabled()
        }
        assertTrue(calls.checks.isEmpty())
    }

    @Test fun anEmptyFieldSavesTheDefaultWithoutAsking() {
        val calls = show()
        drop = DropFolderUi("~/Drop/phone", isDefault = false, askable = true)
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        field().performTextClearance()
        save().assertIsEnabled().performClick()
        assertEquals(listOf<String?>(null), calls.saves)
        assertTrue(calls.checks.isEmpty())
    }

    @Test fun useTheDefaultPutsTheDefaultInTheField() {
        drop = DropFolderUi("~/Drop/phone", isDefault = false, askable = true)
        show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        rule.onNodeWithText(DropFolderCopy.USE_DEFAULT).performScrollTo().performClick()
        field().assert(hasText("~/Paddock"))
    }

    @Test fun cancelClosesTheSheetAndSavesNothing() {
        val calls = show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, calls.closes)
        assertTrue(calls.saves.isEmpty())
        rule.onNodeWithText(DropFolderCopy.EDIT_TITLE).assertDoesNotExist()
    }

    /**
     * The app lets the connection go a few seconds after it leaves the front. The sheet stays open through that: the text is kept, Check waits and says why, and it
     * can be asked once the machine is back. (It used to close, and what was typed went with it.)
     */
    @Test fun theSheetKeepsItsTextWhileTheMachineIsNotConnectedAndChecksOnceItIsBack() {
        val calls = show()
        rule.onNodeWithText("Change…").performScrollTo().performClick()
        field().performTextClearance(); field().performTextInput("~/Drop/phone")
        drop = drop.copy(askable = false)
        rule.waitForIdle()
        field().assert(hasText("~/Drop/phone"))
        rule.onNodeWithText(DropFolderCopy.NOT_CONNECTED).assertIsDisplayed()
        check().assertIsNotEnabled()
        drop = drop.copy(askable = true)
        rule.waitForIdle()
        field().assert(hasText("~/Drop/phone"))
        check().assertIsEnabled().performClick()
        assertEquals(listOf("~/Drop/phone"), calls.checks)
    }

    @Test fun whileTheMachineIsBeingAskedNothingCanBeChecked() {
        drop = drop.copy(editing = true, asking = true)
        show()
        rule.onNodeWithText(DropFolderCopy.CHECKING).assertIsDisplayed()
        check().assertIsNotEnabled()
        save().assertIsNotEnabled()
    }

    // ---- audits ----

    @Test fun auditSectionDark() {
        drop = drop.copy(files = DropFilesUi.Listed(listOf(DropRowUi("shot.png", "2 KB · changed 3 min ago")), 0, "/home/jdoe/Paddock", true, "14:02:11"))
        show(); SemanticsAudit.expectClean(rule, "Machine page, drop folder, dark")
    }
    @Test fun auditSectionLight() { show(dark = false); SemanticsAudit.expectClean(rule, "Machine page, drop folder, light") }
    @Test fun auditSectionAt200Percent() { show(fontScale = 2f); SemanticsAudit.expectClean(rule, "Machine page, drop folder, 200%") }
    @Test fun auditSectionNotAskable() { drop = drop.copy(askable = false); show(); SemanticsAudit.expectClean(rule, "Machine page, drop folder, not watched") }

}
