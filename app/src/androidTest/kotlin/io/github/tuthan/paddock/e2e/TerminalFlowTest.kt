package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.ui.components.TerminalTiming
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 05 on a device, through the whole app: Add machine over SSH, the Terminal tab observing a real herdr pane, an owner
 * conflict with a desktop client, the helper install consent, take over, typing with the hardware keyboard, a 200-line scroll
 * timed five times, the Android keyboard (keys injected by the system reach the pane), Resize to fit, release, control again, the app sent to the background and Back pressed while in control
 * (both must release at once), then control once more and the link dying under it. `tools/run-terminal-e2e.sh`
 * prepares the host, runs the checks that need the host (`pane get`, the desktop client, the proxy) at each checkpoint and
 * releases the test with a file; the test never waits for the script for more than two minutes.
 */
class TerminalFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2234"
    private val home by lazy { args.getString("home") ?: error("home argument missing") }
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    /** Compose cannot capture a window that holds a dialog below API 28, so those pictures are skipped there, not the checks. */
    private fun shoot(name: String, dialog: Boolean = false) {
        if (dialog && android.os.Build.VERSION.SDK_INT < 28) { log("screenshot $name skipped on API ${android.os.Build.VERSION.SDK_INT} (dialog)"); return }
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "term-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)

    /**
     * Tells the script where the test is and waits for the file it answers with. With [pump] the wait is the Compose rule's own
     * polling, which keeps the UI drawing as it would for a user; a plain sleep leaves the rule's frame clock parked, and the
     * screen then shows nothing new until the wait ends (so timing is only meaningful with [pump]).
     */
    private fun checkpoint(marker: String, timeoutMs: Long = 120_000, pump: Boolean = false) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try {
            if (pump) rule.waitUntil(timeoutMs) { go.exists() }
            else { val end = System.currentTimeMillis() + timeoutMs; while (!go.exists()) { if (System.currentTimeMillis() > end) throw AssertionError("x"); Thread.sleep(150) } }
        } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    /** A sleep that keeps the UI drawing (see [checkpoint]). */
    private fun pumpSleep(ms: Long) { val end = System.currentTimeMillis() + ms; rule.waitUntil(ms + 5_000) { System.currentTimeMillis() >= end } }

    private fun screenNode() = rule.onNode(desc("Terminal, "))
    private fun screenText(): String = rule.onAllNodes(desc("Terminal, ")).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString() ?: ""
    private fun pill(): String = listOf("read-only", "in control", "connecting", "asking for control", "releasing", "ended", "not connected")
        .firstOrNull { hasNode(desc("Terminal: $it")) } ?: "?"

    private fun pillFull(): String = rule.onAllNodes(desc("Terminal: ")).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString() ?: "?"

    private fun keyFor(c: Char): Key = when (c) {
        in 'a'..'z' -> Key(android.view.KeyEvent.KEYCODE_A + (c - 'a')); in '0'..'9' -> Key(android.view.KeyEvent.KEYCODE_0 + (c - '0'))
        ' ' -> Key.Spacebar; '-' -> Key.Minus; else -> error("no key for '$c'")
    }

    private fun typeLine(line: String) {
        screenNode().requestFocus()
        screenNode().performKeyInput { for (c in line) pressKey(keyFor(c)); pressKey(Key.Enter) }
    }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    @Test fun observeConflictTakeOverTypeScrollResizeReleaseThenLoseTheLink() {
        // --- get to the agent's Terminal tab through the real UI ---
        waitFor("the Add machine screen") { hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(desc("Blocked")) }
        if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
        waitFor("the blocked agent on Home") { hasNode(desc("Blocked")) && hasNode(desc("live")) }
        rule.onNode(desc("Blocked")).performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
        rule.onNodeWithText("Terminal").performClick()

        // --- AC-05.3: observing, with the pane's geometry checked from the host before and after ---
        waitFor("the read-only pill") { pill() == "read-only" }
        waitFor("the first screen") { hasNode(desc("Terminal, ")) && screenText().contains("Screen text") }
        shoot("observing")
        log("OBSERVING pill=${pill()}")
        checkpoint("observing")

        // --- control: the helper is not on the host yet, and a desktop client owns input ---
        rule.onNodeWithText("Request control").performClick()
        waitFor("the helper consent") { hasNode(text("Install the control helper?")) }
        rule.onNodeWithText("$home/.local/share/paddock/paddock-control.py").assertIsDisplayed()
        shoot("helper-consent", dialog = true)
        checkpoint("helper-consent")
        rule.onNodeWithText("Install and request control").performClick()
        waitFor("the owner conflict") { hasNode(text("Another client is attached", substring = true)) }
        waitFor("read-only again after the refusal") { pill() == "read-only" }
        assertTrue("the refusal is still on screen once the observer is back", hasNode(text("Another client is attached", substring = true)))
        shoot("conflict")
        checkpoint("conflict")
        rule.onNodeWithText("Take over").performClick()
        waitFor("in control") { pill() == "in control" }
        shoot("in-control")
        checkpoint("controlling")

        // --- typing with the hardware keyboard ---
        typeLine("echo phone-e2e")
        waitFor("the typed output on the screen") { screenText().lines().count { it.trim() == "phone-e2e" } >= 1 }
        shoot("typed")
        checkpoint("typed")

        // --- a rotation must not hand the terminal back: the script turns the device to landscape and back ---
        checkpoint("rotate")
        waitFor("still in control after the rotation") { pill() == "in control" }
        assertTrue("the screen survived the rotation", screenText().contains("phone-e2e"))
        typeLine("echo after-rotation")
        waitFor("typing still works after the rotation") { screenText().lines().any { it.trim() == "after-rotation" } }
        shoot("after-rotation")

        // --- the Android keyboard: Keyboard puts the focus on the hidden field the keyboard types into. The script then injects
        // keys through the system's input pipeline (`input text`, Enter), which is what a keyboard attached to the phone does, and
        // the host must see them. Whether the on-screen keyboard itself came up is logged, not asserted: an emulator with a hardware
        // keyboard configured may not show one. ---
        rule.onNodeWithText("Keyboard").performClick()
        pumpSleep(1_500)
        log("KEYBOARD after the tap the button reads: ${if (hasNode(hasText("Hide keyboard"))) "Hide keyboard (the IME is up)" else "Keyboard (no IME shown)"}")
        shoot("soft-keyboard")
        if (hasNode(hasText("Hide keyboard"))) {
            // With the keyboard really up the screen is the focus layout: the tabs and the state chip are gone and the grid keeps room.
            val density = rule.density
            val grid = rule.onAllNodes(desc("Terminal, ")).fetchSemanticsNodes().first().size.height
            val gridDp = with(density) { grid.toDp().value }
            val escDp = with(density) { rule.onNode(desc("Escape", substring = false)).fetchSemanticsNode().size.height.toDp().value }
            log("KEYBOARD the grid is $gridDp dp tall with the keyboard up; the Esc key is $escDp dp")
            assertFalse("the tabs are hidden while the keyboard is up", hasNode(hasText("Output")))
            assertTrue("the grid keeps room with the keyboard up ($gridDp dp)", gridDp >= 150f)
            assertTrue("the strip keys are the 40 dp bars ($escDp dp)", escDp in 39.5f..40.5f)
        }
        checkpoint("soft-keyboard-ready", pump = true)
        waitFor("the soft-typed output on the screen") { screenText().lines().any { it.trim() == "soft-e2e" } }
        shoot("soft-typed")
        if (hasNode(hasText("Hide keyboard"))) rule.onNodeWithText("Hide keyboard").performClick()
        pumpSleep(800)
        assertTrue("still in control after the keyboard was used", pill() == "in control")

        // --- the key strip's modifiers and extra keys, against the real pane: `cat -v` prints each key as the pane's terminal
        // received it (^[ is ESC), with the tty's own echo off so a line appears once. The last tap of each line is Enter. ---
        fun tapKey(d: String) { rule.onNode(desc(d, substring = false)).performScrollTo().performClick() }
        typeLine("stty -echo"); typeLine("cat -v"); pumpSleep(700)
        tapKey("Shift, for the next key"); tapKey("Tab")
        tapKey("Control, for the next key"); tapKey("Right arrow")
        tapKey("Insert"); tapKey("Delete forward")
        tapKey("Enter")
        waitFor("Shift+Tab, Ctrl+Right, Insert and Delete as the pane received them") { screenText().lines().any { it.trim() == "^[[Z^[[1;5C^[[2~^[[3~" } }
        tapKey("F1"); tapKey("F12")
        tapKey("Shift, for the next key"); tapKey("Page up")
        tapKey("Alt, for the next key"); tapKey("Enter")
        waitFor("F1, F12, Shift+PgUp and Alt+Enter as the pane received them") { screenText().lines().any { it.trim() == "^[OP^[[24~^[[5;2~^[" } }
        shoot("strip-keys")
        checkpoint("strip-keys", pump = true)
        tapKey("Control C"); pumpSleep(500)
        typeLine("stty echo"); pumpSleep(500)
        assertTrue("still in control after the strip's keys", pill() == "in control")

        // --- AC-05.8: a 200-line scroll. Once from the hardware keyboard (to show the keys drive it), then five times with the
        // script starting it from the host while this thread is idle, so the numbers are the app's and not the test harness's.
        fun report(label: String): Triple<Double, Double, Int> {
            val samples = TerminalTiming.samples()
            val last = samples.last()
            val firstArrival = samples.first().arrivedNanos
            log("SCROLL $label frames (arrived -> drawn, ms from the first arrival): " + samples.joinToString(" ") { "#${it.frame} ${"%.0f".format((it.arrivedNanos - firstArrival) / 1e6)}->${"%.0f".format((it.drawnNanos - firstArrival) / 1e6)}" })
            log("SCROLL $label: last frame arrived to drawn ${"%.1f".format(last.millis)} ms; first arrival to last drawn ${"%.1f".format((last.drawnNanos - firstArrival) / 1e6)} ms; ${samples.size} frames drawn; worst single frame ${"%.1f".format(samples.maxOf { it.millis })} ms")
            return Triple(last.millis, (last.drawnNanos - firstArrival) / 1e6, samples.size)
        }
        typeLine("clear"); pumpSleep(700)
        TerminalTiming.clear()
        typeLine("seq 1 200")
        waitFor("the keyboard-driven scroll to reach 200") { screenText().lines().any { it.trim() == "200" } }
        pumpSleep(800)
        report("by keyboard")
        val timings = mutableListOf<Triple<Double, Double, Int>>()
        repeat(5) { run ->
            pumpSleep(500)
            TerminalTiming.clear()
            checkpoint("scroll-${run + 1}", pump = true)
            waitFor("the scroll to reach 200") { screenText().lines().any { it.trim() == "200" } }
            pumpSleep(500)
            timings += report("run ${run + 1}")
        }
        log("SCROLL summary: last-frame-to-drawn ms = ${timings.map { "%.1f".format(it.first) }}; whole scroll ms = ${timings.map { "%.0f".format(it.second) }}")

        // --- AC-05.5: resize to fit, with the warning first ---
        val before = pillFull()
        rule.onNodeWithText("Resize to fit").performClick()
        waitFor("the resize warning") { hasNode(text("This changes the terminal on the desktop too", substring = true)) }
        shoot("resize-warning", dialog = true)
        checkpoint("resize-warning")
        val resizeLabel = rule.onAllNodes(hasText("Resize to ", substring = true) and !hasText("Resize to fit")).fetchSemanticsNodes().first().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.first()?.text ?: error("no confirm label")
        log("RESIZE confirm label: $resizeLabel; pill before: $before")
        rule.onNode(hasText("Resize to ", substring = true) and !hasText("Resize to fit")).performClick()
        waitFor("the grid to follow the resize") { pill() == "in control" && pillFull() != before }
        log("RESIZE pill after: ${pillFull()}")
        Thread.sleep(800)
        shoot("resized")
        checkpoint("resized")

        // --- release, then control again without a conflict ---
        rule.onNodeWithText("Release").performClick()
        waitFor("read-only again") { pill() == "read-only" }
        shoot("released")
        checkpoint("released")
        rule.onNodeWithText("Request control").performClick()
        waitFor("in control again, no conflict this time") { pill() == "in control" }

        // --- leaving while in control: the script sends the app to the background and checks the host, then brings it back;
        // the terminal must come back as an observer and never as the controller ---
        checkpoint("background-ready")
        waitFor("the terminal observing again after the app came back") { pill() == "read-only" }
        assertFalse("coming back from the background does not take control again", hasNode(desc("Terminal: in control")))
        shoot("after-background")
        rule.onNodeWithText("Request control").performClick()
        waitFor("in control after the background trip") { pill() == "in control" }

        // --- Back while in control: the script presses Back and checks the host; the test then opens the terminal again ---
        checkpoint("back-ready", pump = true)   // pumped: Back is only acted on while the harness lets the UI compose
        waitFor("Home after Back") { hasNode(desc("Blocked")) }
        rule.onNode(desc("Blocked")).performClick()
        waitFor("the Output tab again") { hasNode(desc("Terminal output")) }
        rule.onNodeWithText("Terminal").performClick()
        waitFor("a fresh read-only terminal after Back") { pill() == "read-only" }
        rule.onNodeWithText("Request control").performClick()
        waitFor("in control again after Back") { pill() == "in control" }
        checkpoint("control-before-link-loss")

        // --- AC-05.6: the script freezes the link now; the test only watches what the app says ---
        val frozenAt = System.currentTimeMillis()
        var lostAfter: Long? = null
        var shown = ""
        val deadline = frozenAt + 150_000
        while (System.currentTimeMillis() < deadline) {
            if (!hasNode(desc("Terminal: in control"))) {
                lostAfter = System.currentTimeMillis() - frozenAt
                shown = when {
                    hasNode(desc("Terminal: ")) -> pillFull()
                    hasNode(text("This agent is not available right now", substring = true)) -> "the screen for an agent whose machine is not connected"
                    else -> "something else (not the in-control terminal)"
                }
                break
            }
            Thread.sleep(250)
        }
        log("LINKLOSS the app stopped showing 'in control' after ${lostAfter ?: "never (150 s)"} ms (measured from when the script released the checkpoint, a moment after the link was cut); then showing: $shown")
        shoot("linkloss")
        assertTrue("the app must not go on saying it controls the terminal once the link is gone", lostAfter != null)
        checkpoint("done")
    }
}
