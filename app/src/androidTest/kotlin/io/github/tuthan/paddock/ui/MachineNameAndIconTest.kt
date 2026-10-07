package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.tuthan.paddock.hostprofile.HostOs
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.ui.screens.MachineCard
import io.github.tuthan.paddock.ui.screens.MachineListState
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.MachinesScreen
import io.github.tuthan.paddock.ui.screens.WakeWords
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The machine's name is the user's label for it, and the icon is what the machine said or what the user picked. */
class MachineNameAndIconTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Taps { val renamed = mutableListOf<String>(); val os = mutableListOf<HostOs?>() }

    private fun row(os: HostOs? = null) = MachineRow("alpha", "Alpha", "u@alpha:22", null, watched = true, returnFree = false, os = os)

    private fun page(os: HostOs? = null, chosen: HostOs? = null, detected: HostOs? = null, others: List<String> = listOf("Beta"), dark: Boolean = true, busy: Boolean = false): Taps {
        val taps = Taps()
        rule.setContent {
            PaddockTheme(darkTheme = dark) {
                MachinePage(
                    MachinePageState(row(os), "Watching · live", switchLocked = false, wake = WakeWords("Not read yet"), alerts = "No alerts", otherNames = others, chosenOs = chosen, detectedOs = detected, busy = busy),
                    onBack = {}, onWatch = {}, onRemove = {}, onAlertRelay = {}, onCopyWakeCommand = {}, onSaveWakeRelay = {}, onWake = {},
                    onRename = { taps.renamed += it }, onSetOs = { taps.os += it },
                )
            }
        }
        return taps
    }

    // The page has a second text field (the wake relay), so the name field is the one with its label.
    private fun field() = rule.onNode(hasSetTextAction() and hasText("Name on this phone"))

    @Test fun theFieldStartsAtTheNameAndSaveIsOffUntilItChanges() {
        page()
        rule.onNodeWithText("Name on this phone").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save name").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("It is only a label on this phone", substring = true).assertExists()
    }

    @Test fun aNewNameIsSavedTrimmedAndOnlyOnTheTap() {
        val taps = page()
        field().performScrollTo().performTextClearance(); field().performTextInput("  Build box ")
        rule.onNodeWithText("Save name").performScrollTo().assertIsEnabled()
        assertEquals(emptyList<String>(), taps.renamed)
        rule.onNodeWithText("Save name").performClick()
        assertEquals(listOf("Build box"), taps.renamed)
    }

    @Test fun aNameThatIsEmptyOrTakenIsRefusedInWordsAndCannotBeSaved() {
        page()
        field().performScrollTo().performTextClearance()
        rule.onNodeWithText("Give the machine a name.").assertIsDisplayed(); rule.onNodeWithText("Save name").performScrollTo().assertIsNotEnabled()
        field().performTextInput("beta")
        rule.onNodeWithText("Another machine already has that name.").assertIsDisplayed(); rule.onNodeWithText("Save name").performScrollTo().assertIsNotEnabled()
    }

    @Test fun whileARemovalRunsTheNameCannotBeSaved() {
        page(busy = true)
        field().performScrollTo().performTextClearance(); field().performTextInput("Other")
        rule.onNodeWithText("Save name").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theIconSaysWhereItCameFromAndEachChoiceReportsItself() {
        val taps = page(os = HostOs.Mac, detected = HostOs.Mac)
        rule.onNodeWithText("macOS (read from the machine)").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("macOS machine")).assertExists()
        rule.onNodeWithText("Windows").performScrollTo().performClick()
        rule.onNodeWithText("Automatic (macOS)").performScrollTo().performClick()
        rule.onNodeWithText("Linux").performScrollTo().performClick()
        assertEquals(listOf<HostOs?>(HostOs.Windows, null, HostOs.Linux), taps.os)
    }

    @Test fun aPickIsCalledAPickAndAMachineWithNoGlyphSaysItIsNotKnown() {
        page(os = HostOs.Linux, chosen = HostOs.Linux)
        rule.onNodeWithText("Linux (your pick)").performScrollTo().assertIsDisplayed()
    }

    @Test fun aMachineWithoutAKnownKindDrawsNoGlyphAndSaysSo() {
        page()
        rule.onNodeWithText("Not known yet").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Linux machine")).assertDoesNotExist()
        rule.onNode(hasContentDescription("macOS machine")).assertDoesNotExist()
        rule.onNode(hasContentDescription("Windows machine")).assertDoesNotExist()
    }

    @Test fun theCardInTheListCarriesTheGlyphOfItsMachine() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                MachinesScreen(
                    MachineListState(listOf(MachineCard(row(HostOs.Linux), "Watching · live"), MachineCard(MachineRow("beta", "Beta", "u@beta:22", null, false, false, HostOs.Windows), "Not watched")), switchLocked = false),
                    onBack = {}, onOpen = {}, onWatch = {}, onAdd = {},
                )
            }
        }
        rule.onNode(hasContentDescription("Linux machine")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Windows machine")).assertIsDisplayed()
    }

    @Test fun auditMachinePageWithIconDark() { page(os = HostOs.Linux, detected = HostOs.Linux); SemanticsAudit.expectClean(rule, "Machine page, name and icon, dark") }
    @Test fun auditMachinePageWithIconLight() { page(os = HostOs.Mac, chosen = HostOs.Mac, dark = false); SemanticsAudit.expectClean(rule, "Machine page, name and icon, light") }
}
