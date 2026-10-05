package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 09 on a device, through the whole app over SSH to a herdr of its own (`paddock-harness/run-spaces-e2e.sh`). The script prepares an isolated
 * HOME with a running session `paddock-test-e2e9` (the one this phone watches), a stopped one with a saved layout, `claude` stand-ins, and
 * the throwaway sshd, then checks at each checkpoint what only the host can see and releases the test with a file. Nothing here touches
 * `default` or `paddock-test` of the developer's herdr: the isolated HOME has its own, and its `default` is a stopped session without a
 * saved layout, which is exactly what the Delete-is-refused check needs.
 *
 * The test: Add machine; Spaces lists the sessions (the watched one marked, the stopped one's saved layout, `default` with no Delete);
 * deleting the stopped session asks first and opens on Cancel, then goes through; a tab agent starts (`claude` stand-in), then a worktree
 * agent; a duplicate name stops at its recovery card (naming the pane it created), which can start under another name, and the next
 * duplicate is closed through the card; a long-press renames an agent and moves the desktop's focus; Activity records it all; and
 * stopping the watched session asks first and then stops it.
 */
class SpacesFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2233"
    private val session = args.getString("session") ?: "paddock-test-e2e9"
    private val stoppedSession = args.getString("stopped") ?: "paddock-test-e2e9b"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "spaces-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)) }; throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)
    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun checkpoint(marker: String, timeoutMs: Long = 180_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    /** The progress card's Dismiss, which is after any notice banner's own Dismiss on the screen. */
    private fun dismissProgress() { rule.onAllNodes(button("Dismiss")).onLast().performScrollTo().performClick() }

    private fun tab(label: String) { rule.onNode(text(label) and hasClickAction()).performClick() }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    private fun cancelIsFocused() = waitFor("Cancel focused") { runCatching { rule.onNode(button("Cancel")).assertIsFocused() }.isSuccess }

    /** The form: [kind] stays claude (the stand-in), [name] typed, optionally a worktree branch. Ends on the tap of Start agent. */
    private fun startAgent(name: String, branch: String? = null) {
        rule.onNode(button("Start an agent…")).performScrollTo().performClick()
        waitFor("the start form") { hasNode(text("Start an agent")) && hasNode(button("Start agent")) }
        rule.onNode(text("Name") and hasSetTextAction()).performTextInput(name)
        if (branch != null) {
            rule.onNodeWithText("Start in a new worktree").performScrollTo().performClick()
            rule.onNode(text("Branch") and hasSetTextAction()).performScrollTo().performTextInput(branch)
        }
        shoot("start-form-$name")
        rule.onNode(button("Start agent") and isEnabled()).performScrollTo().performClick()
    }

    @Test fun t1_spacesAgainstARealHerdr() {
        // --- Add machine through the real UI, watching the running session ---
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(desc("live")) }
        if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
        waitFor("the watched session live", 90_000) { hasNode(desc("live")) }

        // --- Spaces: the sessions, as that moment's read ---
        tab("Spaces")
        waitFor("the session list", 60_000) { hasNode(text(stoppedSession)) && hasNode(button("Start an agent…")) }
        assertTrue("the watched session is marked", hasNode(text("watched by this phone", substring = true)))
        assertTrue("the stopped session shows what herdr saved", hasNode(text("Saved layout: notes", substring = true)))
        assertTrue("default shows no saved contents", hasNode(text("saved contents unavailable")))
        assertTrue("Restart says why it is off", hasNode(text("Restart is not offered yet", substring = true)))
        assertTrue("the list says it is a read, not a live view", hasNode(text("List read at", substring = true)))
        rule.onAllNodes(button("Delete…") and !isEnabled()).onFirst().performScrollTo()
        assertTrue("default's Delete is not offered", hasNode(text("The default session cannot be deleted.")))
        shoot("list")
        checkpoint("listed")

        // --- AC-09.1: Delete asks first, opens on Cancel, and changes nothing until confirmed ---
        rule.onAllNodes(button("Delete…") and isEnabled()).onFirst().performScrollTo().performClick()
        waitFor("the delete dialog") { hasNode(text("Delete session $stoppedSession?")) }
        cancelIsFocused()
        assertTrue("the dialog names the machine", hasNode(desc("Machine: ")))
        shoot("delete-dialog")
        checkpoint("delete-asked")
        rule.onNode(button("Cancel")).performClick()
        waitFor("the dialog gone") { !hasNode(text("Delete session $stoppedSession?")) }
        checkpoint("delete-cancelled")
        rule.onAllNodes(button("Delete…") and isEnabled()).onFirst().performScrollTo().performClick()
        rule.onNode(button("Delete session")).performClick()
        waitFor("herdr's answer and the list read again", 60_000) { hasNode(text("herdr says session $stoppedSession is deleted", substring = true)) && !hasNode(text(stoppedSession)) }
        checkpoint("deleted")

        // --- AC-09.3: a tab agent, then a worktree agent ---
        startAgent("e2e-worker")
        waitFor("the agent running", 90_000) { hasNode(text("e2e-worker (claude) is running.")) }
        shoot("started-tab")
        checkpoint("tab-started")
        dismissProgress()

        startAgent("e2e-wt", branch = "e2e-branch")
        waitFor("the worktree agent running", 120_000) { hasNode(text("e2e-wt (claude) is running.")) }
        checkpoint("worktree-started")
        dismissProgress()

        // --- AC-09.4: a duplicate name stops at a card that names the pane it created; another name goes ---
        startAgent("e2e-worker")
        waitFor("the recovery card", 90_000) { hasNode(desc("Recovery card. Starting e2e-worker (claude) stopped")) }
        rule.onNode(button("Choose another name")).performScrollTo()
        assertTrue("the card says the name is taken", hasNode(text("agent_name_taken", substring = true)) || hasNode(text("already", substring = true)))
        assertTrue("the card lists the pane it created", hasNode(desc("Pane: ")))
        shoot("recovery-name-taken")
        checkpoint("name-taken")
        rule.onNode(button("Choose another name")).performClick()
        rule.onNode(text("New name") and hasSetTextAction()).performScrollTo().performTextInput("e2e-worker-2")
        rule.onNode(button("Start with this name") and isEnabled()).performScrollTo().performClick()
        waitFor("the agent running under the new name", 90_000) { hasNode(text("e2e-worker-2 (claude) is running.")) }
        checkpoint("renamed-start")
        dismissProgress()

        // A second duplicate is closed through the card, after its own confirmation.
        startAgent("e2e-worker")
        waitFor("the second recovery card", 90_000) { hasNode(button("Close the new tab…")) }
        rule.onNode(button("Close the new tab…")).performScrollTo().performClick()
        waitFor("the close dialog") { hasNode(button("Close it")) }
        cancelIsFocused()
        checkpoint("close-asked")
        rule.onNode(button("Cancel")).performClick()
        rule.onNode(button("Close the new tab…")).performScrollTo().performClick()
        rule.onNode(button("Close it")).performClick()
        waitFor("the card gone", 60_000) { !hasNode(button("Close the new tab…")) }
        checkpoint("closed")

        // --- Rename and focus from a long-press on a herd row ---
        tab("Herd")
        waitFor("the started agent on the herd", 60_000) { hasNode(desc("e2e-worker-2")) }
        rule.onAllNodes(desc("e2e-worker-2") and hasClickAction()).onFirst().performTouchInput { longClick() }
        waitFor("the row's actions") { hasNode(button("Rename agent…")) }
        cancelIsFocused()
        rule.onNode(button("Rename agent…")).performClick()
        rule.onNode(text("Name") and hasSetTextAction()).performTextReplacement("e2e-renamed")
        rule.onNode(button("Rename") and isEnabled()).performClick()
        waitFor("the renamed agent on the herd", 60_000) { hasNode(desc("e2e-renamed")) }
        checkpoint("renamed")
        rule.onAllNodes(desc("e2e-renamed") and hasClickAction()).onFirst().performTouchInput { longClick() }
        waitFor("the row's actions again") { hasNode(button("Show its workspace on the desktop")) }
        rule.onNode(button("Show its workspace on the desktop")).performClick()
        waitFor("the focus notice") { hasNode(text("The desktop now shows this", substring = true)) }
        checkpoint("focused-workspace")

        // --- Activity lists it, with the saga's ids ---
        tab("Activity")
        waitFor("Activity rows") { hasNode(desc("You focused workspace")) }
        // The list is lazy: scroll to each row before looking for it.
        fun row(d: String) { rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(desc(d)); assertTrue("Activity has a row for: $d", hasNode(desc(d))) }
        row("You started e2e-worker (claude)")
        row("You started e2e-wt (claude)")
        row("Starting e2e-worker (claude) stopped: start the agent")   // the duplicate: its ids are in the row's detail
        row("Created: tab")
        row("You renamed")
        row("You deleted session $stoppedSession")
        assertFalse("no prompt text anywhere", hasNode(desc("prompt text")))
        shoot("activity")
        checkpoint("activity")

        // --- AC-09.1: Stop asks first; the watched session stops only after the confirm ---
        tab("Spaces")
        waitFor("the running session's Stop") { hasNode(button("Stop…") and isEnabled()) }
        rule.onNode(button("Stop…") and isEnabled()).performScrollTo().performClick()
        waitFor("the stop dialog") { hasNode(text("Stop session $session?")) }
        cancelIsFocused()
        shoot("stop-dialog")
        checkpoint("stop-asked")
        rule.onNode(button("Cancel")).performClick()
        checkpoint("stop-cancelled")
        rule.onNode(button("Stop…") and isEnabled()).performScrollTo().performClick()
        rule.onNode(button("Stop session")).performClick()
        waitFor("herdr's answer", 60_000) { hasNode(text("herdr stopped session $session", substring = true)) }
        shoot("stopped")
        checkpoint("stopped")
        checkpoint("done")
    }
}
