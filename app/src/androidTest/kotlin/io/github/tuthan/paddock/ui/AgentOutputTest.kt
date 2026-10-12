package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for the Output screen from fixture states (Phase 04 slice 5). */
class AgentOutputTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_000_000L
    private val esc = "\u001B"
    private val header = AgentHeader("approve edit to build.gradle", "api · claude", StateWord.Blocked, now - 40_000)

    private fun text(n: Int) = (1..n).joinToString("\n") { i -> if (i % 7 == 0) "$esc[31merror$esc[0m line $i" else if (i % 5 == 0) "$esc[90mnote$esc[0m line $i" else "line $i of the agent output" }
    private fun showing(n: Int = 30, stale: Boolean = false, readAt: Long = now - 1_000) = OutputState.Showing(Ansi.parse(text(n)), readAt, stale)

    private fun byDesc(d: String) = rule.onNode(hasContentDescription(d))

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Calls { var back = 0; var up = 0; var resume = 0; var tab: AgentTab? = null }

    private fun show(
        output: OutputState, following: Boolean = true, tab: AgentTab = AgentTab.Output, dark: Boolean = true,
        fontScale: Float? = null, header: AgentHeader = this.header, calls: Calls = Calls(), terminal: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    terminalFocus: Boolean = false, onSelectText: (() -> Unit)? = null,
        onLineAction: ((List<io.github.tuthan.paddock.output.AnsiLine>, Int, io.github.tuthan.paddock.ui.components.LineAction) -> Unit)? = null,
    ): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    AgentOutput(
                        header, output, following, now, tab,
                        onTab = { calls.tab = it }, onBack = { calls.back++ }, onUserScrolledUp = { calls.up++ }, onResumeFollowing = { calls.resume++ },
                        terminal = terminal, terminalFocus = terminalFocus, onSelectText = onSelectText, onLineAction = onLineAction,
                    )
                }
            }
        }
        return calls
    }

    @Test fun theHeaderNamesTheAgentItsStateAndWhenThePhoneSawIt() {
        show(showing())
        rule.onNodeWithText("approve edit to build.gradle").assertIsDisplayed()
        rule.onNodeWithText("api · claude").assertIsDisplayed()
        byDesc("Blocked, observed 40 s ago").assertIsDisplayed()
        shoot("output-showing-dark-100")
    }

    @Test fun anAgentThePhoneNeverObservedSaysSoInsteadOfInventingAnAge() {
        show(showing(), header = header.copy(observedAtMillis = null))
        byDesc("Blocked, not observed by this phone").assertIsDisplayed()
    }

    @Test fun outputTextIsShownAndTheNewestLineIsInViewWhileFollowing() {
        show(showing(80))
        rule.onNodeWithText("line 80", substring = true).assertIsDisplayed()
    }

    @Test fun theSlabScrollingItselfToTheEndNeverCountsAsTheUserScrollingUp() {
        val calls = show(showing(200))
        rule.waitForIdle()
        assertEquals(0, calls.up)
    }

    @Test fun draggingDownToEarlierLinesReportsAScrollUpAndTheScreenDecidesWhatItMeans() {
        val calls = show(showing(200))
        rule.onNode(hasContentDescription("Terminal output")).performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue("user scroll-up was not reported", calls.up >= 1)
    }

    @Test fun followingShowsAPassiveChip() {
        val calls = show(showing(), following = true)
        rule.onNodeWithText("Following").assertIsDisplayed().assertHasNoClickAction()
        assertEquals(0, calls.resume)
    }

    @Test fun tappingThePausedChipResumesFollowing() {
        val calls = show(showing(), following = false)
        byDesc("Paused").assertIsDisplayed()
        rule.onNodeWithText("Paused · tap to follow").assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.resume)
        shoot("output-paused-dark-100")
    }

    @Test fun aStaleReadKeepsTheTextAndSaysHowOldItIs() {
        show(showing(n = 5, stale = true, readAt = now - 12_000))
        rule.onNodeWithText("Last read failed. Showing the output from 12 s ago.").assertIsDisplayed()
        rule.onNodeWithText("line 1 of the agent output").assertIsDisplayed()
        shoot("output-stale-dark-100")
    }

    @Test fun aVanishedTerminalSaysSoAndOffersBackNeverAnotherPanesText() {
        val calls = show(OutputState.PaneGone)
        rule.onNodeWithText("This terminal is no longer in the session.").assertIsDisplayed()
        // Nothing that belongs to a live agent stays: no state chip, no tabs, no keys.
        rule.onNode(hasContentDescription("Keys, unavailable", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("Terminal").assertDoesNotExist()
        rule.onNode(hasContentDescription("Blocked, observed", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("Back to the herd").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.back)
        shoot("output-gone-dark-100")
    }

    @Test fun anExitedAgentIsNotCalledAMissingTerminal() {
        show(OutputState.AgentGone)
        rule.onNodeWithText("The agent in this terminal has exited.").assertIsDisplayed()
        rule.onNodeWithText("This terminal is no longer in the session.").assertDoesNotExist()
    }

    @Test fun loadingIsExplainedNotBlank() {
        show(OutputState.Loading)
        rule.onNodeWithText("Reading output…").assertIsDisplayed()
    }

    @Test fun unavailableSaysWhy() {
        show(OutputState.Unavailable("no route to host"))
        rule.onNodeWithText("Output is unavailable: no route to host").assertIsDisplayed()
    }

    @Test fun theTerminalTabShowsWhatTheScreenGivesItAndTheOutputStripIsNotDrawnUnderIt() {
        show(showing(), tab = AgentTab.Terminal, terminal = { androidx.compose.material3.Text("terminal slot content") })
        rule.onNodeWithText("terminal slot content").assertIsDisplayed()
        rule.onNode(hasContentDescription("Keys, unavailable.", substring = true)).assertDoesNotExist()
    }

    @Test fun withTheKeyboardUpTheTerminalTabKeepsOnlyOneLineOfTitleAndTheStateAndGivesTheRestToTheTerminal() {
        val long = header.copy(title = "Approve scoped TLS adapter review | blindpass with a title that is much too long for one line on a phone screen")
        val calls = show(showing(), tab = AgentTab.Terminal, header = long, terminal = { androidx.compose.material3.Text("terminal slot content") }, terminalFocus = true)
        rule.onNodeWithText("terminal slot content").assertIsDisplayed()
        rule.onNodeWithText("Output").assertDoesNotExist()
        rule.onNodeWithText("api · claude").assertDoesNotExist()
        rule.onNode(hasContentDescription("Blocked, observed", substring = true)).assertDoesNotExist()
        val bar = rule.onNodeWithText(long.title, substring = true).fetchSemanticsNode()
        assertTrue("the title is one line (${bar.size.height} px)", bar.size.height < 2 * with(rule.density) { 22.dp.toPx() })
        rule.onNode(hasContentDescription("Blocked")).assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.back)
    }

    @Test fun theFocusLayoutIsOnlyForTheTerminalTab() {
        show(showing(), tab = AgentTab.Output, terminalFocus = true)
        rule.onNodeWithText("Terminal").assertIsDisplayed() // the tabs are still there on Output
        rule.onNodeWithText("api · claude").assertIsDisplayed()
    }

    @Test fun anAgentThatIsGoneKeepsItsFullHeaderEvenWithTheKeyboardUp() {
        show(OutputState.PaneGone, tab = AgentTab.Terminal, terminalFocus = true)
        rule.onNodeWithText("This terminal is no longer in the session.").assertIsDisplayed()
        rule.onNodeWithText("api · claude").assertIsDisplayed()
    }

    @Test fun withoutTheKeyboardTheTabsAndTheStateChipAreBack() {
        show(showing(), tab = AgentTab.Terminal, terminal = { androidx.compose.material3.Text("terminal slot content") }, terminalFocus = false)
        rule.onNodeWithText("Output").assertIsDisplayed()
        rule.onNodeWithText("Terminal").assertIsDisplayed()
        rule.onNodeWithText("api · claude").assertIsDisplayed()
    }

    @Test fun withoutATerminalTheTabSaysSo() {
        show(showing(), tab = AgentTab.Terminal)
        rule.onNodeWithText("The terminal is not available here.", substring = true).assertIsDisplayed()
    }

    @Test fun switchingTabsReportsTheChoiceAndMarksTheSelectedOne() {
        val calls = show(showing())
        rule.onNodeWithText("Output").assertIsSelected()
        rule.onNodeWithText("Terminal").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(AgentTab.Terminal, calls.tab)
    }

    @Test fun theKeyStripIsDisabledAndSaysWhy() {
        show(showing())
        byDesc("Keys, unavailable. ${io.github.tuthan.paddock.ui.components.KEYS_NOTE}").assertIsNotEnabled()
        rule.onNodeWithText(io.github.tuthan.paddock.ui.components.KEYS_NOTE).assertExists()
    }

    @Test fun backIsAButtonOfAtLeastTheMinimumTarget() {
        val calls = show(showing())
        byDesc("Back").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.back)
    }

    @Test fun anOversizedTitleIsBoundedAndTheScreenStillFits() {
        show(showing(), header = header.copy(title = "word ".repeat(200)))
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val back = byDesc("Back").getUnclippedBoundsInRoot()
        assertTrue(back.left >= root.left && back.right <= root.right + 0.5.dp)
        rule.onNodeWithText("Following").assertIsDisplayed()
    }

    @Test fun twoHundredPercentFontKeepsEveryControlReachable() {
        show(showing(), fontScale = 2f)
        rule.onNodeWithText("Output").assertIsDisplayed()
        rule.onNodeWithText("Terminal").assertIsDisplayed()
        byDesc("Back").assertIsDisplayed()
        shoot("output-showing-dark-200")
    }

    @Test fun resumingFollowingReturnsToTheNewestLine() {
        val following = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                AgentOutput(header, showing(200), following.value, now, AgentTab.Output, {}, {}, { following.value = false }, { following.value = true })
            }
        }
        rule.onNodeWithText("line 200", substring = true).assertIsDisplayed()
        rule.onNode(hasContentDescription("Terminal output")).performTouchInput { swipeDown(); swipeDown() }
        rule.waitForIdle()
        assertEquals("a user drag toward older lines pauses following", false, following.value)
        rule.onNodeWithText("Paused · tap to follow").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("line 200", substring = true).assertIsDisplayed()
    }

    @Test fun lightThemeRenders() {
        show(showing(), dark = false)
        rule.onNodeWithText("Following").assertIsDisplayed()
        shoot("output-showing-light-100")
    }

    @Test fun theComposerEntryNamesTheAgentAndOpensTheComposerOnlyOnTheOutputTab() {
        var composed = 0
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                AgentOutput(header.copy(agentKind = "claude"), showing(), true, now, AgentTab.Output, {}, {}, {}, {}, onCompose = { composed++ })
            }
        }
        rule.onNodeWithText("Ask claude…").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNode(hasContentDescription("Write a prompt for claude")).performClick()
        assertEquals(1, composed)
    }

    /**
     * A request is offered the moment its file shows, whatever herdr says about the agent: Codex is `working` or `idle` while its hook waits, and opencode and
     * Claude Code are `blocked` for their questions too. What the entry shows is decided from the host's files (live, unexpired, hook running), not from the state chip.
     */
    @Test fun theDecisionEntryShowsForAnAgentInAnyStateWhileARequestIsLive() {
        val entry = DecisionEntryModel("Bash", "Answer within 20 s", "Codex")
        val shownHeader = androidx.compose.runtime.mutableStateOf(header)
        val withEntry = androidx.compose.runtime.mutableStateOf<DecisionEntryModel?>(entry)
        rule.setContent { PaddockTheme(darkTheme = true) { AgentOutput(shownHeader.value, showing(), true, now, AgentTab.Output, {}, {}, {}, {}, decision = withEntry.value, onOpenDecision = {}) } }
        for ((state, kind) in listOf(StateWord.Blocked to "claude", StateWord.Working to "codex", StateWord.Done to "codex", StateWord.Working to "opencode", StateWord.Done to "claude", StateWord.Unknown to null)) {
            rule.runOnIdle { shownHeader.value = header.copy(state = state, agentKind = kind) }
            rule.onNodeWithTag("decision-entry").assertIsDisplayed()
        }
        rule.runOnIdle { withEntry.value = null }
        rule.onNodeWithTag("decision-entry").assertDoesNotExist()
    }

    @Test fun withoutAJournalThereIsNoComposerEntry() {
        show(showing())
        rule.onNodeWithText("Ask the agent…").assertDoesNotExist()
    }

    /** The output is what the screen is for: it keeps 100 dp at every font size. */
    private val OUTPUT = SemanticsAudit.Options(regions = mapOf("Terminal output" to 100f))

    // ---- the accessibility audit (AC-10.3): SemanticsAudit over this screen, both themes ------------------------------------------

    @Test fun auditOutputDark() { show(showing()); SemanticsAudit.expectClean(rule, "Agent output, dark", OUTPUT) }
    @Test fun auditOutputLight() { show(showing(), dark = false); SemanticsAudit.expectClean(rule, "Agent output, light", OUTPUT) }
    @Test fun auditOutputStaleDark() { show(showing(stale = true)); SemanticsAudit.expectClean(rule, "Agent output, stale, dark", OUTPUT) }


    // ---- Phase 15: the Select text chip, a line's long-press menu and its TalkBack actions ------------------------------------------

    @Test fun theSelectTextChipShowsOnlyWhileOutputIsShowing() {
        show(showing(), onSelectText = {})
        rule.onNodeWithText("Select text").assertIsDisplayed()
    }

    @Test fun noSelectTextChipWhileLoading() {
        show(OutputState.Loading, onSelectText = {})
        rule.onNodeWithText("Select text").assertDoesNotExist()
    }

    @Test fun noSelectTextChipWhenTheOutputIsUnavailableOrGone() {
        show(OutputState.Unavailable("no route"), onSelectText = {})
        rule.onNodeWithText("Select text").assertDoesNotExist()
    }

    @Test fun noSelectTextChipOnceTheTerminalIsGone() {
        show(OutputState.PaneGone, onSelectText = {})
        rule.onNodeWithText("Select text").assertDoesNotExist()
    }

    @Test fun theSelectTextChipIsAButtonWithItsLabelAndAFullTarget() {
        var taps = 0
        show(showing(), onSelectText = { taps++ })
        byDesc("Select text to copy").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, taps)
    }

    @Test fun noChipWhenNothingCanBeOpened() {
        show(showing())
        rule.onNodeWithText("Select text").assertDoesNotExist()
    }

    private class Taken { val got = mutableListOf<Triple<List<io.github.tuthan.paddock.output.AnsiLine>, Int, io.github.tuthan.paddock.ui.components.LineAction>>() }

    @Test fun aLongPressPausesFollowingAndOpensTheMenuAtThatLine() {
        val calls = show(showing(30), onLineAction = { _, _, _ -> })
        rule.onNodeWithText("line 29 of the agent output", substring = true).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("a long press pauses following the way a drag does", 1, calls.up)
        rule.onNodeWithText("Copy line").assertIsDisplayed()
        rule.onNodeWithText("Copy from here to newest").assertIsDisplayed()
        rule.onNodeWithText("Select text…").assertIsDisplayed()
    }

    @Test fun copyLineAndCopyFromHereReportTheIndexAndTheLines() {
        val taken = Taken()
        val lines = showing(30).lines
        show(showing(30), onLineAction = { l, i, a -> taken.got += Triple(l, i, a) })
        rule.onNodeWithText("line 29 of the agent output", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Copy line").performClick()
        rule.onNodeWithText("line 29 of the agent output", substring = true).performTouchInput { longClick() }
        rule.onNodeWithText("Copy from here to newest").performClick()
        assertEquals(2, taken.got.size)
        assertEquals(io.github.tuthan.paddock.ui.components.LineAction.CopyLine, taken.got[0].third)
        assertEquals("oldest-first index of line 29", 28, taken.got[0].second)
        assertEquals(lines, taken.got[0].first)
        assertEquals(io.github.tuthan.paddock.ui.components.LineAction.CopyToNewest, taken.got[1].third)
    }

    @Test fun theMenuActsOnTheLinesItWasOpenedOnEvenWhenTheOutputChangesUnderIt() {
        val taken = Taken()
        val first = showing(30)
        var output by androidx.compose.runtime.mutableStateOf<OutputState>(first)
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                AgentOutput(header, output, true, now, AgentTab.Output, {}, {}, {}, {}, onLineAction = { l, i, a -> taken.got += Triple(l, i, a) })
            }
        }
        rule.onNodeWithText("line 29 of the agent output", substring = true).performTouchInput { longClick() }
        output = showing(60) // an answer that lands while the menu is open
        rule.waitForIdle()
        rule.onNodeWithText("Copy from here to newest").performClick()
        assertEquals("the lines at the press, not the new answer", first.lines, taken.got.single().first)
    }

    @Test fun anEmptyLineOffersNoCopyLine() {
        val empty = OutputState.Showing(Ansi.parse("alpha\n\nomega"), now, false)
        show(empty, onLineAction = { _, _, _ -> })
        val rows = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions)).fetchSemanticsNodes()
        val names = rows.map { n -> n.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label } }
        assertEquals(3, names.size)
        assertEquals("exactly one line has only two actions", 1, names.count { it.size == 2 })
        assertTrue(names.single { it.size == 2 }.none { it == "Copy line" })
    }

    @Test fun everyLineHasTheThreeCustomActionsAndNoClick() {
        show(showing(10), onLineAction = { _, _, _ -> })
        val rows = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions)).fetchSemanticsNodes()
        assertEquals(10, rows.size)
        for (n in rows) {
            assertEquals(listOf("Copy line", "Copy from here to newest", "Select text from this line"), n.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
            assertTrue("a line is not clickable", n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null)
        }
    }

    @Test fun aCustomActionDoesWhatTheMenuItemDoes() {
        val taken = Taken(); val calls = Calls()
        show(showing(10), calls = calls, onLineAction = { l, i, a -> taken.got += Triple(l, i, a) })
        val node = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CustomActions))[0]
        val action = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Select text from this line" }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
        assertEquals(io.github.tuthan.paddock.ui.components.LineAction.SelectFromHere, taken.got.single().third)
        assertEquals("TalkBack's action pauses following like the gesture", 1, calls.up)
    }

    @Test fun aDragStillScrollsAndStillPausesWithTheMenuInPlace() {
        val calls = show(showing(200), onLineAction = { _, _, _ -> })
        rule.onNode(hasContentDescription("Terminal output")).performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue("a drag over the lines still reports a scroll up", calls.up >= 1)
        rule.onNodeWithText("Copy line").assertDoesNotExist()
    }

    @Test fun auditOutputWithLineActionsDark() { show(showing(), onSelectText = {}, onLineAction = { _, _, _ -> }); SemanticsAudit.expectClean(rule, "Agent output with copy, dark", OUTPUT) }
    @Test fun auditOutputWithLineActionsLight() { show(showing(), dark = false, onSelectText = {}, onLineAction = { _, _, _ -> }); SemanticsAudit.expectClean(rule, "Agent output with copy, light", OUTPUT) }

}
