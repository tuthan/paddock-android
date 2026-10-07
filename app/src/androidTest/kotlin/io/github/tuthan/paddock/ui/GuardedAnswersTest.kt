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
import io.github.tuthan.paddock.ui.screens.GuardedAgent
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
    private val pluginSha = "3".repeat(64)
    private val pluginPath = "/home/u/.local/share/paddock/paddock-opencode-permission.js"
    private val hookPath = "/home/u/.local/share/paddock/paddock-claude-permission-hook.py"
    private val decidePath = "/home/u/.local/share/paddock/paddock-decide.py"
    private class Calls { var install = 0; var check = 0; var copyConfig = 0; val registrations = mutableListOf<GuardedAgent>(); var back = 0; val windows = mutableListOf<Int>(); val codexWindows = mutableListOf<Int>() }

    private fun known(hook: RelayState, decide: RelayState = hook, plugin: RelayState? = hook) = GuardedHostState.Known(AnswerSetup(decide, hook, decidePath, hookPath, plugin, plugin?.let { pluginPath }))

    private fun show(
        host: GuardedHostState, calls: Calls = Calls(), window: Int = 60, fontScale: Float? = null, dark: Boolean = true, installError: String? = null, installing: Boolean = false,
        copied: GuardedCopied = GuardedCopied.None, codexWindow: Int = 20, withPlugin: Boolean = true,
    ): Calls {
        val known = (host as? GuardedHostState.Known)?.setup
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    GuardedAnswers(
                        GuardedAnswersUi(
                            "Laptop", decideSha, hookSha, host, installing, installError, window, AnswerSetupText.configCommand(window, codexWindow),
                            known?.let { AnswerSetupText.settingsSnippet(it.hookDestination, window) }, copied,
                            opencodeSha256 = pluginSha.takeIf { withPlugin }, codexWindowSeconds = codexWindow,
                            codexSnippet = known?.let { AnswerSetupText.codexSnippet(it.hookDestination, codexWindow) },
                            opencodeCommand = known?.opencodeDestination?.let { AnswerSetupText.opencodeCommand(it) },
                        ),
                        onBack = { calls.back++ }, onInstall = { calls.install++ }, onCheck = { calls.check++ }, onWindow = { calls.windows += it },
                        onCopyConfig = { calls.copyConfig++ }, onCopyRegistration = { calls.registrations += it }, onCodexWindow = { calls.codexWindows += it },
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
        rule.onNodeWithText("the agent keeps the desktop's answer", substring = true).assertExists()
        rule.onNodeWithText("Codex shows nothing on the desktop until its hooks have returned", substring = true).assertExists()
        rule.onNodeWithText("Needs Python 3.11 or newer on Laptop.").assertExists()
    }

    @Test fun everyHashIsOnScreenAndNotConnectedDisablesInstall() {
        show(GuardedHostState.NotConnected)
        rule.onNode(hasContentDescription("SHA-256 of paddock-claude-permission-hook.py: $hookSha")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of paddock-decide.py: $decideSha")).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of paddock-opencode-permission.js: $pluginSha")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock is not connected to Laptop", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Install the scripts…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missingScriptsAreInstalledOnlyAfterTheConfirmationWhichRepeatsEveryFileAndHash() {
        val calls = show(known(RelayState.Missing))
        rule.onNodeWithText("Install the scripts…").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Install the answer scripts on Laptop?").assertIsDisplayed()
        rule.onNodeWithText("Paddock writes these three files", substring = true).assertIsDisplayed()
        rule.onNodeWithText("It does not touch any agent's settings, does not register the hook and starts nothing.", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("SHA-256 of the plugin: $pluginSha")).assertExists()
        rule.onNode(hasContentDescription("Plugin: $pluginPath")).assertExists()
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
        show(known(hook = RelayState.Mismatch("0".repeat(64)), decide = RelayState.Current, plugin = RelayState.Current))
        rule.onNodeWithText("A different file is there. It is never run; installing replaces it.").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Installed, and it is the pinned script.").assertCountEquals(2)
        rule.onNodeWithText("Replace the scripts…").performScrollTo().assertIsEnabled()
    }

    @Test fun currentScriptsOfferOnlyAReinstall() {
        show(known(RelayState.Current))
        rule.onAllNodesWithText("Installed, and it is the pinned script.").assertCountEquals(3)
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
        assertEquals(1, calls.copyConfig); assertEquals(listOf(GuardedAgent.Claude), calls.registrations)
        assertEquals(0, calls.install)
        rule.onNodeWithText("Paddock never edits that file", substring = true).performScrollTo().assertIsDisplayed()
    }

    // ---- one registration per agent -------------------------------------------------------------------------------------------------

    @Test fun claudeCodeIsTheAgentShownFirstAndTheOtherTwoAreOneTapAway() {
        show(known(RelayState.Current))
        rule.onNode(hasContentDescription("Claude Code, chosen")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("~/.claude/settings.json", substring = true).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("--agent codex", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("ln -sf", substring = true).assertCountEquals(0)
        rule.onNode(hasContentDescription("Codex")).assertIsDisplayed()
        rule.onNode(hasContentDescription("opencode")).assertIsDisplayed()
    }

    @Test fun codexShowsItsHooksFileTheTrustStepAndTimeoutIsItsWindowPlusTen() {
        val calls = show(known(RelayState.Current), codexWindow = 30)
        rule.onNode(hasContentDescription("Codex")).performScrollTo().performClick()
        rule.onNode(hasContentDescription("Codex, chosen")).assertIsDisplayed()
        rule.onNodeWithText("python3 $hookPath --agent codex", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("\"timeout\": 40", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("~/.codex/hooks.json", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Codex asks you to trust a new hook", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock never edits that file", substring = true).assertExists()
        rule.onNodeWithText("Copy the registration").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(GuardedAgent.Codex), calls.registrations)
    }

    @Test fun opencodeShowsOneLinkCommandForTheFileThatWasInstalledAndNothingEditsItsConfig() {
        val calls = show(known(RelayState.Current))
        rule.onNode(hasContentDescription("opencode")).performScrollTo().performClick()
        rule.onNodeWithText("ln -sf '$pluginPath' ~/.config/opencode/plugins/paddock-opencode-permission.js", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("restart opencode", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Paddock never writes into opencode's folders", substring = true).assertExists()
        rule.onAllNodesWithText("\"PermissionRequest\"", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Copy the registration").performScrollTo().performClick()
        assertEquals(listOf(GuardedAgent.Opencode), calls.registrations)
    }

    @Test fun codexHasAWindowOfItsOwnThatIsShortAndSeparateFromTheOthers() {
        val calls = show(known(RelayState.Current))
        rule.onNode(hasContentDescription("Codex 20 seconds, chosen")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("this window is a delay of that prompt", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("Codex 10 seconds")).performScrollTo().performClick()
        assertEquals(listOf(10), calls.codexWindows)
        assertEquals(emptyList<Int>(), calls.windows)
        rule.onNodeWithText("codex_window_seconds = 20", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun withoutAPluginInTheBuildThereIsNoOpencodePart() {
        show(known(RelayState.Current, plugin = null), withPlugin = false)
        rule.onAllNodesWithText("SHA-256 of paddock-opencode-permission.js", substring = true).assertCountEquals(0)
        rule.onNode(hasContentDescription("opencode")).assertDoesNotExist()
        rule.onNodeWithText("Install the scripts…").assertDoesNotExist()
        rule.onNodeWithText("Reinstall the scripts…").performScrollTo().assertIsEnabled()
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

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditGuardedAnswersDark() { show(known(RelayState.Current)); SemanticsAudit.expectClean(rule, "Guarded answers, installed, dark") }
    @Test fun auditGuardedAnswersLight() { show(known(RelayState.Current), dark = false); SemanticsAudit.expectClean(rule, "Guarded answers, installed, light") }
    @Test fun auditGuardedAnswersMissingDark() { show(known(RelayState.Missing)); SemanticsAudit.expectClean(rule, "Guarded answers, not installed, dark") }
    @Test fun auditGuardedAnswersCodexDark() {
        show(known(RelayState.Current)); rule.onNode(hasContentDescription("Codex")).performScrollTo().performClick()
        SemanticsAudit.expectClean(rule, "Guarded answers, Codex, dark")
    }
    @Test fun auditGuardedAnswersOpencodeLight() {
        show(known(RelayState.Current), dark = false); rule.onNode(hasContentDescription("opencode")).performScrollTo().performClick()
        SemanticsAudit.expectClean(rule, "Guarded answers, opencode, light")
    }

}
