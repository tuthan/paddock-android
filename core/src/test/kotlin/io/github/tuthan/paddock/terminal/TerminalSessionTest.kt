package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.relay.FakeStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A host that speaks just enough: process-info and stty answers, and a stream per openStream the test drives by hand. */
private class TestHost(var stty: String? = "20 60\n") : SshSession {
    override val link = MutableStateFlow<LinkState>(LinkState.Up(0))
    val streams = CopyOnWriteArrayList<FakeStream>()
    val execs = CopyOnWriteArrayList<List<String>>()
    @Volatile var openFails = false
    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
        execs += argv
        return when {
            argv.contains("process-info") -> ExecResult(0, """{"id":"cli:pane:process_info","result":{"process_info":{"shell_pid":4242,"pane_id":"w1:p1"},"type":"pane_process_info"}}""".toByteArray(), ByteArray(0), false, false, Duration.ZERO)
            argv.firstOrNull() == "cat" -> ExecResult(0, "4242 (bash) S 1 4242 4242 34834 4242 4194304".toByteArray(), ByteArray(0), false, false, Duration.ZERO)
            argv.firstOrNull() == "stty" -> if (stty == null) ExecResult(1, ByteArray(0), "stty: failed".toByteArray(), false, false, Duration.ZERO) else ExecResult(0, stty!!.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
            else -> ExecResult(1, ByteArray(0), ByteArray(0), false, false, Duration.ZERO)
        }
    }
    override suspend fun openStream(argv: List<String>): StreamChannel {
        if (openFails) throw java.io.IOException("no channel")
        return FakeStream(argv).also { streams += it }
    }
    override suspend fun close() = Unit
    val observers get() = streams.filter { "observe" in it.argv }
    val controllers get() = streams.filter { it.argv.firstOrNull() == "python3" }
}

private class FakeHelper(var path: String? = "/home/u/.local/share/paddock/paddock-control.py", var replacing: Boolean = false) : ControlHelperPort {
    override val expectedSha256 = "ab".repeat(32)
    var installs = 0
    override suspend fun verifiedPath() = path
    override suspend fun destination() = "/home/u/.local/share/paddock/paddock-control.py"
    override suspend fun isReplacing() = replacing
    override suspend fun install() { installs++; path = destination() }
}

class TerminalSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private val herdr = "/usr/bin/herdr"
    private val fast = TerminalOptions(leaseSeconds = 15, pingEvery = 40.milliseconds, releaseWait = 200.milliseconds, resyncWait = 200.milliseconds, viewportSettle = 80.milliseconds)
    private val clock = Clock { System.currentTimeMillis() }

    private fun session(host: TestHost, helper: ControlHelperPort? = FakeHelper(), pane: () -> String? = { "w1:p1" }, engine: VtEngine = VtEngine()) =
        TerminalSession(scope, host, HerdrCli(herdr, "paddock-test"), pane, helper, clock, engine, fast)

    private fun frame(seq: Long, full: Boolean, text: String, w: Int = 60, h: Int = 20): String {
        val bytes = Base64.getEncoder().encodeToString(text.toByteArray())
        return """{"type":"terminal.frame","seq":$seq,"encoding":"ansi","full":$full,"width":$w,"height":$h,"bytes":"$bytes"}""" + "\n"
    }
    private fun closed(reason: String) = """{"type":"terminal.closed","reason":"$reason"}""" + "\n"
    private val ESC = "\u001B"
    private fun full(seq: Long = 1, text: String = "${ESC}[2J${ESC}[1;1Hhello") = frame(seq, true, text)

    private fun waitUntil(what: String, ms: Long = 10_000, cond: () -> Boolean) = runBlocking {
        withTimeout(ms) { while (!cond()) delay(5) }
    }.also { }

    private fun TerminalSession.mode() = view.value.mode
    private fun TerminalSession.waitMode(expected: TerminalMode) = waitUntil("mode $expected (now ${view.value.mode})") { view.value.mode == expected }
    private fun TerminalSession.row0() = view.value.grid?.rowText(0)

    /** Opens, lets the probe run and the observer open, and feeds its first full frame. */
    private fun observing(host: TestHost, s: TerminalSession): FakeStream {
        s.open(40, 25)
        waitUntil("observer opened") { host.observers.isNotEmpty() }
        val o = host.observers.last()
        o.feed(full())
        s.waitMode(TerminalMode.Observing)
        return o
    }

    /** Requests control and feeds the first frame of the controller. */
    private fun controlling(host: TestHost, s: TerminalSession): FakeStream {
        s.requestControl()
        waitUntil("controller opened") { host.controllers.isNotEmpty() }
        val c = host.controllers.last()
        c.feed(full())
        s.waitMode(TerminalMode.Controlling)
        return c
    }

    // --- observing ---

    @Test fun anObserverIsOpenedForTheTerminalsRealSizeNotThePhonesViewport() {
        val host = TestHost(stty = "20 60\n"); val s = session(host)
        s.open(40, 25)
        waitUntil("observer") { host.observers.isNotEmpty() }
        assertEquals(listOf(herdr, "--session", "paddock-test", "terminal", "session", "observe", "w1:p1", "--cols", "60", "--rows", "20"), host.observers.single().argv)
        assertEquals(PtySize(60, 20), s.view.value.pty)
        assertTrue(host.execs.any { "process-info" in it && "w1:p1" in it }); assertTrue(host.execs.any { it.take(3) == listOf("stty", "-F", "/dev/pts/18") })
    }

    @Test fun whenTheSizeCannotBeReadTheObserverFollowsThePhonesViewport() {
        val host = TestHost(stty = null); val s = session(host)
        s.open(40, 25)
        waitUntil("observer") { host.observers.isNotEmpty() }
        assertEquals(listOf("--cols", "40", "--rows", "25"), host.observers.single().argv.takeLast(4))
        assertNull(s.view.value.pty); assertTrue(s.view.value.controlWouldResizeDesktop)
    }

    @Test fun framesPaintTheGridAndTheModeIsObserving() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        assertEquals("hello", s.row0()); assertEquals(TerminalMode.Observing, s.mode())
        assertEquals(60, s.view.value.cols); assertEquals(20, s.view.value.rows); assertEquals(1, s.view.value.frames)
        o.feed(frame(2, false, "${ESC}[1;6H world"))
        waitUntil("second frame") { s.view.value.frames == 2L }
        assertEquals("hello world", s.row0())
        assertNull(s.view.value.notice)
    }

    @Test fun aFullFrameReplacesTheWholeScreen() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed(frame(2, true, "${ESC}[1;1Hnew", w = 60, h = 20))
        waitUntil("replaced") { s.row0() == "new" }
    }

    @Test fun anObserverCannotSendAnythingAndNothingIsWritten() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        assertFalse(s.send("ls\r".toByteArray())); assertFalse(s.resizeToFit(40, 10))
        s.release()
        Thread.sleep(150)
        assertEquals("", o.writtenText(), "an observer's stdin stays empty")
        assertEquals(1, host.streams.size)
    }

    @Test fun theViewportOnlyMattersWhileTheRealSizeIsUnknown() {
        val known = TestHost(stty = "20 60\n"); val a = session(known); observing(known, a)
        a.setViewport(30, 12); Thread.sleep(300)
        assertEquals(1, known.streams.size, "the observer is drawn for the terminal; a pinch changes only the font")

        val unknown = TestHost(stty = null); val b = session(unknown); b.open(40, 25)
        waitUntil("first observer") { unknown.observers.size == 1 }
        unknown.observers.last().feed(full(text = "x", seq = 1).replace("\"width\":60", "\"width\":40").replace("\"height\":20", "\"height\":25"))
        b.setViewport(30, 12); b.setViewport(31, 12)
        waitUntil("reopened for the new viewport") { unknown.observers.size == 2 }
        assertEquals(listOf("--cols", "31", "--rows", "12"), unknown.observers.last().argv.takeLast(4))
        assertTrue(unknown.observers.first().closed, "the old observer was closed first")
    }

    // --- control ---

    @Test fun requestingControlOpensTheHelperWithoutTakeoverAtTheTerminalsOwnSize() {
        val host = TestHost(stty = "20 60\n"); val s = session(host); val o = observing(host, s)
        val c = controlling(host, s)
        assertEquals(listOf("python3", "/home/u/.local/share/paddock/paddock-control.py", "15", herdr, "paddock-test", "w1:p1", "60", "20"), c.argv)
        assertTrue("takeover" !in c.argv)
        assertTrue(o.closed, "one terminal channel at a time: the observer was closed")
        assertEquals(TerminalMode.Controlling, s.mode()); assertNull(s.view.value.notice)
    }

    @Test fun whileControllingInputIsWrittenAsBase64BytesInBoundedRecords() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        assertTrue(s.send("ls\r".toByteArray()))
        waitUntil("input written") { c.writtenText().contains("terminal.input") }
        val line = c.writtenText().lines().first { "terminal.input" in it }
        assertEquals("""{"type":"terminal.input","bytes":"${Base64.getEncoder().encodeToString("ls\r".toByteArray())}"}""", line)
        val big = ByteArray(TerminalLimits.INPUT_BYTES * 2 + 10) { 'a'.code.toByte() }
        assertTrue(s.send(big))
        waitUntil("big input split") { c.writtenText().lines().count { "terminal.input" in it } == 4 }
        val total = c.writtenText().lines().filter { "terminal.input" in it }.drop(1).sumOf { Base64.getDecoder().decode(Regex("\"bytes\":\"([^\"]+)\"").find(it)!!.groupValues[1]).size }
        assertEquals(big.size, total)
    }

    @Test fun theLeasePingGoesOutRegularlyWhileControlling() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        waitUntil("three pings") { c.writtenText().lines().count { it == """{"type":"paddock.lease"}""" } >= 3 }
    }

    @Test fun anOwnerConflictIsExplainedAndTheSessionGoesBackToObserving() {
        val host = TestHost(); val s = session(host); val o1 = observing(host, s)
        s.requestControl()
        waitUntil("controller") { host.controllers.size == 1 }
        host.controllers.single().feed(closed("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"))
        waitUntil("conflict") { s.view.value.notice is TerminalNotice.Conflict }
        assertEquals("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover", (s.view.value.notice as TerminalNotice.Conflict).herdrSays)
        waitUntil("observer reopened") { host.observers.size == 2 }
        host.observers.last().feed(full()); s.waitMode(TerminalMode.Observing)
        assertIs<TerminalNotice.Conflict>(s.view.value.notice, "the new observer's first frame must not wipe the refusal")
        assertTrue(o1.closed); assertTrue(host.controllers.single().closed)
        assertEquals("hello", s.row0(), "the screen stayed readable through the refusal")
        assertTrue(host.controllers.none { "takeover" in it.argv }, "a refusal never retries with takeover by itself")
        assertFalse(s.send("x".toByteArray()))
    }

    @Test fun dismissingTheNoticeClearsItAndNothingElse() {
        val host = TestHost(); val s = session(host); observing(host, s)
        s.requestControl(); waitUntil("controller") { host.controllers.size == 1 }
        host.controllers.single().feed(closed("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"))
        waitUntil("conflict") { s.view.value.notice is TerminalNotice.Conflict }
        waitUntil("observer reopened") { host.observers.size == 2 }
        s.dismissNotice()
        waitUntil("cleared") { s.view.value.notice == null }
        assertEquals(1, host.controllers.size); assertFalse(s.send("x".toByteArray()))
    }

    @Test fun aNewRequestReplacesTheLastAnswerAndAGrantedOneLeavesNoNotice() {
        val host = TestHost(); val s = session(host); observing(host, s)
        s.requestControl(); waitUntil("controller") { host.controllers.size == 1 }
        host.controllers.last().feed(closed("terminal attach failed: terminal term_1 already has an attached client; retry with --takeover"))
        waitUntil("conflict") { s.view.value.notice is TerminalNotice.Conflict }
        waitUntil("observer reopened") { host.observers.size == 2 }
        host.observers.last().feed(full()); Thread.sleep(100)
        assertIs<TerminalNotice.Conflict>(s.view.value.notice)
        s.takeOver(); waitUntil("second controller") { host.controllers.size == 2 }
        waitUntil("the old answer is gone while the new request is open") { s.view.value.notice == null }
        host.controllers.last().feed(full()); s.waitMode(TerminalMode.Controlling)
        assertNull(s.view.value.notice)
    }

    @Test fun takeOverIsASeparateCommandThatAddsTheFlag() {
        val host = TestHost(); val s = session(host); observing(host, s)
        s.takeOver()
        waitUntil("takeover controller") { host.controllers.size == 1 }
        assertEquals("takeover", host.controllers.single().argv.last())
        host.controllers.single().feed(full()); s.waitMode(TerminalMode.Controlling)
    }

    @Test fun anotherRefusalIsReportedWithoutTheTakeOverOffer() {
        val host = TestHost(); val s = session(host); observing(host, s)
        s.requestControl(); waitUntil("controller") { host.controllers.size == 1 }
        host.controllers.single().feed(closed("terminal control failed: something else"))
        waitUntil("refused") { s.view.value.notice is TerminalNotice.ControlRefused }
        assertEquals("terminal control failed: something else", (s.view.value.notice as TerminalNotice.ControlRefused).herdrSays)
    }

    @Test fun releaseWritesTheReleaseAndReturnsToObservingWithoutANotice() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        s.release()
        waitUntil("release written") { c.writtenText().contains("terminal.release") }
        assertFalse(s.send("x".toByteArray()), "input stops the moment release is asked for")
        c.feed(closed("detached"))
        waitUntil("observer again") { host.observers.size == 2 }
        host.observers.last().feed(full()); s.waitMode(TerminalMode.Observing)
        assertNull(s.view.value.notice); assertTrue(c.closed)
    }

    @Test fun aReleaseThatIsNeverAnsweredIsClosedAnyway() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        s.release()
        waitUntil("gave up waiting") { c.closed }
        waitUntil("observer again") { host.observers.size == 2 }
    }

    @Test fun controlTakenByTheDesktopIsReportedAndNotRegained() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        c.feed(closed("terminal attach taken over"))
        waitUntil("ended") { s.view.value.notice is TerminalNotice.ControlEnded }
        assertEquals("terminal attach taken over", (s.view.value.notice as TerminalNotice.ControlEnded).herdrSays)
        waitUntil("observer") { host.observers.size == 2 }
        assertFalse(s.send("x".toByteArray())); assertEquals(1, host.controllers.size, "no automatic second request")
    }

    @Test fun aDroppedLinkUnderControlEndsTheSessionAndClosesTheChannel() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        host.link.value = LinkState.Down(io.github.tuthan.paddock.ports.DownReason.Timeout, 1)
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        assertEquals(TerminalMode.Ended(EndReason.LinkLost), s.mode())
        assertTrue(c.closed); assertFalse(s.send("x".toByteArray()))
        assertEquals("hello", s.row0(), "the last screen is still readable")
    }

    @Test fun suspendingReleasesAndForgetsAndResumingOnlyObserves() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        s.suspend()
        waitUntil("closed") { c.closed }
        waitUntil("forgotten") { s.view.value.grid == null && s.mode() == TerminalMode.Idle }
        assertFalse(s.send("x".toByteArray()))
        s.resume()
        waitUntil("observing again") { host.observers.size == 2 }
        assertEquals(1, host.controllers.size)
        host.observers.last().feed(full()); s.waitMode(TerminalMode.Observing)
    }

    @Test fun closingEndsEverythingAndClearsTheScreen() {
        val host = TestHost(); val engine = VtEngine(); val s = session(host, engine = engine); observing(host, s); val c = controlling(host, s)
        s.close()
        waitUntil("closed") { c.closed && s.view.value.grid == null }
        assertEquals("", engine.grid().rowText(0), "the engine was cleared")
        assertFalse(s.send("x".toByteArray()))
    }

    @Test fun resizeToFitSendsAResizeOnlyWhileControlling() {
        val host = TestHost(); val s = session(host); observing(host, s)
        assertFalse(s.resizeToFit(40, 12))
        val c = controlling(host, s)
        assertTrue(s.resizeToFit(40, 12)); assertFalse(s.resizeToFit(0, 12))
        waitUntil("resize") { c.writtenText().contains("terminal.resize") }
        assertTrue(c.writtenText().contains("""{"type":"terminal.resize","cols":40,"rows":12}"""))
        c.feed(frame(2, true, "${ESC}[1;1Hfit", w = 40, h = 12))
        waitUntil("the screen followed") { s.view.value.cols == 40 && s.row0() == "fit" }
    }

    // --- the helper ---

    @Test fun withoutTheVerifiedHelperControlIsNotAttemptedAndTheUserIsAsked() {
        val host = TestHost(); val helper = FakeHelper(path = null, replacing = true); val s = session(host, helper); observing(host, s)
        s.requestControl()
        waitUntil("asked") { s.view.value.notice is TerminalNotice.HelperNeeded }
        val n = s.view.value.notice as TerminalNotice.HelperNeeded
        assertEquals("/home/u/.local/share/paddock/paddock-control.py", n.destination); assertEquals("ab".repeat(32), n.sha256); assertTrue(n.replacing)
        Thread.sleep(100)
        assertTrue(host.controllers.isEmpty(), "nothing is started without the pinned helper"); assertEquals(TerminalMode.Observing, s.mode())
    }

    @Test fun installingTheHelperThenRequestsControl() {
        val host = TestHost(); val helper = FakeHelper(path = null); val s = session(host, helper); observing(host, s)
        s.requestControl(); waitUntil("asked") { s.view.value.notice is TerminalNotice.HelperNeeded }
        s.installHelper()
        waitUntil("controller") { host.controllers.size == 1 }
        assertEquals(1, helper.installs)
        host.controllers.single().feed(full()); s.waitMode(TerminalMode.Controlling)
    }

    @Test fun noHelperAtAllMeansControlIsUnavailable() {
        val host = TestHost(); val s = session(host, helper = null); observing(host, s)
        s.requestControl(); waitUntil("unavailable") { s.view.value.notice == TerminalNotice.HelperUnavailable }
        assertTrue(host.controllers.isEmpty())
    }

    // --- loss and repair ---

    @Test fun aGapOnTheObserverReconnectsForAFullFrame() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed(frame(2, false, "${ESC}[1;6H!")); waitUntil("2") { s.view.value.frames == 2L }
        o.feed(frame(5, false, "${ESC}[1;7H?"))
        waitUntil("reconnected") { host.observers.size == 2 }
        assertTrue(o.closed); waitUntil("the resynced notice") { s.view.value.notice == TerminalNotice.Resynced }
        host.observers.last().feed(full(seq = 1, text = "${ESC}[2J${ESC}[1;1Hfresh")); waitUntil("fresh") { s.row0() == "fresh" }
    }

    @Test fun aGapOnTheControllerAsksForAFullFrameByResizingToItsOwnSize() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        c.feed(frame(4, false, "${ESC}[1;1Hlost"))
        waitUntil("resync request") { c.writtenText().contains("""{"type":"terminal.resize","cols":60,"rows":20}""") }
        waitUntil("the resynced notice") { s.view.value.notice == TerminalNotice.Resynced }
        c.feed(frame(5, false, "${ESC}[1;1Hignored"))      // patches are dropped until the full frame
        c.feed(frame(6, true, "${ESC}[1;1Hrepaired"))
        waitUntil("repaired") { s.row0() == "repaired" }
        assertEquals(TerminalMode.Controlling, s.mode()); assertEquals(1, host.controllers.size)
    }

    @Test fun aControllerThatNeverAnswersTheResyncIsGivenBack() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        c.feed(frame(9, false, "x"))
        waitUntil("given back") { s.view.value.notice is TerminalNotice.ControlEnded }
        waitUntil("observer") { host.observers.size == 2 }; assertTrue(c.closed)
    }

    @Test fun aRecordTypeWeDoNotKnowIsSkippedAndNothingElseChanges() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed("""{"type":"terminal.graphics","x":1}""" + "\n")
        o.feed(frame(2, false, "${ESC}[1;6H again"))
        waitUntil("the next frame still applies") { s.row0() == "hello again" }
        assertEquals(1, host.observers.size); assertEquals(TerminalMode.Observing, s.mode())
    }

    @Test fun anUnreadableRecordOnTheObserverIsNeverShownAndTheStreamIsReopenedForAFullFrame() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed("not json\n")
        waitUntil("reopened") { host.observers.size == 2 }
        assertTrue(o.closed); assertEquals("hello", s.row0(), "what was on the screen stays until the new full frame")
        host.observers.last().feed(full(seq = 1, text = "${ESC}[2J${ESC}[1;1Hagain"))
        waitUntil("repainted") { s.row0() == "again" }
        assertEquals(TerminalMode.Observing, s.mode())
    }

    @Test fun anObserverThatKeepsProducingUnreadableRecordsIsGivenUp() {
        val host = TestHost(); val s = session(host); observing(host, s)
        repeat(12) { i -> host.observers.last().feed("garbage $i\n"); Thread.sleep(20) }
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        assertIs<EndReason.Failed>((s.mode() as TerminalMode.Ended).reason)
    }

    @Test fun aClosedPaneEndsTheSessionAsPaneGone() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed(closed("terminal attach ended: terminal term_1 not found"))
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        assertEquals(TerminalMode.Ended(EndReason.PaneGone), s.mode()); assertTrue(o.closed)
    }

    @Test fun aTerminalThatIsAlreadyGoneIsReportedWithoutOpeningAnything() {
        val host = TestHost(); val s = session(host, pane = { null }); s.open(40, 25)
        waitUntil("gone") { s.mode() == TerminalMode.Ended(EndReason.PaneGone) }
        assertTrue(host.streams.isEmpty())
    }

    @Test fun anotherReasonHerdrClosedTheStreamIsShownAsItsWords() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed(closed("server shutting down"))
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        assertEquals(TerminalMode.Ended(EndReason.Closed("server shutting down")), s.mode())
    }

    @Test fun aStreamThatEndsWithoutASayingIsReportedWithItsExitAndStderr() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.exit = 1; o.end()
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        val reason = (s.mode() as TerminalMode.Ended).reason
        assertIs<EndReason.Failed>(reason); assertTrue("exit 1" in reason.message, reason.message)
    }

    @Test fun aControllerStreamThatEndsReturnsToObservingWithANotice() {
        val host = TestHost(); val s = session(host); observing(host, s); val c = controlling(host, s)
        c.exit = 1; c.end()
        waitUntil("notice") { s.view.value.notice is TerminalNotice.ControlEnded }
        waitUntil("observer") { host.observers.size == 2 }
    }

    @Test fun aChannelThatCannotBeOpenedEndsTheSessionVisibly() {
        val host = TestHost().also { it.openFails = true }; val s = session(host); s.open(40, 25)
        waitUntil("ended") { s.mode() is TerminalMode.Ended }
        assertIs<EndReason.Failed>((s.mode() as TerminalMode.Ended).reason)
    }

    @Test fun aCommandAfterEndingChangesNothing() {
        val host = TestHost(); val s = session(host); val o = observing(host, s)
        o.feed(closed("terminal attach ended: terminal term_1 not found")); waitUntil("ended") { s.mode() is TerminalMode.Ended }
        s.requestControl(); s.release(); Thread.sleep(100)
        assertTrue(host.controllers.isEmpty()); assertTrue(s.mode() is TerminalMode.Ended)
    }

    @Test fun lateRecordsFromAClosedChannelAreIgnored() {
        val host = TestHost(); val s = session(host); val o = observing(host, s); val c = controlling(host, s)
        o.feed(frame(2, false, "${ESC}[1;1HSTALE"))       // the observer was closed when control opened
        Thread.sleep(150)
        assertEquals("hello", s.row0())
        assertEquals(TerminalMode.Controlling, s.mode())
    }

    @Test fun framesAreNeverKeptAfterTheSessionIsClosed() {
        val host = TestHost(); val s = session(host); observing(host, s)
        s.close(); waitUntil("cleared") { s.view.value == TerminalView() }
    }
}
