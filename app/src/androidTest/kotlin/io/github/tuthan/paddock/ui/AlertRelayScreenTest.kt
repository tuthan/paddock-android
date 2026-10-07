package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AlertRelayStatus
import io.github.tuthan.paddock.alerts.DeliveryMode
import io.github.tuthan.paddock.alerts.RelayCheck
import io.github.tuthan.paddock.alerts.SavedAlertSetup
import io.github.tuthan.paddock.alerts.ServiceState
import io.github.tuthan.paddock.alerts.SetupStep
import io.github.tuthan.paddock.alerts.StepState
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.screens.AlertForm
import io.github.tuthan.paddock.ui.screens.AlertRelay
import io.github.tuthan.paddock.ui.screens.AlertRelayHostState
import io.github.tuthan.paddock.ui.screens.AlertRelayUi
import io.github.tuthan.paddock.ui.screens.PushDistributorUi
import io.github.tuthan.paddock.ui.screens.PushRegisteredUi
import io.github.tuthan.paddock.ui.screens.PushUi
import io.github.tuthan.paddock.ui.screens.SetupRunUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The locked-phone alerts screen: one form (how the alert reaches you, which server), one confirmation that lists what will be written on the machine, and
 * the rest done for you. The hash of the relay is shown before anything is installed, nothing runs before the confirmation, and the manual commands are the
 * exact text that runs.
 */
class AlertRelayScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val hash = "a0829ba8cbb0e01a354ba8f53a8b373fe32f3b850b211885f5787e3ff9df6abe"
    private val path = "/home/u/.local/share/paddock/paddock-alert-relay.py"
    private val topic = "Zx9_abcdefghijklmnopqrstuvwxyz0123"
    private class Calls {
        var turnOn = 0; var turnOff = 0; var test = 0; var check = 0; var copy = 0; var install = 0; var back = 0
        val forms = mutableListOf<AlertForm>(); val opened = mutableListOf<String>(); val copiedLinks = mutableListOf<String>()
    }

    private fun status(script: RelayState, check: RelayCheck? = null, service: ServiceState = ServiceState.NotInstalled) =
        AlertRelayHostState.Known(AlertRelayStatus(script, path, check, service))

    private val running = status(RelayState.Current, RelayCheck(0, listOf("config ok; herdr reachable")), ServiceState.Active)

    private fun show(
        host: AlertRelayHostState, form: AlertForm = AlertForm(), calls: Calls = Calls(), fontScale: Float? = null, dark: Boolean = true,
        saved: SavedAlertSetup? = null, push: PushUi = PushUi(), run: SetupRunUi = SetupRunUi(), script: String? = "mkdir -p ~/.config/paddock\nsystemctl --user enable paddock-alert-relay.service",
        installError: String? = null, result: String? = null, resultIsProblem: Boolean = false, busy: Boolean = false,
        platform: io.github.tuthan.paddock.alerts.ServicePlatform = io.github.tuthan.paddock.alerts.ServicePlatform.Systemd,
    ): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    AlertRelay(
                        AlertRelayUi("Laptop", "laptop", hash, host, saved, push, run, script, installError = installError, busy = busy, result = result, resultIsProblem = resultIsProblem, platform = platform),
                        form, { calls.forms += it }, { calls.back++ },
                        onTurnOn = { calls.turnOn++ }, onTurnOff = { calls.turnOff++ }, onTest = { calls.test++ }, onCheck = { calls.check++ }, onCopy = { calls.copy++ },
                        onInstall = { calls.install++ }, onOpenNtfy = { calls.opened += it }, onCopyLink = { calls.copiedLinks += it },
                    )
                }
            }
        }
        return calls
    }

    private val ntfySaved = SavedAlertSetup(DeliveryMode.NtfyApp, "https://ntfy.sh", topic)
    private val pushSaved = SavedAlertSetup(DeliveryMode.Push)
    private val oneDistributor = PushUi(distributors = listOf(PushDistributorUi("io.heckel.ntfy", "ntfy")))

    // ---- what the screen says first ----

    @Test fun theHowIsToldAndDeliveryIsLabelledBestEffortNeverAlwaysOn() {
        show(AlertRelayHostState.NotConnected)
        rule.onNodeWithText("Paddock can show the alert itself, or the ntfy app can", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Best effort.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("It is not an always-on service.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Measured delivery:", substring = true).assertExists()
    }

    @Test fun withoutAConnectionNothingCanBeStartedAndTheScreenSaysWhy() {
        show(AlertRelayHostState.NotConnected)
        rule.onNodeWithText("Paddock is not connected to Laptop", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Connect to Laptop to set up alerts.").performScrollTo().assertIsDisplayed()
    }

    @Test fun aReadFailureOffersTryAgain() {
        val calls = show(AlertRelayHostState.Failed("the connection dropped"))
        rule.onNodeWithText("Could not read the machine: the connection dropped").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, calls.check)
    }

    // ---- the form ----

    @Test fun theTwoWaysAreChoicesAndEachTapReportsTheNewForm() {
        val calls = show(status(RelayState.Missing), push = oneDistributor)
        rule.onNodeWithText("Paddock shows the alert").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Through ntfy.", substring = true).assertExists()
        rule.onNodeWithText("The ntfy app shows the alert").performScrollTo().performClick()
        rule.onNodeWithText("Paddock shows the alert").performClick()
        assertEquals(listOf(DeliveryMode.NtfyApp, DeliveryMode.Push), calls.forms.map { it.mode })
    }

    @Test fun thePublicServerNeedsNothingTypedAndOwnServerAsksForAnAddress() {
        val calls = show(status(RelayState.Missing))
        rule.onNodeWithText("Public server (ntfy.sh)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("My own server").performScrollTo().performClick()
        assertEquals(true, calls.forms.single().ownServer)
    }

    @Test fun anOwnServerWithoutAnAddressOrWithPlainHttpCannotStart() {
        show(status(RelayState.Missing), form = AlertForm(ownServer = true))
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Server address").assertExists()
        rule.onNodeWithText("Access token (optional)").assertExists()
    }

    @Test fun aServerAddressThatIsNotHttpsIsRefusedInWords() {
        show(status(RelayState.Missing), form = AlertForm(ownServer = true, ownUrl = "http://ntfy.example.org"))
        rule.onNodeWithText("Use an https address", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aValidOwnServerStarts() {
        show(status(RelayState.Missing), form = AlertForm(ownServer = true, ownUrl = "ntfy.example.org"))
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsEnabled()
    }

    @Test fun theAppShowsTheAlertModeWithoutADistributorSaysSoAndCannotStart() {
        show(status(RelayState.Missing), form = AlertForm(mode = DeliveryMode.Push))
        rule.onNodeWithText("Paddock needs the ntfy app", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun withTheDistributorInstalledThePaddockModeStartsAndSaysWhoPicksTheServer() {
        show(status(RelayState.Missing), form = AlertForm(mode = DeliveryMode.Push), push = oneDistributor)
        rule.onNodeWithText("The server is the one set in the ntfy app", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsEnabled()
    }

    @Test fun severalDistributorsAreAChoiceAndAKnownServerIsNamed() {
        val calls = show(
            status(RelayState.Missing), form = AlertForm(mode = DeliveryMode.Push),
            push = PushUi(distributors = listOf(PushDistributorUi("io.heckel.ntfy", "ntfy"), PushDistributorUi("org.other", "Other")),
                registered = PushRegisteredUi("ntfy", true, "ntfy.example.org", true, false, null)),
        )
        rule.onNodeWithText("Other").performScrollTo().performClick()
        assertEquals("org.other", calls.forms.single().distributor)
        rule.onNodeWithText("Alerts go through your server, ntfy.example.org.", substring = true).performScrollTo().assertIsDisplayed()
    }

    // ---- the one confirmation ----

    @Test fun nothingRunsBeforeTheConfirmationAndItListsWhatWillBeWritten() {
        val calls = show(status(RelayState.Missing))
        rule.onNodeWithText("Turn on alerts…").performScrollTo().performClick()
        assertEquals(0, calls.turnOn)
        rule.onNodeWithText("Turn on alerts on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("Nothing is started before the relay's own check", substring = true).assertIsDisplayed()
        rule.onNodeWithText("~/.config/paddock/alert-relay.toml, owner-only", substring = true).assertIsDisplayed()
        rule.onNodeWithText("~/.config/systemd/user/paddock-alert-relay.service", substring = true).assertIsDisplayed()
        rule.onNodeWithText("loginctl enable-linger", substring = true).assertIsDisplayed()
        rule.onNodeWithText(hash.take(16), substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, calls.turnOn)
        rule.onNodeWithText("Turn on alerts…").performScrollTo().performClick()
        rule.onNodeWithText("Turn on").performClick()
        assertEquals(1, calls.turnOn)
    }

    @Test fun anAlreadyPinnedRelayIsNotListedAsAWriteAndThePaddockModeNamesTheAddressFile() {
        show(running, form = AlertForm(mode = DeliveryMode.Push), push = oneDistributor)
        rule.onNodeWithText("Update alerts…").performScrollTo().performClick() // a relay is already running, so the button updates it
        rule.onAllNodes(hasText("Relay script", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("push-endpoint.json", substring = true).assertIsDisplayed()
        rule.onNodeWithText("whoever has the address", substring = true).assertExists()
    }

    // ---- where things stand ----

    @Test fun nothingSetUpSaysOff() {
        show(status(RelayState.Missing))
        rule.onNodeWithText("Alerts are off").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send a test alert").assertDoesNotExist()
    }

    @Test fun aRunningRelayThatWasSetUpHereSaysOnAndOffersTestAndTurnOff() {
        val calls = show(running, saved = ntfySaved)
        rule.onNodeWithText("Alerts are on").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("the ntfy app shows them, through the public ntfy.sh server", substring = true).assertExists()
        rule.onNodeWithText("Update alerts…").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Send a test alert").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, calls.test)
        rule.onNodeWithText("Turn off alerts…").performScrollTo().performClick()
        assertEquals("nothing is stopped before the confirmation", 0, calls.turnOff)
        rule.onNodeWithText("Turn off alerts on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("The configuration stays", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Turn off").performClick()
        assertEquals(1, calls.turnOff)
    }

    @Test fun aRelayNobodyHereSetUpIsSaidSoAndItsReplacementIsExplained() {
        show(running)
        rule.onNodeWithText("A relay is running").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("the old one is kept", substring = true).assertExists()
    }

    @Test fun aSetUpRelayThatIsNotRunningNeedsAttentionAndSaysWhichPart() {
        show(status(RelayState.Current, RelayCheck(0, emptyList()), ServiceState.Failed), saved = ntfySaved)
        rule.onNodeWithText("Alerts need attention").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The relay is failed on Laptop.", substring = true).assertExists()
        rule.onNodeWithText("Turn alerts on again to repair it.", substring = true).assertExists()
    }

    @Test fun aRefusedConfigurationShowsTheRelaysOwnReason() {
        show(status(RelayState.Current, RelayCheck(2, listOf("config: profile must be lowercase letters")), ServiceState.Failed), saved = ntfySaved)
        rule.onNodeWithText("The relay's own check fails: config: profile must be lowercase letters.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun aGoneAddressFileOfThePaddockModeNeedsAttention() {
        show(running, saved = pushSaved, push = PushUi(distributors = oneDistributor.distributors, onHost = false))
        rule.onNodeWithText("Alerts need attention").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The address file is missing on Laptop.", substring = true).assertExists()
    }

    // ---- progress and the end of it ----

    @Test fun eachStepIsListedWithItsStateInWordsAndAFailureSaysWhy() {
        show(
            status(RelayState.Missing),
            run = SetupRunUi(
                steps = listOf(SetupStep.Install to StepState.Done, SetupStep.Configure to StepState.Failed), running = false,
                message = "The relay refused the configuration: socket must be an absolute path.",
            ),
        )
        rule.onNodeWithText("Install the relay script").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("done").assertExists(); rule.onNodeWithText("failed").assertExists()
        rule.onNodeWithText("The relay refused the configuration: socket must be an absolute path.").performScrollTo().assertIsDisplayed()
    }

    @Test fun whileItRunsTheButtonSaysSoAndCannotBeTappedAgain() {
        show(status(RelayState.Missing), run = SetupRunUi(steps = listOf(SetupStep.Install to StepState.Running, SetupStep.Configure to StepState.Pending), running = true))
        rule.onNodeWithText("Setting up…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("working…").assertExists(); rule.onNodeWithText("waiting").assertExists()
    }

    @Test fun aFinishedSetupSaysAlertsAreOnAndPassesOnTheLingerNote() {
        show(running, saved = ntfySaved, run = SetupRunUi(steps = listOf(SetupStep.Configure to StepState.Done), finished = true, note = "The relay runs now but will stop when you log out."))
        rule.onNodeWithText("Alerts are on for Laptop.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("will stop when you log out", substring = true).assertExists()
    }

    // ---- subscribing the ntfy app ----

    @Test fun theSubscribeLinkOpensTheNtfyAppAndCanBeCopiedAndTheServerAndTopicAreShownForAnIphone() {
        val calls = show(running, saved = ntfySaved)
        val link = "ntfy://ntfy.sh/$topic"
        rule.onNodeWithText("Open in the ntfy app").performScrollTo().performClick()
        assertEquals(listOf(link), calls.opened)
        rule.onNodeWithText("Copy the subscribe link").performScrollTo().performClick()
        assertEquals(listOf(link), calls.copiedLinks)
        rule.onNode(hasContentDescription("Server: https://ntfy.sh")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Topic: $topic")).assertExists()
        rule.onNodeWithText("On an iPhone:", substring = true).assertExists()
        rule.onNodeWithText("never the alert", substring = true).assertExists()
    }

    @Test fun anOwnServerSaysTheNtfyAppNeedsTheSameLogin() {
        show(running, saved = SavedAlertSetup(DeliveryMode.NtfyApp, "https://ntfy.example.org", topic))
        rule.onNodeWithText("If your server needs a login", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun thePaddockModeHasNoSubscribeStepBecauseThereIsNoTopic() {
        show(running, saved = pushSaved)
        rule.onNodeWithText("Open in the ntfy app").assertDoesNotExist()
    }

    @Test fun aTestOrTurnOffResultIsShownAsANoteOrAProblem() {
        show(running, saved = ntfySaved, result = "Test sent. It should arrive on this phone within a few seconds.")
        rule.onNodeWithText("Test sent.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun theResultOfATestIsDrawnUnderTheButtonsThatAskedForIt() {
        // It used to sit under the status card at the top: a tap on "Send a test alert" at the bottom of the scroll showed "Working…" and then nothing in view.
        show(running, saved = ntfySaved, result = "Test sent. It should arrive on this phone within a few seconds.")
        // positionInRoot is the layout position, not clipped to what is on screen (boundsInRoot is empty for a node scrolled out of view).
        val test = rule.onNodeWithText("Send a test alert").performScrollTo().fetchSemanticsNode()
        val off = rule.onNodeWithText("Turn off alerts…").fetchSemanticsNode()
        val result = rule.onNodeWithText("Test sent.", substring = true).fetchSemanticsNode()
        assertTrue(
            "the result (y ${result.positionInRoot.y}) is not under the buttons (test y ${test.positionInRoot.y}, turn off y ${off.positionInRoot.y})",
            result.positionInRoot.y >= off.positionInRoot.y + off.size.height && off.positionInRoot.y > test.positionInRoot.y,
        )
    }

    @Test fun aFailedTestIsABanner() {
        show(running, saved = ntfySaved, result = "The test did not go out (HTTPError).", resultIsProblem = true)
        rule.onNodeWithText("The test did not go out (HTTPError).").performScrollTo().assertIsDisplayed()
    }

    // ---- details and the manual way ----

    @Test fun aMacsConfirmationNamesTheLaunchAgentAndTheLoginSessionNotSystemd() {
        show(status(RelayState.Missing), platform = io.github.tuthan.paddock.alerts.ServicePlatform.Launchd)
        rule.onNodeWithText("Turn on alerts…").performScrollTo().performClick()
        rule.onNodeWithText("~/Library/LaunchAgents/io.github.tuthan.paddock-alert-relay.plist", substring = true).assertIsDisplayed()
        rule.onNodeWithText("starts again at login", substring = true).assertIsDisplayed()
        rule.onNodeWithText("systemd", substring = true).assertDoesNotExist()
        rule.onNodeWithText("loginctl", substring = true).assertDoesNotExist()
    }

    @Test fun aMacsDetailsCallTheServiceALaunchAgent() {
        show(running, platform = io.github.tuthan.paddock.alerts.ServicePlatform.Launchd)
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNode(hasContentDescription("LaunchAgent: running")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("systemd user unit: running")).assertDoesNotExist()
    }

    @Test fun theDetailsAreClosedUntilAskedAndShowTheHashTheStateAndTheExactCommands() {
        val calls = show(running)
        rule.onNode(hasContentDescription("SHA-256 of paddock-alert-relay.py: $hash")).assertDoesNotExist()
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNode(hasContentDescription("SHA-256 of paddock-alert-relay.py: $hash")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Installed, and it is the pinned script.").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("systemd user unit: running")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("This is exactly what \"Turn on alerts\" runs", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("systemctl --user enable paddock-alert-relay.service", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copy the commands").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.copy)
        rule.onNodeWithText("Check the setup").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, calls.check)
    }

    @Test fun anIncompleteFormHasNoCommandsToCopy() {
        show(running, script = null)
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNodeWithText("The commands appear once Paddock is connected", substring = true).performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Copy the commands")).assertCountEquals(0)
    }

    @Test fun aMissingScriptIsInstalledFromTheDetailsOnlyAfterTheConfirmation() {
        val calls = show(status(RelayState.Missing))
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNodeWithText("Not installed on Laptop yet.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install the script…").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Install the alert relay on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("It does not write the unit or the configuration, and it does not start or enable anything.", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of the script: $hash")).assertExists()
        assertEquals(0, calls.install)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, calls.install)
        rule.onNodeWithText("Install the script…").performScrollTo().performClick()
        rule.onNodeWithText("Install").performClick()
        assertEquals(1, calls.install)
    }

    @Test fun aDifferentFileIsNeverRunAndTheButtonSaysReplace() {
        show(status(RelayState.Mismatch("0".repeat(64))))
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNodeWithText("A different file is there. It is never run; installing replaces it.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace the script…").performScrollTo().performClick()
        rule.onNodeWithText("Replace the alert relay on Laptop?").assertIsDisplayed()
    }

    @Test fun anInstallThatFailedSaysSo() {
        show(status(RelayState.Missing), installError = "The install did not finish: exit 1")
        rule.onNodeWithText("Details and manual setup").performScrollTo().performClick()
        rule.onNodeWithText("The install did not finish: exit 1").performScrollTo().assertIsDisplayed()
    }

    @Test fun theScreenHoldsAt200PercentFont() {
        show(status(RelayState.Missing), fontScale = 2f)
        rule.onNodeWithText("Turn on alerts…").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Details and manual setup").performScrollTo().assertIsDisplayed()
    }

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditAlertRelayOffDark() { show(status(RelayState.Missing)); SemanticsAudit.expectClean(rule, "Alert relay, off, dark") }
    @Test fun auditAlertRelayOffLight() { show(status(RelayState.Missing), dark = false); SemanticsAudit.expectClean(rule, "Alert relay, off, light") }
    @Test fun auditAlertRelayOnDark() { show(running, saved = ntfySaved); SemanticsAudit.expectClean(rule, "Alert relay, on, dark") }
    @Test fun auditAlertRelayOnLight() { show(running, saved = ntfySaved, dark = false); SemanticsAudit.expectClean(rule, "Alert relay, on, light") }
    @Test fun auditAlertRelayNeedsAttentionDark() {
        show(status(RelayState.Current, RelayCheck(2, listOf("config: profile must be lowercase letters")), ServiceState.Failed), saved = ntfySaved)
        SemanticsAudit.expectClean(rule, "Alert relay, needs attention, dark")
    }
}
