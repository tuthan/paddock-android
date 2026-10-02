package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.KeyGate
import io.github.tuthan.paddock.ops.ManualInputRules
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.KEYS_NOTE
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.MANUAL_INPUT_FACT
import io.github.tuthan.paddock.ui.screens.MANUAL_KEYS_FACT
import io.github.tuthan.paddock.ui.screens.ManualInput
import io.github.tuthan.paddock.ui.screens.ManualInputActions
import io.github.tuthan.paddock.ui.screens.ManualInputUi
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for Manual input (Phase 06 slice 5, AC-06.5): Esc and Ctrl+C exist only after the user chose the mode, and only on a fresh read. */
class ManualInputPanelTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_000_000L
    private val entered = now - 3_000
    private val header = AgentHeader("approve edit to build.gradle", "api · claude", StateWord.Working, now - 40_000, agentKind = "claude")
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), 2)

    private class Calls {
        var enter = 0; var leave = 0; var dismiss = 0; var terminal = 0; var reread = 0
        val keys = mutableListOf<OperationKind>()
    }

    private val calls = Calls()
    private var ui by mutableStateOf(ManualInputUi(gate = null))

    private fun actions(withReread: Boolean = false) = ManualInputActions(
        onEnter = { calls.enter++ }, onLeave = { calls.leave++ }, onKey = { calls.keys += it },
        onDismissOutcome = { calls.dismiss++ }, onOpenTerminal = { calls.terminal++ }, onReread = if (withReread) ({ calls.reread++ }) else null,
    )

    private fun showPanel(state: ManualInputUi, dark: Boolean = true, withReread: Boolean = false) {
        ui = state
        rule.setContent { PaddockTheme(darkTheme = dark) { Column(Modifier.padding(16.dp)) { ManualInput(ui, now, actions(withReread)) } } }
    }

    private fun agent(status: AgentStatus = AgentStatus.Working) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status)

    private fun row(outcome: OperationOutcome, kind: OperationKind = OperationKind.Esc) =
        OperationRecord(1, "h1", "paddock-test", "term_1", 2, kind, entered + 100, outcome)

    /** The real gate, so the sentences on screen are the ones the app shows. */
    private fun gate(a: Agent? = agent(), readAt: Long? = now - 500, live: Boolean = true, records: List<OperationRecord> = emptyList(), epoch: Long? = 2L): KeyGate =
        ManualInputRules.gate(a, readAt, entered, live, records, key, epoch)

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun byDesc(d: String) = rule.onNode(hasContentDescription(d))

    @Test fun offTheTabShowsOneButtonAndTheRuleAndNoKeys() {
        showPanel(ManualInputUi(gate = null))
        rule.onNodeWithText("Manual input").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(MANUAL_INPUT_FACT).assertIsDisplayed()
        rule.onNodeWithText("Esc").assertDoesNotExist()
        rule.onNodeWithText("Ctrl+C").assertDoesNotExist()
        rule.onNodeWithText("Manual input").performClick()
        assertEquals(1, calls.enter)
        assertEquals("choosing the mode sends nothing", emptyList<OperationKind>(), calls.keys)
    }

    @Test fun enteringStartsWithAReadAndTheKeysStayOffUntilItArrives() {
        val reading = gate(readAt = null)
        assertEquals(SendBlock.Reading, (reading as KeyGate.Closed).block)
        showPanel(ManualInputUi(reading))
        rule.onAllNodesWithText("Reading the agent's state…").assertCountEquals(1)
        rule.onNodeWithText("Esc").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("Ctrl+C").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("Esc").performClick()
        rule.onNodeWithText("Ctrl+C").performClick()
        assertEquals("a key that is off sends nothing", emptyList<OperationKind>(), calls.keys)
        shoot("manual-reading-dark")
    }

    @Test fun afterTheFreshReadEachKeySendsItsOwnKindOnce() {
        showPanel(ManualInputUi(gate(), readAtMillis = now - 2_000))
        rule.onNodeWithText("MANUAL INPUT · ON").assertIsDisplayed()
        rule.onNodeWithText("Read just now").assertIsDisplayed()
        rule.onNodeWithText("Esc").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(OperationKind.Esc), calls.keys)
        rule.onNodeWithText("Ctrl+C").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(OperationKind.Esc, OperationKind.CtrlC), calls.keys)
        rule.onNodeWithText(MANUAL_KEYS_FACT).assertIsDisplayed()
        shoot("manual-open-dark")
    }

    @Test fun eachConditionThatClosesTheKeysIsSaidUnderThemAndBothKeysAreOff() {
        val cases = listOf(
            SendBlock.AgentGone to gate(a = null),
            SendBlock.NotLive to gate(live = false),
            SendBlock.Stale to gate(epoch = 3L),
            SendBlock.InFlight to gate(records = listOf(row(OperationOutcome.Sent))),
            SendBlock.NeedsReread to gate(records = listOf(row(OperationOutcome.Unknown, OperationKind.Prompt))),
        )
        showPanel(ManualInputUi(KeyGate.Open, readAtMillis = now - 500))
        for ((block, g) in cases) {
            val closed = g as KeyGate.Closed
            assertEquals(block, closed.block)
            ui = ManualInputUi(g, readAtMillis = now - 500)
            rule.waitForIdle()
            rule.onNodeWithText(closed.sentence).assertIsDisplayed()
            byDesc("Keys are off. ${closed.sentence}").assertIsDisplayed()
            rule.onNodeWithText("Esc").assertIsNotEnabled()
            rule.onNodeWithText("Ctrl+C").assertIsNotEnabled()
        }
    }

    @Test fun aReconnectOffersAReReadThatEntersTheModeAgain() {
        showPanel(ManualInputUi(gate(epoch = 3L), readAtMillis = now - 500))
        rule.onNodeWithText("Re-read").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.enter)
        ui = ManualInputUi(gate(live = false), readAtMillis = now - 500)
        rule.waitForIdle()
        rule.onNodeWithText("Re-read").assertDoesNotExist()
    }

    @Test fun aCallStillOnItsWayHoldsBothKeys() {
        showPanel(ManualInputUi(gate(), running = true, readAtMillis = now - 500))
        rule.onNodeWithText("Esc").assertIsNotEnabled()
        rule.onNodeWithText("Ctrl+C").assertIsNotEnabled()
    }

    @Test fun doneLeavesTheMode() {
        showPanel(ManualInputUi(gate(), readAtMillis = now - 500))
        rule.onNodeWithText("Done").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.leave)
    }

    @Test fun anAcceptedKeyIsReportedPlainlyWhetherOrNotTheModeIsStillOn() {
        val line = ResultLine("Esc sent 14:03:12 · accepted by herdr", ResultTone.Ok)
        showPanel(ManualInputUi(gate = null, outcome = line))
        rule.onNodeWithText(line.text).assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.dismiss)
        ui = ManualInputUi(gate(), readAtMillis = now - 500, outcome = line)
        rule.waitForIdle()
        rule.onNodeWithText(line.text).assertIsDisplayed()
    }

    @Test fun anUnknownKeyOffersAReReadAndNeverAResend() {
        val unknown = ResultLine("Esc sent 14:03:12 · outcome unknown · re-read before sending again", ResultTone.Unknown, unknown = true)
        showPanel(ManualInputUi(gate(), readAtMillis = now - 500, outcome = unknown), withReread = true)
        rule.onNodeWithText(unknown.text).assertIsDisplayed()
        rule.onNodeWithText("Re-read").assertIsDisplayed().performClick()
        assertEquals(1, calls.reread)
        rule.onNodeWithText("Dismiss").assertDoesNotExist()
        for (word in listOf("Resend", "Retry", "Try again", "Send again")) rule.onNodeWithText(word, substring = true, ignoreCase = true).assertDoesNotExist()
        shoot("manual-unknown-dark")
    }

    @Test fun aRefusedKeyOffersTheTerminal() {
        val refused = ResultLine("herdr does not accept that key (nope).", ResultTone.Refused, opensTerminal = true)
        showPanel(ManualInputUi(gate(), readAtMillis = now - 500, outcome = refused))
        rule.onNodeWithText("Open terminal").performClick()
        assertEquals(1, calls.terminal)
    }

    private fun screen(manual: ManualInputUi?, tab: AgentTab = AgentTab.Output, fontScale: Float? = null, dark: Boolean = true) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    AgentOutput(
                        header, OutputState.Showing(Ansi.parse((1..30).joinToString("\n") { "line $it of the agent output" }), now - 1_000, false), true, now, tab,
                        {}, {}, {}, {}, onCompose = {}, manualInput = manual, manualActions = manual?.let { actions() },
                    )
                }
            }
        }
    }

    @Test fun onTheOutputTabTheEntryReplacesTheInertStripAndItsReadOnlyNote() {
        screen(ManualInputUi(gate = null))
        rule.onNodeWithText("Manual input").assertIsDisplayed()
        rule.onNodeWithText(KEYS_NOTE).assertDoesNotExist()
        rule.onNode(hasContentDescription("Keys, unavailable", substring = true)).assertDoesNotExist()
    }

    @Test fun onTheTerminalTabManualInputIsNotDrawn() {
        screen(ManualInputUi(gate()), tab = AgentTab.Terminal)
        rule.onNodeWithText("Manual input").assertDoesNotExist()
        rule.onNodeWithText("Esc").assertDoesNotExist()
    }

    @Test fun twoHundredPercentFontNeverSqueezesTheOutputOutAndTheWholePanelStaysReachable() {
        val closed = gate(live = false) as KeyGate.Closed
        screen(ManualInputUi(closed, readAtMillis = now - 500), fontScale = 2f)
        rule.onNodeWithText("line 30", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Esc").assertIsDisplayed()
        rule.onNodeWithText("Ctrl+C").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
        shoot("manual-closed-dark-200")
        rule.onNodeWithText(closed.sentence).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MANUAL_KEYS_FACT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ask claude…").assertIsDisplayed()
    }

    @Test fun theOpenPanelOnTheAgentScreenInLightTheme() {
        screen(ManualInputUi(gate(), readAtMillis = now - 500), dark = false)
        rule.onNodeWithText("Esc").assertIsEnabled()
        rule.onNodeWithText("Ask claude…").assertIsDisplayed()
        shoot("manual-screen-light-100")
    }
}
