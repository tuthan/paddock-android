package io.github.tuthan.paddock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.pairing.PairingState
import io.github.tuthan.paddock.pairing.PairingText
import io.github.tuthan.paddock.pairing.PendingPairing
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ssh.OpenSshKeys
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.PairWithDesktop
import io.github.tuthan.paddock.ui.screens.PairingTarget
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Phase 14 slice 6: the pairing page for every state, and Send the key as an addition to the traditional paths (D1). */
class PairWithDesktopTest {
    @get:Rule val rule = createComposeRule()

    private val fp = "SHA256:abcdefgh1234567890ABCDEFGHIJKLMNOPQRSTUVWXY"
    private val pending = PendingPairing("a".repeat(22), "192.168.42.86", 41234, fp, "ecdsa-sha2-nistp256 AAAA", 0, 61_000, 1)
    private val target = PairingTarget("192.168.42.86:41234", fp)
    private val line = OpenSshKeys.publicLine(
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey,
        "paddock@phone",
    )

    private fun content(fontScale: Float?, dark: Boolean = true, body: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) { PaddockTheme(darkTheme = dark) { body() } }
    }

    private class Taps { var cancel = 0; var back = 0; var connect = 0 }

    private fun page(state: PairingState, fontScale: Float? = null, dark: Boolean = true): Taps {
        val taps = Taps()
        val view = PairingText.view(state, 1_000)!!
        content(fontScale, dark) { PairWithDesktop(view, target, onCancel = { taps.cancel++ }, onBack = { taps.back++ }, onConnect = { taps.connect++ }) }
        return taps
    }

    @Test fun waitingShowsTheFingerprintWholeAndItsHeadAndTheTimeLeft() {
        page(PairingState.Waiting(pending))
        rule.onNodeWithText("Waiting for approval on the desktop.").assertIsDisplayed()
        rule.onNode(hasContentDescription("Fingerprint of this phone's key: $fp")).assertIsDisplayed()
        rule.onNode(hasContentDescription("First 8 characters: abcdefgh")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Expires in: 1:00")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Sending to: 192.168.42.86:41234")).assertIsDisplayed()
    }

    @Test fun anActiveRequestOffersOnlyCancel() {
        val taps = page(PairingState.Waiting(pending))
        rule.onAllNodesWithText("Back").assertCountEquals(0)
        rule.onAllNodesWithText("Connect").assertCountEquals(0)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, taps.cancel)
    }

    @Test fun aFinishedRequestOffersBackAndOnlyAnUnheardOneOffersConnect() {
        val rejected = page(PairingState.Rejected(pending))
        rule.onAllNodesWithText("Connect").assertCountEquals(0)
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, rejected.back)
    }

    @Test fun anUnheardWindowOffersConnectBecauseTheDesktopMayHaveWrittenTheKey() {
        val taps = page(PairingState.NotConfirmed(pending))
        rule.onNodeWithText("Connect").performClick()
        assertEquals(1, taps.connect)
        rule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun theLongestStateIsReadableAtTwiceTheFontSize() {
        val view = PairingText.view(PairingState.Unreachable(pending, "x"), 1_000)!!
        content(2f) { PairWithDesktop(view, target, {}, {}, {}) }
        rule.onNodeWithText(view.headline).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun auditWaitingDark() { page(PairingState.Waiting(pending)); SemanticsAudit.expectClean(rule, "Pair with the desktop, waiting, dark") }
    @Test fun auditNotConfirmedLight() { page(PairingState.NotConfirmed(pending), dark = false); SemanticsAudit.expectClean(rule, "Pair with the desktop, not confirmed, light") }

    // ---- Send the key is an addition (D1) -----------------------------------------------------------------------------------------

    private fun form(offer: String?, initial: AddMachineInput = AddMachineInput("192.168.42.86", "22", "jdoe", pairedFingerprints = listOf("SHA256:hostkeyfp")), onSend: ((AddMachineInput) -> Unit)? = {}) =
        content(null) {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, line, KeyBacking.Tee, pairOfferHost = offer),
                onConnect = {}, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = {}, onBack = {}, onSendKey = onSend, initial = initial,
            )
        }

    @Test fun theOfferAddsSendTheKeyAndKeepsEveryTraditionalControl() {
        form("192.168.42.86")
        rule.onNodeWithText("Send the key to 192.168.42.86").performScrollTo().assertIsDisplayed()
        for (label in listOf("Copy", "Share", "Copy key only", "Show as QR")) rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        // Connect is pinned below the scrolling body, so it needs no scrolling.
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNode(hasContentDescription("Command to run on the machine", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test fun showingTheKeyToTheCameraRaisesTheBrightnessAndHidingItRestoresIt() {
        form("192.168.42.86")
        fun brightness(): Float {
            var b = -2f
            rule.runOnUiThread { b = (rule as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity.window.attributes.screenBrightness }
            return b
        }
        val before = brightness()
        rule.onNodeWithText("Show the key to the desktop's camera").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, brightness(), 0f)
        rule.onNode(hasContentDescription("QR code of this phone's public key")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Hide the QR").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(before, brightness(), 0f)
    }

    @Test fun withoutAnOfferThereIsNoSendButton() {
        form(null)
        rule.onAllNodesWithText("Send the key to", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Copy key only").performScrollTo().assertIsDisplayed()
    }

    @Test fun changingTheHostHidesTheOfferBecauseItNamedAnotherMachine() {
        form("192.168.42.86", initial = AddMachineInput("10.9.9.9", "22", "jdoe"))
        rule.onAllNodesWithText("Send the key to", substring = true).assertCountEquals(0)
    }

    @Test fun sendingGivesTheFormAsTyped() {
        var sent: AddMachineInput? = null
        form("192.168.42.86", onSend = { sent = it })
        rule.onNodeWithText("Send the key to 192.168.42.86").performScrollTo().performClick()
        assertEquals("192.168.42.86", sent?.host)
        assertEquals("jdoe", sent?.user)
    }
}
