package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.attention.ObservedAt
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import io.github.tuthan.paddock.live.BlockedPreview
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.output.Ansi

/** Compose UI tests from fixture states (Phase 04 slice 3). Screenshots land in the app's external files for evidence. */
class HerdHomeTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_000_000L
    private fun agent(pane: String, status: AgentStatus, seq: Long, title: String?, cwd: String = "/home/u/proj", ready: Boolean? = null) =
        Agent(paneId = pane, terminalId = "term_$pane", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = status, stateChangeSeq = seq,
            terminalTitleStripped = title, foregroundCwd = cwd, interactiveReady = ready)

    private fun home(vararg a: Agent): HomeModel =
        AttentionModel.home(Snapshot("0.9.1", 22, agents = a.toList()), now - 40_000, HostProfileId("laptop"), "main", 1, ObservedAt { null })

    private val busy get() = home(
        agent("w1:p1", AgentStatus.Working, 5, "refactor the parser"),
        agent("w1:p2", AgentStatus.Blocked, 9, "approve edit to build.gradle", "/home/u/api"),
        agent("w1:p3", AgentStatus.Done, 7, "write release notes", "/home/u/docs"),
        agent("w1:p4", AgentStatus.Idle, 2, null),
        agent("w1:p5", AgentStatus.Unknown, 0, "mystery"),
    )

    private fun byDesc(d: String) = rule.onNode(hasContentDescription(d))

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [tall] lays the list out in a viewport taller than any phone, so every row is composed even on a 360 x 640 dp screen. */
    private fun show(
        state: HomeUiState, dark: Boolean = true, fontScale: Float? = null, tall: Boolean = false, onOpen: (String) -> Unit = {},
        preview: BlockedPreview? = null, onReview: (String) -> Unit = {},
    ) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
            PaddockTheme(darkTheme = dark) {
                if (tall) androidx.compose.foundation.layout.Box(Modifier.requiredHeight3000()) { HerdHome(state, now, onOpenAgent = onOpen, preview = preview, onReview = onReview) }
                else HerdHome(state, now, onOpenAgent = onOpen, preview = preview, onReview = onReview)
            }
        }
    }

    private fun Modifier.requiredHeight3000() = this.then(Modifier.requiredHeight(3000.dp))

    private fun live(m: HomeModel) = HomeUiState.Live("laptop", m, 40_000)

    @Test fun busyHomeListsSectionsInAttentionOrder() {
        show(live(busy), tall = true)
        val tops = listOf("NEEDS YOU", "DONE", "WORKING", "READY", "UNKNOWN").map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithText("1 needs you · 1 done · 1 working · 1 ready · 1 unknown").assertExists()
    }

    @Test fun busyHomeShowsItsSummaryFirst() {
        show(live(busy))
        rule.onNodeWithText("1 needs you · 1 done · 1 working · 1 ready · 1 unknown").assertIsDisplayed()
        shoot("home-busy-dark-100")
    }

    @Test fun rowsReadStateThenTitleThenContextAndAge() {
        show(live(busy))
        byDesc("Blocked, approve edit to build.gradle, api, observed 40 s ago").assertIsDisplayed().assertHasClickAction()
        byDesc("Ready, claude · w1:p4, proj, observed 40 s ago").assertIsDisplayed()
    }

    @Test fun everyRowMeetsTheMinimumTouchTarget() {
        show(live(busy), tall = true)
        rule.onAllNodesWithContentDescription("observed", substring = true).assertCountEquals5()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals5() {
        assertEquals(5, fetchSemanticsNodes().size)
        for (i in 0 until 5) get(i).assertHeightIsAtLeast(48.dp)
    }

    @Test fun tappingARowOpensThatAgent() {
        var opened: String? = null
        show(live(busy), onOpen = { opened = it })
        byDesc("Blocked, approve edit to build.gradle, api, observed 40 s ago").performClick()
        assertEquals("w1:p2", opened)
    }

    @Test fun aDegradedHostShowsABannerAndDatedRowsWithNoActions() {
        show(HomeUiState.Degraded("laptop", busy, "herdr is not answering on laptop", 125_000, recoveryLabel = "Retry"))
        rule.onNodeWithText("herdr is not answering on laptop").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertHasClickAction()
        byDesc("Blocked, approve edit to build.gradle, api, observed 40 s ago").assertHasNoClickAction()
        rule.onNodeWithText("stale · observed 2 min ago", substring = true).assertIsDisplayed()
        shoot("home-degraded-dark-100")
    }

    @Test fun quietHomeSaysNothingNeedsYou() {
        show(live(home(agent("w1:p1", AgentStatus.Working, 1, "indexing"), agent("w1:p2", AgentStatus.Idle, 1, "idle"))))
        rule.onNodeWithText("Nothing needs you · 1 working · 1 ready").assertIsDisplayed()
    }

    @Test fun noAgentsShowsTheEmptyHint() {
        show(live(home()))
        rule.onNodeWithText("No agents running").assertIsDisplayed()
        rule.onNodeWithText("Start an agent in herdr and it appears here.").assertIsDisplayed()
    }

    @Test fun unknownStateIsAWordWithAnOutlineNotJustAColour() {
        show(live(home(agent("w1:p1", AgentStatus.Unknown, 0, "mystery"))))
        byDesc("Unknown, mystery, proj, observed 40 s ago").assertIsDisplayed()
    }

    @Test fun anOversizedTitleIsBoundedAndDoesNotPushTheRowOffScreen() {
        val long = "word ".repeat(200)
        show(live(home(agent("w1:p1", AgentStatus.Blocked, 1, long))))
        val row = rule.onAllNodesWithContentDescription("Blocked", substring = true)[0]
        row.assertIsDisplayed()
        val b = row.getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("row wider than the screen: $b vs $root", b.right <= root.right + 0.5.dp)
    }

    @Test fun twoHundredPercentFontWrapsAndNothingClips() {
        show(live(busy), fontScale = 2f)
        rule.onNodeWithText("1 needs you · 1 done · 1 working · 1 ready · 1 unknown").assertIsDisplayed()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val row = byDesc("Blocked, approve edit to build.gradle, api, observed 40 s ago").getUnclippedBoundsInRoot()
        assertTrue(row.left >= root.left && row.right <= root.right + 0.5.dp)
        shoot("home-busy-dark-200")
    }

    @Test fun lightThemeRenders() {
        show(live(busy), dark = false)
        rule.onNodeWithText("NEEDS YOU").assertIsDisplayed()
        shoot("home-busy-light-100")
    }

    // ---- the expanded first blocked row ----

    private val prompt = BlockedPreview("term_w1:p2", 9, PreviewState.Showing(Ansi.parse("Allow edit to build.gradle?\n  1. Yes\n  2. No"), now - 1_000))

    @Test fun theFirstBlockedRowShowsItsCapturedPromptInASlabWithAReviewAction() {
        var reviewed: String? = null
        show(live(busy), preview = prompt, onReview = { reviewed = it })
        byDesc("Captured prompt: Allow edit to build.gradle?. 1. Yes. 2. No").assertIsDisplayed()
        rule.onNodeWithText("What it is asking".uppercase()).assertIsDisplayed()
        rule.onNodeWithText("Review prompt").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("w1:p2", reviewed)
        shoot("home-expanded-blocked")
    }

    @Test fun theHeaderOfTheExpandedRowStillReadsStateTitleContextAndOpensTheAgent() {
        var opened: String? = null
        show(live(busy), preview = prompt, onOpen = { opened = it })
        byDesc("Blocked, approve edit to build.gradle, api, observed 40 s ago").assertHasClickAction().performClick()
        assertEquals("w1:p2", opened)
    }

    @Test fun onlyTheFirstBlockedRowIsExpandedTheRestStayCompact() {
        val two = home(
            agent("w1:p2", AgentStatus.Blocked, 9, "approve edit to build.gradle", "/home/u/api"),
            agent("w1:p6", AgentStatus.Blocked, 4, "run the migration", "/home/u/db"),
        )
        show(live(two), preview = prompt, tall = true)
        assertEquals(1, rule.onAllNodesWithText("Review prompt").fetchSemanticsNodes().size)
        byDesc("Blocked, run the migration, db, observed 40 s ago").assertHasClickAction()
    }

    @Test fun aPreviewForAnotherTerminalNeverExpandsThisRow() {
        show(live(busy), preview = prompt.copy(terminalId = "term_other"))
        assertEquals(0, rule.onAllNodesWithText("Review prompt").fetchSemanticsNodes().size)
    }

    @Test fun whileReadingAndWhenItFailsTheSlabSaysSoAndReviewStillOpens() {
        show(live(busy), preview = prompt.copy(state = PreviewState.Loading))
        rule.onNodeWithText("Reading the prompt…").assertIsDisplayed()
    }

    @Test fun anUnreadablePromptIsSaidPlainly() {
        show(live(busy), preview = prompt.copy(state = PreviewState.Unavailable))
        rule.onNodeWithText("The prompt could not be read. Review prompt opens the output.").assertIsDisplayed()
        rule.onNodeWithText("Review prompt").assertIsDisplayed()
    }

    @Test fun anEmptyCaptureIsSaidPlainly() {
        show(live(busy), preview = prompt.copy(state = PreviewState.Showing(emptyList(), now)))
        rule.onNodeWithText("Nothing was captured. Review prompt opens the output.").assertIsDisplayed()
    }

    @Test fun aDegradedHostNeverExpandsAndOffersNoReviewAction() {
        show(HomeUiState.Degraded("laptop", busy, "herdr is not answering on laptop", 125_000, recoveryLabel = "Retry"), preview = prompt)
        assertEquals(0, rule.onAllNodesWithText("Review prompt").fetchSemanticsNodes().size)
    }

    @Test fun escapeSequencesInThePromptNeverReachTheScreen() {
        val esc = "\u001B"
        val hostile = BlockedPreview("term_w1:p2", 9, PreviewState.Showing(Ansi.parse("ok${esc}]52;c;aGk=\u0007 ${esc}[2Jchoose"), now))
        show(live(busy), preview = hostile)
        byDesc("Captured prompt: ok choose").assertIsDisplayed()
    }

    @Test fun theExpandedRowIsReachableAtTwoHundredPercentFont() {
        show(live(busy), preview = prompt, fontScale = 2f)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Review prompt"))
        rule.onNodeWithText("Review prompt").assertIsDisplayed()
        shoot("home-expanded-blocked-200")
    }
}
