package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 08 on a device, through the whole app over SSH (`paddock-harness/run-answers-e2e.sh`). The script prepares the host: a fake agent
 * that logs every key and submission outside its pane (so "no key was sent" is a count), a throwaway sshd, the link-cut proxy, and
 * the real hook script run the way Claude Code runs it (a PermissionRequest on stdin, herdr's environment for the pane). It checks
 * at each checkpoint what only the host can see: the request files, what the hook printed, the journal on the phone.
 *
 * One process: Add machine; Settings > Guarded answers installs the two pinned scripts after a confirmation; then, for a blocked
 * agent, a request is answered Yes (bound to that request), another No, a newer request is offered but never swapped in under the
 * user, a cut input and an expired request leave both buttons off, and an answer whose reply is lost is shown as unknown and settled
 * by reading the host's files, with nothing sent again.
 */
class AnswersFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2234"
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "answers-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)

    private fun checkpoint(marker: String, timeoutMs: Long = 240_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun nodeText(m: androidx.compose.ui.test.SemanticsMatcher): String =
        rule.onAllNodes(m).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text } ?: ""

    private fun enabled(label: String) = hasNode(hasText(label) and isEnabled() and hasClickAction())
    private fun present(label: String) = hasNode(hasText(label) and hasClickAction())

    // ---- the steps --------------------------------------------------------------------------------------------------------

    private fun openAgentFromHome() {
        waitFor("the fake agent on Home, ready and live", 90_000) { hasNode(desc("Ready")) && hasNode(desc("live")) }
        rule.onAllNodes(desc("Ready") and hasClickAction()).onFirst().performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
    }

    /** What the answer controller holds and what agent status it sees, written to the log when a wait times out. */
    private fun dumpAnswers() {
        val graph = (ctx.applicationContext as io.github.tuthan.paddock.PaddockApp).graph
        val h = (graph.hostUi.view.value.phase as? io.github.tuthan.paddock.live.HostPhase.Monitoring)?.host
        val answers = h?.answers
        log("DUMP host=${h != null} answers=${answers != null} installed-agents=" + h?.reconciler?.installed?.value?.snapshot?.agents?.map { it.terminalId + "=" + it.agentStatus })
        answers?.views?.value?.forEach { (id, v) -> log("DUMP view[$id] error=${v.error} reading=${v.reading} shown=${v.shown?.requestId} listing.candidate=${v.listing?.candidate?.requestId} entries=${v.listing?.requests?.map { it.state.name + ":" + it.hookAlive }} answerability=${v.answerability?.let { it::class.simpleName }}") }
    }

    private fun waitEntry(timeoutMs: Long = 60_000) {
        try { rule.waitUntil(timeoutMs) { hasNode(hasTestTag("decision-entry")) } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { dumpAnswers(); shoot("timeout-entry"); throw AssertionError("timed out waiting for the request entry on the Output tab") }
    }

    private fun openSheetFromEntry(marker: String) {
        waitEntry()
        rule.onNode(hasTestTag("decision-entry")).performClick()
        waitFor("the sheet showing $marker") { hasNode(text("Permission request")) && nodeText(hasTestTag("tool-input")).contains(marker) }
    }

    private fun tapYes() { waitFor("Yes enabled", 20_000) { enabled("Yes") }; rule.onNode(hasText("Yes") and hasClickAction()).performClick() }
    private fun tapNo() { waitFor("No enabled", 20_000) { enabled("No") }; rule.onNode(hasText("No") and hasClickAction()).performClick() }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    @Test fun t1_answersThroughTheApp() {
        // --- Add machine through the real UI ---
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and androidx.compose.ui.test.hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(desc("Ready")) }
        if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
        openAgentFromHome()

        // --- Settings > Guarded answers: the hashes first, then the install, which asks ---
        back()
        waitFor("Home") { hasNode(desc("Settings", substring = false)) }
        rule.onNode(desc("Settings", substring = false)).performClick()
        waitFor("Settings") { hasNode(text("Keep prompt text")) }
        rule.onNode(desc("Guarded answers")).performScrollTo().performClick()
        waitFor("the guarded answers screen") { hasNode(text("Yes or No from the phone")) }
        waitFor("the host read: nothing installed", 30_000) { hasNode(text("Not installed on", substring = true)) }
        assertTrue("both hashes are shown before anything is installed", hasNode(desc("SHA-256 of paddock-decide.py")) && hasNode(desc("SHA-256 of paddock-claude-permission-hook.py")))
        shoot("setup-missing")
        checkpoint("setup-shown")
        rule.onNode(text("Install the scripts…")).performScrollTo().performClick()
        waitFor("the confirmation") { hasNode(text("Install the answer scripts on", substring = true)) }
        assertTrue("the dialog repeats both hashes", hasNode(desc("SHA-256 of the hook")) && hasNode(desc("SHA-256 of the writer")))
        checkpoint("install-asked")
        rule.onNode(text("Install") and hasClickAction()).performClick()
        waitFor("both scripts installed", 60_000) { rule.onAllNodes(text("Installed, and it is the pinned script.")).fetchSemanticsNodes().size >= 2 }
        waitFor("the registration text") { hasNode(text("\"PermissionRequest\"", substring = true)) }
        shoot("setup-installed")
        log("SETUP registration shown: " + nodeText(text("\"PermissionRequest\"", substring = true)).replace("\n", " "))
        checkpoint("installed")
        back(); back()
        openAgentFromHome()

        // --- Yes: the sheet shows the request whole, and the Yes is bound to that request ---
        checkpoint("ready-for-yes")
        openSheetFromEntry("e2e-one")
        val input = nodeText(hasTestTag("tool-input"))
        log("SHEET input: " + input.replace("\n", " | "))
        assertTrue("the whole input is on the sheet", input.contains("echo e2e-one") && input.contains("e2e description one"))
        assertTrue("the tool is named", hasNode(text("Bash")))
        // A request that has just appeared keeps Yes and No off for 1.5 s; the request itself is on screen at once.
        waitFor("Yes and No on once the request has been on screen for a moment", 20_000) { enabled("Yes") && enabled("No") }
        shoot("sheet-request")
        tapYes()
        waitFor("the answer recorded", 30_000) { hasNode(text("Yes written", substring = true)) }
        waitFor("what the host's files show", 30_000) { hasNode(text("Handed to Claude Code as Yes", substring = true)) }
        assertTrue("the desktop caveat is on screen", hasNode(text("kept the desktop's answer", substring = true)))
        assertFalse("both buttons are off after an answer", enabled("Yes") || enabled("No"))
        shoot("sheet-yes")
        checkpoint("yes-tapped")
        back()

        // --- No ---
        checkpoint("ready-for-no")
        openSheetFromEntry("e2e-two")
        tapNo()
        waitFor("the No recorded", 30_000) { hasNode(text("No written", substring = true)) }
        waitFor("what the host's files show", 30_000) { hasNode(text("Handed to Claude Code as No", substring = true)) }
        shoot("sheet-no")
        checkpoint("no-tapped")
        back()

        // --- a newer request of the same session expires the older one; the sheet moves to it, with Yes and No off for a moment ---
        checkpoint("ready-for-replace")
        openSheetFromEntry("e2e-three-old")
        waitFor("Yes on for the request shown", 20_000) { enabled("Yes") }
        checkpoint("old-shown")
        waitFor("the sheet moved to the new request", 40_000) { nodeText(hasTestTag("tool-input")).contains("e2e-three-new") }
        assertFalse("the old request is no longer shown", nodeText(hasTestTag("tool-input")).contains("e2e-three-old"))
        assertTrue("right after the swap Yes and No are off and the sheet says why", !enabled("Yes") && !enabled("No") && hasNode(text(io.github.tuthan.paddock.answers.DecisionPresenter.JUST_APPEARED)))
        shoot("sheet-replaced")
        tapYes()                               // waits for Yes to come on: the answer is bound to the new request's id
        waitFor("the answer for the new request", 30_000) { hasNode(text("Yes written", substring = true)) }
        checkpoint("replace-answered")
        back()

        // --- an input cut by the hook: shown, never answerable ---
        checkpoint("ready-for-cut")
        waitEntry()
        rule.onNode(hasTestTag("decision-entry")).performClick()
        waitFor("the cut input note", 40_000) { hasNode(text("The input was cut at", substring = true)) }
        waitFor("both buttons off with the reason", 20_000) { !enabled("Yes") && !enabled("No") && hasNode(text("The input is too large to show in full", substring = true)) }
        assertTrue("the way out is there", present("Answer in the terminal instead"))
        shoot("sheet-cut")
        checkpoint("cut-shown")
        back()

        // --- an expired request: both buttons off, the desktop's dialog is what is left ---
        checkpoint("ready-for-expiry")
        openSheetFromEntry("e2e-expiry")
        waitFor("the expiry", 60_000) { hasNode(text("Expired. Answer on the desktop.")) }
        waitFor("both buttons off", 20_000) { !enabled("Yes") && !enabled("No") }
        shoot("sheet-expired")
        checkpoint("expired-shown")
        back()

        // --- an answer whose reply is lost: unknown, never resent, settled by reading the host's files ---
        checkpoint("ready-for-cut-link")
        openSheetFromEntry("e2e-seven")
        checkpoint("arm-cut")
        tapYes()
        waitFor("the unknown outcome", 120_000) { hasNode(text("outcome unknown", substring = true)) }
        val unknown = nodeText(text("outcome unknown", substring = true)).lines().first { "outcome unknown" in it }
        log("UNKNOWN $unknown")
        assertTrue("it reads as the row words it: $unknown", Regex("""Yes sent \d\d:\d\d:\d\d · outcome unknown · re-read before sending again""").matches(unknown))
        assertFalse("no resend is offered", enabled("Yes") || enabled("No"))
        for (word in listOf("Resend", "Retry")) assertFalse("no $word offered", hasNode(text(word, substring = true)))
        shoot("sheet-unknown")
        checkpoint("unknown")
        // The link was thawed at the checkpoint; the app reconnects on its own. Reading the files before that fails with a sentence
        // and sends nothing, so the read is simply tried again until it can go through.
        var attempts = 0
        while (true) {
            if (present("Read the files")) rule.onNode(text("Read the files") and hasClickAction()).performClick()
            try { rule.waitUntil(15_000) { hasNode(text("Read from the host's files:", substring = true)) }; break }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { if (++attempts >= 10) { shoot("timeout-settle"); throw AssertionError("the host's files were never read") } }
        }
        val settled = nodeText(text("Read from the host's files:", substring = true)).lines().first { "Read from the host's files:" in it }
        log("SETTLED $settled")
        assertTrue("it says the hook took the answer and keeps the caveat: $settled", settled.contains("Handed to Claude Code as Yes") && settled.contains("kept the desktop's answer"))
        assertFalse("still nothing to answer", enabled("Yes") || enabled("No"))
        shoot("sheet-settled")
        checkpoint("settled")
        checkpoint("done", 60_000)
    }
}
