package io.github.tuthan.paddock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.OutputCopy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.screens.SelectText
import io.github.tuthan.paddock.ui.screens.Since
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Select text view from fixture snapshots (Phase 15 slice 4): its header, its text, its buttons, the toolbar's Copy and the audits. */
class SelectTextTest {
    @get:Rule val rule = createComposeRule()

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cm get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val esc = "\u001B"

    private fun tail(n: Int) = (1..n).joinToString("\n") { i -> if (i % 7 == 0) "$esc[31merror$esc[0m line $i   " else "line $i of the agent output" }
    private fun showing(raw: String = tail(30), stale: Boolean = false) = OutputState.Showing(Ansi.parse(raw), 1_000_000L, stale)

    private class Calls { var copied: OutputCopy.Fit? = null; var shared: OutputCopy.Fit? = null; var reread = 0; var close = 0 }

    private fun show(
        snapshot: OutputState.Showing = showing(), since: Since? = null, protect: Boolean = true, startLine: Int? = null, reReading: Boolean = false,
        dark: Boolean = true, fontScale: Float? = null, calls: Calls = Calls(),
    ): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) {
                    SelectText(
                        "approve edit to build.gradle", snapshot, "14:03:12", since, protect, startLine, reReading,
                        onCopyAll = { fit -> calls.copied = fit; copyText(ctx, CopyKind.Output, fit.text, protect) },
                        onShare = { calls.shared = it }, onReRead = { calls.reread++ }, onClose = { calls.close++ },
                    )
                }
            }
        }
        return calls
    }

    private fun fieldText() = rule.onNodeWithTag("SELECT_TEXT").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
    private fun clip(): ClipData? { var c: ClipData? = null; rule.runOnUiThread { c = cm.primaryClip }; return c }
    private fun marked(c: ClipData?) = c?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE_KEY, false) == true

    @Test fun theHeaderSaysWhatTheCopyIsAndWhenItWasRead() {
        show(showing(tail(200)))
        rule.onNodeWithText("Select text").assertIsDisplayed()
        rule.onNodeWithText("Text as read at 14:03:12 · 200 lines").assertIsDisplayed()
        rule.onNodeWithText("The last read failed.").assertDoesNotExist()
        rule.onNodeWithText("The agent's own line breaks, markers and borders come along.").assertIsDisplayed()
    }

    @Test fun aFailedReadACutLineAndAnExitedAgentAreEachSaid() {
        show(showing("y".repeat(Ansi.MAX_LINE_CHARS + 5) + "\nshort", stale = true), since = Since.AgentExited)
        rule.onNodeWithText("The last read failed.").assertIsDisplayed()
        rule.onNodeWithText("1 line is cut at 2,000 characters.").assertIsDisplayed()
        rule.onNodeWithText("This agent has exited since.").assertIsDisplayed()
    }

    @Test fun aClosedTerminalIsSaidToo() {
        show(since = Since.TerminalClosed)
        rule.onNodeWithText("This terminal has closed since.").assertIsDisplayed()
    }

    @Test fun theTextIsExactlyTheCopyText() {
        val snapshot = showing(tail(40))
        show(snapshot)
        rule.waitForIdle()
        assertEquals(OutputCopy.fit(snapshot.lines).text, fieldText())
    }

    @Test fun copyAllHandsOverTheFitAndPutsItOnTheClipboardMarkedWhileProtected() {
        val calls = show(showing(tail(40)), protect = true)
        rule.onNodeWithText("Copy all").performClick()
        val fit = calls.copied!!
        assertEquals(OutputCopy.fit(showing(tail(40)).lines).text, fit.text)
        rule.waitForIdle()
        assertEquals(fit.text, clip()?.getItemAt(0)?.text?.toString())
        assertTrue(marked(clip()))
    }

    @Test fun copyAllIsNotMarkedWithTheSettingOff() {
        show(showing(tail(10)), protect = false).also { rule.onNodeWithText("Copy all").performClick() }
        rule.waitForIdle()
        assertFalse(marked(clip()))
    }

    @Test fun theToolbarsCopyAfterSelectAllGivesTheSameStringAndIsMarked() {
        val snapshot = showing(tail(40))
        show(snapshot, protect = true)
        val want = OutputCopy.fit(snapshot.lines).text
        rule.runOnUiThread { cm.setPrimaryClip(ClipData.newPlainText("before", "something else")) }
        rule.onNodeWithTag("SELECT_TEXT").performTextInputSelection(TextRange(0, want.length))
        rule.onNodeWithTag("SELECT_TEXT").performSemanticsAction(SemanticsActions.CopyText)
        rule.waitForIdle(); Thread.sleep(300)
        val c = clip()
        assertEquals(want, c?.getItemAt(0)?.text?.toString())
        assertTrue("the toolbar's Copy goes through the wrapper, so it is marked", marked(c))
        assertFalse("no escape code or control character in a copy", want.any { it < ' ' && it != '\n' })
    }

    @Test fun theToolbarsCopyIsNotMarkedWithTheSettingOff() {
        show(showing(tail(10)), protect = false)
        rule.onNodeWithTag("SELECT_TEXT").performTextInputSelection(TextRange(0, 5))
        rule.onNodeWithTag("SELECT_TEXT").performSemanticsAction(SemanticsActions.CopyText)
        rule.waitForIdle(); Thread.sleep(300)
        assertFalse(marked(clip()))
    }

    @Test fun shareHandsOverTheSameFit() {
        val calls = show(showing(tail(12)))
        rule.onNodeWithText("Share").performClick()
        assertEquals(OutputCopy.fit(showing(tail(12)).lines).text, calls.shared?.text)
    }

    @Test fun reReadAsksOnceAndIsOffOnceTheAgentIsGoneOrWhileReading() {
        val calls = show()
        rule.onNodeWithText("Re-read").assertIsEnabled().performClick()
        assertEquals(1, calls.reread)
    }

    @Test fun reReadIsOffWhenTheTerminalIsGone() {
        show(since = Since.TerminalClosed)
        rule.onNodeWithText("Re-read").assertIsNotEnabled()
    }

    @Test fun whileReadingTheButtonSaysSoAndIsOff() {
        show(reReading = true)
        rule.onNodeWithText("Reading…").assertIsNotEnabled()
    }

    @Test fun theWorstCaseOpensCopiesAndSharesTheNewestWholeLinesThatFit() {
        val worst = showing((1..200).joinToString("\n") { "x".repeat(Ansi.MAX_LINE_CHARS + 1) })
        val calls = show(worst)
        rule.waitForIdle()
        val fit = OutputCopy.fit(worst.lines)
        assertTrue(fit.leftOut > 0 && fit.text.length <= OutputCopy.BUDGET)
        rule.onNodeWithText("The newest ${fit.lines.size} of 200 lines; the rest are too long to copy at once.").assertIsDisplayed()
        rule.onNodeWithText("Copy all").performClick(); rule.waitForIdle()
        assertEquals(fit.text.length, calls.copied!!.text.length)
        assertEquals(fit.text.length, clip()?.getItemAt(0)?.text?.length)
        rule.onNodeWithText("Share").performClick()
        assertNotNull(calls.shared)
    }

    @Test fun itOpensAtTheNewestLine() {
        show(showing(tail(300)))
        rule.waitForIdle()
        val axis = rule.onNodeWithTag("SELECT_TEXT").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertNotNull("the field reports its scroll range", axis)
        assertTrue("scrollable at all: ${axis!!.maxValue()}", axis.maxValue() > 0f)
        assertEquals("opens at the end", axis.maxValue(), axis.value(), 1f)
    }

    @Test fun openedFromALineItStartsBelowTheTopOfTheText() {
        show(showing(tail(300)), startLine = 150)
        rule.waitForIdle()
        val axis = rule.onNodeWithTag("SELECT_TEXT").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertNotNull("the field reports its scroll range", axis)
        assertTrue("scrolled to line 150 of 300, not the top and not the end: ${axis!!.value()} of ${axis.maxValue()}", axis.value() > 0f && axis.value() < axis.maxValue())
    }

    @Test fun noKeyboardIsAskedFor() {
        show()
        rule.waitForIdle()
        assertNull("a read-only view takes no focus request", rule.onNodeWithTag("SELECT_TEXT").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)?.takeIf { it })
    }

    @Test fun backClosesThroughTheHeaderButton() {
        val calls = show()
        rule.onNodeWithContentDescriptionBack().performClick()
        assertEquals(1, calls.close)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionBack() = onNode(androidx.compose.ui.test.hasContentDescription("Back"))

    // ---- the accessibility audit (AC-15.9): both themes and at 200 % font -----------------------------------------------------------

    private val AUDIT = SemanticsAudit.Options(regions = mapOf("SELECT_TEXT" to 100f))
    @Test fun auditSelectTextDark() { show(); SemanticsAudit.expectClean(rule, "Select text, dark", AUDIT) }
    @Test fun auditSelectTextLight() { show(dark = false); SemanticsAudit.expectClean(rule, "Select text, light", AUDIT) }
    @Test fun auditSelectTextAtTwoHundredPercentFont() { show(fontScale = 2f); SemanticsAudit.expectClean(rule, "Select text, 200 % font", AUDIT) }
}
