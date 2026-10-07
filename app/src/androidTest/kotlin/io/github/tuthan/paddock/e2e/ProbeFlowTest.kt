package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The chip probe on a device, through the whole app over SSH (`paddock-harness/run-probe-e2e.sh`). Two machines on one host, told apart by port: A through
 * the proxy, B straight to the sshd. B is added last, so it is the watched one, and A's chip must say what the blocked fake agent in `paddock-test` is doing
 * without A ever being watched. The script flips the agent between blocked and idle and, at each checkpoint, counts what only the host can see: the
 * sshd's accepted logins (the probe's looks, and none while the app is in the background) and the keys and submissions the agent logged (none: a look reads).
 */
class ProbeFlowTest {
    // The activity is launched by hand and closed leniently: the script sends the app to the background and brings it back, after which the scenario can no longer
    // find its activity to close (an NPE in ActivityScenario.close, after every assertion has passed).
    @get:Rule val rule: ComposeTestRule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() { runCatching { scenario?.close() } }

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val portA = args.getString("portA") ?: "2234"
    private val portB = args.getString("portB") ?: "2233"
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "probe-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)

    private fun checkpoint(marker: String, timeoutMs: Long = 300_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    /** For a checkpoint the app is sent away during: nothing here touches Compose, which has no activity to idle on while the app is stopped. */
    private fun quietCheckpoint(marker: String, timeoutMs: Long = 300_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        val end = System.currentTimeMillis() + timeoutMs
        while (!go.exists()) { if (System.currentTimeMillis() > end) throw AssertionError("the script did not release checkpoint $marker"); Thread.sleep(250) }
        go.delete()
    }

    private fun addMachine(port: String) {
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and androidx.compose.ui.test.hasSetTextAction()).performScrollTo().performTextInput(session)
        rule.onNodeWithText("Connect").performClick()
        waitFor("the first-trust dialog for $port") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(desc("watching, live")) }
        if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
        waitFor("Home, watching and live", 90_000) { hasNode(desc("watching, live")) }
    }

    /** What TalkBack reads for each chip of a machine that is not watched, into the log the script keeps ("CHIP <stage>: ..."). */
    private fun logChips(stage: String) {
        val names = rule.onAllNodes(desc("not watched")).fetchSemanticsNodes().map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString() }
        log("CHIP $stage: $names")
    }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    @Test fun t1_theChipSaysWhatTheOtherMachineNeeds() {
        // --- A, then B through "Add another machine": B is the watched one, A is only a chip ---
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        addMachine(portA)
        rule.onNode(desc("watching, live") and hasClickAction()).performClick()
        waitFor("the Machines screen") { hasNode(text("Add another machine")) }
        rule.onNode(text("Add another machine")).performClick()
        waitFor("the Add machine screen again") { hasNode(text("Host or IP address")) }
        addMachine(portB)
        assertTrue("with two machines Home draws a chip for the one not watched", hasNode(desc("not watched")))

        // --- the count of the machine that is not watched, from the blocked agent (the script set it before the app started) ---
        waitFor("A's chip counting the blocked agent", 90_000) { hasNode(desc("not watched, 1 agent needs you")) }
        logChips("blocked")
        shoot("blocked")
        assertTrue("the visible words say it too", hasNode(text("1 needs you", substring = true)))
        checkpoint("counted-blocked")

        // --- the agent goes idle: the next look says nothing needs you ---
        waitFor("A's chip clear", 90_000) { hasNode(desc("not watched, no agent needs you")) }
        assertFalse("the old count is gone", hasNode(desc("1 agent needs you")))
        logChips("clear")
        shoot("clear")
        checkpoint("counted-clear")

        // --- the app in the background: no look is made (the script sends it away, counts the host's logins and brings it back); a fresh look follows the return ---
        quietCheckpoint("background-window")
        waitFor("A's chip read again after the return", 90_000) { hasNode(desc("not watched, no agent needs you, read just now")) }
        logChips("after the return")
        checkpoint("foregrounded")

        // --- the count follows the agent back to blocked ---
        waitFor("A's chip counting the blocked agent again", 90_000) { hasNode(desc("not watched, 1 agent needs you")) }
        checkpoint("blocked-again")

        // --- a tap on the chip watches that machine (a source build holds every capability); the other one becomes the chip ---
        rule.onNode(desc("not watched, 1 agent needs you") and hasClickAction()).performClick()
        waitFor("A watched, live", 90_000) { hasNode(desc("watching, live")) && hasNode(desc("not watched")) }
        waitFor("B's chip carrying a count", 90_000) { hasNode(desc("not watched, 1 agent needs you")) }
        logChips("switched")
        shoot("switched")
        checkpoint("switched")
    }
}
