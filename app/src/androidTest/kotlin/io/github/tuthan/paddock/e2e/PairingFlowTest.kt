package io.github.tuthan.paddock.e2e

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 11 slices 1 to 3 on a device (`paddock-harness/run-pairing-e2e.sh`): the phone's key is authorized on a throwaway sshd with only the
 * copied command, and a machine is added from a pairing link delivered as a real VIEW intent through the manifest's filter. The
 * script runs the copied command on the host between t1 and t2 and clears the app's data before t3.
 *
 * t1: create the phone's key, show and copy the command (it is logged for the script). t2: open the pairing link, check Add machine is
 * filled in, Connect, see the link's fingerprint named in the trust dialog, tap Trust, and reach the relay prompt, which only
 * appears after SSH signed in with the key the copied command authorized. t3: a link whose fingerprint is not the host's is refused
 * before any question, nothing is trusted and nothing is stored. t4: the filter does not admit a web page's browsable intent.
 */
class PairingFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun launchTheApp() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    /**
     * MainActivity replaces its intent in onNewIntent by design, and ActivityScenario stops following an activity whose intent is no longer
     * the one it launched (AlertsFlowTest documents the same): the activity does reach DESTROYED, the scenario never sees it. A failure from
     * inside the scenario's close is the harness losing track, not the app; anything else is rethrown.
     */
    @After fun closeScenario() {
        try { scenario?.close() }
        catch (e: Throwable) {
            if (e.stackTrace.none { it.className.startsWith("androidx.test.core.app.ActivityScenario") }) throw e
            log("SCENARIO close gave up: ${e.javaClass.simpleName}")
        }
    }

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2233"
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = android.util.Log.i("E2E", msg)
    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("timed out waiting for $what") }
    }

    /** What a message app or the system does with a tapped link: a VIEW intent for the package, resolved by the manifest's filter and delivered to the running single-task activity. */
    private fun openLink(link: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun link(fp: String, extra: String = "") = "paddock://pair?v=1&host=$host&port=$port&user=$user&fp=$fp&session=$session$extra"

    @Test fun t1_createTheKeyAndCopyTheCommand() {
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        if (hasNode(text("Create this phone's key"))) rule.onNodeWithText("Create this phone's key").performScrollTo().performClick()
        waitFor("the command") { hasNode(hasContentDescription("Command to run on the machine: ", substring = true)) }
        val said = rule.onNode(hasContentDescription("Command to run on the machine: ", substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.single()
        val command = said.removePrefix("Command to run on the machine: ")
        assertTrue("the command is the whole text, not a fragment: $command", command.startsWith("umask 077; mkdir -p ~/.ssh") && command.endsWith("}; }"))
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        waitFor("Copied") { hasNode(text("Copied")) }
        val clip = runCatching {
            var t: String? = null
            rule.runOnUiThread { t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }
            t
        }.getOrNull()
        log("CLIPBOARD matches the command: ${clip == command} (null when Android withheld the clip from the test process: $clip)")
        // One line for the script: it runs exactly this text on the host.
        File(ctx.getExternalFilesDir(null), "copied-command.txt").writeText(command)
        log("COMMAND-WRITTEN ${command.length}")
    }

    @Test fun t2_aPairingLinkFillsInTheMachineAndTheCopiedCommandIsAllTheHostNeeded() {
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        // A decoy first: the link may name several host keys and the machine presents one of them.
        val decoy = "SHA256:" + "A".repeat(43)
        openLink(link("$decoy,$hostFp"))
        waitFor("Add machine filled in from the link") { hasNode(text("Filled in from a pairing link", substring = true)) }
        rule.onNodeWithText(host).assertIsDisplayed()
        rule.onNodeWithText(user).assertIsDisplayed()
        assertTrue("the link's fingerprints are shown", hasNode(hasContentDescription("Host key fingerprints in the link: $decoy\n$hostFp")))
        assertFalse("opening a link connected to nothing", hasNode(text("New host: $host:$port")))
        rule.onNodeWithText("Connect").performClick()

        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("Same as the fingerprint in the pairing link.").assertIsDisplayed()
        Thread.sleep(1_000)
        assertFalse("nothing is trusted until the tap", hasNode(text("Install the relay on $host?")))
        rule.onNodeWithText("Trust and connect").performClick()

        // The relay question comes only after SSH signed in with this phone's key, authorized on the host by the copied command alone.
        waitFor("the relay prompt, after signing in", 60_000) { hasNode(text("Install the relay on $host?")) }
        log("PAIRED-AND-SIGNED-IN")
    }

    // ---- Phase 14 slice 6: the real `pair` popup on the host, listening on loopback (the emulator reaches it at 10.0.2.2) ---------------------------
    // The script (paddock-harness/run-pair-e2e.sh) runs pair.py in a pty and passes the link it printed (with its pair port and session
    // handle) as -e pairLink; it answers the popup's prompt itself: approve (t5), reject (t6) or never answer, with a short window (t7).

    private fun openPairLinkAndSend() {
        val pairLink = args.getString("pairLink") ?: error("pairLink argument missing")
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        openLink(pairLink)
        waitFor("Add machine filled in from the link") { hasNode(text("Filled in from a pairing link", substring = true)) }
        if (hasNode(text("Create this phone's key"))) rule.onNodeWithText("Create this phone's key").performScrollTo().performClick()
        // The key is made off the main thread: wait for its ways to appear. The traditional ones are all still here beside the new one.
        waitFor("the phone's key and its ways") { hasNode(text("Copy key only")) }
        rule.onNodeWithText("Copy key only").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send the key to $host").performScrollTo().performClick()
        waitFor("the page that waits for the desktop", 30_000) { hasNode(text("Waiting for approval on the desktop.")) }
        // The same fingerprint the popup shows, whole, with its first eight characters apart.
        val fp = rule.onNode(hasContentDescription("Fingerprint of this phone's key", substring = true)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
        log("PAIR-WAITING $fp")
    }

    @Test fun t5_sendTheKeyToThePopupApproveThereAndSignInWithoutTheCommand() {
        openPairLinkAndSend()
        // The script approves in the popup; the app connects by itself, and the first connection still asks about the host key.
        waitFor("the first-trust dialog", 90_000) { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText("Trust and connect").performClick()
        waitFor("the relay prompt, after signing in with the key the popup authorized", 60_000) { hasNode(text("Install the relay on $host?")) }
        log("PAIRED-BY-POPUP-AND-SIGNED-IN")
    }

    @Test fun t6_aRejectionAtTheDesktopIsSaidAndNothingConnects() {
        openPairLinkAndSend()
        waitFor("the rejection", 60_000) { hasNode(text("Rejected on the desktop.")) }
        assertFalse("a rejected key did not start a connection", hasNode(text("New host: $host:$port")))
        rule.onNodeWithText("Back").performClick()
        waitFor("the form again, with the command still there") { hasNode(hasContentDescription("Command to run on the machine", substring = true)) }
        log("REJECTED-SAID")
    }

    @Test fun t7_aWindowThatEndsWithoutAnAnswerIsSaidAndTheCommandStillWorks() {
        openPairLinkAndSend()
        waitFor("the expiry", 120_000) { hasNode(text("The time ran out.")) }
        rule.onNodeWithText("Back").performClick()
        waitFor("the form again") { hasNode(text("Send the key to $host")) }
        log("EXPIRED-SAID")
    }

    @Test fun t3_aLinkWhoseFingerprintIsNotTheHostsIsRefusedBeforeAnyQuestion() {
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
        val other = "SHA256:" + "B".repeat(43)
        openLink(link(other))
        waitFor("Add machine filled in from the link") { hasNode(text("Filled in from a pairing link", substring = true)) }
        if (hasNode(text("Create this phone's key"))) rule.onNodeWithText("Create this phone's key").performScrollTo().performClick()
        rule.onNodeWithText("Connect").performClick()

        waitFor("the refusal") { hasNode(text("$host:$port is not the machine in the pairing link")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText(other).assertIsDisplayed()
        assertFalse("no way to trust a key the link does not name", hasNode(text("Trust and connect")))
        assertFalse("no first-trust question was asked", hasNode(text("New host: $host:$port")))
        rule.onNodeWithText("Close").performClick()
        Thread.sleep(1_500)
        assertFalse("the refusal did not turn into a question", hasNode(text("New host: $host:$port")))
        assertFalse("nothing was signed in", hasNode(text("Install the relay on $host?")))
        val pins = File(ctx.filesDir, "host-keys.json")
        assertTrue("nothing was pinned: ${if (pins.exists()) pins.readText().take(200) else "(no file)"}", !pins.exists() || !pins.readText().contains("SHA256"))
        log("REFUSED-AND-NOT-PINNED")
    }

    @Test fun t4_aWebPagesBrowsableIntentCannotOpenThePairingLinkButATapCan() {
        val pm = ctx.packageManager
        val tap = Intent(Intent.ACTION_VIEW, Uri.parse(link("SHA256:" + "A".repeat(43)))).setPackage(ctx.packageName)
        assertNotNull("a tap in a message app resolves", tap.resolveActivity(pm))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(link("SHA256:" + "A".repeat(43)))).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(ctx.packageName)
        assertNull("a web page's browsable intent does not", web.resolveActivity(pm))
        val other = Intent(Intent.ACTION_VIEW, Uri.parse("paddock://pairx?v=1")).setPackage(ctx.packageName)
        assertNull("another host under the scheme is not ours to open", other.resolveActivity(pm))
        assertEquals("pair", Uri.parse(link("x")).host)
    }
}
