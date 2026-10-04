package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.HostKeyPrompt
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.AuthorizeCommand
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.RouteNote
import io.github.tuthan.paddock.net.EndpointClass
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ssh.OpenSshKeys
import io.github.tuthan.paddock.ui.components.FingerprintDialog
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.backingText
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for Add machine and the host-key dialog (Phase 04 slice 7). */
class AddMachineTest {
    @get:Rule val rule = createComposeRule()

    /** A real P-256 line, as the phone's key makes it: the copy command exists only for a line the key parser accepts. */
    private val line = OpenSshKeys.publicLine(
        java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey,
        "paddock@phone",
    )
    private val command = AuthorizeCommand.forText(line)!!
    private class Calls {
        var importKey = 0; var connect: AddMachineInput? = null; var generate = 0; var copied: String? = null; var settings = 0; var back = 0
        var commandCopied: String? = null; var shared: String? = null; var pasted = 0
    }

    private fun state(grant: GateDecision = GateDecision.NotRequired, key: String? = null, backing: KeyBacking? = null, denied: Boolean = false, imported: String? = null, connecting: Boolean = false, summary: String? = null) =
        AddMachineState({ AddMachineForm.route(it, grant) }, key, backing, imported, denied, connecting, importedKeySummary = summary)

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A dialog is its own window: capture that, not the activity's root. Compose cannot capture a dialog below API 28, so older APIs skip the picture, not the test. */
    private fun shootDialog(name: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private var keyboard: androidx.compose.ui.platform.SoftwareKeyboardController? = null

    /** Puts the keyboard away and lets the layout settle, so a tap lands where the node is and not where it was mid-animation. */
    private fun hideKeyboard() {
        rule.runOnIdle { keyboard?.hide() }
        rule.waitForIdle()
        Thread.sleep(400)
        rule.waitForIdle()
    }

    private fun show(state: AddMachineState = state(), fontScale: Float? = null, calls: Calls = Calls(), dark: Boolean = true, initial: AddMachineInput = AddMachineInput(), paste: Boolean = false): Calls {
        rule.setContent {
            keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) { Screen(state, calls, initial, paste) }
            }
        }
        return calls
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: AddMachineState, calls: Calls, initial: AddMachineInput = AddMachineInput(), paste: Boolean = false) = AddMachine(
        state, onConnect = { calls.connect = it }, onGenerateKey = { calls.generate++ }, onCopyPublicKey = { calls.copied = it },
        onOpenSettings = { calls.settings++ }, onBack = { calls.back++ }, onImportKey = { calls.importKey++ },
        onCopyCommand = { calls.commandCopied = it }, onShareCommand = { calls.shared = it }, onPastePairingLink = if (paste) ({ calls.pasted++ }) else null,
        initial = initial,
    )

    private fun fill(host: String = "192.168.1.20", user: String = "jdoe", port: String? = null) {
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        if (port != null) { rule.onNodeWithText("Port").performTextInput(port) }
    }

    @Test fun anEmptyFormShowsAMessageUnderEachFieldAndDoesNotConnect() {
        val calls = show(state(key = line))
        rule.onNodeWithText("Connect").performClick()
        rule.onNodeWithText("Enter a hostname or IP address.").assertIsDisplayed()
        rule.onNodeWithText("Enter the user name to sign in as.").assertIsDisplayed()
        assertNull(calls.connect)
        shoot("add-machine-errors")
    }

    @Test fun aValidFormWithThePhoneKeyConnectsWithTheTypedValues() {
        val calls = show(state(key = line, backing = KeyBacking.Tee))
        fill()
        rule.onNodeWithText("Connect").performClick()
        assertEquals(AddMachineInput("192.168.1.20", "22", "jdoe", KeyKind.Phone, null), calls.connect)
    }

    @Test fun connectWaitsForThePhoneKeyToExist() {
        val calls = show(state(key = null))
        fill()
        rule.onNodeWithText("Connect").performClick()
        assertNull(calls.connect)
        rule.onNodeWithText("Create this phone's key first", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Create this phone's key").performScrollTo().performClick()
        assertEquals(1, calls.generate)
    }

    @Test fun theKeySectionShowsWhereTheKeyIsActuallyHeldAndTheAuthorizeLine() {
        for (b in KeyBacking.entries) {
            // each backing has its own sentence; none is invented when the platform did not say
            assertTrue(backingText(b).isNotBlank())
        }
        show(state(key = line, backing = KeyBacking.Software))
        rule.onNodeWithText(backingText(KeyBacking.Software)).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Public key to authorize: $line")).performScrollTo().assertIsDisplayed()
    }

    @Test fun copyKeyOnlyHandsOverTheExactPublicKeyLine() {
        val calls = show(state(key = line, backing = KeyBacking.StrongBox))
        rule.onNodeWithText("Copy key only").performScrollTo().performClick()
        assertEquals(line, calls.copied)
        assertNull("the bare line is not the command", calls.commandCopied)
    }

    @Test fun theCommandIsShownInFullBeforeItIsCopiedAndCopyHandsOverExactlyThatText() {
        val calls = show(state(key = line, backing = KeyBacking.Tee))
        rule.onNode(hasContentDescription("Command to run on the machine: $command")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(command).assertExists()
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        assertEquals(command, calls.commandCopied)
        rule.onNodeWithText("Copied").performScrollTo().assertIsDisplayed()
        assertNull("copying the command does not copy the bare line", calls.copied)
        shoot("add-machine-command")
    }

    @Test fun shareHandsTheSameCommandTextToTheShareSheetAndNothingElse() {
        val calls = show(state(key = line, backing = KeyBacking.Tee))
        rule.onNodeWithText("Share").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(command, calls.shared)
        assertNull(calls.copied); assertNull(calls.commandCopied)
        assertNull("sharing is not connecting", calls.connect)
    }

    @Test fun theKeyFingerprintIsShownSoItCanBeComparedWithWhatTheMachinePrints() {
        show(state(key = line, backing = KeyBacking.Tee))
        val fp = io.github.tuthan.paddock.ssh.AuthorizedKey.parse(line)!!.fingerprint
        rule.onNode(hasContentDescription("Key fingerprint: $fp")).performScrollTo().assertIsDisplayed()
    }

    @Test fun aKeyLineTheParserDoesNotAcceptGetsNoCommandAndNoShare() {
        val calls = show(state(key = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBHt paddock@phone", backing = KeyBacking.Tee))
        rule.onNodeWithText("Copy key only").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Share").assertCountEquals(0)
        rule.onAllNodesWithText("Copy").assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("Command to run on the machine", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("Append it to", substring = true).performScrollTo().assertIsDisplayed()
        assertNull(calls.shared)
    }

    // --- a pairing link ---

    private val fpLink = "SHA256:" + "A".repeat(43)
    private val link = PairingLink("box.example.ts.net", 2222, "jdoe", listOf(fpLink), "dev")

    @Test fun aPairingLinkPreFillsTheMachineAndShowsTheFingerprintItWillCompare() {
        val calls = show(state(key = line, backing = KeyBacking.Tee), initial = link.toInput())
        rule.onNodeWithText("box.example.ts.net").assertIsDisplayed()
        rule.onNodeWithText("jdoe").assertIsDisplayed()
        rule.onNode(hasContentDescription("Host key fingerprint in the link: $fpLink")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Filled in from a pairing link", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Connect").performClick()
        assertEquals(AddMachineInput("box.example.ts.net", "2222", "jdoe", KeyKind.Phone, null, "dev", listOf(fpLink)), calls.connect)
        shoot("add-machine-pairing")
    }

    @Test fun changingTheHostOrPortAwayFromTheLinkStopsTheComparisonAndSaysSo() {
        val calls = show(state(key = line, backing = KeyBacking.Tee), initial = link.toInput())
        rule.onNodeWithText("Host or IP address").performTextInput("2")   // typed at the start: "2box.example.ts.net", no longer the link's machine
        rule.onNodeWithText("The host or port no longer match the pairing link", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Filled in from a pairing link", substring = true).assertCountEquals(0)
        hideKeyboard()
        rule.onNodeWithText("Connect").performClick()
        assertEquals("2box.example.ts.net", calls.connect!!.host)
        assertNull("the link's fingerprints are not carried to another host", calls.connect!!.pairedFingerprints)
    }

    @Test fun aMachineTypedInCarriesNoLinkAndOffersToPasteOne() {
        val calls = show(state(key = line, backing = KeyBacking.Tee), paste = true)
        rule.onNodeWithText("Paste a pairing link").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.pasted)
        fill()
        hideKeyboard()
        rule.onNodeWithText("Connect").performClick()
        assertNull(calls.connect!!.pairedFingerprints)
    }

    @Test fun theWordsOfARefusedPasteAreShownWithoutTheLink() {
        show(state(key = line).copy(pairingNotice = "That is not a Paddock pairing link. Nothing was filled in."), paste = true)
        rule.onNodeWithText("That is not a Paddock pairing link", substring = true).assertIsDisplayed()
    }

    @Test fun showAsQrDrawsTheCodeAndHideRemovesIt() {
        show(state(key = line, backing = KeyBacking.Tee))
        val qr = hasContentDescription("QR code of this phone's public key")
        rule.onNode(qr).assertDoesNotExist()
        rule.onNodeWithText("Show as QR").performScrollTo().performClick()
        rule.onNode(qr).performScrollTo().assertIsDisplayed()
        shoot("add-machine-qr")
        rule.onNodeWithText("Hide QR").performScrollTo().performClick()
        rule.onNode(qr).assertDoesNotExist()
    }

    @Test fun aLocalAddressExplainsTheAndroidPromptBeforeConnectAndDoesNotAskForItOnItsOwn() {
        show(state(grant = GateDecision.NeedsGrant, key = line))
        rule.onNodeWithText("Host or IP address").performTextInput("192.168.1.20")
        rule.onNodeWithText("This is a local-network address", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test fun aDeniedGrantShowsARecoveryRowThatOpensSettingsAndNamesTheReason() {
        val calls = show(state(grant = GateDecision.NeedsGrant, key = line, denied = true))
        rule.onNodeWithText("Host or IP address").performTextInput("192.168.1.20")
        rule.onNodeWithText("Local-network access is off", substring = true).assertIsDisplayed()
        hideKeyboard()
        rule.onNodeWithText("Open settings").performScrollTo()
        shoot("add-machine-permission-denied")
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, calls.settings)
    }

    @Test fun aVpnOrNamedHostNeedsNoGrantAndSaysSo() {
        show(state(grant = GateDecision.NeedsGrant, key = line))
        rule.onNodeWithText("Host or IP address").performTextInput("box.example.ts.net")
        rule.onNodeWithText("no local-network access needed", substring = true).assertIsDisplayed()
    }

    @Test fun aLanNameIsDescribedAsLocalOnceTypingSettles() {
        // The text says nothing about where nas.lan goes; the resolved answer replaces the text-only hint.
        val resolved = state(grant = GateDecision.NeedsGrant, key = line).copy(
            resolveRoute = { h -> AddMachineForm.route(h, GateDecision.NeedsGrant, if (h == "nas.lan") EndpointClass.Local else EndpointClass.NotLocal) },
        )
        show(resolved)
        rule.onNodeWithText("Host or IP address").performTextInput("nas.lan")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("This is a local-network address", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("no local-network access needed", substring = true).assertDoesNotExist()
    }

    @Test fun settingUpTheKeyStartsFromTheWatchedMachineAndConnectsWithItsValues() {
        val calls = Calls()
        rule.setContent {
            PaddockTheme {
                AddMachine(
                    state(key = line, backing = KeyBacking.Tee), onConnect = { calls.connect = it }, onGenerateKey = {}, onCopyPublicKey = {},
                    onOpenSettings = {}, onBack = {},
                    initial = AddMachineInput("box.lan", "2222", "jdoe", KeyKind.Phone, null, "main"), title = "Set up the key",
                    intro = io.github.tuthan.paddock.ui.screens.SET_UP_KEY_INTRO,
                )
            }
        }
        rule.onNodeWithText("Set up the key").assertIsDisplayed()
        rule.onNodeWithText("can't be read on this phone", substring = true).assertIsDisplayed()
        rule.onNodeWithText("box.lan").assertIsDisplayed()
        rule.onNodeWithText("Connect").performClick()
        assertEquals(AddMachineInput("box.lan", "2222", "jdoe", KeyKind.Phone, null, "main"), calls.connect)
        shoot("add-machine-set-up-key")
    }

    @Test fun anImportedKeyProfileNeedsAKeyFirst() {
        val calls = show(state())
        fill()
        rule.onNodeWithText("An imported key").performScrollTo().performClick()
        rule.onNodeWithText("Connect").performClick()
        assertNull(calls.connect)
        rule.onNodeWithText("Import a key before connecting", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun anImportedKeyConnectsWithItsId() {
        val calls = show(state(imported = "work-key"))
        fill()
        rule.onNodeWithText("An imported key").performScrollTo().performClick()
        rule.onNodeWithText("Connect").performClick()
        assertEquals(KeyKind.Imported, calls.connect!!.key)
        assertEquals("work-key", calls.connect!!.importedKeyId)
    }

    @Test fun theImportedChoiceWithNoKeyOffersToImportOne() {
        val calls = show(state(key = line))
        rule.onNodeWithText("An imported key").performScrollTo().performClick()
        rule.onNodeWithText("Import a private key").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.importKey)
    }

    @Test fun aStoredImportedKeyShowsWhichKeyItIsAndOffersToReplaceIt() {
        val calls = show(state(key = line, imported = "imported", summary = "ssh-ed25519 · SHA256:abcDEF"))
        rule.onNodeWithText("An imported key").performScrollTo().performClick()
        rule.onNode(hasContentDescription("Imported key: ssh-ed25519 · SHA256:abcDEF")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace the imported key").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.importKey)
        assertEquals(0, rule.onAllNodesWithText("Import a private key").fetchSemanticsNodes().size)
    }

    @Test fun connectingDisablesTheButtonSoItCannotBeTappedTwice() {
        val calls = show(state(key = line, connecting = true))
        fill()
        rule.onNodeWithText("Connecting…").performClick()
        assertNull(calls.connect)
    }

    @Test fun everyInteractiveElementIsAtLeastFortyEightDpTall() {
        show(state(key = line, backing = KeyBacking.Tee))
        rule.onNodeWithText("Connect").assertHeightIsAtLeast(48.dp)
        for (t in listOf("Copy", "Share", "Copy key only", "Show as QR", "This phone's key", "An imported key")) {
            rule.onNodeWithText(t).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
        rule.onNode(hasContentDescription("Back")).assertHeightIsAtLeast(48.dp)
    }

    @Test fun connectStaysReachableAtTwoHundredPercentFont() {
        show(state(key = line, backing = KeyBacking.Tee), fontScale = 2f)
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNodeWithText("Host or IP address").assertIsDisplayed()
        shoot("add-machine-200")
    }

    @Test fun typedValuesSurviveRotation() {
        val tester = StateRestorationTester(rule)
        tester.setContent { PaddockTheme(darkTheme = true) { Screen(state(key = line), Calls()) } }
        fill(host = "box.example.ts.net", user = "jdoe")
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("box.example.ts.net").assertIsDisplayed()
        rule.onNodeWithText("jdoe").assertIsDisplayed()
    }

    @Test fun lightThemeRenders() {
        show(state(key = line, backing = KeyBacking.Tee), dark = false)
        rule.onNodeWithText("Connect").assertIsDisplayed()
        shoot("add-machine-light")
    }

    // --- host-key dialog ---

    private val first = HostKeyPrompt.FirstTrust("192.168.1.20:22", "ED25519", "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
    private val changed = HostKeyPrompt.Changed(
        "192.168.1.20:22", "ED25519", "SHA256:OLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDOLDO", 1_700_000_000_000L,
        "ECDSA P-256", "SHA256:NEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEWN", "ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub",
    )

    private class Choice { var trust = 0; var cancel = 0 }

    private fun dialog(prompt: HostKeyPrompt, fontScale: Float? = null): Choice {
        val c = Choice()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = true) { FingerprintDialog(prompt, onTrust = { c.trust++ }, onCancel = { c.cancel++ }) }
            }
        }
        return c
    }

    private fun button(text: String): SemanticsNodeInteraction = rule.onNode(hasText(text) and androidx.compose.ui.test.hasClickAction())

    @Test fun firstTrustShowsTheFingerprintTheKeyTypeAndTheCommandAndFocusesCancel() {
        dialog(first)
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        rule.onNodeWithText(first.fingerprint).assertIsDisplayed()
        rule.onNodeWithText("ED25519").assertIsDisplayed()
        rule.onNodeWithText(first.compareCommand).assertIsDisplayed()
        button("Cancel").assertIsFocused()
        button("Trust and connect").assertIsNotFocused()
        shootDialog("hostkey-first-trust")
    }

    @Test fun theEnterKeyActivatesTheSafeChoice() {
        val c = dialog(first)
        button("Cancel").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, c.cancel); assertEquals(0, c.trust)
    }

    @Test fun trustingIsADeliberateTapOnItsOwnButton() {
        val c = dialog(first)
        button("Trust and connect").performClick()
        assertEquals(1, c.trust); assertEquals(0, c.cancel)
    }

    @Test fun cancelRefusesTheKey() {
        val c = dialog(first)
        button("Cancel").performClick()
        assertEquals(1, c.cancel); assertEquals(0, c.trust)
    }

    @Test fun aChangedKeyShowsBothFingerprintsTheOldDateAndFocusesKeepTheOldKey() {
        dialog(changed)
        rule.onNodeWithText(changed.oldFingerprint).assertIsDisplayed()
        rule.onNodeWithText(changed.newFingerprint).assertIsDisplayed()
        rule.onNode(hasText("first trusted", substring = true, ignoreCase = true)).assertIsDisplayed()
        button("Keep the old key").assertIsFocused()
        button("Replace with the new key").assertIsNotFocused()
        shootDialog("hostkey-changed")
    }

    @Test fun replacingAChangedKeyNeedsItsOwnTapAndKeepingNeverReplaces() {
        val c = dialog(changed)
        button("Keep the old key").performClick()
        assertEquals(0, c.trust); assertEquals(1, c.cancel)
        button("Replace with the new key").performClick()
        assertEquals(1, c.trust)
    }

    @Test fun theDialogButtonsStayReachableAtTwoHundredPercentFont() {
        dialog(changed, fontScale = 2f)
        button("Keep the old key").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        button("Replace with the new key").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        shootDialog("hostkey-changed-200")
    }

    // --- host-key dialog with a pairing link ---

    private val linked = first.copy(matchesPairingLink = true)
    private val mismatch = HostKeyPrompt.PairingMismatch(
        "192.168.1.20:22", "ED25519", "SHA256:OFFEREDOFFEREDOFFEREDOFFEREDOFFEREDOFFEREDO", listOf("SHA256:LINKLINKLINKLINKLINKLINKLINKLINKLINKLINKLIN"), "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
    )

    @Test fun aFingerprintTheLinkNamedSaysSoButTrustIsStillTheUsersTap() {
        val c = dialog(linked)
        rule.onNodeWithText("Same as the fingerprint in the pairing link.").assertIsDisplayed()
        rule.waitUntil(3_000) { runCatching { button("Cancel").assertIsFocused() }.isSuccess }
        button("Trust and connect").assertIsNotFocused()
        assertEquals("nothing is trusted until the tap", 0, c.trust)
        button("Trust and connect").performClick()
        assertEquals(1, c.trust)
        shootDialog("hostkey-first-trust-linked")
    }

    @Test fun withoutALinkTheDialogDoesNotMentionOne() {
        dialog(first)
        rule.onAllNodesWithText("pairing link", substring = true).assertCountEquals(0)
    }

    @Test fun aKeyTheLinkDoesNotNameShowsBothFingerprintsAndOffersNoWayToTrustIt() {
        val c = dialog(mismatch)
        rule.onNodeWithText("192.168.1.20:22 is not the machine in the pairing link").assertIsDisplayed()
        rule.onNodeWithText(mismatch.presentedFingerprint).assertIsDisplayed()
        rule.onNodeWithText(mismatch.linkFingerprints.single()).assertIsDisplayed()
        rule.onAllNodesWithText("Trust and connect").assertCountEquals(0)
        rule.onAllNodesWithText("Replace with the new key").assertCountEquals(0)
        rule.waitUntil(3_000) { runCatching { button("Close").assertIsFocused() }.isSuccess }
        button("Close").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, c.cancel); assertEquals(0, c.trust)
        shootDialog("hostkey-pairing-mismatch")
    }

    @Test fun theMismatchDialogFitsAtTwoHundredPercentFont() {
        dialog(mismatch, fontScale = 2f)
        rule.onNodeWithText(mismatch.presentedFingerprint).assertIsDisplayed()
        button("Close").assertIsDisplayed()
    }

    /** The example inside an empty address field may be cut at a large font; its label above it is not. */
    private val ADDRESS_EXAMPLE = SemanticsAudit.Options(ellipsisOk = { it.startsWith("192.168.1.20 or") })

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditAddMachineNewDark() { show(); SemanticsAudit.expectClean(rule, "Add machine, new, dark", ADDRESS_EXAMPLE) }
    @Test fun auditAddMachineNewLight() { show(dark = false); SemanticsAudit.expectClean(rule, "Add machine, new, light", ADDRESS_EXAMPLE) }
    @Test fun auditAddMachineWithAKeyDark() { show(state(key = line, backing = KeyBacking.Tee)); SemanticsAudit.expectClean(rule, "Add machine, with key, dark", ADDRESS_EXAMPLE) }
    @Test fun auditAddMachineWithAKeyLight() { show(state(key = line, backing = KeyBacking.Tee), dark = false); SemanticsAudit.expectClean(rule, "Add machine, with key, light", ADDRESS_EXAMPLE) }
    @Test fun auditFingerprintDialogFirstTrust() { dialog(first); SemanticsAudit.expectClean(rule, "Fingerprint dialog, first trust, dark") }
    @Test fun auditFingerprintDialogChangedKey() { dialog(changed); SemanticsAudit.expectClean(rule, "Fingerprint dialog, changed key, dark") }

    @Test fun auditAddMachineDeniedDark() { show(state(denied = true)); SemanticsAudit.expectClean(rule, "Add machine, permission denied, dark", ADDRESS_EXAMPLE) }
    @Test fun auditAddMachineConnectingDark() { show(state(connecting = true)); SemanticsAudit.expectClean(rule, "Add machine, connecting, dark", ADDRESS_EXAMPLE) }
    @Test fun auditFingerprintDialogPairingMismatch() { dialog(mismatch); SemanticsAudit.expectClean(rule, "Fingerprint dialog, pairing mismatch, dark") }
    @Test fun auditFingerprintDialogFirstTrustLinked() { dialog(linked); SemanticsAudit.expectClean(rule, "Fingerprint dialog, first trust with link, dark") }

}
