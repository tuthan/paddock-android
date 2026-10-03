package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.answers.DecisionModel
import io.github.tuthan.paddock.answers.DecisionPresenter
import io.github.tuthan.paddock.answers.NotAnswerable
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.DecisionActions
import io.github.tuthan.paddock.ui.screens.DecisionEntry
import io.github.tuthan.paddock.ui.screens.DecisionSheet
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The decision sheet (Phase 8): the request is shown whole, Yes and No are on only when the model says so, and an answer is a record. */
class DecisionSheetTest {
    @get:Rule val rule = createComposeRule()

    private val header = AgentHeader("approve shell command", "claude · main · laptop", StateWord.Blocked, 1_000_000L - 3_000, agentKind = "claude")
    private class Calls { val yesIds = mutableListOf<String>(); val noIds = mutableListOf<String>(); val yes get() = yesIds.size; val no get() = noIds.size; var review = 0; var terminal = 0; var refresh = 0; var dismiss = 0; var settle = 0; var setUp = 0; var back = 0 }

    private val REQUEST_ID = "3f9c1d2a-6b7e-4c1d-8a2b-0123456789ab"
    private val longInput = "command:\n  " + (1..120).joinToString("\n  ") { "echo step $it of the long script" } + "\n  echo THE-LAST-LINE"

    private fun model(
        input: String = "command:\n  rm -rf build/\ndescription:\n  clean the build output",
        tool: String = "Bash", canAnswer: Boolean = true, whyNot: String? = null, inputNote: String? = null, timeLeft: String? = "Answer within 41 s",
        result: String? = null, resultTone: ResultTone? = null, status: String? = null, statusTone: ResultTone? = null, newer: String? = null,
        readError: String? = null, sending: Boolean = false, kind: DecisionModel.Kind = DecisionModel.Kind.Request,
    ) = DecisionModel(
        kind, requestId = REQUEST_ID, toolName = tool, inputText = input, inputNote = inputNote, permissionMode = "default", requestShort = "3f9c1d2a", timeLeft = timeLeft,
        canAnswer = canAnswer, whyNot = whyNot, result = result, resultTone = resultTone, status = status, statusTone = statusTone,
        newerToolName = newer, readError = readError, sending = sending,
    )

    private fun show(m: DecisionModel, calls: Calls = Calls(), awaitsSettle: Boolean = false, fontScale: Float? = null, notice: String? = null, setUp: Boolean = false): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = true) {
                    DecisionSheet(
                        header, m, awaitsSettle,
                        DecisionActions(
                            { id -> calls.yesIds += id }, { id -> calls.noIds += id }, { calls.review++ }, { calls.terminal++ }, { calls.refresh++ }, { calls.dismiss++ }, { calls.settle++ },
                            onSetUp = if (setUp) ({ calls.setUp++ }) else null,
                        ),
                        { calls.back++ }, notice = notice,
                    )
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

    @Test fun theRequestIsShownWholeWithItsToolAndYesAndNoAreOn() {
        val calls = show(model())
        rule.onNodeWithText("Permission request").assertIsDisplayed()
        rule.onNodeWithText("Bash").assertIsDisplayed()
        rule.onNodeWithTag("tool-input").assertTextContains("rm -rf build/", substring = true).assertTextContains("clean the build output", substring = true)
        rule.onNodeWithText("Answer within 41 s").assertIsDisplayed()
        rule.onNodeWithText("Yes").assertIsEnabled().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("No").assertIsEnabled().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Yes").performClick()
        rule.onNodeWithText("No").performClick()
        assertEquals("a tap names the request the screen drew, whole", listOf(REQUEST_ID), calls.yesIds)
        assertEquals(listOf(REQUEST_ID), calls.noIds)
        shoot("decision-request-dark-100")
    }

    @Test fun aLongInputScrollsInsideItsBoxAndEveryLineIsInTheText() {
        show(model(input = longInput))
        val node = rule.onNodeWithTag("tool-input")
        node.assertTextContains("THE-LAST-LINE", substring = true).assertTextContains("echo step 1 of", substring = true)
        fun position() = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, position(), 0f)
        node.performTouchInput { swipeUp() }
        assertTrue("the input scrolled (was at 0, now ${position()})", position() > 0f)
        // the buttons stay on screen while the input scrolls
        rule.onNodeWithText("Yes").assertIsDisplayed()
        rule.onNodeWithText("No").assertIsDisplayed()
        shoot("decision-long-input-dark-100")
    }

    @Test fun aCutInputDisablesBothButtonsAndSaysWhyAndSaysItWasCut() {
        val calls = show(model(canAnswer = false, whyNot = NotAnswerable.TooLarge.sentence, inputNote = "The input was cut at ${DecisionPresenter.TRUNCATED_AT_KIB} KiB by the hook, so this is not all of it."))
        rule.onNodeWithText("Yes").assertIsNotEnabled()
        rule.onNodeWithText("No").assertIsNotEnabled()
        rule.onNodeWithText(NotAnswerable.TooLarge.sentence).assertIsDisplayed()
        rule.onNodeWithText("The input was cut at ${DecisionPresenter.TRUNCATED_AT_KIB} KiB", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Yes").performClick(); rule.onNodeWithText("No").performClick()
        assertEquals(0, calls.yes + calls.no)
        // the way out is always there
        rule.onNodeWithText("Answer in the terminal instead").performClick()
        assertEquals(1, calls.terminal)
        shoot("decision-cut-input-dark-100")
    }

    @Test fun aRequestWithNoTimeLeftHasBothButtonsOffAndSaysExpired() {
        show(model(canAnswer = false, whyNot = NotAnswerable.Expired.sentence, timeLeft = "Expired"))
        rule.onNodeWithText("Expired").assertIsDisplayed()
        rule.onNodeWithText("Yes").assertIsNotEnabled(); rule.onNodeWithText("No").assertIsNotEnabled()
        rule.onNodeWithText(NotAnswerable.Expired.sentence).assertIsDisplayed()
    }

    @Test fun aNewerRequestIsOfferedAndNeverSwappedInUnderAFinger() {
        val calls = show(model(canAnswer = false, whyNot = NotAnswerable.Replaced.sentence, newer = "Write"))
        rule.onNodeWithText("Yes").assertIsNotEnabled(); rule.onNodeWithText("No").assertIsNotEnabled()
        rule.onNodeWithText("A newer request (Write) is waiting.").assertIsDisplayed()
        rule.onNodeWithText("Bash").assertIsDisplayed()
        assertEquals(0, calls.review)
        rule.onNodeWithText("Review the new request").performClick()
        assertEquals(1, calls.review)
        assertEquals(0, calls.yes + calls.no)
    }

    @Test fun anAnswerThatWasWrittenSaysWrittenAndKeepsTheDesktopCaveat() {
        show(model(
            canAnswer = false, result = "allow written 14:03:12 · for the hook to hand to Claude Code, which this does not prove", resultTone = ResultTone.Ok,
            status = "Handed to Claude Code as Yes. Waiting for the agent to move on. ${DecisionPresenter.CAVEAT}", statusTone = ResultTone.Ok, timeLeft = null,
        ))
        rule.onNodeWithText("allow written 14:03:12", substring = true).assertIsDisplayed()
        rule.onNodeWithText(DecisionPresenter.CAVEAT, substring = true).assertIsDisplayed()
        rule.onNodeWithText("Yes").assertIsNotEnabled(); rule.onNodeWithText("No").assertIsNotEnabled()
        shoot("decision-answered-dark-100")
    }

    @Test fun aRequestThatJustAppearedIsShownAtOnceWithBothButtonsOffAndTheReason() {
        val calls = show(model(canAnswer = false, whyNot = io.github.tuthan.paddock.answers.DecisionPresenter.JUST_APPEARED))
        rule.onNodeWithTag("tool-input").assertTextContains("rm -rf build/", substring = true)
        rule.onNodeWithText("Yes").assertIsNotEnabled(); rule.onNodeWithText("No").assertIsNotEnabled()
        rule.onNodeWithText(io.github.tuthan.paddock.answers.DecisionPresenter.JUST_APPEARED).assertIsDisplayed()
        rule.onNodeWithText("Yes").performClick()
        assertEquals(0, calls.yes + calls.no)
    }

    @Test fun aLostRaceIsShownAsLostWithADismissAndTheTerminalAsTheNextStep() {
        val calls = show(model(canAnswer = false, result = "Lost: the request was already answered.", resultTone = ResultTone.Refused))
        rule.onNodeWithText("Lost: the request was already answered.").assertIsDisplayed()
        rule.onNodeWithText("Open terminal").performClick()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.terminal); assertEquals(1, calls.dismiss)
        rule.onNodeWithText("Yes").assertIsNotEnabled()
    }

    @Test fun anUnknownAnswerOffersAReadOfTheHostsFilesNeverAResend() {
        val calls = show(model(canAnswer = false, whyNot = "An earlier send's outcome is unknown. Re-read before sending again.", result = "allow sent 14:03:12 · outcome unknown · re-read before sending again", resultTone = ResultTone.Unknown), awaitsSettle = true)
        rule.onNodeWithText("allow sent 14:03:12", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Read the files").performClick()
        assertEquals(1, calls.settle)
        rule.onNodeWithText("Yes").assertIsNotEnabled(); rule.onNodeWithText("No").assertIsNotEnabled()
        assertEquals(0, calls.yes + calls.no)
        // the gate's generic sentence (capital R) is not repeated under the banner that already says it (lower-case r) and offers the read
        rule.onAllNodesWithText("Re-read before sending again", substring = true).assertCountEquals(0)
    }

    @Test fun anUnknownRowFromBeforeARestartOffersTheSameReadWithNothingOnTheSheetToShowFor() {
        val calls = show(DecisionModel(DecisionModel.Kind.NoRequest, whyNot = NotAnswerable.NothingPending.sentence), awaitsSettle = true)
        rule.onNodeWithText("An earlier answer from this phone ended with its outcome unknown", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Read the files").performClick()
        assertEquals(1, calls.settle)
        rule.onNodeWithText("Check again").performClick()
        assertEquals(1, calls.refresh)
    }

    @Test fun aSendInProgressSaysSendingAndIgnoresTaps() {
        val calls = show(model(canAnswer = false, sending = true))
        rule.onNodeWithText("Sending…").assertIsNotEnabled()
        rule.onNodeWithText("No").assertIsNotEnabled()
        rule.onNodeWithText("Sending…").performClick()
        assertEquals(0, calls.yes)
    }

    @Test fun noRequestSaysSoAndOffersAReadAndTheTerminal() {
        val calls = show(DecisionModel(DecisionModel.Kind.NoRequest, whyNot = NotAnswerable.NothingPending.sentence))
        rule.onNodeWithText(NotAnswerable.NothingPending.sentence).assertIsDisplayed()
        rule.onNodeWithText("Check again").performClick()
        rule.onNodeWithText("Open terminal").performClick()
        assertEquals(1, calls.refresh); assertEquals(1, calls.terminal)
        rule.onAllNodesWithText("Yes").assertCountEquals(0)
    }

    @Test fun aFailedReadOffersSetUpWhenTheHookMayNotBeInstalled() {
        val calls = show(DecisionModel(DecisionModel.Kind.NoRequest, whyNot = NotAnswerable.NothingPending.sentence, readError = "the script is not installed on this machine"), setUp = true)
        rule.onNodeWithText("Could not read the request: the script is not installed on this machine").assertIsDisplayed()
        rule.onNodeWithText("Set up").performClick()
        assertEquals(1, calls.setUp)
    }

    @Test fun theFactAboutWhoAnswersFirstIsOnTheSheetAndPaddockNeverOffersAlwaysAllow() {
        show(model())
        rule.onNodeWithText("never sends \"always allow\"", substring = true).assertExists()
        rule.onNodeWithText("whoever answers first wins", substring = true).assertExists()
    }

    @Test fun theButtonsAndTheInputSurviveALargeFontAndTheSheetStillScrolls() {
        show(model(input = longInput, canAnswer = true), fontScale = 2.0f)
        rule.onNodeWithText("Yes").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("No").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("tool-input").assertTextContains("THE-LAST-LINE", substring = true)
        shoot("decision-request-dark-200")
    }

    @Test fun theEntryOnTheOutputTabNamesTheToolAndOpensTheSheetWithoutAnswering() {
        var opened = 0
        rule.setContent { PaddockTheme(darkTheme = true) { DecisionEntry(DecisionEntryModel("Bash", "Answer within 41 s"), { opened++ }) } }
        rule.onNodeWithTag("decision-entry").assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Claude Code is waiting for a Yes or No · Bash · Answer within 41 s").assertIsDisplayed()
        rule.onAllNodesWithText("Yes").assertCountEquals(0)
        rule.onNodeWithTag("decision-entry").performClick()
        assertEquals(1, opened)
    }
}
