package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.RECONNECT_POLICY
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for Settings and the FLAG_SECURE effect (Phase 04 slice 8). */
class SettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Calls { var protect: Boolean? = null; var system = 0; var back = 0; var add = 0 }

    private val machine = io.github.tuthan.paddock.ui.screens.MachineSummary("Laptop", "jdoe@10.0.0.2:22", null)

    private fun show(
        protect: Boolean = true, local: LocalAccess = LocalAccess.NotRequired, fontScale: Float? = null, dark: Boolean = true, calls: Calls = Calls(),
        machine: io.github.tuthan.paddock.ui.screens.MachineSummary? = this.machine,
    ): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    Settings(SettingsState(protect, local, "0.1.0", machine = machine, herdrVersion = "0.9.1"), { calls.protect = it }, { calls.system++ }, { calls.back++ }, onAddMachine = { calls.add++ })
                }
            }
        }
        return calls
    }

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun monitoringIsOnAndTheReconnectPolicyIsShortThenStatedInFullOnATap() {
        show()
        rule.onNode(hasContentDescription("Monitor while the app is open", substring = true)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(io.github.tuthan.paddock.ui.screens.RECONNECT_SHORT).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(RECONNECT_POLICY).performScrollTo().assertIsDisplayed()
        shoot("settings")
    }

    @Test fun theWatchedMachineIsListedAndAddingAnotherIsHere() {
        val calls = show()
        rule.onNode(hasContentDescription("Laptop, jdoe@10.0.0.2:22 · default session", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Add another machine").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.add)
    }

    @Test fun withNoMachineTheButtonSaysAddAMachine() {
        show(machine = null)
        rule.onNodeWithText("Add a machine").assertIsDisplayed()
    }

    @Test fun aboutNamesTheHerdrVersionAndTheProjectsIndependence() {
        show()
        rule.onNode(hasContentDescription("Version, 0.1.0")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("host on 0.9.1", substring = true).assertExists()
        rule.onNodeWithText("not affiliated with herdr", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun aboutLinksTheFontLicencesAndTheDialogShowsBothAndCloses() {
        show()
        rule.onNode(hasContentDescription("Fonts, IBM Plex Sans and JetBrains Mono", substring = true)).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Font licences").assertIsDisplayed()
        // The text is read from the APK's assets off the main thread: wait for it rather than assume.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Reserved Font Name \"Plex\"", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("JetBrains Mono Project Authors", substring = true).assertExists()
        rule.onNodeWithText("SIL OPEN FONT LICENSE Version 1.1", substring = true).assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Font licences").assertDoesNotExist()
    }

    @Test fun notificationRowsAreDisabledAndSaySo() {
        show()
        for (label in listOf("Needs you", "Done")) {
            val node = rule.onNode(hasContentDescription("$label, Not yet", substring = true)).performScrollTo()
            node.assertIsDisplayed()
            assertTrue(node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            assertTrue(node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.single().endsWith("unavailable"))
        }
    }

    @Test fun theThemeRowIsFixedAndNamesThePalette() {
        show()
        rule.onNode(hasContentDescription("Theme, Paddock palette", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theProtectToggleReportsTheNewValueAndIsAnnouncedWithItsState() {
        val calls = show(protect = true)
        rule.onNodeWithText("Protect sensitive screens").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(false, calls.protect)
        val state = rule.onNodeWithText("Protect sensitive screens").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("On", state)
    }

    @Test fun nothingAboutTheLocalNetworkShowsWhenTheGrantDoesNotApply() {
        show(local = LocalAccess.NotRequired)
        rule.onNodeWithText("Local network").assertDoesNotExist() // kicker text is upper case, so also check the row
        rule.onNodeWithText("LOCAL NETWORK").assertDoesNotExist()
    }

    @Test fun aDeniedGrantShowsTheRecoveryRowHere() {
        val calls = show(local = LocalAccess.Denied)
        rule.onNodeWithText("Local-network access is off", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, calls.system)
        shoot("settings-permission-denied")
    }

    @Test fun aGrantedLocalNetworkIsStatedNotHidden() {
        show(local = LocalAccess.Granted)
        rule.onNode(hasContentDescription("Local-network access, Allowed", substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test fun backIsAtLeastFortyEightDp() {
        val calls = show()
        rule.onNode(hasContentDescription("Back")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.back)
    }

    @Test fun everythingIsReachableAtTwoHundredPercentFont() {
        show(fontScale = 2f, local = LocalAccess.Denied)
        rule.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Protect sensitive screens").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Version, 0.1.0", substring = true)).performScrollTo().assertIsDisplayed()
        shoot("settings-200")
    }

    @Test fun lightThemeRenders() {
        show(dark = false)
        rule.onNodeWithText("Protect sensitive screens").performScrollTo().assertIsDisplayed()
        shoot("settings-light")
    }

    // --- FLAG_SECURE ---

    private fun withSecure(initial: Boolean) {
        var on by mutableStateOf(initial)
        flag = { on = it }
        rule.setContent { SecureWindow(on) }
    }
    private var flag: (Boolean) -> Unit = {}

    @Test fun theWindowIsSecureOnlyWhileProtectionIsOnAndTheScreenIsShown() {
        assertFalse(secure())
        withSecure(true)
        rule.waitForIdle()
        assertTrue(secure())
        rule.runOnUiThread { flag(false) }
        rule.waitForIdle()
        assertFalse("turning the toggle off removes the flag", secure())
        rule.runOnUiThread { flag(true) }
        rule.waitForIdle()
        assertTrue(secure())
    }

    @Test fun leavingTheScreenRemovesTheFlagItAdded() {
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureWindow(true) }
        rule.waitForIdle()
        assertTrue(secure())
        rule.runOnUiThread { shown = false }
        rule.waitForIdle()
        assertFalse(secure())
    }

    @Test fun twoScreensThatAskForTheFlagKeepItUntilTheLastOneLeavesInEitherOrder() {
        var a by mutableStateOf(true)
        var b by mutableStateOf(true)
        rule.setContent { if (a) SecureWindow(true); if (b) SecureWindow(true) }
        rule.waitForIdle()
        assertTrue(secure())
        rule.runOnUiThread { a = false }
        rule.waitForIdle()
        assertTrue("the first to leave must not clear the second's protection", secure())
        rule.runOnUiThread { b = false }
        rule.waitForIdle()
        assertFalse(secure())
        rule.runOnUiThread { b = true }
        rule.waitForIdle()
        rule.runOnUiThread { a = true }
        rule.waitForIdle()
        rule.runOnUiThread { b = false }
        rule.waitForIdle()
        assertTrue(secure())
        rule.runOnUiThread { a = false }
        rule.waitForIdle()
        assertFalse(secure())
    }

    @Test fun aFlagSomeoneElseSetIsLeftAloneWhenTheScreenGoesAway() {
        rule.runOnUiThread { rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureWindow(true) }
        rule.waitForIdle()
        rule.runOnUiThread { shown = false }
        rule.waitForIdle()
        assertTrue(secure())
    }

    @Test fun keepPromptTextIsOffByDefaultAndTheSwitchSaysWhatItChanges() {
        var kept: Boolean? = null
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machine = machine), {}, {}, {}, onKeepPromptText = { kept = it })
            }
        }
        rule.onNode(hasContentDescription("Keep prompt text", substring = true).or(androidx.compose.ui.test.hasText("Keep prompt text"))).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Off: the record of what you sent keeps only a fingerprint of each prompt.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Keep prompt text").performScrollTo().performClick()
        assertEquals(true, kept)
    }

    @Test fun theSnippetsRowShowsTheCountAndOpensTheEditor() {
        var opened = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machine = machine, snippetCount = 3), {}, {}, {}, onEditSnippets = { opened++ })
            }
        }
        rule.onNode(hasContentDescription("Snippets, 3 saved", substring = true)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }

    @Test fun anUnreadableSendRecordSaysSendingIsOffAndOffersRetryAndAResetThatAsksFirst() {
        var retried = 0; var reset = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                Settings(
                    SettingsState(true, LocalAccess.NotRequired, "0.1.0", machine = machine, journalUnreadable = "the saved journal is not valid"),
                    {}, {}, {}, onRetryJournal = { retried++ }, onResetJournal = { reset++ },
                )
            }
        }
        rule.onNodeWithText("Sending is off.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("the saved journal is not valid", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Retry").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, retried)

        // The reset forgets unknown outcomes, so a tap only asks; Cancel is the safe choice and changes nothing.
        rule.onNodeWithText("Reset the record…").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Reset the record of sends?").assertIsDisplayed()
        rule.onNodeWithText("an earlier prompt may already have arrived", substring = true).assertIsDisplayed()
        assertEquals(0, reset)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Reset the record of sends?").assertDoesNotExist()
        assertEquals(0, reset)

        rule.onNodeWithText("Reset the record…").performScrollTo().performClick()
        rule.onNodeWithText("Reset the record").performClick()
        assertEquals(1, reset)
        rule.onNodeWithText("Reset the record of sends?").assertDoesNotExist()
    }

    @Test fun aReadableSendRecordShowsNoSendRecordCard() {
        rule.setContent { PaddockTheme(darkTheme = true) { Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machine = machine), {}, {}, {}) } }
        rule.onNodeWithText("Sending is off.", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reset the record…").assertDoesNotExist()
    }
}
