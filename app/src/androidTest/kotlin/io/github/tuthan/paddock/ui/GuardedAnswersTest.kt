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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.answers.AnswerSetup
import io.github.tuthan.paddock.answers.AnswerSetupText
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.screens.GuardedAnswers
import io.github.tuthan.paddock.ui.screens.GuardedAnswersUi
import io.github.tuthan.paddock.ui.screens.GuardedCopied
import io.github.tuthan.paddock.ui.screens.GuardedHostState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The guarded-answers setup screen (Phase 8): both hashes are shown before anything is installed or copied, and installing asks first. */
class GuardedAnswersTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val hookSha = "1".repeat(64)
    private val decideSha = "2".repeat(64)
    private val hookPath = "/home/u/.local/share/paddock/paddock-claude-permission-hook.py"
    private val decidePath = "/home/u/.local/share/paddock/paddock-decide.py"
    private class Calls { var install = 0; var check = 0; var copyConfig = 0; var copySettings = 0; var back = 0; val windows = mutableListOf<Int>() }

    private fun known(hook: RelayState, decide: RelayState = hook) = GuardedHostState.Known(AnswerSetup(decide, hook, decidePath, hookPath))

    private fun show(host: GuardedHostState, calls: Calls = Calls(), window: Int = 60, fontScale: Float? = null, installError: String? = null, installing: Boolean = false, copied: GuardedCopied = GuardedCopied.None): Calls {
        val known = (host as? GuardedHostState.Known)?.setup
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = true) {
                    GuardedAnswers(
                        GuardedAnswersUi("Laptop", decideSha, hookSha, host, installing, installError, window, AnswerSetupText.configCommand(window), known?.let { AnswerSetupText.settingsSnippet(it.hookDestination, window) }, copied),
                        onBack = { calls.back++ }, onInstall = { calls.install++ }, onCheck = { calls.check++ }, onWindow = { calls.windows += it },
                        onCopyConfig = { calls.copyConfig++ }, onCopySettings = { calls.copySettings++ },
                    )
                }
            }
        }
        return calls
    }

    @Test fun whatItDoesAndDoesNotDoIsOnTheScreen() {
        show(GuardedHostState.NotConnected)
        rule.onNodeWithText("Yes or No from the phone").assertIsDisplayed()
        rule.onNodeWithText("whoever answers first wins", substring = true).assertExists()
        rule.onNodeWithText("Every other question an agent asks stays manual", substring = true).assertExists()
        rule.onNodeWithText("Paddock sends no keys for this", substring = true).assertExists()
        rule.onNodeWithText("Claude Code keeps the desktop's answer", substring = true).assertExists()
        rule.onNodeWithText("Needs Python 3.11 or newer on Laptop.").assertExists()
    }

    @Test fun bothHashesAreOnScreenAndNotConnectedDisablesInstall() {
        show(GuardedHostState.NotConnected)
        rule.onNode(hasContentDescription("SHA-256 of paddock-claude-permission-hook.py: $hookSha")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of paddock-decide.py: $decideSha")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock is not connected to Laptop", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install the scripts…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missingScriptsAreInstalledOnlyAfterTheConfirmationWhichRepeatsBothFilesAndHashes() {
        val calls = show(known(RelayState.Missing))
        rule.onNodeWithText("Install the scripts…").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Install the answer scripts on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("It does not touch Claude Code's settings, does not register the hook and starts nothing.", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of the hook: $hookSha")).assertExists()
        rule.onNode(hasContentDescription("SHA-256 of the writer: $decideSha")).assertExists()
        rule.onNode(hasContentDescription("Hook: $hookPath")).assertExists()
        rule.onNode(hasContentDescription("Writer: $decidePath")).assertExists()
        assertEquals(0, calls.install)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, calls.install)
        rule.onNodeWithText("Install the scripts…").performScrollTo().performClick()
        rule.onNodeWithText("Install").performClick()
        assertEquals(1, calls.install)
    }

    @Test fun aDifferentFileIsNeverRunAndTheButtonSaysReplace() {
        show(known(hook = RelayState.Mismatch("0".repeat(64)), decide = RelayState.Current))
        rule.onNodeWithText("A different file is there. It is never run; installing replaces it.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Installed, and it is the pinned script.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace the scripts…").performScrollTo().assertIsEnabled()
    }

    @Test fun currentScriptsOfferOnlyAReinstall() {
        show(known(RelayState.Current))
        rule.onAllNodesWithText("Installed, and it is the pinned script.").assertCountEquals(2)
        rule.onNodeWithText("Reinstall the scripts…").performScrollTo().assertIsEnabled()
    }

    @Test fun anInstallFailureIsShownAndInstallingDisablesTheButton() {
        show(known(RelayState.Missing), installError = "The install did not finish: connection reset")
        rule.onNodeWithText("The install did not finish: connection reset").performScrollTo().assertIsDisplayed()
    }

    @Test fun theWindowChoiceChangesBothTextsAndTheRegistrationTimeoutIsTheWindowPlusFive() {
        val calls = show(known(RelayState.Current), window = 120)
        rule.onNode(hasContentDescription("120 seconds, chosen")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("window_seconds = 120", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("\"timeout\": 125", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("python3 $hookPath", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("30 seconds")).performScrollTo().performClick()
        assertEquals(listOf(30), calls.windows)
    }

    @Test fun bothPastedTextsAreShownInFullBeforeTheyCanBeCopiedAndCopyingDoesNothingElse() {
        val calls = show(known(RelayState.Current))
        rule.onNodeWithText("mkdir -p ~/.config/paddock", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("[ -e ~/.config/paddock/hook.toml ] ||", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("\"PermissionRequest\"", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copy the configuration command").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Copy the registration").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.copyConfig); assertEquals(1, calls.copySettings)
        assertEquals(0, calls.install)
        rule.onNodeWithText("Paddock never edits that file", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun theRegistrationTextIsAbsentUntilThePathOnTheHostIsKnown() {
        show(GuardedHostState.Reading)
        rule.onAllNodesWithText("\"PermissionRequest\"", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Reading the machine…").performScrollTo().assertIsDisplayed()
    }

    @Test fun aFailedReadOffersTryAgain() {
        val calls = show(GuardedHostState.Failed("host unreachable"))
        rule.onNodeWithText("Could not read the machine: host unreachable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, calls.check)
    }

    @Test fun theScreenWorksAtALargeFont() {
        show(known(RelayState.Missing), fontScale = 2.0f)
        rule.onNodeWithText("Install the scripts…").performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Copy the registration").performScrollTo().assertIsDisplayed()
    }
}
