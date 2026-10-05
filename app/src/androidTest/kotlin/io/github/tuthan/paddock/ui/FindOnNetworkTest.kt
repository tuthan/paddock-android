package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.discovery.FinderPhase
import io.github.tuthan.paddock.discovery.FinderState
import io.github.tuthan.paddock.discovery.FinderText
import io.github.tuthan.paddock.discovery.FoundHost
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ssh.OpenSshKeys
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.FINDER_LOCAL_ACCESS_OFF
import io.github.tuthan.paddock.ui.screens.FindOnNetwork
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Phase 14 slice 4: the finder page for every state, and a pick that fills the address, the port and the announced name and nothing else. */
class FindOnNetworkTest {
    @get:Rule val rule = createComposeRule()

    private val sentence = "Paddock asks every address on 192.168.42.0/24 whether an SSH server answers on port 22."
    private val idle = FinderState(FinderPhase.Idle, sentence, canStart = true)
    private val laptop = FoundHost("192.168.42.86", 22, "devbox", "OpenSSH 9.9")
    private val other = FoundHost("192.168.42.40", 2233, null, "OpenSSH 10.5")

    private fun content(fontScale: Float?, dark: Boolean = true, body: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) { PaddockTheme(darkTheme = dark) { body() } }
    }

    private class Taps { var start = 0; var cancel = 0; var allow = 0; var back = 0; var settings = 0; val picked = mutableListOf<FoundHost>() }

    private fun page(state: FinderState, port: String = "", fontScale: Float? = null, dark: Boolean = true, denied: Boolean = false): Taps {
        val taps = Taps()
        content(fontScale, dark) {
            FindOnNetwork(
                state, port, {}, onStart = { taps.start++ }, onCancel = { taps.cancel++ }, onAllow = { taps.allow++ },
                onPick = { taps.picked += it }, onBack = { taps.back++ }, accessDenied = denied, onOpenSettings = { taps.settings++ },
            )
        }
        return taps
    }

    @Test fun beforeAnythingRunsItSaysWhatItWillDoAndOffersStart() {
        val taps = page(idle)
        rule.onNodeWithText(sentence).assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsEnabled().performClick()
        assertEquals(1, taps.start)
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test fun anInvalidPortShowsItsErrorAndStartCannotBePressed() {
        page(idle, port = "70000")
        rule.onNodeWithText(FinderText.PORT_ERROR).assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun aMissingGrantOffersAllowAndNoPortFieldOrStart() {
        val taps = page(FinderState(FinderPhase.Idle, "Android has to let Paddock reach devices on your network first.", needsGrant = true))
        rule.onNodeWithText("Allow local-network access").performClick()
        assertEquals(1, taps.allow)
        rule.onAllNodesWithText("Start").assertCountEquals(0)
        rule.onAllNodesWithText("Port to look at besides 22 (optional)").assertCountEquals(0)
    }

    @Test fun aRefusedGrantSaysWhereToTurnItOn() {
        val taps = page(FinderState(FinderPhase.Idle, "Android has to let Paddock reach devices on your network first.", needsGrant = true), denied = true)
        rule.onNodeWithText(FINDER_LOCAL_ACCESS_OFF).assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, taps.settings)
    }

    @Test fun aNetworkThatCannotBeScannedSaysSoAndOffersNothingToStart() {
        page(FinderState(FinderPhase.Idle, "This network is larger than Paddock will probe (a /16). Type the machine's address instead."))
        rule.onNodeWithText("This network is larger than Paddock will probe (a /16). Type the machine's address instead.").assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test fun whileScanningTheCountMovesRowsAreLiveAndTheOnlyActionIsCancel() {
        val taps = page(FinderState(FinderPhase.Scanning, sentence, done = 12, total = 508, rows = listOf(laptop)))
        rule.onNodeWithText("Tried 12 of 508 addresses").assertIsDisplayed()
        rule.onNodeWithText(laptop.labelWithPort).assertIsDisplayed()
        rule.onAllNodesWithText("Start").assertCountEquals(0)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, taps.cancel)
    }

    @Test fun aRowIsTheNameTheAddressAndTheSoftwareAndATapPicksIt() {
        val taps = page(FinderState(FinderPhase.Done, sentence, canStart = true, done = 508, total = 508, rows = listOf(laptop, other)))
        rule.onNodeWithText("Found 2 machines. Tap one to fill in its address and port. Nothing has been connected to.").assertIsDisplayed()
        rule.onNodeWithText("192.168.42.86 · devbox · OpenSSH 9.9").performClick()
        rule.onNodeWithText("192.168.42.40 · OpenSSH 10.5 · port 2233").performClick()
        assertEquals(listOf(laptop, other), taps.picked)
        rule.onNodeWithText("Search again").assertIsEnabled()
    }

    @Test fun anEmptyResultSaysSoAndOffersAnotherSearch() {
        page(FinderState(FinderPhase.Done, sentence, canStart = true, done = 508, total = 508))
        rule.onNodeWithText("No SSH server answered", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Search again").assertIsDisplayed()
    }

    @Test fun aStoppedScanKeepsWhatWasFound() {
        page(FinderState(FinderPhase.Cancelled, sentence, canStart = true, rows = listOf(laptop)))
        rule.onNodeWithText("Stopped. Found so far: 1 machine.").assertIsDisplayed()
        rule.onNodeWithText(laptop.labelWithPort).assertIsDisplayed()
    }

    @Test fun theMdnsNoteShowsOnlyWithTheFinishedResult() {
        page(FinderState(FinderPhase.Done, sentence, canStart = true, rows = listOf(laptop), note = "Names announced on the network could not be listened for."))
        rule.onNodeWithText("Names announced on the network could not be listened for.").assertIsDisplayed()
    }

    @Test fun theFullestStateIsReadableAtTwiceTheFontSize() {
        page(FinderState(FinderPhase.Done, sentence, canStart = true, rows = listOf(laptop, other), note = "Names announced on the network could not be listened for."), fontScale = 2f)
        rule.onNodeWithText("Search again").assertIsDisplayed()
        rule.onNodeWithText(laptop.labelWithPort).performScrollTo().assertIsDisplayed()
    }

    @Test fun auditResultDark() { page(FinderState(FinderPhase.Done, sentence, canStart = true, rows = listOf(laptop, other))); SemanticsAudit.expectClean(rule, "Find on this network, result, dark") }
    @Test fun auditScanningLight() { page(FinderState(FinderPhase.Scanning, sentence, done = 3, total = 508, rows = listOf(laptop)), dark = false); SemanticsAudit.expectClean(rule, "Find on this network, scanning, light") }

    // ---- The form takes a pick: address, port and announced name, nothing else -------------------------------------------------------

    private val line = OpenSshKeys.publicLine(
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey,
        "paddock@phone",
    )

    private class Form { var connected: AddMachineInput? = null; var applied = 0; var found = 0 }

    private fun form(pick: androidx.compose.runtime.MutableState<AddMachineInput?>, withFind: Boolean = true): Form {
        val f = Form()
        content(null) {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, line, KeyBacking.Tee),
                onConnect = { f.connected = it }, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = {}, onBack = {},
                onFind = if (withFind) ({ f.found++ }) else null,
                picked = pick.value, onPickedApplied = { f.applied++; pick.value = null },
            )
        }
        return f
    }

    @Test fun addMachineOffersFindUnderTheHostFieldAndKeepsPasteAndTheTraditionalKey() {
        val f = form(mutableStateOf(null))
        rule.onNodeWithText("Find on this network").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, f.found)
        rule.onNodeWithText("Copy key only").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test fun withoutAFinderThereIsNoFindButton() {
        form(mutableStateOf(null), withFind = false)
        rule.onAllNodesWithText("Find on this network").assertCountEquals(0)
    }

    @Test fun aPickFillsTheHostPortAndNameAndLeavesTheUserAlone() {
        val pick = mutableStateOf<AddMachineInput?>(null)
        val f = form(pick)
        rule.onNodeWithText("User").performTextInput("jdoe")
        rule.runOnUiThread { pick.value = AddMachineInput(host = "192.168.42.86", port = "2233", name = "devbox") }
        rule.waitForIdle()
        assertEquals(1, f.applied)
        assertNull(pick.value)
        rule.onNodeWithText("Connect").performClick()
        val c = f.connected!!
        assertEquals("192.168.42.86", c.host)
        assertEquals("2233", c.port)
        assertEquals("jdoe", c.user)
        assertEquals("devbox", c.name)
        assertNull(c.pairedFingerprints)
    }

    @Test fun typingAnotherHostAfterAPickDropsTheAnnouncedName() {
        val pick = mutableStateOf<AddMachineInput?>(null)
        val f = form(pick)
        rule.onNodeWithText("User").performTextInput("jdoe")
        rule.runOnUiThread { pick.value = AddMachineInput(host = "192.168.42.86", port = "22", name = "devbox") }
        rule.waitForIdle()
        rule.onNodeWithText("192.168.42.86").performTextInput("1")
        rule.onNodeWithText("Connect").performClick()
        assertEquals("1192.168.42.86", f.connected!!.host)
        assertNull(f.connected!!.name)
    }

    @Test fun welcomeOffersFindWhenItIsGivenOne() {
        var found = 0
        content(null) { Welcome(onEnterAddress = {}, onFind = { found++ }) }
        rule.onNodeWithText("Find on this network").performClick()
        assertEquals(1, found)
        rule.onNodeWithText("Enter the address").assertIsDisplayed()
    }
}
