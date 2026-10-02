package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import io.github.tuthan.paddock.ui.screens.ESC_ENTERS_MANUAL_NOTE
import io.github.tuthan.paddock.ui.screens.FOCUS_QUESTION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 06 on a device, through the whole app over SSH, in two stages with an app restart between them
 * (`tools/run-operations-e2e.sh`). The script prepares the host (a fake agent that logs every key and submission outside the
 * pane, a throwaway sshd, the link-cut proxy), checks what only the host can see at each checkpoint, and releases the test
 * with a file.
 *
 * Stage 1: Add machine; Manual input and an Esc; desktop focus, which asks the first time; a prompt that is accepted; the
 * phone's own Activity; Keep prompt text turned on; then a prompt sent into a link that dies right after the request has
 * been delivered (the answer is lost): the app must say the outcome is unknown, keep the text, offer a Re-read and no resend,
 * and send nothing by itself when the link comes back. Stage 2, in a new process: the journal's row is still there, a Re-read
 * frees it and says what it found about the text without judging it, a deliberate prompt then goes, focus no longer asks, the
 * unknown row in Activity shows it was re-read, the composer's Esc is the way into Manual input (the first tap turns the
 * mode on and sends nothing, the second sends one Esc), and Ctrl+C ends the fake agent.
 */
class OperationsFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2234"
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val unknownFile get() = File(ctx.getExternalFilesDir(null), "unknown-line.txt")

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun shoot(name: String, dialog: Boolean = false) {
        if (dialog && android.os.Build.VERSION.SDK_INT < 28) { log("screenshot $name skipped on API ${android.os.Build.VERSION.SDK_INT} (dialog)"); return }
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "ops-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)

    private fun checkpoint(marker: String, timeoutMs: Long = 180_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    private fun pumpSleep(ms: Long) { val end = System.currentTimeMillis() + ms; rule.waitUntil(ms + 5_000) { System.currentTimeMillis() >= end } }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun nodeText(m: androidx.compose.ui.test.SemanticsMatcher): String =
        rule.onAllNodes(m).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text } ?: ""

    private fun fieldText(): String =
        rule.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    // ---- navigation -------------------------------------------------------------------------------------------------------

    private fun openAgentFromHome() {
        waitFor("the fake agent on Home, ready and live", 90_000) { hasNode(desc("Ready")) && hasNode(desc("live")) }
        rule.onAllNodes(desc("Ready") and hasClickAction()).onFirst().performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
    }

    private fun hasClickAction() = androidx.compose.ui.test.hasClickAction()

    private fun openComposer(initial: String? = null) {
        rule.onNode(desc("Write a prompt for claude")).performClick()
        waitFor("the composer") { hasNode(hasSetTextAction()) }
        if (initial != null) rule.onNode(hasSetTextAction()).performTextInput(initial)
    }

    private fun sendableNow() = hasNode(hasText("Send prompt") and isEnabled())

    private fun enterManualInput() {
        rule.onNodeWithText("Manual input").performClick()
        waitFor("the keys open after the fresh read") { hasNode(hasText("Esc") and isEnabled()) }
    }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    // ---- stage 1 ---------------------------------------------------------------------------------------------------------

    @Test fun t1_keysFocusAndPromptsThenTheLinkDiesAfterTheWriteLeavingAnUnknownOutcome() {
        // --- Add machine through the real UI ---
        waitFor("the Add machine screen") { hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(desc("Ready")) }
        if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
        openAgentFromHome()
        shoot("output-manual-off")

        // --- AC-06.5: Esc exists only after Manual input is chosen, and goes as one recorded key ---
        assertFalse("no Esc before Manual input is chosen", hasNode(text("Esc")))
        enterManualInput()
        shoot("manual-on")
        rule.onNode(hasText("Esc")).performClick()
        waitFor("the Esc outcome") { hasNode(text("Esc sent", substring = true)) }
        checkpoint("esc-sent")

        // --- AC-06.6: the first desktop focus asks; agreeing focuses; the second tap does not ask ---
        rule.onNode(hasText("Focus on desktop")).performClick()
        waitFor("the confirmation") { hasNode(text(FOCUS_QUESTION)) }
        shoot("focus-confirm", dialog = true)
        checkpoint("focus-asked")
        rule.onNode(hasText("Focus on desktop") and hasAnyAncestor(isDialog())).performClick()
        waitFor("the focus outcome") { hasNode(text("The desktop now has this agent focused", substring = true)) }
        checkpoint("focused")
        rule.onNode(hasText("Focus on desktop") and !hasAnyAncestor(isDialog())).performClick()
        pumpSleep(1_500)
        assertFalse("the second focus does not ask again", hasNode(text(FOCUS_QUESTION)))
        checkpoint("focused-again")
        rule.onNode(hasText("Done")).performClick()

        // --- AC-06.1 and AC-06.7 on the device: a prompt lands once; the journal keeps only a hash while the setting is off ---
        openComposer("first prompt from the phone")
        waitFor("Send open once the composer has read the agent") { sendableNow() }
        shoot("composer-ready")
        rule.onNodeWithText("Send prompt").performClick()
        waitFor("the accepted prompt") { hasNode(text("accepted by herdr", substring = true)) }
        assertEquals("the text goes with the prompt: the field is empty again", "", fieldText())
        shoot("prompt-accepted")
        checkpoint("prompt-sent")
        // Five seconds without a state observation: the label, and Send stays closed (empty text) whatever the label says.
        waitFor("the no-progress label", 40_000) { hasNode(text("no progress observed", substring = true)) }
        log("PROGRESS label shown: ${nodeText(text("no progress observed", substring = true)).lines().first()}")
        shoot("no-progress")

        // --- the phone's own Activity lists what was done ---
        back(); back()
        waitFor("Home") { hasNode(text("Activity")) }
        rule.onNodeWithText("Activity").performClick()
        rule.onNodeWithText("From this phone").performClick()
        waitFor("the Esc row") { hasNode(desc("You sent Esc to")) }
        waitFor("the focus rows") { hasNode(desc("You focused")) }
        waitFor("the prompt row") { hasNode(desc("You prompted")) }
        assertFalse("no prompt text in Activity", hasNode(desc("first prompt from the phone")))
        shoot("activity")
        rule.onNodeWithText("Herd").performClick()

        // --- Keep prompt text on, so the later re-read can look for the text ---
        rule.onNode(desc("Settings", substring = false)).performClick()
        waitFor("Settings") { hasNode(text("Keep prompt text")) }
        rule.onNodeWithText("Keep prompt text").performScrollTo().performClick()
        waitFor("the toggle on") { rule.onAllNodes(hasText("Keep prompt text")).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.StateDescription) == "On" } }
        back()

        // --- AC-06.3: a prompt sent into a link that dies right after the request is delivered ---
        openAgentFromHome()
        openComposer("second prompt, cut after the write")
        // The draft outlives the composer: Edit snippets and Back must not lose what was typed.
        rule.onNode(hasText("Edit snippets", substring = true)).performScrollTo().performClick()
        waitFor("the snippet editor") { hasNode(text("Snippets")) && !hasNode(hasText("Send prompt")) }
        back()
        waitFor("the composer again") { hasNode(hasText("Send prompt")) }
        assertEquals("the draft survives a trip to Edit snippets", "second prompt, cut after the write", fieldText())
        waitFor("Send open") { sendableNow() }
        checkpoint("arm-cut")
        rule.onNodeWithText("Send prompt").performClick()
        waitFor("the unknown outcome", 120_000) { hasNode(text("outcome unknown · re-read before sending again", substring = true)) }
        val line = nodeText(text("outcome unknown · re-read before sending again", substring = true)).lines().first { "outcome unknown" in it }
        log("UNKNOWN $line")
        assertTrue("the line reads as the note words it: $line", Regex("""prompt sent \d\d:\d\d:\d\d · outcome unknown · re-read before sending again""").matches(line))
        assertEquals("the text is kept for the user", "second prompt, cut after the write", fieldText())
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        for (word in listOf("Resend", "Retry", "Try again")) assertFalse("no $word offered", hasNode(text(word, substring = true)))
        shoot("unknown")
        unknownFile.writeText(line)
        checkpoint("unknown")
        // The link is back (the script thawed it and waited); the app must still hold the row and have sent nothing.
        waitFor("the composer to know the link is back and still refuse", 120_000) { hasNode(text("An earlier send's outcome is unknown. Re-read before sending again.")) }
        assertEquals("the text is still there", "second prompt, cut after the write", fieldText())
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        shoot("unknown-after-reconnect")
        back()
        waitFor("the persisted unknown row on the agent screen") { hasNode(text(line)) }
        shoot("unknown-row")
        checkpoint("restart")
    }

    // ---- stage 2: a new process ----------------------------------------------------------------------------------------------

    @Test fun t2_afterARestartTheRowIsStillThereAReReadFreesItAndFocusNoLongerAsks() {
        val line = unknownFile.readText()
        openAgentFromHome()
        waitFor("the journal's unknown row, written before the restart") { hasNode(text(line)) }
        assertTrue("it is the same row, the same time: $line", hasNode(text(line)))
        shoot("restart-unknown-row")

        // The composer refuses, with the reason and the way out.
        openComposer("a prompt that must wait")
        waitFor("the composer's refusal") { hasNode(text("An earlier send's outcome is unknown. Re-read before sending again.")) }
        rule.onNodeWithText("Send prompt").assertIsNotEnabled()
        back()

        // Re-read: the row is freed, and the report states facts without judging.
        rule.onNode(text("Re-read") and hasClickAction()).performClick()
        waitFor("the re-read report") { hasNode(text("herdr reports the agent as", substring = true)) }
        waitFor("what the text check found") { hasNode(text("The prompt's text appears in the last 200 lines of output.", substring = true)) }
        log("REREAD ${nodeText(text("herdr reports the agent as", substring = true)).lines().first()}")
        assertFalse("the unknown row is gone once re-read", hasNode(text(line)))
        for (word in listOf("was received", "was delivered", "succeeded", "failed")) assertFalse("the report never says '$word'", hasNode(text(word, substring = true)))
        shoot("reread-report")
        checkpoint("reread")

        // The user's own decision: a deliberate prompt goes now, once. The text typed while Send was off is still there (the draft
        // is kept when the composer is left), and the user replaces it.
        openComposer()
        assertEquals("the draft typed before the re-read is still in the field", "a prompt that must wait", fieldText())
        rule.onNode(hasSetTextAction()).performTextReplacement("third prompt, deliberate")
        waitFor("Send open after the re-read", 60_000) { sendableNow() }
        rule.onNodeWithText("Send prompt").performClick()
        waitFor("the accepted prompt") { hasNode(text("accepted by herdr", substring = true)) }
        checkpoint("third-sent")
        back()

        // AC-06.6: the confirmation was remembered across the restart.
        checkpoint("focus-away")
        rule.onNode(hasText("Focus on desktop")).performClick()
        pumpSleep(2_000)
        assertFalse("focus does not ask again after a restart", hasNode(text(FOCUS_QUESTION)))
        waitFor("the focus outcome") { hasNode(text("The desktop now has this agent focused", substring = true)) }
        checkpoint("focus-back")

        // Activity: the unknown row shows it was re-read, offers nothing more, and nothing else claims an outcome it does not have.
        back()
        waitFor("Home") { hasNode(text("Activity")) }
        rule.onNodeWithText("Activity").performClick()
        rule.onNodeWithText("From this phone").performClick()
        waitFor("the unknown row") { hasNode(desc("outcome unknown")) }
        waitFor("it says it was re-read") { hasNode(desc("· re-read ")) }
        assertFalse("no Re-read button once it is re-read", hasNode(text("Re-read") and hasClickAction()))
        shoot("activity-after-reread")
        rule.onNodeWithText("Herd").performClick()

        // The composer's Esc is the way into Manual input (this process has not turned it on): the first tap sends nothing, the
        // second sends one Esc.
        openAgentFromHome()
        openComposer()
        val escButton = hasText("Esc · Interrupt")
        waitFor("the composer's Esc offers Manual input") { hasNode(text(ESC_ENTERS_MANUAL_NOTE)) && hasNode(escButton and isEnabled()) }
        shoot("composer-esc-offer")
        rule.onNode(escButton).performClick()
        waitFor("the keys open after the fresh read") { hasNode(escButton and isEnabled()) && !hasNode(text(ESC_ENTERS_MANUAL_NOTE)) }
        pumpSleep(1_000)
        checkpoint("composer-esc-on")
        rule.onNode(escButton).performClick()
        checkpoint("composer-esc-sent")
        back()
        waitFor("the Esc outcome on the agent screen") { hasNode(text("Esc sent", substring = true)) }
        shoot("composer-esc-sent")

        // Ctrl+C last: the fake agent ends on it.
        if (!hasNode(hasText("Ctrl+C") and isEnabled())) enterManualInput()
        rule.onNode(hasText("Ctrl+C")).performClick()
        waitFor("the Ctrl+C outcome") { hasNode(text("Ctrl+C sent", substring = true)) }
        shoot("ctrl-c")
        checkpoint("ctrl-c")
        checkpoint("done")
    }
}
