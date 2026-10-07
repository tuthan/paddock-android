package io.github.tuthan.paddock.e2e

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.MainActivity
import io.github.tuthan.paddock.alerts.AlertHint
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.alerts.DeepLink
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.PaddockApp
import io.github.tuthan.paddock.notify.AndroidAlertNotifier
import io.github.tuthan.paddock.notify.FakeDistributor
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 07 on a device (`paddock-harness/run-alerts-e2e.sh`): the stopgap mode, through the whole app over SSH. The real relay posts to a
 * loopback ntfy stub; the script then does what the ntfy app does when its notification is tapped, a VIEW intent for the
 * message's `click` link, and this test says what the app did with it. The script prepares the host (a fake agent that logs every
 * key and submission outside the pane, a second pane, the link-cut proxy), checks what only the host can see at each
 * checkpoint, and releases the test with a file.
 *
 * Stage 1, one process: a live blocker opens its Output tab (Terminal for an agent the phone cannot answer); an agent that moved on is reported as changed; a pane that is
 * gone is "no longer observed"; a tap that arrives while the link is down shows the herd and nothing else until a fresh read
 * made after it, on the reconnected link, resolves it; a link naming another machine and a link that is not valid change
 * nothing. Stage 2, a new process started by the tap itself: the app resolves it once the herd is read. Stage 3, connector mode: the
 * address a (test) distributor gave the phone is sent to the host through the app's own confirmation, the relay posts to it, the
 * script hands the post to the phone as the push server would, and the notification the push raises (generic, one, the herd not in
 * front) opens the machine's herd with what a fresh read found.
 */
class AlertsFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private val args = InstrumentationRegistry.getArguments()
    private val hostFp by lazy { args.getString("hostFp") ?: error("hostFp argument missing") }
    private val user by lazy { args.getString("user") ?: error("user argument missing") }
    private val port = args.getString("port") ?: "2234"
    private val session = args.getString("session") ?: "paddock-test"
    private val host = "10.0.2.2"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * ActivityScenario stops following an activity whose intent is no longer the one it launched, and MainActivity replaces its
     * intent in onNewIntent by design (a tap on an alert while the app is open); it also loses its activity when the test sent the
     * app to the background and a notification brought it back. The activity does reach DESTROYED (logcat shows it); the scenario
     * just never sees it and fails in its own code ("never becomes requested state", "Current state was null"). A failure from
     * inside ActivityScenario's close is the harness losing track, not the app; anything else is rethrown.
     */
    @After fun closeScenario() {
        try { scenario?.close() }
        catch (e: Throwable) {
            if (e.stackTrace.none { it.className.startsWith("androidx.test.core.app.ActivityScenario") }) throw e
            log("SCENARIO close gave up: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    private fun log(msg: String) = android.util.Log.i("E2E", msg)

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
    }

    private fun shoot(name: String) {
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "alerts-$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        try { rule.waitUntil(ms, cond) } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { shoot("timeout-" + what.replace(Regex("[^a-z0-9]+"), "-").take(30)) }; throw AssertionError("timed out waiting for $what")
        }
    }

    private fun hasNode(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String, substring: Boolean = false) = hasText(t, substring = substring)
    private fun desc(d: String, substring: Boolean = true) = hasContentDescription(d, substring = substring)

    /** The notice bar's words. The banner is one node, so its text is matched whole or by its description. */
    private fun noticeShown(words: String) = hasNode(text(words, substring = true) or desc(words))

    private fun waitForNotice(words: String, ms: Long = 40_000) = waitFor("the notice \"$words\"", ms) { noticeShown(words) }

    /** The host chip reads "<machine>, watching, live · <age>" or "<machine>, watching, not live, <status>" (`MachineCopy.chipWatched`). */
    private fun live() = hasNode(desc(", watching, live ·"))
    private fun notLive() = hasNode(desc(", not live,"))

    /** The herd, live. A host that has not had the relay installed yet (a fresh home) asks first, as it does on first use. */
    private fun waitForLiveHerd(ms: Long = 90_000) {
        waitFor("the relay prompt or the herd, live", ms) { hasNode(text("Install the relay on $host?")) || live() }
        if (hasNode(text("Install the relay on $host?"))) {
            rule.onNodeWithText("Install the relay").performClick()
            waitFor("the herd, live", ms) { live() }
        }
    }

    private fun tabSelected(label: String) = hasNode(text(label) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

    private fun checkpoint(marker: String, timeoutMs: Long = 240_000) {
        log("AT $marker")
        val go = File(ctx.getExternalFilesDir(null), "go-$marker")
        try { rule.waitUntil(timeoutMs) { go.exists() } } catch (e: Throwable) { throw AssertionError("the script did not release checkpoint $marker") }
        go.delete()
    }

    private fun dismissNotice() {
        rule.onAllNodes(text("Dismiss")).onFirst().performClick()
        waitFor("the notice to go") { !hasNode(text("Dismiss")) }
    }

    private fun back() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    @Test fun t0_exportAppKey() {
        File(ctx.getExternalFilesDir(null), "app-phone.pub").writeText(io.github.tuthan.paddock.ssh.PhoneKey().publicLine("paddock-e2e") + "\n")
    }

    @Test fun t1_aTapOnAnAlertIsResolvedAgainstAFreshReadWhateverHappenedSinceItWasSent() {
        launch()
        // --- Add machine through the real UI ---
        waitFor("the Add machine screen") { rule.passWelcome(); hasNode(text("Add a machine")) }
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
        waitFor("the herd, live", 90_000) { hasNode(desc("Ready")) && live() }
        shoot("home")

        // --- AC-07.3 (a): a live blocker opens the agent, observing, and says so. The agent here is Claude Code, which this phone can answer (Pro unlocked in a
        // debug build, guarded answers wired), so it opens the Output tab, which carries the request entry; an agent it cannot answer opens the Terminal tab (LiveFlowTest) ---
        checkpoint("case-a")
        waitForNotice("Opened from an alert. This agent is still blocked.")
        waitFor("the Output tab, where the request entry is") { tabSelected("Output") }
        assertFalse("an answerable agent does not land on the observing Terminal tab", tabSelected("Terminal"))
        assertFalse("nothing here is in control of the terminal", hasNode(desc("Terminal: in control")))
        shoot("a-live-blocker")
        dismissNotice(); back()
        waitFor("Home") { hasNode(text("Activity")) }

        // --- (b): the agent moved on after the alert was sent ---
        checkpoint("case-b")
        waitForNotice("State changed since the alert: this agent is working now.")
        waitFor("the Output tab") { tabSelected("Output") }
        shoot("b-moved-on")
        dismissNotice(); back()
        waitFor("Home") { hasNode(text("Activity")) }

        // --- (c): the terminal was closed after the alert was sent ---
        checkpoint("case-c")
        waitForNotice("No longer observed: that agent is not on $host now.")
        assertTrue("a cleared terminal leaves the herd on screen", hasNode(text("Activity")))
        assertFalse("and opens no agent", tabSelected("Terminal") || tabSelected("Output"))
        shoot("c-cleared")
        dismissNotice()

        // --- (d): the tap arrives while the link is down, and the reconnect that follows starts a new epoch ---
        checkpoint("freeze")
        waitFor("the app to notice the dead link", 150_000) { notLive() }
        log("LINKDOWN the herd says not live")
        shoot("d-link-down")
        checkpoint("link-down")
        waitForNotice("Opening the alert. Paddock is reading the herd first.")
        assertFalse("the stale herd does not answer the alert", noticeShown("Opened from an alert"))
        assertFalse("and opens no agent from it", tabSelected("Terminal") || tabSelected("Output"))
        shoot("d-opening")
        checkpoint("thawed")
        waitForNotice("Opened from an alert. This agent is still blocked.", 60_000)
        waitFor("the Output tab, where the request entry is") { tabSelected("Output") }
        shoot("d-after-reconnect")
        dismissNotice(); back()
        waitFor("Home") { hasNode(text("Activity")) }

        // --- (e) a link for a machine this phone does not have, (f) a link that is not valid: a notice, no navigation ---
        checkpoint("case-e")
        waitForNotice("No longer observed: that alert named a machine this phone does not have.")
        assertFalse(tabSelected("Terminal") || tabSelected("Output"))
        shoot("e-unknown-machine")
        dismissNotice()
        checkpoint("case-f")
        waitForNotice("That alert link is not valid, so it was ignored.")
        assertFalse(tabSelected("Terminal") || tabSelected("Output"))
        shoot("f-invalid")
        dismissNotice()
        checkpoint("done")
    }

    @Test fun t2_aTapOnAnAlertStartsTheAppFromNothingAndItIsResolvedOnceTheHerdIsRead() {
        val link = File(ctx.getExternalFilesDir(null), "link.txt").readText().trim()
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(ctx.packageName, MainActivity::class.java.name))
        waitForNotice("Opened from an alert. This agent is still blocked.", 90_000)
        waitFor("the Output tab, where the request entry is") { tabSelected("Output") }
        assertFalse("no first-trust question after a restart", hasNode(text("Trust and connect")))
        shoot("g-cold-start")
        checkpoint("cold-start")
        // A recreation (rotation) carries the same intent again: the link was handled when the activity was first created, so it is not run twice.
        dismissNotice()
        scenario!!.recreate()
        rule.waitForIdle()
        Thread.sleep(3_000)
        assertFalse("a recreated activity does not replay the link", noticeShown("Opened from an alert") || noticeShown("Opening the alert"))
        checkpoint("recreated")
    }

    @Test fun t3_aPushFromTheRelayRaisesOneGenericNotificationThatOpensTheMachinesHerd() {
        val inst = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val graph = (ctx.applicationContext as PaddockApp).graph
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        val endpoint = args.getString("pushEndpoint") ?: error("pushEndpoint argument missing")
        launch()
        waitForLiveHerd()

        // The user's choice of distributor stands in for the tap on "Register with <distributor>" (the screen tests cover the list):
        // the test's distributor gives the address the script's stub serves, which is what the relay will post to.
        FakeDistributor.start(ctx); FakeDistributor.accept(endpoint)
        val profile = kotlinx.coroutines.runBlocking { graph.profiles.list().single() }
        kotlinx.coroutines.runBlocking { graph.push.register(profile.id, profile.name, ctx.packageName) }
        waitFor("the distributor's address") { graph.push.registrations.value.firstOrNull()?.endpoint == endpoint }

        // --- the address goes to the host only through the confirmation, and the screen says where it went ---
        rule.onNode(desc("Settings", substring = false)).performClick()
        waitFor("Settings") { hasNode(text("Keep prompt text")) }
        rule.onNode(desc("Locked-phone alerts")).performScrollTo().performClick()
        waitFor("the relay screen") { hasNode(text("Locked-phone alerts")) && !hasNode(text("Keep prompt text")) }
        waitFor("the address ready to send") { hasNode(text("It is not on $host yet.", substring = true)) }
        // The button is open once the relay screen has read the machine (inspect asks the host a few things over SSH).
        waitFor("the send button to open", 60_000) { hasNode(text("Send the address to $host…") and isEnabled()) }
        rule.onNodeWithText("Send the address to $host…").performScrollTo().performClick()
        waitFor("the confirmation") { hasNode(text("Send the address to $host?")) }
        shoot("h-push-confirm")
        rule.onNodeWithText("Send the address").performClick()
        waitFor("the address on the host") { hasNode(text("The address is on $host", substring = true)) }
        shoot("h-push-sent")
        checkpoint("push-sent")

        // --- the herd leaves the front: only then does a push raise a notification ---
        back(); back()
        waitFor("Home") { hasNode(text("Activity")) }
        inst.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        waitFor("the app out of the front", 15_000) { !graph.triggers.interactive.value }
        checkpoint("push-posted")
        val token = graph.push.registrations.value.single().token
        val body = File(ctx.getExternalFilesDir(null), "push-body.json").readBytes()
        FakeDistributor.push(ctx, token, body, id = "e2e-1")
        val end = System.currentTimeMillis() + 10_000
        var mine = nm.activeNotifications.filter { it.tag == AndroidAlertNotifier.TAG }
        while (mine.isEmpty() && System.currentTimeMillis() < end) { Thread.sleep(100); mine = nm.activeNotifications.filter { it.tag == AndroidAlertNotifier.TAG } }
        assertEquals("one notification for the push", 1, mine.size)
        val n = mine.single().notification
        log("PUSHNOTIFICATION ${n.extras.getString(Notification.EXTRA_TITLE)} | ${n.extras.getString(Notification.EXTRA_TEXT)}")
        assertEquals("Paddock: attention on $host", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("An agent needs your attention.", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("the notification's actions are Open and Review", listOf("Open", "Review"), n.actions.map { it.title.toString() })
        checkpoint("push-notified")

        // --- the tap: the herd of that machine, read fresh, and what that read found ---
        n.contentIntent.send()
        waitForNotice("needs you on $host", 60_000)
        assertFalse("a push opens no agent by itself", tabSelected("Terminal") || tabSelected("Output"))
        waitFor("the notification to clear itself") { nm.activeNotifications.none { it.tag == AndroidAlertNotifier.TAG } }
        shoot("h-push-tap")
        checkpoint("push-tapped")

        // --- unregistering from the screen takes the address off the host too ---
        dismissNotice()
        rule.onNode(desc("Settings", substring = false)).performClick()
        waitFor("Settings") { hasNode(text("Keep prompt text")) }
        rule.onNode(desc("Locked-phone alerts")).performScrollTo().performClick()
        waitFor("the relay screen") { hasNode(text("Locked-phone alerts")) && !hasNode(text("Keep prompt text")) }
        rule.onNodeWithText("Unregister").performScrollTo().performClick()
        waitFor("the registration to go") { graph.push.registrations.value.isEmpty() }
        checkpoint("push-removed")
        FakeDistributor.stop(ctx)
    }

    /**
     * How long a tap takes to become a verdict on a warm, live connection to a machine on the same host (loopback): from the VIEW
     * intent to the notice that says what the fresh read found. A phone on a real network adds its own round trips to this.
     */
    @Test fun t4_aTapOnALiveBlockerBecomesAVerdictInTheTimeRecorded() {
        val terminal = args.getString("terminal") ?: error("terminal argument missing")
        val profile = args.getString("profile") ?: error("profile argument missing")
        launch()
        waitForLiveHerd()
        val times = mutableListOf<Long>()
        repeat(10) { i ->
            val hint = AlertHint(TargetRef(HostProfileId(profile), session, terminal), "w1:p1", AlertState.Blocked, System.currentTimeMillis() / 1000, 9_000_000L + i)
            val link = DeepLink.build(hint)
            val t0 = android.os.SystemClock.elapsedRealtime()
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("the verdict", 30_000) { noticeShown("Opened from an alert. This agent is still blocked.") }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            times += ms
            log("TAPLATENCY $ms")
            dismissNotice(); back()
            waitFor("Home") { hasNode(text("Activity")) }
            Thread.sleep(1_000)
        }
        log("TAPLATENCY-DONE ${times.sorted()}")
    }
}
