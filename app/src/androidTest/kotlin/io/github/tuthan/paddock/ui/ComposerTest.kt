package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.ComposerRules
import io.github.tuthan.paddock.ops.NotReadyReason
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.ResultTone
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ops.SendGate
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.COMPOSER_FACT
import io.github.tuthan.paddock.ui.screens.Composer
import io.github.tuthan.paddock.ui.screens.ComposerUi
import io.github.tuthan.paddock.ui.screens.PromptEntry
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for the composer (Phase 06 slice 4): Send's gate, snippets, Esc kept apart, and how a send's outcome reads. */
class ComposerTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_000_000L
    private val header = AgentHeader("Assess Rust migration", "codex · blindpass › tab 3 · main · devbox", StateWord.Ready, now - 120_000, agentKind = "codex")
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), 2)
    private val open = SendGate.Open(hintsUnreported = false)

    private class Calls {
        var send = 0; var back = 0; var esc = 0; var snippets = 0; var terminal = 0; var dismiss = 0; var reread = 0; var gateAction = 0; var dismissReread = 0
        val inserted = mutableListOf<String>()
    }

    private var ui by mutableStateOf(ComposerUi(header, SendGate.Open(false)))
    private var escEnabled by mutableStateOf(false)

    private fun show(
        state: ComposerUi, initialText: String = "", fontScale: Float? = null, dark: Boolean = true, calls: Calls = Calls(),
        onReread: (() -> Unit)? = null, gateActionLabel: String? = null,
    ): Calls {
        ui = state
        escEnabled = false
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    var text by remember { mutableStateOf(initialText) }
                    Composer(
                        ui, now, text, { text = it }, onSnippet = { calls.inserted += it; text = Snippets.insert(text, it) },
                        onSend = { calls.send++ }, onBack = { calls.back++ }, onEditSnippets = { calls.snippets++ },
                        onOpenTerminal = { calls.terminal++ }, onDismissOutcome = { calls.dismiss++ },
                        escEnabled = escEnabled, onEsc = { calls.esc++ },
                        onReread = onReread?.let { f -> { calls.reread++; f() } }, onDismissReread = { calls.dismissReread++ },
                        gateActionLabel = gateActionLabel, onGateAction = { calls.gateAction++ },
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

    private fun fieldText(): String = rule.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun agent(status: AgentStatus = AgentStatus.Idle, ready: Boolean? = null, launching: Boolean? = null) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status, interactiveReady = ready, launchPending = launching)

    private fun record(outcome: OperationOutcome, resolved: Boolean = false) =
        OperationRecord(1, "h1", "paddock-test", "term_1", 2, OperationKind.Prompt, 1_000, outcome, resolvedAt = if (resolved) 1_100 else null)

    /** The real gate for each situation, so this test and the rules cannot drift apart. */
    private fun gate(a: Agent? = agent(), live: Boolean = true, epoch: Long = 2, readAt: Long? = 1_500, records: List<OperationRecord> = emptyList(), text: String = "hello") =
        ComposerRules.gate(a, readAt, 1_000, live, records, key, text, currentEpoch = epoch)

    // ---- Send's gate ---------------------------------------------------------------------------------------------

    @Test fun sendIsOnWhenEverythingHoldsAndATapSendsOnce() {
        val calls = show(ComposerUi(header, open), initialText = "Continue with the plan")
        rule.onNodeWithText("Send prompt").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.send)
        shoot("composer-ready-dark")
    }

    @Test fun everyClosedCaseTurnsSendOffAndNamesItsCondition() {
        show(ComposerUi(header, open), initialText = "hello")
        val cases = listOf(
            "agent gone" to gate(a = null),
            "link down" to gate(live = false),
            "epoch moved" to gate(epoch = 3),
            "not read since opening" to gate(readAt = null),
            "in flight" to gate(records = listOf(record(OperationOutcome.Sent))),
            "unknown outcome" to gate(records = listOf(record(OperationOutcome.Unknown))),
            "working" to gate(agent(AgentStatus.Working)),
            "blocked" to gate(agent(AgentStatus.Blocked)),
            "status unknown" to gate(agent(AgentStatus.Unknown)),
            "not interactive" to gate(agent(ready = false)),
            "launch pending" to gate(agent(launching = true)),
        )
        for ((name, g) in cases) {
            val closed = g as SendGate.Closed
            rule.runOnIdle { ui = ComposerUi(header, g) }
            rule.onNodeWithText("Send prompt").assertIsNotEnabled()
            rule.onNode(hasContentDescription("Send is off. ${closed.sentence}")).assertExists("no reason shown for: $name")
            rule.onNodeWithText(closed.sentence).assertIsDisplayed()
        }
        assertEquals("every state-related block was exercised", SendBlock.entries.filter { it != SendBlock.EmptyPrompt && it != SendBlock.TooLong }.toSet(), cases.map { (it.second as SendGate.Closed).block }.toSet())
        shoot("composer-blocked-dark")
    }

    @Test fun everyNotReadyReasonReadsAsItsOwnSentence() {
        show(ComposerUi(header, open), initialText = "hello")
        for (reason in NotReadyReason.entries.filter { it != NotReadyReason.StaleRead && it != NotReadyReason.HintsUnreported }) {
            val g = SendGate.Closed(SendBlock.NotReady, reason.sentence, reason)
            rule.runOnIdle { ui = ComposerUi(header, g) }
            rule.onNodeWithText(reason.sentence).assertIsDisplayed()
            rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        }
    }

    @Test fun anEmptyPromptTurnsSendOffWithoutScolding() {
        show(ComposerUi(header, gate(text = "")), initialText = "")
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        rule.onNodeWithText("Write a prompt to send.").assertDoesNotExist()
    }

    @Test fun aSendUnderWayShowsSendingAndIsOff() {
        val calls = show(ComposerUi(header, open, sending = true), initialText = "hello")
        rule.onNodeWithText("Sending…").assertIsNotEnabled().performClick()
        assertEquals(0, calls.send)
    }

    @Test fun anAgentWithoutHintsSaysWhatBacksTheSend() {
        show(ComposerUi(header, SendGate.Open(hintsUnreported = true)), initialText = "hello")
        rule.onNodeWithText("Ready by herdr's status", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send prompt").assertIsEnabled()
    }

    @Test fun aStaleScreenOffersTheReReadThatClearsIt() {
        val calls = show(ComposerUi(header, gate(epoch = 3)), initialText = "hello", gateActionLabel = "Re-read")
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        rule.onNodeWithText("Re-read").performScrollTo().performClick()
        assertEquals(1, calls.gateAction)
    }

    // ---- the field and snippets --------------------------------------------------------------------------------------

    @Test fun theFieldIsMultiLineAndKeepsLineBreaks() {
        show(ComposerUi(header, open))
        rule.onNode(hasSetTextAction()).performTextInput("first line\nsecond line")
        assertEquals("first line\nsecond line", fieldText())
    }

    @Test fun snippetChipsInsertIntoTheFieldWithOneSpaceBetween() {
        val calls = show(ComposerUi(header, open, snippets = listOf("Continue", "Run the tests", "Where are you?")))
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Run the tests").performClick()
        assertEquals("Continue Run the tests", fieldText())
        assertEquals(listOf("Continue", "Run the tests"), calls.inserted)
        shoot("composer-snippets-dark")
    }

    @Test fun aLongSnippetIsAbbreviatedOnItsChipButInsertedWhole() {
        val long = "Skip the MINA option. Spike sshj with a Keystore-backed P-256 key and report what the host accepts."
        show(ComposerUi(header, open, snippets = listOf(long)))
        rule.onNode(hasContentDescription("Insert snippet: $long")).performClick()
        assertEquals(long, fieldText())
    }

    @Test fun theSnippetRowCountsThemAndSaysTheyAreNeverSynced() {
        val calls = show(ComposerUi(header, open, snippets = listOf("a", "b")))
        rule.onNodeWithText("Edit snippets · 2 saved · synced nowhere, kept on this phone").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.snippets)
    }

    // ---- Esc is kept apart ------------------------------------------------------------------------------------------

    @Test fun escIsASeparateButtonThatIsOffUntilManualInput() {
        val calls = show(ComposerUi(header, open), initialText = "hello")
        rule.onNodeWithText("Esc · Interrupt").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Esc is available in Manual input, on the agent screen.").performScrollTo().assertIsDisplayed()
        assertEquals(0, calls.esc); assertEquals(0, calls.send)
        val esc = rule.onNodeWithText("Esc · Interrupt").getUnclippedBoundsInRoot()
        val send = rule.onNodeWithText("Send prompt").getUnclippedBoundsInRoot()
        assertTrue("Esc and Send overlap: $esc $send", esc.right <= send.left || send.right <= esc.left || esc.bottom <= send.top || send.bottom <= esc.top)
    }

    @Test fun inManualInputEscWorksAndNeverSends() {
        val calls = show(ComposerUi(header, open), initialText = "hello")
        rule.runOnIdle { escEnabled = true }
        rule.onNodeWithText("Esc · Interrupt").assertIsEnabled().performClick()
        assertEquals(1, calls.esc); assertEquals(0, calls.send)
    }

    // ---- how a send ended -----------------------------------------------------------------------------------------

    @Test fun anAcceptedSendIsSaidPlainlyAndCanBeDismissed() {
        val calls = show(ComposerUi(header, open, outcome = ResultLine("Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn", ResultTone.Ok)))
        rule.onNodeWithText("Prompt sent 14:03:12", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performScrollTo().performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test fun aBlockedRefusalOffersTheTerminal() {
        val calls = show(ComposerUi(header, open, outcome = ResultLine("herdr refused the prompt: the agent is blocked and needs an answer. Nothing was typed. Use the terminal.", ResultTone.Refused, opensTerminal = true)), initialText = "keep this text")
        rule.onNodeWithText("herdr refused the prompt", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open terminal").performScrollTo().performClick()
        assertEquals(1, calls.terminal)
        assertEquals("keep this text", fieldText())
    }

    @Test fun anUnknownOutcomeOffersAReReadAndNeverAResend() {
        val calls = show(
            ComposerUi(header, SendGate.Closed(SendBlock.NeedsReread, "An earlier send's outcome is unknown. Re-read before sending again."),
                outcome = ResultLine("prompt sent 14:03:12 · outcome unknown · re-read before sending again", ResultTone.Unknown, unknown = true)),
            initialText = "hello", onReread = {},
        )
        rule.onNodeWithText("prompt sent 14:03:12 · outcome unknown · re-read before sending again").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Re-read").performScrollTo().performClick()
        assertEquals(1, calls.reread)
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        rule.onNodeWithText("Resend").assertDoesNotExist()
        rule.onNodeWithText("Send again").assertDoesNotExist()
        rule.onNodeWithText("Dismiss").assertDoesNotExist()
        shoot("composer-unknown-dark")
    }

    @Test fun theFactLineSaysOneSubmissionAndThatAcceptingIsNotAReceipt() {
        show(ComposerUi(header, open))
        rule.onNodeWithText(COMPOSER_FACT).performScrollTo().assertIsDisplayed()
        assertTrue("one submission, Enter included" in COMPOSER_FACT)
        assertTrue("not a receipt" in COMPOSER_FACT && "refuses a blocked agent" in COMPOSER_FACT)
    }

    @Test fun theStateChipSaysWhatThePhoneSawAndWhen() {
        show(ComposerUi(header, open))
        rule.onNode(hasContentDescription("Ready, observed 2 min ago")).assertIsDisplayed()
    }

    // ---- layout ----------------------------------------------------------------------------------------------------------

    @Test fun atTwiceTheFontSizeEverythingIsStillReachableAndAtLeast48dp() {
        show(ComposerUi(header, open, snippets = listOf("Continue", "Run the tests")), initialText = "hello", fontScale = 2f)
        rule.onNodeWithText("Send prompt").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Esc · Interrupt").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Edit snippets", substring = true).performScrollTo().assertIsDisplayed()
        shoot("composer-200pct-dark")
    }

    @Test fun theLightThemeIsDrawnToo() {
        show(ComposerUi(header, open, snippets = listOf("Continue")), initialText = "hello", dark = false)
        rule.onNodeWithText("Send prompt").assertIsDisplayed()
        shoot("composer-ready-light")
    }

    // ---- the entry on the Output tab -----------------------------------------------------------------------------------

    @Test fun thePromptEntryNamesTheAgentAndOpensTheComposer() {
        var opened = 0
        rule.setContent { PaddockTheme(darkTheme = true) { PromptEntry("codex", { opened++ }) } }
        rule.onNodeWithText("Ask codex…").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNode(hasContentDescription("Write a prompt for codex")).performClick()
        assertEquals(1, opened)
    }

    @Test fun aReReadReportShowsUnderTheOutcomeAndCanBeDismissed() {
        val lines = listOf("Re-read 14:05:40 · herdr reports the agent as working")
        val calls = show(ComposerUi(header, open, rereadLines = lines))
        rule.onNodeWithText(lines.single()).assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.dismissReread)
    }

    @Test fun anUnknownGateOffersAReReadAndNoResend() {
        val holding = ComposerRules.gate(agent(), 1_000_000L - 500, 1_000_000L - 3_000, true, listOf(OperationRecord(1, "h1", "paddock-test", "term_1", 2, OperationKind.Prompt, 1, OperationOutcome.Unknown, sentAt = 2)), key, "hello")
        assertEquals(SendBlock.NeedsReread, (holding as SendGate.Closed).block)
        val calls = show(ComposerUi(header, holding), initialText = "hello", gateActionLabel = "Re-read")
        rule.onNodeWithText(holding.sentence).assertIsDisplayed()
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        rule.onNodeWithText("Re-read").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.gateAction)
        for (word in listOf("Resend", "Retry", "Try again")) rule.onNodeWithText(word, substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
