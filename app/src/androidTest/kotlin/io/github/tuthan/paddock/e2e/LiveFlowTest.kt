package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The whole product on one device, driven through its UI against a throwaway sshd and the disposable `paddock-test`
 * herdr session: Add machine, first-trust fingerprint, relay install consent, a live home from real agents, Output,
 * Activity, Settings. `tools/run-live-e2e.sh` sets the host up and passes `hostFp`, `user`, `port` and `home`.
 */
class LiveFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2233"
    private val home by lazy { args.getString("home") ?: error("home argument missing") }
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"

    private fun shoot(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "e2e-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)); throw AssertionError("timed out waiting for $what") }
    }
    /** Lets the script take a screenshot from outside the process (a shell capture honours FLAG_SECURE; an in-process one does not). */
    private fun hold(marker: String) {
        val ms = args.getString("holdMs")?.toLongOrNull() ?: 0L
        if (ms <= 0) return
        android.util.Log.i("E2E", "HOLD $marker")
        Thread.sleep(ms)
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)

    /** Phase 1, run by the script before the flow: create the app's own phone key and write its public line for the host to authorize. */
    @Test fun t0_exportAppKey() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val line = io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e")
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(line + "\n")
    }

    @Test fun addAMachineTrustItInstallTheRelayWatchAgentsReadOutputAndSeeActivity() {
        val started = System.currentTimeMillis()

        // --- Add machine (first run: no machines, so the app opens it itself) ---
        waitFor("the Add machine screen") { hasNode(text("Add a machine")) }
        rule.onNodeWithText("Host or IP address").performTextInput(host)
        rule.onNodeWithText("User").performTextInput(user)
        rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
        rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
        shoot("add-machine-filled")
        rule.onNodeWithText("Connect").performClick()

        // --- First trust: the fingerprint on the phone is the one the host's key file has ---
        waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
        rule.onNodeWithText(hostFp).assertIsDisplayed()
        rule.onNodeWithText("ED25519").assertIsDisplayed()
        rule.onNodeWithText("Trust and connect").performClick()

        // --- Relay: asked before anything is written, then installed under the sshd's isolated HOME ---
        waitFor("the relay prompt") { hasNode(text("Install the relay on $host?")) }
        rule.onNodeWithText("$home/.local/share/paddock/paddock-relay.py").assertIsDisplayed()
        shoot("relay-prompt")
        rule.onNodeWithText("Install the relay").performClick()

        // --- Home: real agents, state as words, a live chip ---
        waitFor("the home with agents") { hasNode(hasContentDescription("Blocked", substring = true)) && hasNode(hasContentDescription("Working", substring = true)) }
        waitFor("live status") { hasNode(hasContentDescription("live", substring = true)) }
        val toHome = System.currentTimeMillis() - started
        shoot("home-live")

        // --- Output: opens from a row, shows the pane text on the slab, and the window is protected ---
        rule.onNode(hasContentDescription("Blocked", substring = true)).performClick()
        waitFor("terminal output") { hasNode(hasContentDescription("Terminal output")) }
        waitFor("some pane text") { hasNode(text(user, substring = true)) || hasNode(text("paddock-test", substring = true)) }
        shoot("output")
        hold("OUTPUT")
        val secure = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertTrue("Output is FLAG_SECURE by default", secure)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("back on home") { hasNode(text("Activity")) }
        rule.waitForIdle()
        hold("HOME")
        // The blocked agent's captured prompt is on Home now, so Home is protected too, and it names its Review action.
        waitFor("the blocked prompt on Home") { hasNode(text("Review prompt")) }
        shoot("home-prompt")
        val secureOnHomeWithPrompt = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertTrue("Home is FLAG_SECURE while a captured prompt is on it", secureOnHomeWithPrompt)

        // --- Activity: the phone's own record of what it saw ---
        rule.onNodeWithText("Activity").performClick()
        waitFor("an activity entry") { hasNode(text("Connected", substring = true)) || hasNode(text("→", substring = true)) || hasNode(text("appeared", substring = true)) }
        shoot("activity")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        // --- Settings: the gear on Home ---
        waitFor("home again") { hasNode(hasContentDescription("Settings")) }
        rule.onNode(hasContentDescription("Settings")).performClick()
        waitFor("settings") { hasNode(text("Protect sensitive screens")) }
        shoot("settings")
        val notSecureOnSettings = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0
        assertTrue("a screen with no agent text clears the flag", notSecureOnSettings)
        android.util.Log.i("E2E", "first launch to populated home: $toHome ms (emulator, informational)")
        assertEquals(true, toHome > 0)
    }

    /**
     * A second run on cleared app data: import a real (throwaway) private key by pasting it, come back to the form with
     * what was typed still there, and sign in with that key instead of the phone's. The script generated the key and
     * authorized its public half on the throwaway sshd; the key text is read from the app's files dir and deleted after.
     */
    @Test fun importAKeyThenConnectWithIt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile = File(ctx.getExternalFilesDir(null), "e2e-import-key")
        val pem = keyFile.readText()
        try {
            waitFor("the Add machine screen") { hasNode(text("Add a machine")) }
            rule.onNodeWithText("Host or IP address").performTextInput(host)
            rule.onNodeWithText("User").performTextInput(user)
            rule.onNode(hasText("Port") and hasSetTextAction()).performTextReplacement(port)
            rule.onNode(hasText("herdr session (optional)") and hasSetTextAction()).performScrollTo().performTextInput(session)
            rule.onNodeWithText("An imported key").performScrollTo().performClick()
            rule.onNodeWithText("Import a private key").performScrollTo().performClick()

            waitFor("the import screen") { hasNode(text("Or paste the key")) }
            assertTrue("the import screen is FLAG_SECURE by default", rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            rule.onNodeWithText("Or paste the key").performTextInput(pem)
            shoot("import-key")
            rule.onNodeWithText("Import key").performClick()

            waitFor("back on the form with the key stored") { hasNode(hasContentDescription("Imported key: ssh-ed25519", substring = true)) }
            rule.onNodeWithText(host).assertIsDisplayed()
            assertTrue("the key text is nowhere on the form", rule.onAllNodes(hasText("PRIVATE KEY", substring = true)).fetchSemanticsNodes().isEmpty())
            shoot("import-key-stored")
            rule.onNodeWithText("Connect").performClick()

            waitFor("the first-trust dialog") { hasNode(text("New host: $host:$port")) }
            rule.onNodeWithText("Trust and connect").performClick()
            waitFor("the relay prompt or the home") { hasNode(text("Install the relay on $host?")) || hasNode(hasContentDescription("live", substring = true)) }
            if (hasNode(text("Install the relay on $host?"))) rule.onNodeWithText("Install the relay").performClick()
            waitFor("the home with agents, signed in with the imported key") { hasNode(hasContentDescription("Blocked", substring = true)) && hasNode(hasContentDescription("live", substring = true)) }
            shoot("import-key-home")
        } finally { keyFile.delete() }
    }
}
