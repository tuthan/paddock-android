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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.PaddockApp
import io.github.tuthan.paddock.ProGateHost
import io.github.tuthan.paddock.SettingsRoute
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.EntitlementState
import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.billing.ProCopy
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.billing.ProStatus
import io.github.tuthan.paddock.billing.ProView
import io.github.tuthan.paddock.proCard
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.ABOUT_THEME
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.ProCardState
import io.github.tuthan.paddock.ui.screens.RECONNECT_EXPANDED
import io.github.tuthan.paddock.ui.screens.RECONNECT_MONITORING
import io.github.tuthan.paddock.ui.screens.RECONNECT_POLICY
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for Settings and the FLAG_SECURE effect (Phase 04 slice 8). */
class SettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Calls { var protect: Boolean? = null; var system = 0; var back = 0; var machines = 0; var overview = 0 }

    private fun show(
        protect: Boolean = true, local: LocalAccess = LocalAccess.NotRequired, fontScale: Float? = null, dark: Boolean = true, calls: Calls = Calls(),
        machineCount: Int = 1, pro: ProCardState = ProCardState(),
    ): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    Settings(
                        SettingsState(protect, local, "0.1.0", machineCount = machineCount, watchedMachine = "Laptop", herdrVersion = "0.9.1", pro = pro),
                        { calls.protect = it }, { calls.system++ }, { calls.back++ }, onMachines = { calls.machines++ }, onProOverview = { calls.overview++ },
                    )
                }
            }
        }
        return calls
    }

    /** The free version's card, as `proCard` builds it for a locked foss build: the headline, where Pro comes from, and the overview row. */
    private val freeCard = ProCardState("Free version", ProCopy.FREE_VERSION_ROUTE, overview = ProCopy.coverageShort(ProGate.GATED))

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun theReconnectPolicyIsShortThenStatedInFullWithWhenPaddockChecksOnATap() {
        show()
        // The inert "Monitor while the app is open · On" row is gone (D5); its fact is the last sentence of the expanded policy.
        assertEquals("Paddock checks your agents only while this app is open and stops a few seconds after you leave it.", RECONNECT_MONITORING)
        assertEquals("$RECONNECT_POLICY $RECONNECT_MONITORING", RECONNECT_EXPANDED)
        assertEquals(0, rule.onAllNodesWithText("Monitor while the app is open", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasContentDescription("Monitor while the app is open", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithText(io.github.tuthan.paddock.ui.screens.RECONNECT_SHORT).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(RECONNECT_EXPANDED).performScrollTo().assertIsDisplayed()
        shoot("settings")
    }

    // ---- decision D5: the sections and their order --------------------------------------------------------------------------------

    /** The section kickers (headings) as drawn, top to bottom; the screen's own title is not one of them. */
    private fun kickers(): List<String> =
        rule.onAllNodes(isHeading()).fetchSemanticsNodes()
            .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.let { it to n.positionInRoot.y } }
            .sortedBy { it.second }.map { it.first }.filter { it != "Settings" }

    @Test fun theSectionsComeInTheirOrder() {
        show(pro = freeCard)
        assertEquals(listOf("MACHINES", "ALERTS", "SENDING", "APPEARANCE", "PRIVACY", "PRO", "CONNECTION", "ABOUT"), kickers())
    }

    @Test fun theLocalNetworkRowsFollowReconnectInsideConnection() {
        show(pro = freeCard, local = LocalAccess.Granted)
        assertEquals(listOf("MACHINES", "ALERTS", "SENDING", "APPEARANCE", "PRIVACY", "PRO", "CONNECTION", "LOCAL NETWORK", "ABOUT"), kickers())
    }

    @Test fun sendingHoldsSnippetsAndPointsToTheMachinePageForGuardedAnswers() {
        show()
        val top = { d: String -> rule.onNode(hasContentDescription(d, substring = true)).getUnclippedBoundsInRoot().top }
        val sending = rule.onNodeWithText("SENDING").getUnclippedBoundsInRoot().bottom
        val appearance = rule.onNodeWithText("APPEARANCE").getUnclippedBoundsInRoot().top
        assertTrue("Snippets sit under Sending", sending <= top("Snippets, ") && top("Snippets, ") < appearance)
        // Guarded answers are per machine now: no row here, one line that says where they went.
        assertEquals(0, rule.onAllNodes(hasContentDescription("Guarded answers", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithText("Guarded answers (Yes or No for an agent's permission prompt) are set up per machine: Machines, then the machine's page.").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("ANSWERS").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("PROMPTS").fetchSemanticsNodes().size)
    }

    // ---- the Machines row (decision D3, 2026-10-06): the saved machines live on their own screen ----------------------------------------

    @Test fun theMachinesRowCountsTheSavedMachinesNamesTheWatchedOneAndOpensTheList() {
        val calls = show(machineCount = 2)
        rule.onNodeWithText("2 saved · watching Laptop").assertIsDisplayed()
        rule.onNode(hasContentDescription("Machines, 2 saved · watching Laptop")).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.machines)
    }

    @Test fun oneSavedMachineSaysSo() {
        show(machineCount = 1)
        rule.onNodeWithText("1 saved · watching Laptop").assertIsDisplayed()
    }

    @Test fun wakingAddingAndTheChipNoteLeftSettingsForTheMachinesScreen() {
        show()
        // Wake-on-LAN is on each machine's page now (decision D2: for every saved machine, not only the watched one), and so is Add another machine.
        rule.onNodeWithText("WAKE-ON-LAN").assertDoesNotExist()
        rule.onNodeWithText("Wake the machine").assertDoesNotExist()
        rule.onNodeWithText("Add another machine").assertDoesNotExist()
        rule.onNodeWithText("Add a machine").assertDoesNotExist()
        rule.onNodeWithText("The machine chip on Home", substring = true).assertDoesNotExist()
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

    private fun showAlerts(alerts: io.github.tuthan.paddock.ui.screens.AlertsState, fontScale: Float? = null, onAlerts: (Boolean) -> Unit = {}, onHide: (Boolean) -> Unit = {}, onRecovery: (io.github.tuthan.paddock.alerts.AccessRecovery.Action) -> Unit = {}) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = true) {
                    Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop", alerts = alerts), {}, {}, {}, onLocalAlerts = onAlerts, onHideOnLockScreen = onHide, onAlertRecovery = onRecovery)
                }
            }
        }
    }

    private class LockCalls { val lock = mutableListOf<Boolean>(); val after = mutableListOf<Int>(); var security = 0 }

    private fun showLock(
        on: Boolean = false, after: Int = 60, deviceLock: io.github.tuthan.paddock.applock.DeviceLock = io.github.tuthan.paddock.applock.DeviceLock.Ready,
        fontScale: Float? = null, dark: Boolean = true,
    ): LockCalls {
        val calls = LockCalls()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    Settings(
                        SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop", appLock = on, appLockAfterSeconds = after, deviceLock = deviceLock),
                        {}, {}, {}, onAppLock = { calls.lock += it }, onAppLockAfter = { calls.after += it }, onOpenSecuritySettings = { calls.security++ },
                    )
                }
            }
        }
        return calls
    }

    @Test fun theLockIsOffByDefaultAndTurningItOnAsksTheCaller() {
        val calls = showLock()
        rule.onNodeWithText("Lock Paddock").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals("Off", announced("Lock Paddock"))
        rule.onNodeWithText("Paddock keeps no code of its own.", substring = true).assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Ask again after")).assertCountEquals(0)
        rule.onNodeWithText("Lock Paddock").performClick()
        assertEquals(listOf(true), calls.lock)
    }

    @Test fun whenTheLockIsOnTheTimeoutIsAChoiceAndTheScopeIsSaid() {
        val calls = showLock(on = true, after = 300)
        assertEquals("On", announced("Lock Paddock"))
        rule.onNodeWithText("ASK AGAIN AFTER").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("5 minutes").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Immediately").performScrollTo().performClick()
        rule.onNodeWithText("15 minutes").performScrollTo().performClick()
        assertEquals(listOf(0, 900), calls.after)
        rule.onNodeWithText("Home-screen widgets and notifications are not covered", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Lock Paddock").performClick()
        assertEquals(listOf(false), calls.lock)
    }

    @Test fun aPhoneWithoutAScreenLockCannotTurnItOnAndPointsToAndroidsSecuritySettings() {
        val calls = showLock(deviceLock = io.github.tuthan.paddock.applock.DeviceLock.NotSet)
        rule.onNodeWithText("Lock Paddock").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("This phone has no screen lock.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open security settings").performScrollTo().performClick()
        assertEquals(1, calls.security); assertEquals(emptyList<Boolean>(), calls.lock)
    }

    @Test fun aLockThatIsOnCanStillBeTurnedOffWhenTheScreenLockIsGone() {
        showLock(on = true, deviceLock = io.github.tuthan.paddock.applock.DeviceLock.NotSet)
        rule.onNodeWithText("Lock Paddock").performScrollTo().assertIsEnabled()
    }

    @Test fun theLockRowHoldsAt200PercentFont() {
        showLock(on = true, fontScale = 2f)
        rule.onNodeWithText("Lock Paddock").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("15 minutes").performScrollTo().assertIsDisplayed()
    }

    @Test fun auditSettingsLockOnDark() { showLock(on = true); SemanticsAudit.expectClean(rule, "Settings, lock on, dark") }
    @Test fun auditSettingsLockNoScreenLockLight() { showLock(deviceLock = io.github.tuthan.paddock.applock.DeviceLock.NotSet, dark = false); SemanticsAudit.expectClean(rule, "Settings, no screen lock, light") }

    private fun announced(label: String) = rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun alertsAreOffByDefaultAndTheLockScreenRedactionIsOn() {
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState())
        rule.onNodeWithText("Alerts from this app").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals("Off", announced("Alerts from this app"))
        rule.onNodeWithText("Hide prompt text on the lock screen").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals("On", announced("Hide prompt text on the lock screen"))
        rule.onNodeWithText("Paddock does not watch in the background in this version.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Every notification has two buttons, Open and Review.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun theRelayModeNamesItsProviderInSettings() {
        // The privacy checklist: relay mode says who carries the message, in the row that opens the alert relay screen, not only inside it.
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState())
        rule.onNodeWithText(io.github.tuthan.paddock.alerts.AlertDelivery.MODE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Through ntfy: the public server or your own").assertIsDisplayed()
    }

    @Test fun theSwitchesReportTheirNewValues() {
        var alerts: Boolean? = null; var hide: Boolean? = null
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(), onAlerts = { alerts = it }, onHide = { hide = it })
        rule.onNodeWithText("Alerts from this app").performScrollTo().performClick()
        rule.onNodeWithText("Hide prompt text on the lock screen").performScrollTo().performClick()
        assertEquals(true, alerts); assertEquals(false, hide)
    }

    @Test fun aDeniedPermissionHasARecoveryRowWithAnActionWhileAlertsAreOn() {
        var action: io.github.tuthan.paddock.alerts.AccessRecovery.Action? = null
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission(canAsk = true))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = true, recovery = recovery), onRecovery = { action = it })
        rule.onNodeWithText("Paddock needs your permission to show notifications.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Allow notifications").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(io.github.tuthan.paddock.alerts.AccessRecovery.Action.AskPermission, action)
    }

    @Test fun aPermissionAndroidWillNotAskForAgainSendsTheUserToSystemSettings() {
        var action: io.github.tuthan.paddock.alerts.AccessRecovery.Action? = null
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission(canAsk = false))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = true, recovery = recovery), onRecovery = { action = it })
        rule.onNodeWithText("Notifications are blocked for Paddock in system settings", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(io.github.tuthan.paddock.alerts.AccessRecovery.Action.OpenAppSettings, action)
    }

    @Test fun aDisabledChannelIsExplainedByName() {
        var action: io.github.tuthan.paddock.alerts.AccessRecovery.Action? = null
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.ChannelsBlocked(listOf(io.github.tuthan.paddock.alerts.AlertChannel.NeedsYou)))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = true, recovery = recovery), onRecovery = { action = it })
        rule.onNodeWithText("The \"Needs you\" channel is turned off in system settings", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open channel settings").performScrollTo().performClick()
        assertEquals(io.github.tuthan.paddock.alerts.AccessRecovery.Action.OpenChannelSettings, action)
    }

    @Test fun noRecoveryRowShowsWhileAlertsAreOffOrWhenNothingIsWrong() {
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission(canAsk = true))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = false, recovery = recovery))
        rule.onNodeWithText("Allow notifications").assertDoesNotExist()
    }

    @Test fun theAlertsSectionHoldsAt200PercentFont() {
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission(canAsk = true))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = true, recovery = recovery), fontScale = 2f)
        rule.onNodeWithText("Allow notifications").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Hide prompt text on the lock screen").performScrollTo().assertIsDisplayed()
    }

    @Test fun theThemeIsAFactOfAboutAndHasNoRowOfItsOwn() {
        show()
        // The inert "Theme · Paddock palette" row under Appearance is gone (D5); the fact is on About's version row.
        assertEquals("Paddock palette follows the system light or dark setting", ABOUT_THEME)
        assertEquals(0, rule.onAllNodesWithText("Theme").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasContentDescription("Theme", substring = true)).fetchSemanticsNodes().size)
        val version = rule.onNode(hasContentDescription("Version, 0.1.0"))
        version.performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("herdr protocol 22 · host on 0.9.1 · $ABOUT_THEME · no analytics, no crash uploads", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("under About", version.getUnclippedBoundsInRoot().top >= rule.onNodeWithText("ABOUT").getUnclippedBoundsInRoot().bottom)
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
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop"), {}, {}, {}, onKeepPromptText = { kept = it })
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
                Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop", snippetCount = 3), {}, {}, {}, onEditSnippets = { opened++ })
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
                    SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop", journalUnreadable = "the saved journal is not valid"),
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
        rule.setContent { PaddockTheme(darkTheme = true) { Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop"), {}, {}, {}) } }
        rule.onNodeWithText("Sending is off.", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reset the record…").assertDoesNotExist()
    }

    private fun showIcons(on: Boolean, fontScale: Float? = null, dark: Boolean = true, onIcons: (Boolean) -> Unit = {}) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    Settings(SettingsState(true, LocalAccess.NotRequired, "0.1.0", machineCount = 1, watchedMachine = "Laptop", agentGlyphs = on), {}, {}, {}, onAgentGlyphs = onIcons)
                }
            }
        }
    }

    @Test fun agentIconsIsAToggleUnderAppearanceOnByDefaultThatReportsItsNewValue() {
        var seen: Boolean? = null
        showIcons(on = true, onIcons = { seen = it })
        // One scroll, then all three positions: the column composes every card, so off-screen bounds are still laid out.
        rule.onNodeWithText("Agent icons").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val appearance = rule.onNodeWithText("APPEARANCE").getUnclippedBoundsInRoot()
        val icons = rule.onNodeWithText("Agent icons").getUnclippedBoundsInRoot()
        val privacy = rule.onNodeWithText("PRIVACY").getUnclippedBoundsInRoot()
        assertTrue("under Appearance", icons.top >= appearance.bottom && icons.top < privacy.top)
        assertEquals("On", announced("Agent icons"))
        rule.onNodeWithText("Pictures for the common agents, letters for the rest.").assertIsDisplayed()
        rule.onNodeWithText("Agent icons").performClick()
        assertEquals(false, seen)
    }

    @Test fun withAgentIconsOffTheToggleSaysSoAndOffersTheLettersExplanation() {
        showIcons(on = false)
        assertEquals("Off", announced("Agent icons"))
        rule.onNodeWithText("Two letters for every agent.").performScrollTo().assertIsDisplayed()
    }

    @Test fun agentIconsFitsAtDoubleFontSizeInTheLightTheme() {
        showIcons(on = true, fontScale = 2.0f, dark = false)
        rule.onNodeWithText("Agent icons").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Pictures for the common agents, letters for the rest.").assertIsDisplayed()
        shoot("settings-agent-icons-light-200")
    }

    // ---- decision D5: the Pro card's "What Pro covers" row and the overview it opens ------------------------------------------------

    @Test fun withoutProTheCardHasTheOverviewRowWithTheShortListAndNoNote() {
        val calls = show(pro = freeCard)
        val short = ProCopy.coverageShort(ProGate.GATED)
        rule.onNode(hasContentDescription("What Pro covers, $short")).performScrollTo().assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(short, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText(ProCopy.coverage(ProGate.GATED)).fetchSemanticsNodes().size)
        rule.onNode(hasContentDescription("What Pro covers, $short")).performClick()
        assertEquals("the row only asks for the overview; the sheet is the graph's", 1, calls.overview)
    }

    @Test fun withProHeldTheCoverageNoteShowsAndTheRowDoesNot() {
        // A held Pro exists only where Pro is sold (the play build), so the rule is held to proCard with a held view, in either build.
        val held = ProView(state = EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true), sellsPro = true)
        val card = proCard(held, nowMillis = 2L)
        assertEquals(ProCopy.coverage(ProGate.GATED), card.coverage)
        assertEquals("", card.overview)
        show(pro = card)
        rule.onNodeWithText(ProCopy.coverage(ProGate.GATED)).performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText(ProCopy.OVERVIEW_ROW).fetchSemanticsNodes().size)
    }

    @Test fun theCardsCoverageFollowsTheGrantRule() {
        val short = ProCopy.coverageShort(ProGate.GATED)
        // Not held, nothing sold (the published foss build): the row.
        proCard(ProView(sellsPro = false), 0L).let { assertEquals(short, it.overview); assertEquals("", it.coverage) }
        // Not held where Pro is sold, and a leftover PRO answer in a build that cannot verify it: the row.
        proCard(ProView(sellsPro = true), 0L).let { assertEquals(short, it.overview); assertEquals("", it.coverage) }
        proCard(ProView(state = EntitlementState(status = ProStatus.PRO, verifiedAtMillis = 1L, acknowledged = true), sellsPro = false), 2L)
            .let { assertEquals(short, it.overview); assertEquals("", it.coverage) }
        // A build that unlocks everything: neither, since nothing there is Pro.
        proCard(ProView(unlocked = true), 0L).let { assertEquals("", it.overview); assertEquals("", it.coverage) }
    }

    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PaddockApp).graph

    /** Settings and the gate host exactly as the app composes them, on the app's real graph, so the card and the sheet are this build's. */
    private fun showRoute() {
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                SettingsRoute(graph, onBack = {}, onMachines = {}, onEditSnippets = {}, onAlertRelay = {})
                ProGateHost(graph, pendingAnswerOnScreen = false)
            }
        }
    }

    @After fun clearTheGate() { graph.dismissGate(); graph.dismissGateNotice() }

    private fun inSheet(m: SemanticsMatcher) = rule.onNode(m and hasAnyAncestor(isDialog()))

    @Test fun aLockedBuildsOverviewRowOpensTheSheetTitledWhatProCoversWithEveryLineAndNoFreePath() {
        assumeTrue("a build with -PpaddockUnlocked=false", !Distribution.UNLOCKED)
        assertEquals("what the gate sees as busy: ${graph.journal.records.value.filter { it.inFlight }}", GateContext.IDLE, graph.gateContext(false))
        showRoute()
        val short = ProCopy.coverageShort(ProGate.GATED)
        rule.onNode(hasContentDescription("What Pro covers, $short")).performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(ProCopy.OVERVIEW_ID, graph.gateRequest.value)
        inSheet(hasText("What Pro covers")).assertIsDisplayed()
        val lines = ProCopy.coverageLines(ProGate.GATED)
        assertEquals(5, lines.size)
        inSheet(hasContentDescription("Pro covers: " + lines.joinToString(", "))).assertIsDisplayed()
        // No capability was chosen, so no free path for one: every free path starts "Free: ".
        assertEquals(0, rule.onAllNodes(hasText("Free: ", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
        if (Distribution.SELLS_PRO) {
            inSheet(hasText("Buy Pro", substring = true)).performScrollTo().assertIsDisplayed()
        } else {
            inSheet(hasText(ProCopy.FREE_VERSION_ROUTE)).performScrollTo().assertIsDisplayed()
            assertEquals(0, rule.onAllNodesWithText("Buy Pro", substring = true).fetchSemanticsNodes().size)
        }
        shoot("settings-pro-overview")
        inSheet(hasText("Not now")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(null, graph.gateRequest.value)
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    @Test fun anUnlockedBuildHasNoOverviewRowNoCoverageNoteAndDropsAnOverviewRequest() {
        assumeTrue("the unlocked foss debug build", Distribution.UNLOCKED)
        showRoute()
        rule.onNodeWithText("Everything is unlocked").performScrollTo().assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText(ProCopy.OVERVIEW_ROW).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasContentDescription(ProCopy.OVERVIEW_ROW, substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText(ProCopy.coverage(ProGate.GATED)).fetchSemanticsNodes().size)
        // Asked for anyway, the host drops it: Pro is held, so there is nothing to show.
        rule.runOnIdle { graph.openProOverview() }
        rule.waitForIdle()
        assertEquals(null, graph.gateRequest.value)
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditSettingsDark() { show(); SemanticsAudit.expectClean(rule, "Settings, dark") }
    @Test fun auditSettingsLight() { show(dark = false); SemanticsAudit.expectClean(rule, "Settings, light") }
    @Test fun auditSettingsWithTheProOverviewRowDark() { show(pro = freeCard); SemanticsAudit.expectClean(rule, "Settings, Pro overview row, dark") }
    @Test fun auditSettingsWithTheProOverviewRowLight() { show(pro = freeCard, dark = false); SemanticsAudit.expectClean(rule, "Settings, Pro overview row, light") }

    @Test fun auditSettingsAlertsOnWithARecoveryRowDark() {
        val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission(canAsk = true))
        showAlerts(io.github.tuthan.paddock.ui.screens.AlertsState(localAlerts = true, recovery = recovery)); SemanticsAudit.expectClean(rule, "Settings, alerts on with recovery, dark")
    }

}
