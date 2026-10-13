package io.github.tuthan.paddock.e2e

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.PaddockApp
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProCopy
import io.github.tuthan.paddock.demo.Demo
import io.github.tuthan.paddock.demo.DemoCopy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The demo machine through the whole app (Phase 16, AC-16.5): from Welcome's Try a demo to Home with its herd, a blocked agent that appears on the
 * clock, Output, a prompt that is accepted, the places that say "Not in the demo", and Remove, after which nothing of it is left. It needs no machine
 * and no network: the demo answers from memory. Screenshots of the screens go to the app's external files (`screens/demo-*.png`), which is where the
 * listing's screenshots come from (docs/demo.md); the harness's `check-demo.sh` runs this class with the socket sampler running.
 */
class DemoFlowTest {
    @get:Rule val rule: AndroidComposeTestRule<*, MainActivity> = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (ctx.applicationContext as PaddockApp).graph
    private var watchedBefore: String? = null
    private var chosenBefore: String? = null

    @Before fun remember() { watchedBefore = graph.machineBook.profile.value?.id; chosenBefore = graph.prefs.value.chosenProfileId }

    @After fun restore() = runBlocking {
        graph.machineBook.remove(Demo.PROFILE_ID)
        chosenBefore?.takeIf { id -> graph.machineBook.machines.value.any { it.id == id } }?.let { graph.machineBook.watch(it, chosen = true) }
        watchedBefore?.takeIf { id -> graph.machineBook.machines.value.any { it.id == id } }?.let { graph.machineBook.watch(it) }
        Unit
    }

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "demo-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)) }; throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = true) = hasText(t, substring = substring, ignoreCase = true)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun enterDemo() {
        rule.waitUntil(15_000) { hasNode(text("Try a demo")) || hasNode(text("Refactor order validation")) || hasNode(desc("Settings", substring = false)) }
        if (hasNode(text("Try a demo"))) rule.onNodeWithText("Try a demo").performScrollTo().performClick() else graph.startDemo()
        waitFor("Home with the demo's herd") { hasNode(text("Refactor order validation")) }
    }

    @Test fun theDemoRunsFromHomeToAPromptAndLeavesNothingBehind() {
        enterDemo()
        assertTrue("the demo is a machine, and in memory only", graph.demo.active)
        waitFor("the banner that says what it is") { hasNode(text(DemoCopy.BANNER)) }
        shoot("home-start")
        // Done and Working agents are rows; the idle ones are one summary line (Home's own rule), so they are looked for after they are opened.
        for (title in listOf("Invoice status migration", "Refactor order validation", "2 agents ready")) assertTrue("nothing titled $title on Home", hasNode(text(title)))

        // The clock: the blocked agent appears twenty seconds in, and the in-app alert follows the herd.
        waitFor("an agent that needs an answer", 60_000) { hasNode(text("Needs you")) }
        waitFor("the blocked agent's row") { hasNode(text("Write the install guide")) }
        shoot("home-blocked")
        // The idle agent is below the fold (Ready is the last section); it is one summary line, and tapping that opens the agent.
        rule.onNode(hasScrollAction()).performScrollToNode(text("ready —") or text("Keep the test suite green"))
        shoot("home-ready")

        // Output of an idle agent, and a prompt that the demo accepts.
        rule.onAllNodes((text("ready —") or text("Keep the test suite green")) and hasClickAction()).onFirst().performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
        waitFor("the agent's screen") { hasNode(text("All 31 tests pass")) }
        shoot("output")
        rule.onNode(desc("Write a prompt for opencode")).performClick()
        waitFor("the composer") { hasNode(hasSetTextAction()) }
        // Files are not in the demo: the attach control says so in place.
        assertTrue("the composer says files are not in the demo", hasNode(text(DemoCopy.FILES)) || hasNode(desc("Attach", substring = true)))
        rule.onNode(hasSetTextAction()).performTextInput("Add a test for the empty basket")
        waitFor("Send is on") { hasNode(hasText("Send prompt") and isEnabled()) }
        shoot("composer")
        rule.onNode(hasText("Send prompt") and isEnabled()).performClick()
        back()
        waitFor("the demo's answer on the agent's screen", 40_000) { hasNode(text("nothing ran")) }
        shoot("output-after-prompt")
        back()

        // Remove the demo from the machine's page: Welcome (or the next saved machine) returns and nothing of the demo is left.
        val others = graph.machineBook.machines.value.filter { it.id != Demo.PROFILE_ID }
        runBlocking { graph.machineBook.remove(Demo.PROFILE_ID) }
        assertFalse("the demo has ended", graph.demo.active)
        assertTrue(runBlocking { graph.profiles.list() }.none { Demo.isDemo(it) })
        assertTrue("its rows in the journal are gone", graph.journal.records.value.none { it.host == Demo.PROFILE_ID })
        assertTrue("and what the ledger saw", graph.ledger.observations().none { it.host == Demo.PROFILE_ID })
        assertFalse("never written to the profile file", File(ctx.filesDir, "host-profiles.json").takeIf { it.exists() }?.readText().orEmpty().contains(Demo.HOST))
        if (others.isEmpty()) waitFor("Welcome again") { hasNode(text("Try a demo")) }
        assertEquals(others.map { it.id }.toSet(), graph.machineBook.machines.value.map { it.id }.toSet())
    }

    @Test fun theMachinePageTheTerminalAndSpacesSayWhatTheDemoDoesNotDo() {
        enterDemo()
        // Home's chip opens Machines, a card opens the machine's page.
        rule.onAllNodes(text("Demo machine", substring = false) and hasClickAction()).onFirst().performClick()
        waitFor("Machines") { hasNode(text("Paddock watches one machine at a time")) }
        shoot("machines")
        rule.onAllNodes(text("Demo machine", substring = false) and hasClickAction()).onFirst().performClick()
        waitFor("the machine's page") { hasNode(text("Locked-phone alerts")) }
        shoot("machine-page")
        // Wake and the drop folder say so in place of their controls.
        rule.onNode(text(DemoCopy.WAKE)).performScrollTo()
        assertTrue(hasNode(text(DemoCopy.DROP_FOLDER)))
        // Alerts and guarded answers are not in the demo, and no page of setup opens: one sentence says so.
        rule.onNode(text("Set up or check…")).performScrollTo().performClick()
        waitFor("the alert setup's refusal") { hasNode(text(DemoCopy.ALERTS)) }
        rule.onNode(desc("Guarded answers, ")).performScrollTo().performClick()
        waitFor("the guarded answers' refusal") { hasNode(text(DemoCopy.GUARDED_ANSWERS)) }
        rule.onAllNodes(text("Change address", substring = true)).assertCountEquals(0)
        back(); back()

        // The terminal observes the agent's screen and gives no control.
        rule.onAllNodes(text("Refactor order validation") and hasClickAction()).onFirst().performClick()
        waitFor("the Output tab") { hasNode(desc("Terminal output")) }
        rule.onNodeWithText("Terminal").performClick()
        waitFor("the terminal, read-only") { hasNode(text("Request control")) }
        shoot("terminal")
        rule.onNodeWithText("Request control").performClick()
        waitFor("the terminal's refusal") { hasNode(text(DemoCopy.CONTROL)) }
        back()

        // Spaces: the demo's one session, and Start an agent says it is not in the demo (behind the Pro gate where the build has one).
        rule.onAllNodes(text("Spaces", substring = false)).onFirst().performClick()
        waitFor("Spaces") { hasNode(text("demo")) }
        shoot("spaces")
        // In the build that sells Pro the gate is the real gate (docs/demo.md): Stop asks for Pro first, and nothing is sent to the demo's herdr until it is bought.
        if (Distribution.SELLS_PRO && graph.proService.locked(ProCapabilities.MANAGE_SESSIONS)) {
            rule.onAllNodes(text("Stop…") and hasClickAction()).onFirst().performScrollTo().performClick()
            waitFor("the Pro gate") { hasNode(text(ProCopy.gateTitle(ProCapabilities.MANAGE_SESSIONS.id))) }
            shoot("pro-gate")
            // The gate's state belongs to the app, not to the activity: left open, it would cover the next test's Home.
            rule.onNodeWithText("Not now").performClick()
            waitFor("the gate to close") { !hasNode(text(ProCopy.gateTitle(ProCapabilities.MANAGE_SESSIONS.id))) }
        }
    }
}
