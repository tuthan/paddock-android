package io.github.tuthan.paddock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.tuthan.paddock.GUARDED_SETUP_IS_PRO
import io.github.tuthan.paddock.RequestWatch
import io.github.tuthan.paddock.answers.AnswerController
import io.github.tuthan.paddock.answers.AnswerPort
import io.github.tuthan.paddock.answers.Behavior
import io.github.tuthan.paddock.answers.DecisionModel
import io.github.tuthan.paddock.answers.NotAnswerable
import io.github.tuthan.paddock.answers.RequestListing
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.decisionNotice
import io.github.tuthan.paddock.decisionSetUp
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.DecisionActions
import io.github.tuthan.paddock.ui.screens.DecisionSheet
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Guarded answers locked (vault M8, AC-13.5), at the two places the review found a lock that was only half applied. Both are driven by an explicit
 * `locked` value, as `ProGateTest` drives the other Pro controls, so they run in the unlocked foss debug build and the locked builds alike; the graph's
 * own answer to "is it locked" is `ProGraphTest`'s. What these cannot see is `PaddockRoot` passing the graph's answer in; that is one expression per call.
 */
class GuardedAnswersLockTest {
    @get:Rule val rule = createComposeRule()

    private val header = AgentHeader("approve shell command", "claude · main · laptop", StateWord.Blocked, 1_000_000L - 3_000, agentKind = "claude")
    private val hookMissing = "No such file: ~/.config/paddock/paddock-hook.py"
    private class Calls { var setUp = 0; var refresh = 0; var terminal = 0; val yes = mutableListOf<String>() }

    private fun sheet(model: DecisionModel, locked: Boolean, settled: String? = null): Calls {
        val calls = Calls()
        rule.setContent {
            PaddockTheme(darkTheme = true) {
                DecisionSheet(
                    header, model, false,
                    DecisionActions(
                        onYes = { calls.yes += it }, onRefresh = { calls.refresh++ }, onOpenTerminal = { calls.terminal++ },
                        onSetUp = decisionSetUp(model.readError, locked) { calls.setUp++ },
                    ),
                    onBack = {}, notice = decisionNotice(settled, model.readError, locked),
                )
            }
        }
        return calls
    }

    private fun hookNotInstalled() = DecisionModel(DecisionModel.Kind.NoRequest, whyNot = NotAnswerable.NothingPending.sentence, readError = hookMissing)
    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    // ---- F7: the decision sheet's Set up --------------------------------------------------------------------------------------

    @Test fun lockedWithTheHookNotInstalledThereIsNoSetUpButTheReasonAndAWorkingWayOut() {
        val calls = sheet(hookNotInstalled(), locked = true)
        rule.onNodeWithText("Could not read the request", substring = true).assertIsDisplayed()
        // On the old code this is the assertion that fails: a "Set up" button was drawn, and tapping it did nothing, with no Pro mark and no sentence.
        assertEquals("no Set up button that cannot do anything", 0, rule.onAllNodesWithText("Set up").fetchSemanticsNodes().size)
        rule.onNodeWithText(GUARDED_SETUP_IS_PRO).assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsEnabled().assertHasClickAction().performClick()
        assertEquals("Try again re-reads", 1, calls.refresh)
        assertEquals("nothing set up behind the gate's back", 0, calls.setUp)
        rule.onNodeWithText("Open terminal").assertIsDisplayed().performClick()
        assertEquals(1, calls.terminal)
    }

    @Test fun unlockedWithTheHookNotInstalledSetUpIsOfferedAndRunsAndNothingSaysPro() {
        val calls = sheet(hookNotInstalled(), locked = false)
        rule.onNodeWithText("Set up").assertIsEnabled().assertHasClickAction().performClick()
        assertEquals(1, calls.setUp)
        assertEquals(0, count(GUARDED_SETUP_IS_PRO))
        assertEquals(0, count("· Pro"))
        assertEquals(0, count("Try again"))
    }

    @Test fun aSettledNoteAndTheLockNoteAreBothOnTheLockedSheet() {
        sheet(hookNotInstalled(), locked = true, settled = "The host's files show the request was answered on the desktop.")
        rule.onNodeWithText("answered on the desktop", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Guarded answers are Pro", substring = true).assertIsDisplayed()
    }

    @Test fun aRequestOnScreenIsStillAnsweredOnALockedPhoneAndTheSheetSaysNothingAboutPro() {
        val request = DecisionModel(DecisionModel.Kind.Request, requestId = "3f9c1d2a-6b7e-4c1d-8a2b-0123456789ab", toolName = "Bash", inputText = "command:\n  ls", canAnswer = true)
        val calls = sheet(request, locked = true)
        rule.onNodeWithText("Yes").assertIsEnabled().performClick()
        assertEquals(listOf("3f9c1d2a-6b7e-4c1d-8a2b-0123456789ab"), calls.yes)
        assertEquals(0, count(GUARDED_SETUP_IS_PRO))
        assertEquals(0, count("· Pro"))
    }

    // ---- F13: the Output tab's watch ------------------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val now = 5_000_000L
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 1)
    private val reads = AtomicInteger()

    /** A host that cannot be reached: every read is counted and fails, which is all a watch has to do for these tests to see it. */
    private val port = object : AnswerPort {
        override suspend fun list(paneId: String): RequestListing { reads.incrementAndGet(); throw IOException("no host in this test") }
        override suspend fun decide(paneId: String, claudeSessionId: String, requestId: String, behavior: Behavior, beforeWrite: suspend () -> Unit) = error("nothing is answered here")
    }
    private val installed = Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1"))), now, 1)
    private val answers = AnswerController(
        scope, port, OperationJournal(InMemoryJournalStore()) { now }, { installed }, { AgentStatus.Blocked }, { now },
        pollMillis = 20, settlingPollMillis = 20, errorPollMillis = 20,
    )
    private var locked by mutableStateOf(true)

    private fun show() = rule.setContent { RequestWatch(answers, key, enabled = !locked) }
    private fun pause(ms: Long) = Thread.sleep(ms)

    @Test fun lockedTheHostIsNeverPolledAndNothingIsRecordedForTheTerminal() {
        show()
        pause(400)
        // On the old code (watch started whatever Pro says) this fails: the poll ran every 20 ms here, and every few seconds against a real host.
        assertEquals("no read while locked", 0, reads.get())
        assertTrue("no view for the terminal", answers.views.value.isEmpty())
    }

    @Test fun theWatchStartsTheMomentProAppearsWithoutTheScreenBeingRecreated() {
        show()
        pause(200)
        assertEquals(0, reads.get())
        rule.runOnIdle { locked = false }
        rule.waitUntil(5_000) { reads.get() > 0 }
    }

    @Test fun theWatchEndsWhenProGoesAway() {
        locked = false
        show()
        rule.waitUntil(5_000) { reads.get() > 0 }
        rule.runOnIdle { locked = true }
        pause(200) // a read that was already on its way may still land
        val settled = reads.get()
        pause(400)
        assertEquals("no read after the lock came back", settled, reads.get())
    }

    @Test fun withNoKeyYetThereIsNothingToWatchEvenWithProAndAReconnectStartsIt() {
        locked = false
        var current by mutableStateOf<TerminalKey?>(null)
        rule.setContent { RequestWatch(answers, current, enabled = !locked) }
        pause(200)
        assertEquals(0, reads.get())
        rule.runOnIdle { current = key }
        rule.waitUntil(5_000) { reads.get() > 0 }
    }
}
