package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.ui.screens.MachineAddressDialog
import io.github.tuthan.paddock.ui.screens.MachinePage
import io.github.tuthan.paddock.ui.screens.MachinePageState
import io.github.tuthan.paddock.ui.screens.WakeWords
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A machine whose IP address changed is moved on its page: the dialog starts at the saved address, says why a move is safe, and saves only on the tap. */
class MachineAddressDialogTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val alpha = HostProfile("alpha", "Alpha", "192.168.42.86", 22, "jdoe")
    private val beta = HostProfile("beta", "Beta", "192.168.42.90", 22, "jdoe")

    /** What the network-search switch was set to, in order. */
    private val searches = mutableListOf<Boolean>()

    private fun page(withProfile: Boolean = true, busy: Boolean = false, dark: Boolean = true, searchNetwork: Boolean = false): MutableList<Pair<String, Int>> {
        val moves = mutableListOf<Pair<String, Int>>()
        val row = MachineRow(alpha.id, alpha.name, "jdoe@192.168.42.86:22", null, watched = true, returnFree = false)
        rule.setContent {
            PaddockTheme(darkTheme = dark) {
                MachinePage(
                    MachinePageState(
                        row, "Watching · live", switchLocked = false, wake = WakeWords("Not read yet"), alerts = "No alerts", busy = busy,
                        profile = alpha.copy(searchNetwork = searchNetwork).takeIf { withProfile }, otherProfiles = listOf(beta),
                    ),
                    onBack = {}, onWatch = {}, onRemove = {}, onAlertRelay = {}, onCopyWakeCommand = {}, onSaveWakeRelay = {}, onWake = {},
                    onChangeAddress = { host, port -> moves += host to port }, onSetSearchNetwork = { searches += it },
                )
            }
        }
        return moves
    }

    private fun open() = rule.onNodeWithText("Change address…").performScrollTo().performClick()
    private fun host() = rule.onNode(hasSetTextAction() and hasText("Host or IP address"))
    private fun port() = rule.onNode(hasSetTextAction() and hasText("Port"))
    private fun save() = rule.onNodeWithText("Save address").performScrollTo()

    @Test fun thePageOffersTheMoveByNameAndTheDialogStartsAtTheSavedAddress() {
        page()
        rule.onNode(hasContentDescription("Change the address of Alpha…")).performScrollTo().assertIsDisplayed()
        open()
        rule.onNodeWithText("Change the address of Alpha").assertIsDisplayed()
        host().assertExists().assert(hasText("192.168.42.86"))
        port().assertExists().assert(hasText("22"))
        rule.onNodeWithText("checks that the machine at the new address shows the same host key", substring = true).assertExists()
        rule.onNodeWithText("DHCP reservation", substring = true).assertExists()
        save().assertIsNotEnabled()
    }

    @Test fun aNewAddressIsSavedTrimmedAndOnlyOnTheTapAndTheDialogCloses() {
        val moves = page()
        open()
        host().performTextClearance(); host().performTextInput("  devbox.local ")
        port().performTextClearance(); port().performTextInput("2222")
        save().assertIsEnabled()
        assertEquals(emptyList<Pair<String, Int>>(), moves)
        save().performClick()
        assertEquals(listOf("devbox.local" to 2222), moves)
        rule.onNodeWithText("Change the address of Alpha").assertDoesNotExist()
    }

    @Test fun anotherMachinesAddressAndABadPortAreRefusedInWords() {
        page()
        open()
        host().performTextClearance(); host().performTextInput("192.168.42.90")
        rule.onNodeWithText("Beta already has this address.", substring = true).assertIsDisplayed()
        save().assertIsNotEnabled()
        host().performTextClearance(); host().performTextInput("192.168.42.120")
        port().performTextClearance(); port().performTextInput("70000")
        rule.onNodeWithText("Enter a port from 1 to 65535.").performScrollTo().assertIsDisplayed()
        save().assertIsNotEnabled()
    }

    @Test fun cancelChangesNothing() {
        val moves = page()
        open()
        host().performTextClearance(); host().performTextInput("192.168.42.120")
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText("Change the address of Alpha").assertDoesNotExist()
        assertEquals(emptyList<Pair<String, Int>>(), moves)
    }

    @Test fun whileARemovalRunsTheMachineCannotBeMoved() {
        page(busy = true)
        rule.onNodeWithText("Change address…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theHostnameTheMachineSaidIsShownOnlyOnceItIsKnown() {
        page()
        // A Fact is one node read as "<label>: <value>" (its label is drawn in capitals).
        rule.onNode(hasContentDescription("Hostname", substring = true)).assertDoesNotExist()
    }

    @Test fun aKnownHostnameIsShownWithTheAddress() {
        val row = MachineRow(alpha.id, alpha.name, "jdoe@192.168.42.86:22", null, watched = true, returnFree = false)
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                MachinePage(
                    MachinePageState(row, "Watching · live", switchLocked = false, wake = WakeWords("Not read yet"), alerts = "No alerts", profile = alpha.copy(detectedName = "labbox")),
                    onBack = {}, onWatch = {}, onRemove = {}, onAlertRelay = {}, onCopyWakeCommand = {}, onSaveWakeRelay = {}, onWake = {},
                )
            }
        }
        rule.onNode(hasContentDescription("Hostname: labbox")).assertIsDisplayed()
    }

    @Test fun aPageWithoutItsProfileHasNoMove() {
        page(withProfile = false)
        rule.onNodeWithText("Change address…").assertDoesNotExist()
        rule.onNodeWithText(MachineCopy.SEARCH_LABEL).assertDoesNotExist()
    }

    private fun searchSwitch() = rule.onNode(hasText(MachineCopy.SEARCH_LABEL) and isToggleable())

    @Test fun theNetworkSearchIsOffUntilTheUserTurnsItOnAndTheRowSaysWhatItDoes() {
        page()
        searchSwitch().performScrollTo().assertIsDisplayed().assertIsOff()
        rule.onNodeWithText(MachineCopy.SEARCH_KICKER, ignoreCase = true).assertExists()
        rule.onNodeWithText("never signs in", substring = true).assertExists()
        assertEquals(emptyList<Boolean>(), searches)
        searchSwitch().performClick()
        assertEquals(listOf(true), searches)
    }

    @Test fun aSwitchedOnSearchShowsOnAndATapTurnsItOff() {
        page(searchNetwork = true)
        searchSwitch().performScrollTo().assertIsOn()
        searchSwitch().performClick()
        assertEquals(listOf(false), searches)
    }

    @Test fun whileARemovalRunsTheSearchCannotBeChanged() {
        page(busy = true)
        searchSwitch().performScrollTo().assertIsNotEnabled()
    }

    // The dialog alone, as the other dialogs' audits draw theirs: with the page's window behind it, the audit measured the dialog's text runs against
    // pixels that are not theirs (the Port label read 3.17:1 dark and 2.52:1 light; drawn, it is the dim token on the surface, 5.8:1 and 7.0:1).
    private fun dialogAlone(dark: Boolean) = rule.setContent {
        PaddockTheme(darkTheme = dark) { MachineAddressDialog("Alpha", alpha, listOf(beta), busy = false, onSave = { _, _ -> }, onCancel = {}) }
    }

    @Test fun auditAddressDialogDark() { dialogAlone(dark = true); SemanticsAudit.expectClean(rule, "Machine address dialog, dark") }
    @Test fun auditAddressDialogLight() { dialogAlone(dark = false); SemanticsAudit.expectClean(rule, "Machine address dialog, light") }
    @Test fun auditPageWithChangeAddressDark() { page(); SemanticsAudit.expectClean(rule, "Machine page with Change address, dark") }
    @Test fun auditPageWithTheSearchSwitchOnLight() { page(dark = false, searchNetwork = true); SemanticsAudit.expectClean(rule, "Machine page with the search switch on, light") }
}
