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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AlertRelayStatus
import io.github.tuthan.paddock.alerts.RelayCheck
import io.github.tuthan.paddock.alerts.ServiceState
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.screens.AlertRelay
import io.github.tuthan.paddock.ui.screens.AlertRelayHostState
import io.github.tuthan.paddock.ui.screens.AlertRelayUi
import io.github.tuthan.paddock.ui.screens.PushDistributorUi
import io.github.tuthan.paddock.ui.screens.PushRegisteredUi
import io.github.tuthan.paddock.ui.screens.PushUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The alert relay screen (Phase 07 slice 4): the hash is shown before anything can be installed or copied, and installing asks first. */
class AlertRelayScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val hash = "a0829ba8cbb0e01a354ba8f53a8b373fe32f3b850b211885f5787e3ff9df6abe"
    private val path = "/home/u/.local/share/paddock/paddock-alert-relay.py"
    private class Calls { var install = 0; var check = 0; var copy = 0; var back = 0 }

    private fun status(script: RelayState, check: RelayCheck? = null, service: ServiceState = ServiceState.NotInstalled) = AlertRelayHostState.Known(AlertRelayStatus(script, path, check, service))

    private fun show(host: AlertRelayHostState, calls: Calls = Calls(), commands: String? = "mkdir -p ~/.config/paddock\nsystemctl --user enable --now paddock-alert-relay.service", fontScale: Float? = null, dark: Boolean = true, installError: String? = null, installing: Boolean = false): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    AlertRelay(AlertRelayUi("Laptop", "laptop", hash, host, installing, installError, commands), { calls.back++ }, { calls.install++ }, { calls.check++ }, { calls.copy++ })
                }
            }
        }
        return calls
    }

    @Test fun theModeIsNamedAndDeliveryIsLabelledBestEffortNeverAlwaysOn() {
        show(AlertRelayHostState.NotConnected)
        rule.onNodeWithText("Alerts via ntfy app").assertIsDisplayed()
        rule.onNodeWithText("Best effort.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("It is not an always-on service.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Measured delivery:", substring = true).assertExists()
    }

    @Test fun theHashIsOnScreenAlongsideTheCopyButtonAndNotConnectedDisablesInstall() {
        show(AlertRelayHostState.NotConnected)
        rule.onNode(hasContentDescription("SHA-256 of paddock-alert-relay.py: $hash")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock is not connected to Laptop", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install the script…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Copy the setup commands").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun aMissingScriptIsInstalledOnlyAfterTheConfirmation() {
        val calls = show(status(RelayState.Missing))
        rule.onNodeWithText("Not installed on Laptop yet.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install the script…").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Install the alert relay on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("It does not write the unit or the configuration, and it does not start or enable anything.", substring = true).assertIsDisplayed()
        // the dialog repeats the file and the hash it is about to write (the screen behind it shows the file too)
        rule.onAllNodes(hasContentDescription("File on the host: $path")).assertCountEquals(2)
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
        rule.onNodeWithText("A different file is there. It is never run; installing replaces it.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace the script…").performScrollTo().performClick()
        rule.onNodeWithText("Replace the alert relay on Laptop?").assertIsDisplayed()
    }

    @Test fun theCurrentScriptShowsItsOwnCheckAndTheUnitState() {
        val calls = show(status(RelayState.Current, RelayCheck(0, listOf("config ok; herdr reachable (session default, delivery ntfy)")), ServiceState.Active))
        rule.onNodeWithText("Installed, and it is the pinned script.").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Check of its configuration:", substring = true)).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("config ok; herdr reachable", substring = true)).assertExists()
        rule.onNode(hasContentDescription("systemd user unit: running")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Check the setup").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, calls.check)
    }

    @Test fun aConfigProblemIsShownAsNeedingAttention() {
        show(status(RelayState.Current, RelayCheck(2, listOf("config: profile must be lowercase letters")), ServiceState.Failed))
        rule.onNode(hasContentDescription("needs attention (exit 2)", substring = true)).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("systemd user unit: failed")).performScrollTo().assertIsDisplayed()
    }

    @Test fun theSetupCommandsAreShownInFullBeforeTheyCanBeCopied() {
        val calls = show(status(RelayState.Current))
        rule.onNodeWithText("systemctl --user enable --now paddock-alert-relay.service", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock never enables the unit itself.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copy the setup commands").performScrollTo().performClick()
        assertEquals(1, calls.copy)
    }

    @Test fun anInstallThatFailedSaysSoAndAReadFailureOffersTryAgain() {
        show(status(RelayState.Missing), installError = "The install did not finish: exit 1")
        rule.onNodeWithText("The install did not finish: exit 1").performScrollTo().assertIsDisplayed()
    }

    @Test fun readingFailureOffersTryAgain() {
        val calls = show(AlertRelayHostState.Failed("the connection dropped"))
        rule.onNodeWithText("Could not read the machine: the connection dropped").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, calls.check)
    }

    @Test fun theScreenHoldsAt200PercentFont() {
        show(status(RelayState.Missing), fontScale = 2f)
        rule.onNodeWithText("Install the script…").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Copy the setup commands").performScrollTo().assertIsDisplayed()
    }

    // ---- connector mode (UnifiedPush) ----

    private class PushCalls { val registered = mutableListOf<String>(); var share = 0; var remove = 0 }
    private fun showPush(push: PushUi, host: AlertRelayHostState = status(RelayState.Current)): PushCalls {
        val calls = PushCalls()
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                AlertRelay(
                    AlertRelayUi("Laptop", "laptop", hash, host, push = push), {}, {}, {}, {},
                    onPushRegister = { calls.registered += it }, onPushShare = { calls.share++ }, onPushRemove = { calls.remove++ },
                )
            }
        }
        return calls
    }

    private fun registered(hasEndpoint: Boolean = true, shared: Boolean = false, failure: String? = null, installed: Boolean = true) =
        PushRegisteredUi("ntfy", installed, if (hasEndpoint) "ntfy.example.org" else null, hasEndpoint, shared, failure)

    @Test fun withoutADistributorTheModeIsUnavailableAndTheOtherModeIsPointedTo() {
        showPush(PushUi())
        rule.onNodeWithText("No UnifiedPush distributor is installed", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The ntfy-app mode above still works.", substring = true).assertExists()
    }

    @Test fun eachInstalledDistributorIsAChoiceAndTappingOneRegistersWithIt() {
        val calls = showPush(PushUi(distributors = listOf(PushDistributorUi("io.ntfy", "ntfy"), PushDistributorUi("org.other", "Other"))))
        rule.onNodeWithText("Register with Other").performScrollTo().performClick()
        assertEquals(listOf("org.other"), calls.registered)
        rule.onNodeWithText("Register with ntfy").assertExists()
    }

    @Test fun beforeTheAddressArrivesNothingCanBeSent() {
        showPush(PushUi(registered = registered(hasEndpoint = false)))
        rule.onNodeWithText("Waiting for the distributor's address", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send the address to Laptop…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theAddressIsSentOnlyAfterAConfirmationThatNamesTheFileAndTheRisk() {
        val calls = showPush(PushUi(registered = registered()))
        rule.onNodeWithText("It is not on Laptop yet.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send the address to Laptop…").performScrollTo().assertIsEnabled().performClick()
        assertEquals("nothing is written before the confirmation", 0, calls.share)
        rule.onNodeWithText("Send the address to Laptop?").assertIsDisplayed()
        rule.onNodeWithText("Whoever has the address can send this phone an alert hint", substring = true).assertIsDisplayed()
        rule.onNodeWithText("~/.config/paddock/push-endpoint.json").assertIsDisplayed()
        rule.onNodeWithText("ntfy.example.org").assertIsDisplayed()
        rule.onNodeWithText("Send the address").performClick()
        assertEquals(1, calls.share)
    }

    @Test fun theAddressItselfIsNeverDrawnOnlyItsHost() {
        showPush(PushUi(registered = registered(shared = true)))
        rule.onNodeWithText("The address is on Laptop (host ntfy.example.org).").performScrollTo().assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText("upSecret", substring = true)).assertCountEquals(0)
    }

    @Test fun aFileThatIsGoneFromTheHostIsSaidSoAndCanBeSentAgain() {
        showPush(PushUi(registered = registered(shared = true), onHost = false))
        rule.onNodeWithText("the file is not on Laptop now", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send the address again…").performScrollTo().assertIsEnabled()
    }

    @Test fun aFailureAndADroppedRegistrationAreSaidAndUnregisteringIsOneTap() {
        val calls = showPush(PushUi(registered = registered(hasEndpoint = false, failure = "The distributor has no network. Try again when it is back."), notice = "The distributor removed this registration. Register again to receive alerts."))
        rule.onNodeWithText("The distributor has no network.", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("The distributor removed this registration.", substring = true).assertExists()
        rule.onNodeWithText("Unregister").performScrollTo().performClick()
        assertEquals(1, calls.remove)
    }

    @Test fun withoutAConnectionTheAddressCannotBeSentAndAMissingDistributorIsSaid() {
        showPush(PushUi(registered = registered(installed = false)), host = AlertRelayHostState.NotConnected)
        rule.onNodeWithText("ntfy (not installed)", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Send the address to Laptop…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun whileWorkingNeitherButtonCanBeTapped() {
        showPush(PushUi(registered = registered(), busy = true))
        rule.onNodeWithText("Send the address to Laptop…").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Unregister").performScrollTo().assertIsNotEnabled()
    }

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditAlertRelayMissingDark() { show(status(RelayState.Missing)); SemanticsAudit.expectClean(rule, "Alert relay, not installed, dark") }
    @Test fun auditAlertRelayMissingLight() { show(status(RelayState.Missing), dark = false); SemanticsAudit.expectClean(rule, "Alert relay, not installed, light") }
    @Test fun auditAlertRelayFailedCheckDark() { show(status(RelayState.Current, RelayCheck(2, listOf("config: profile must be lowercase letters")), ServiceState.Failed)); SemanticsAudit.expectClean(rule, "Alert relay, failed check, dark") }

}
