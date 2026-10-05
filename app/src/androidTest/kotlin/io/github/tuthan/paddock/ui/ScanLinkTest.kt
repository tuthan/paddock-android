package io.github.tuthan.paddock.ui

import android.content.Context
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.scanner.QrScanner
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.SCAN_CAMERA_OFF
import io.github.tuthan.paddock.ui.screens.SCAN_INTRO
import io.github.tuthan.paddock.ui.screens.ScanLink
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 14 slice 9: the scanner page with a stand-in for the camera, and the two entry points. */
class ScanLinkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // The page starts the camera only when the phone says CAMERA is granted, as it does for a real user; the test app is a fresh install without it.
    // `pm` through the shell works on every API level (UiAutomation.grantRuntimePermission is API 28). It is never revoked here: revoking a runtime
    // permission kills the app's process, and the instrumentation with it. `connectedAndroidTest` uninstalls the app afterwards.
    private fun pm(verb: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("pm $verb $pkg android.permission.CAMERA").use { fd -> java.io.FileInputStream(fd.fileDescriptor).readBytes() }
    }
    @org.junit.Before fun grantCamera() = pm("grant")

    private fun content(fontScale: Float? = null, dark: Boolean = true, body: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) { PaddockTheme(darkTheme = dark) { body() } }
    }

    /**
     * Stands in for the camera: records what the page asked of it and lets a test deliver a code or a failure. Like the real scanner, one
     * that has started or been closed refuses to start again ("Scanner has already started or closed"), so a page that reuses one shows it.
     */
    private class FakeCamera {
        val made = mutableListOf<Pair<(String) -> Unit, (String) -> Unit>>()
        var closed = 0
        var started = 0
        val factory: (Context, (String) -> Unit, (String) -> Unit) -> QrScanner = { _, payload, failure ->
            made += payload to failure
            object : QrScanner {
                private var used = false
                private var shut = false
                override fun start(preview: Surface) {
                    check(!shut && !used) { "Scanner has already started or closed" }
                    used = true
                    started++
                }
                override fun close() { if (!shut) { shut = true; closed++ } }
            }
        }
    }

    private class Taps { var allow = 0; var settings = 0; var paste = 0; var back = 0; val payloads = mutableListOf<String>() }

    private fun page(granted: Boolean, camera: FakeCamera = FakeCamera(), notice: String? = null, fontScale: Float? = null, dark: Boolean = true): Pair<Taps, FakeCamera> {
        val taps = Taps()
        content(fontScale, dark) {
            ScanLink(granted, { taps.allow++ }, { taps.settings++ }, { taps.payloads += it }, { taps.paste++ }, { taps.back++ }, notice = notice, scannerFactory = camera.factory)
        }
        return taps to camera
    }

    @Test fun withoutTheCameraItSaysWhyItIsAskedAndOffersAllowSettingsAndPaste() {
        val (taps, camera) = page(granted = false)
        rule.onNodeWithText(SCAN_INTRO).assertIsDisplayed()
        rule.onNodeWithText(SCAN_CAMERA_OFF).assertIsDisplayed()
        assertTrue("no camera was opened without the permission", camera.made.isEmpty())
        rule.onNodeWithText("Allow the camera").performClick()
        rule.onNodeWithText("Open settings").performClick()
        rule.onNodeWithText("Paste a pairing link").performClick()
        assertEquals(listOf(1, 1, 1), listOf(taps.allow, taps.settings, taps.paste))
    }

    @Test fun withTheCameraThePreviewIsThereTheCameraStartsOnItsSurfaceAndAReadCodeIsDelivered() {
        val (taps, camera) = page(granted = true)
        rule.onNode(hasContentDescription("Camera preview")).assertIsDisplayed()
        rule.waitUntil(5_000) { camera.started == 1 }
        rule.onAllNodesWithText("Allow the camera").assertCountEquals(0)
        camera.made.single().first("paddock://pair?v=1")
        assertEquals(listOf("paddock://pair?v=1"), taps.payloads)
        rule.onNodeWithText("Paste a pairing link").assertIsDisplayed()
    }

    @Test fun aCameraThatCannotStartSaysSoAndTryAgainMakesANewOne() {
        val (_, camera) = page(granted = true)
        rule.waitUntil(5_000) { camera.made.size == 1 }
        camera.made.single().second("No back camera is available")
        rule.onNodeWithText("The camera could not start: No back camera is available").assertIsDisplayed()
        rule.onNodeWithText("Paste a pairing link").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        rule.waitUntil(5_000) { camera.made.size == 2 }
        assertEquals("the failed scanner was closed", 1, camera.closed)
        rule.onAllNodesWithText("The camera could not start", substring = true).assertCountEquals(0)
    }

    @Test fun leavingThePageClosesTheCamera() {
        val camera = FakeCamera()
        var shown by mutableStateOf(true)
        content { if (shown) ScanLink(true, {}, {}, {}, {}, {}, scannerFactory = camera.factory) }
        rule.waitUntil(5_000) { camera.made.size == 1 }
        rule.runOnUiThread { shown = false }
        rule.waitForIdle()
        assertEquals(1, camera.closed)
    }

    @Test fun leavingTheAppAndComingBackClosesTheCameraAndStartsANewOneInsteadOfFailing() {
        val (_, camera) = page(granted = true)
        rule.waitUntil(5_000) { camera.started == 1 }
        // Home or the lock screen: the activity stops and its preview surface goes. Nothing may keep the camera open meanwhile.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(5_000) { camera.closed == 1 }
        // Back: the surface comes again, and with it a new scanner on it (the closed one cannot start twice).
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { camera.started == 2 }
        assertEquals(2, camera.made.size)
        assertEquals("the first scanner was closed once, the second is running", 1, camera.closed)
        rule.onAllNodesWithText("The camera could not start", substring = true).assertCountEquals(0)
        rule.onNode(hasContentDescription("Camera preview")).assertIsDisplayed()
    }

    @Test fun aCodeTheCallerRefusesDoesNotRestartTheCameraNorCloseIt() {
        // The page reports each code and leaves the decision to its caller; a code that is not a pairing link keeps the camera running.
        val (taps, camera) = page(granted = true)
        rule.waitUntil(5_000) { camera.started == 1 }
        val deliver = camera.made.single().first
        deliver("https://example.com/menu")
        deliver("https://example.com/menu")
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/menu", "https://example.com/menu"), taps.payloads)
        assertEquals("no new scanner", 1, camera.made.size)
        assertEquals("no restart", 1, camera.started)
        assertEquals("not closed", 0, camera.closed)
        rule.onNode(hasContentDescription("Camera preview")).assertIsDisplayed()
    }

    @Test fun aNoticeAboutTheLastCodeShowsAboveTheCamera() {
        page(granted = true, notice = "That is not a Paddock pairing link. Nothing was filled in.")
        rule.onNodeWithText("That is not a Paddock pairing link. Nothing was filled in.").assertIsDisplayed()
    }

    @Test fun backLeavesTheScanner() {
        val (taps, _) = page(granted = false)
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals(1, taps.back)
    }

    @Test fun theFullestStateIsReadableAtTwiceTheFontSize() {
        page(granted = false, fontScale = 2f)
        rule.onNodeWithText("Allow the camera").assertIsDisplayed()
        rule.onNodeWithText("Paste a pairing link").assertIsDisplayed()
        rule.onNodeWithText(SCAN_CAMERA_OFF).performScrollTo().assertIsDisplayed()
    }

    @Test fun auditNoCameraDark() { page(granted = false); SemanticsAudit.expectClean(rule, "Scan the code, no camera, dark") }
    @Test fun auditNoCameraLight() { page(granted = false, dark = false); SemanticsAudit.expectClean(rule, "Scan the code, no camera, light") }
    // The live preview is a SurfaceView, which a screenshot-based contrast audit cannot sample on every API level (API 26 reads it wrongly); the preview state is looked at in the harness run instead.

    // ---- The entry points ---------------------------------------------------------------------------------------------------------

    @Test fun welcomeOffersTheScanner() {
        var scans = 0
        content { Welcome(onEnterAddress = {}, onScan = { scans++ }) }
        rule.onNodeWithText("Scan the code on the desktop").performClick()
        assertEquals(1, scans)
        rule.onNodeWithText("Enter the address").assertIsDisplayed()
    }

    @Test fun addMachineOffersTheScannerBesidePasteAndKeepsTheTraditionalControls() {
        var scans = 0
        content {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, null, KeyBacking.Tee),
                onConnect = {}, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = {}, onBack = {},
                onPastePairingLink = {}, onScan = { scans++ },
            )
        }
        rule.onNodeWithText("Scan the code on the desktop").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, scans)
        rule.onNodeWithText("Paste a pairing link").assertIsDisplayed()
        rule.onNodeWithText("Create this phone's key").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test fun addMachineWithoutAScannerHasNoScanButton() {
        content {
            AddMachine(
                AddMachineState({ AddMachineForm.route(it, GateDecision.NotRequired) }, null, KeyBacking.Tee),
                onConnect = {}, onGenerateKey = {}, onCopyPublicKey = {}, onOpenSettings = {}, onBack = {}, initial = AddMachineInput(),
            )
        }
        rule.onAllNodesWithText("Scan the code on the desktop").assertCountEquals(0)
    }
}
