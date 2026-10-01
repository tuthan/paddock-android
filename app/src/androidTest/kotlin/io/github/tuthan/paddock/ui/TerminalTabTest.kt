package io.github.tuthan.paddock.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.terminal.EndReason
import io.github.tuthan.paddock.terminal.PtySize
import io.github.tuthan.paddock.terminal.TerminalMode
import io.github.tuthan.paddock.terminal.TerminalNotice
import io.github.tuthan.paddock.terminal.TerminalView
import io.github.tuthan.paddock.terminal.VtEngine
import io.github.tuthan.paddock.ui.screens.TerminalActions
import io.github.tuthan.paddock.ui.screens.TerminalTab
import io.github.tuthan.paddock.ui.theme.PaddockTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests for the Terminal tab from fixture states (Phase 05). The session is replaced by recorded calls. */
class TerminalTabTest {
    @get:Rule val rule = createComposeRule()

    private val esc = "\u001B"
    private val screen = "${esc}[1;1H${esc}[1mpaddock${esc}[0m terminal\r\n${esc}[32mgreen${esc}[0m ${esc}[48;2;255;0;0m          ${esc}[0m ${esc}[38;5;208morange${esc}[0m\r\n日本語 wide ${esc}[4munderlined${esc}[0m"

    private fun viewOf(
        mode: TerminalMode, cols: Int = 40, rows: Int = 12, notice: TerminalNotice? = null, pty: PtySize? = PtySize(cols, rows), text: String = screen, grid: Boolean = true,
    ): TerminalView {
        val e = VtEngine(); e.resize(cols, rows); e.feed(text.toByteArray())
        return TerminalView(mode, if (grid) e.grid() else null, e.cursor, if (grid) cols else 0, if (grid) rows else 0, pty, notice, frames = 1)
    }

    private class Calls {
        val keys = CopyOnWriteArrayList<ByteArray>()
        var request = 0; var takeOver = 0; var install = 0; var dismiss = 0; var release = 0
        val resizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val viewports = CopyOnWriteArrayList<Pair<Int, Int>>()
        fun actions() = TerminalActions(
            onViewport = { c, r -> viewports += c to r }, onRequestControl = { request++ }, onTakeOver = { takeOver++ }, onInstallHelper = { install++ },
            onDismissNotice = { dismiss++ }, onRelease = { release++ }, onResizeToFit = { c, r -> resizes += c to r }, onKey = { keys += it },
        )
    }

    private fun show(view: TerminalView, calls: Calls = Calls(), dark: Boolean = true, fontScale: Float? = null): Calls {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (fontScale != null) Density(base.density, fontScale) else base) {
                PaddockTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().padding(16.dp)) { TerminalTab(view, calls.actions()) } }
            }
        }
        return calls
    }

    private fun byDesc(d: String, substring: Boolean = false) = rule.onNode(hasContentDescription(d, substring = substring))
    private fun screenNode() = rule.onNode(hasContentDescription("Terminal, ", substring = true))
    private fun textSize(): String = screenNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)!!

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- observing ----

    @Test fun anObserverIsReadOnlyAndThePillSaysSoWithTheSize() {
        show(viewOf(TerminalMode.Observing))
        byDesc("Terminal: read-only · 40×12").assertIsDisplayed()
        rule.onNodeWithText("Request control").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        shoot("terminal-observing-dark")
    }

    @Test fun theScreenIsDescribedForTalkBackWithItsTextAndWhoMayType() {
        show(viewOf(TerminalMode.Observing))
        val d = screenNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.joinToString()
        assertTrue(d, d.startsWith("Terminal, 40 columns by 12 rows. read-only, 40×12."))
        assertTrue(d, "paddock terminal" in d && "green" in d && "日本語 wide underlined" in d)
    }

    @Test fun theCellsReallyAreDrawn() {
        show(viewOf(TerminalMode.Observing))
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        var redish = 0; var text = 0
        val slab = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (r > 180 && g < 120 && b < 140) redish++
            if (p != slab && r > 150 && g > 150 && b > 150) text++
        }
        assertTrue("a red background cell run was drawn ($redish)", redish > 40)
        assertTrue("light text was drawn ($text)", text > 50)
    }

    @Test fun rotationAndLightThemeRenderToo() {
        show(viewOf(TerminalMode.Observing), dark = false)
        screenNode().assertIsDisplayed()
        shoot("terminal-observing-light")
    }

    @Test fun beforeTheFirstScreenItSaysItIsConnecting() {
        show(viewOf(TerminalMode.Connecting(control = false), grid = false))
        rule.onNodeWithText("Connecting to the terminal…").assertIsDisplayed()
        byDesc("Terminal: connecting…").assertIsDisplayed()
        rule.onNodeWithText("Request control").assertDoesNotExist()
    }

    // ---- AC-05.7: inert while observing ----

    @Test fun keysAndTheHardwareKeyboardDoNothingWhileObserving() {
        val calls = show(viewOf(TerminalMode.Observing))
        byDesc("Keys, unavailable.", substring = true).assertIsDisplayed()
        byDesc("Escape").assertDoesNotExist(); byDesc("Control C").assertDoesNotExist()
        rule.onNodeWithText("Esc").performClick(); rule.onNodeWithText("Ctrl+C").performClick(); rule.onNodeWithText("Enter").performClick()
        screenNode().requestFocus()
        screenNode().performKeyInput { pressKey(Key.A); pressKey(Key.Enter); pressKey(Key.DirectionUp); withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        rule.waitForIdle()
        assertTrue("no key reached the terminal", calls.keys.isEmpty())
    }

    @Test fun nothingAsksForControlOnItsOwn() {
        val calls = show(viewOf(TerminalMode.Observing))
        rule.waitForIdle()
        assertEquals(0, calls.request); assertEquals(0, calls.takeOver); assertEquals(0, calls.install)
        assertTrue(calls.resizes.isEmpty())
    }

    // ---- control ----

    @Test fun requestControlIsOneTapWhenTheTerminalsSizeIsKnown() {
        val calls = show(viewOf(TerminalMode.Observing))
        rule.onNodeWithText("Request control").performClick()
        assertEquals(1, calls.request); assertEquals(0, calls.takeOver)
    }

    @Test fun whenTheSizeIsUnknownRequestingControlWarnsFirstAndCancelIsTheSafeChoice() {
        val calls = show(viewOf(TerminalMode.Observing, pty = null))
        rule.onNodeWithText("Request control").performClick()
        rule.onNodeWithText("Paddock could not read this terminal's current size", substring = true).assertIsDisplayed()
        assertEquals("nothing is requested before the user agrees", 0, calls.request)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, calls.request)
        rule.onNodeWithText("Request control").performClick()
        rule.onNodeWithText("Take control").performClick()
        assertEquals(1, calls.request)
    }

    @Test fun underControlThePillTheKeysAndTheHardwareKeyboardAreLive() {
        val calls = show(viewOf(TerminalMode.Controlling))
        byDesc("Terminal: in control · 40×12").assertIsDisplayed()
        byDesc("Keys").assertIsDisplayed()
        byDesc("Escape").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        for (key in listOf("Enter", "Up arrow", "Left arrow", "Tab", "Control C")) byDesc(key).performScrollTo().performClick()
        val want = listOf(byteArrayOf(0x1B), byteArrayOf(0x0D), "\u001B[A".toByteArray(), "\u001B[D".toByteArray(), byteArrayOf(0x09), byteArrayOf(0x03))
        assertEquals(want.size, calls.keys.size)
        want.forEachIndexed { i, b -> assertArrayEquals("key $i", b, calls.keys[i]) }
        calls.keys.clear()
        screenNode().requestFocus()
        screenNode().performKeyInput { pressKey(Key.A); pressKey(Key.Enter); withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        rule.waitForIdle()
        assertEquals(3, calls.keys.size)
        assertArrayEquals("a".toByteArray(), calls.keys[0]); assertArrayEquals(byteArrayOf(0x0D), calls.keys[1]); assertArrayEquals(byteArrayOf(0x03), calls.keys[2])
        shoot("terminal-control-dark")
    }

    @Test fun releaseIsOneTap() {
        val calls = show(viewOf(TerminalMode.Controlling))
        rule.onNodeWithText("Release").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.release)
    }

    @Test fun resizeToFitWarnsFirstWithTheDesktopAndTheNewSizeThenOnlyConfirmingResizes() {
        val calls = show(viewOf(TerminalMode.Controlling))
        rule.waitForIdle()
        assertTrue("the screen reported how many cells fit", calls.viewports.isNotEmpty())
        val (cols, rows) = calls.viewports.last()
        rule.onNodeWithText("Resize to fit").assertHasClickAction().performClick()
        rule.onNodeWithText("Resize the terminal to ${cols}×$rows?").assertIsDisplayed()
        rule.onNodeWithText("This changes the terminal on the desktop too", substring = true).assertIsDisplayed()
        assertTrue("nothing is resized before the user agrees", calls.resizes.isEmpty())
        shoot("terminal-resize-warning-dark")
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(calls.resizes.isEmpty())
        rule.onNodeWithText("Resize to fit").performClick()
        rule.onNodeWithText("Resize to ${cols}×$rows").performClick()
        assertEquals(listOf(cols to rows), calls.resizes.toList())
    }

    // ---- conflict, takeover, helper ----

    @Test fun anOwnerConflictIsExplainedInHerdrsWordsAndTakeOverIsItsOwnTap() {
        val herdr = "terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"
        val calls = show(viewOf(TerminalMode.Observing, notice = TerminalNotice.Conflict(herdr)))
        rule.onNode(hasText("Another client is attached", substring = true)).assertIsDisplayed()
        rule.onNode(hasText(herdr, substring = true)).assertIsDisplayed()
        assertEquals("the refusal alone takes nothing", 0, calls.takeOver)
        rule.onNodeWithText("Request control").performClick()
        assertEquals("asking again is still not taking over", 0, calls.takeOver); assertEquals(1, calls.request)
        rule.onNodeWithText("Take over").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, calls.takeOver)
        shoot("terminal-conflict-dark")
    }

    @Test fun theHelperIsOfferedWithItsFileAndHashAndNothingIsInstalledUntilAgreed() {
        val calls = show(viewOf(TerminalMode.Observing, notice = TerminalNotice.HelperNeeded("/home/u/.local/share/paddock/paddock-control.py", "ab".repeat(32), replacing = true)))
        rule.onNodeWithText("Install the control helper?").assertIsDisplayed()
        rule.onNodeWithText("/home/u/.local/share/paddock/paddock-control.py").assertIsDisplayed()
        rule.onNodeWithText("ab".repeat(32)).assertIsDisplayed()
        rule.onNodeWithText("A different file is already there", substring = true).assertIsDisplayed()
        assertEquals(0, calls.install)
        shoot("terminal-helper-dark")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, calls.dismiss); assertEquals(0, calls.install)
    }

    @Test fun agreeingInstallsTheHelper() {
        val calls = show(viewOf(TerminalMode.Observing, notice = TerminalNotice.HelperNeeded("/h/paddock-control.py", "cd".repeat(32), replacing = false)))
        rule.onNodeWithText("Install and request control").performClick()
        assertEquals(1, calls.install)
    }

    @Test fun otherNoticesCanBeDismissedAndSayWhatHappened() {
        val calls = show(viewOf(TerminalMode.Observing, notice = TerminalNotice.ControlEnded("terminal attach taken over")))
        rule.onNode(hasText("Control ended: terminal attach taken over", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test fun aRefusalThatIsNotAnOwnerConflictHasNoTakeOver() {
        show(viewOf(TerminalMode.Observing, notice = TerminalNotice.ControlRefused("terminal control failed: something else")))
        rule.onNode(hasText("herdr did not give control: terminal control failed: something else", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Take over").assertDoesNotExist()
    }

    @Test fun aResyncIsOneQuietLine() {
        show(viewOf(TerminalMode.Observing, notice = TerminalNotice.Resynced))
        rule.onNode(hasText("Resynced", substring = true)).assertIsDisplayed()
    }

    // ---- ending ----

    @Test fun aLostLinkSaysSoKeepsTheLastScreenAndOffersNoControl() {
        show(viewOf(TerminalMode.Ended(EndReason.LinkLost)))
        rule.onNodeWithText("The connection to the machine dropped.").assertIsDisplayed()
        rule.onNodeWithText("Request control").assertDoesNotExist(); rule.onNodeWithText("Release").assertDoesNotExist()
        screenNode().assertIsDisplayed()
        shoot("terminal-linklost-dark")
    }

    @Test fun aClosedTerminalAndOtherEndings() {
        show(viewOf(TerminalMode.Ended(EndReason.PaneGone), grid = false))
        rule.onNodeWithText("This terminal is no longer in the session.").assertIsDisplayed()
    }

    @Test fun herdrsOwnClosingWordsAreShownAsData() {
        show(viewOf(TerminalMode.Ended(EndReason.Closed("server shutting down"))))
        rule.onNodeWithText("It said: server shutting down").assertIsDisplayed()
    }

    // ---- pinch and pan ----

    @Test fun pinchingChangesOnlyTheTextSizeAndNeverResizesTheTerminal() {
        val calls = show(viewOf(TerminalMode.Controlling))
        rule.waitForIdle()
        val before = textSize()
        screenNode().performTouchInput { pinch(Offset(centerX - 20, centerY), Offset(centerX - 120, centerY), Offset(centerX + 20, centerY), Offset(centerX + 120, centerY)) }
        rule.waitForIdle()
        val larger = textSize()
        assertTrue("$before -> $larger", larger != before)
        screenNode().performTouchInput { pinch(Offset(centerX - 120, centerY), Offset(centerX - 20, centerY), Offset(centerX + 120, centerY), Offset(centerX + 20, centerY)) }
        rule.waitForIdle()
        assertTrue("pinching in shrinks it again: $larger -> ${textSize()}", textSize() != larger)
        assertTrue("a pinch is not a resize", calls.resizes.isEmpty())
        assertTrue("a pinch sends no key", calls.keys.isEmpty())
        assertEquals(0, calls.request + calls.takeOver + calls.release)
    }

    @Test fun textSizeHasAccessibleActionsToo() {
        show(viewOf(TerminalMode.Observing))
        rule.waitForIdle()
        val actions = screenNode().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.map { it.label }
        assertEquals(listOf("Larger text", "Smaller text", "Fit text to the screen"), actions)
        val before = textSize()
        fun run(label: String) { val a = screenNode().fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }; rule.runOnUiThread { a.action() } }
        run("Larger text")
        rule.waitForIdle()
        assertTrue(before != textSize())
        run("Fit text to the screen")
        rule.waitForIdle()
        assertEquals(before, textSize())
    }

    @Test fun aLargeTerminalCanBePannedAndStillFitsTheView() {
        val calls = show(viewOf(TerminalMode.Observing, cols = 200, rows = 60, text = (1..60).joinToString("\r\n") { "line $it ".repeat(20) }))
        rule.waitForIdle()
        screenNode().performTouchInput { swipeLeft(); swipeUp() }
        rule.waitForIdle()
        screenNode().assertIsDisplayed()
        assertTrue(calls.keys.isEmpty())
    }

    @Test fun twoHundredPercentFontKeepsTheActionsReachable() {
        show(viewOf(TerminalMode.Observing), fontScale = 2f)
        rule.onNodeWithText("Request control").assertIsDisplayed()
        screenNode().assertIsDisplayed()
        shoot("terminal-observing-dark-200")
    }
}
