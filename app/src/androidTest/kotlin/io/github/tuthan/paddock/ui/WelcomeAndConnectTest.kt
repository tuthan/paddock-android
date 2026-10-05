package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ssh.OpenSshKeys
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_FAILED_IMPORTED_KEY
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_FAILED_PHONE_KEY
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_INTRO
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Phase 14 slice 2: the Welcome screen, and Connect staying on the form with what went wrong and what fixes it. */
class WelcomeAndConnectTest {
    @get:Rule val rule = createComposeRule()

    private val line = OpenSshKeys.publicLine(
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey,
        "paddock@phone",
    )

    private fun content(fontScale: Float?, dark: Boolean = true, body: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) { PaddockTheme(darkTheme = dark) { body() } }
    }

    // ---- Welcome ---------------------------------------------------------------------------------------------------------------

    @Test fun welcomeNamesWhatTheMachineNeedsWithTheCommandThatChecksEach() {
        content(null) { Welcome(onEnterAddress = {}) }
        rule.onNodeWithText("herdr session list").assertIsDisplayed()
        rule.onNodeWithText("python3 --version").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Enter the address").performScrollTo().assertIsDisplayed()
    }

    @Test fun onlyTheWaysInThatAreWiredAreOffered() {
        content(null) { Welcome(onEnterAddress = {}) }
        rule.onAllNodesWithText("Find on this network").assertCountEquals0()
        rule.onAllNodesWithText("Paste a pairing link").assertCountEquals0()
        rule.onAllNodesWithText("Scan the code on the desktop").assertCountEquals0()
    }

    @Test fun everyWiredWayCallsItsOwnHandler() {
        val calls = mutableListOf<String>()
        content(null) { Welcome(onEnterAddress = { calls += "enter" }, onFind = { calls += "find" }, onPasteLink = { calls += "paste" }, onScan = { calls += "scan" }) }
        for (label in listOf("Enter the address", "Find on this network", "Paste a pairing link", "Scan the code on the desktop")) rule.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(listOf("enter", "find", "paste", "scan"), calls)
    }

    @Test fun aRefusedPastedLinkIsExplainedAboveTheWays() {
        content(null) { Welcome(onEnterAddress = {}, onPasteLink = {}, notice = "That is not a Paddock pairing link.") }
        rule.onNodeWithText("That is not a Paddock pairing link.").assertIsDisplayed()
    }

    @Test fun welcomeStaysUsableAtTwiceTheFontSize() {
        content(2f) { Welcome(onEnterAddress = {}, onFind = {}, onPasteLink = {}, onScan = {}) }
        for (label in listOf("Enter the address", "Find on this network", "Paste a pairing link", "Scan the code on the desktop")) rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    @Test fun auditWelcomeDark() { content(null) { Welcome(onEnterAddress = {}, onFind = {}, onPasteLink = {}, onScan = {}) }; SemanticsAudit.expectClean(rule, "Welcome, dark") }
    @Test fun auditWelcomeLight() { content(null, dark = false) { Welcome(onEnterAddress = {}, onFind = {}, onPasteLink = {}, onScan = {}) }; SemanticsAudit.expectClean(rule, "Welcome, light") }

    // ---- Connect stays on the form ------------------------------------------------------------------------------------------------

    private class Calls { var settings = 0; var connect: AddMachineInput? = null }

    private fun form(error: String? = null, fix: ConnectFix? = null, connecting: Boolean = false, key: String? = line, imported: String? = null, initial: AddMachineInput = AddMachineInput("192.168.1.20", "22", "jdoe")): Calls {
        val calls = Calls()
        content(null) {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, key, KeyBacking.Tee, imported, false, connecting, error, fix),
                onConnect = { calls.connect = it }, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = { calls.settings++ }, onBack = {},
                initial = initial,
            )
        }
        return calls
    }

    @Test fun whileConnectingTheButtonSaysSoAndCannotBePressedTwice() {
        form(connecting = true)
        rule.onNodeWithText("Connecting…").assertIsNotEnabled()
    }

    @Test fun aRefusedPhoneKeyShowsTheCommandRightBelowTheSentenceThatPointsAtIt() {
        form(error = "The host did not accept this phone's key.", fix = ConnectFix.ShowCommand)
        rule.onNodeWithText(AUTHORIZE_FAILED_PHONE_KEY).assertIsDisplayed()
        // Scrolled to without a tap: the screen brought the sentence and its command into view by itself.
        rule.onNode(hasContentDescription("Command to run on the machine", substring = true)).assertIsDisplayed()
        // The generic error is not repeated at the bottom: one sentence, next to its fix.
        rule.onAllNodesWithText("The host did not accept this phone's key.").assertCountEquals0()
        rule.onNodeWithText("Connect").assertIsEnabled()
    }

    @Test fun aRefusedImportedKeySaysSoAndPointsAtThePhoneKeyAsTheOtherWay() {
        form(error = "refused", fix = ConnectFix.ShowCommand, imported = "box", initial = AddMachineInput("192.168.1.20", "22", "jdoe", KeyKind.Imported, "box"))
        rule.onNodeWithText(AUTHORIZE_FAILED_IMPORTED_KEY).assertIsDisplayed()
    }

    @Test fun anyOtherFailureIsSaidAtTheBottomWithoutTheCommandBanner() {
        form(error = "The host did not answer in time.", fix = ConnectFix.Retry)
        // Pinned above Connect, not in the scrolling body: it is on screen without scrolling.
        rule.onNodeWithText("The host did not answer in time.").assertIsDisplayed()
        rule.onAllNodesWithText(AUTHORIZE_FAILED_PHONE_KEY).assertCountEquals0()
    }

    @Test fun aSettingsFixOffersOpenSettingsAndItWorks() {
        val calls = form(error = "Local-network access is off, so Paddock cannot reach this address.", fix = ConnectFix.OpenSettings)
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, calls.settings)
    }

    @Test fun theTraditionalWaysStayWhateverWentWrong() {
        form(error = "x", fix = ConnectFix.ShowCommand)
        for (label in listOf("Copy", "Share", "Copy key only", "Show as QR")) rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    @Test fun theAuthorizeIntroLeadsWithHowToAuthorize() {
        content(null) {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, line, KeyBacking.Tee), onConnect = {}, onGenerateKey = {},
                onCopyPublicKey = {}, onOpenSettings = {}, onBack = {}, title = "Authorize this phone", intro = AUTHORIZE_INTRO,
            )
        }
        rule.onNodeWithText("Authorize this phone").assertIsDisplayed()
        rule.onNodeWithText(AUTHORIZE_INTRO).assertIsDisplayed()
    }

    @Test fun auditTheRefusedKeyFormDark() { form(error = "x", fix = ConnectFix.ShowCommand); SemanticsAudit.expectClean(rule, "Add machine, key refused, dark", SemanticsAudit.Options(ellipsisOk = { it.startsWith("192.168.1.20 or") })) }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() = assertCountEquals(0)
