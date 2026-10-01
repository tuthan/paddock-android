package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.relay.sha256Hex
import io.github.tuthan.paddock.terminal.ControlEncoder
import io.github.tuthan.paddock.terminal.ControlHelperPort
import io.github.tuthan.paddock.terminal.EndReason
import io.github.tuthan.paddock.terminal.PtyProbe
import io.github.tuthan.paddock.terminal.PtySize
import io.github.tuthan.paddock.terminal.ScrollDirection
import io.github.tuthan.paddock.terminal.ScrollSource
import io.github.tuthan.paddock.terminal.TerminalMode
import io.github.tuthan.paddock.terminal.TerminalNotice
import io.github.tuthan.paddock.terminal.TerminalOptions
import io.github.tuthan.paddock.terminal.TerminalSession
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Phase 05 against the disposable `paddock-test` session: AC-05.3 (observing leaves the pane's geometry alone), AC-05.4
 * (control without takeover is refused while a desktop client owns input, takeover is its own step, release gives the desktop
 * its typing back), AC-05.5 (resize under control changes the PTY and what herdr reports), plus the shapes of every control
 * command against the installed herdr. The desktop client is `tools/desktop-client.py`, a real `herdr terminal attach` in a
 * pseudo-terminal. Control runs through the pinned `host/paddock-control.py`. Run with
 * `PADDOCK_TEST_SOCKET=<.../sessions/paddock-test/herdr.sock>`; skipped without it.
 */
class Phase05LiveTest {
    private lateinit var env: PaddockTest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val extraPanes = CopyOnWriteArrayList<String>()
    private val desktops = CopyOnWriteArrayList<Desktop>()
    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    private val profile = HostProfile("local", "Local", "127.0.0.1", 22, "tester")
    private val root = File(System.getProperty("paddock.repoRoot"))
    private lateinit var base: String

    @Before fun setUp() = runBlocking<Unit> { env = PaddockTest.orSkip(); base = env.basePane() }

    @After fun tearDown() {
        desktops.forEach { it.quit() }
        if (::env.isInitialized) runBlocking { extraPanes.forEach { runCatching { env.close(it) } } }
        scope.coroutineContext[Job]?.cancel()
    }

    /** The pinned helper straight from the repository; the install flow is covered by unit tests and the device run. */
    private val helper = object : ControlHelperPort {
        private val file = File(root, "host/paddock-control.py")
        override val expectedSha256 = sha256Hex(file.readBytes())
        override suspend fun verifiedPath(): String = file.absolutePath
        override suspend fun destination(): String = file.absolutePath
        override suspend fun isReplacing() = false
        override suspend fun install() = Unit
    }

    private suspend fun host(): MonitoredHost =
        MonitoredHost(
            scope, profile, env.sessionName, env.session, env.relayScript, env.socket, ledger, clock, MutableStateFlow(true), env.herdr,
            controlHelper = helper, terminalOptions = TerminalOptions(leaseSeconds = 15, pingEvery = kotlin.time.Duration.parse("2s")),
        ).also { it.start(); withTimeout(15_000) { it.freshness.first { f -> f == Freshness.Live } } }

    private suspend fun until(what: String, ms: Long = 15_000, cond: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!cond()) delay(25) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private fun scoped(vararg rest: String) = listOf(env.herdr, "--session", env.sessionName) + rest
    private suspend fun run(vararg argv: String): String {
        val r = env.session.exec(scoped(*argv))
        check(r.exit == 0) { "herdr ${argv.toList()} failed (${r.exit}): ${r.stderr.toString(Charsets.UTF_8)}" }
        return r.stdout.toString(Charsets.UTF_8)
    }

    private suspend fun newPane(): String = env.split(base).also { extraPanes += it }
    private suspend fun paneGet(pane: String): JsonObject = Json.parseToJsonElement(run("pane", "get", pane)).jsonObject["result"]!!.jsonObject["pane"]!!.jsonObject
    private suspend fun terminalId(pane: String) = paneGet(pane)["terminal_id"]!!.jsonPrimitive.content
    private suspend fun viewportRows(pane: String) = paneGet(pane)["scroll"]!!.jsonObject["viewport_rows"]!!.jsonPrimitive.int
    private suspend fun ptySize(pane: String) = PtyProbe.probe(env.session, env.cli, pane)
    private suspend fun paneText(pane: String) = run("pane", "read", pane, "--source", "recent", "--lines", "40")

    /** A session for [pane], opened once the host's own snapshot knows the pane. */
    private suspend fun session(host: MonitoredHost, pane: String): TerminalSession {
        val tid = terminalId(pane)
        until("the snapshot to list $pane") { host.reconciler.installed.value?.snapshot?.panes?.any { it.terminalId == tid } == true }
        return host.terminalSession(tid)
    }

    private suspend fun TerminalSession.waitMode(mode: TerminalMode) = until("mode $mode (now ${view.value.mode}, notice ${view.value.notice})") { view.value.mode == mode }

    /** A desktop client attached to a terminal; commands and answers are JSON lines. */
    private inner class Desktop(terminalId: String, rows: Int = 30, cols: Int = 100) {
        private val proc = ProcessBuilder("python3", File(root, "tools/desktop-client.py").path, terminalId, rows.toString(), cols.toString())
            .also { it.environment()["PADDOCK_TEST_SESSION"] = env.sessionName; it.environment()["PADDOCK_HERDR"] = env.herdr; it.redirectError(ProcessBuilder.Redirect.DISCARD) }.start()
        private val out: BufferedReader = proc.inputStream.bufferedReader()
        init { desktops += this }
        private fun ask(cmd: String): JsonObject { proc.outputStream.write((cmd + "\n").toByteArray()); proc.outputStream.flush(); return answer() }
        private fun answer(): JsonObject = Json.parseToJsonElement(out.readLine() ?: error("the desktop client ended")).jsonObject
        val ready: JsonObject = answer()
        fun type(text: String) = ask("type $text")
        fun status(): JsonObject = ask("status")
        fun alive() = status()["alive"]!!.jsonPrimitive.content == "true"
        fun quit() { runCatching { proc.outputStream.write("quit\n".toByteArray()); proc.outputStream.flush() }; runCatching { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }; proc.destroyForcibly() }
    }

    // ---- AC-05.3 -----------------------------------------------------------------------------------------------

    @Test fun observingAPaneThatIsPrintingLeavesItsGeometryAsItWas() = runBlocking<Unit> {
        val pane = newPane()
        env.runInPane(pane, "i=0; while [ \$i -lt 300 ]; do echo \"tick \$i\"; i=\$((i+1)); sleep 0.03; done")
        val rowsBefore = viewportRows(pane)
        val sizeBefore = ptySize(pane)!!
        val h = host()
        val t = session(h, pane)
        t.open(33, 7)   // a viewport that is nothing like the pane
        until("frames while the pane prints") { t.view.value.frames >= 10 }
        val sizeDuring = ptySize(pane)
        assertEquals(sizeBefore, t.view.value.pty, "the observer is drawn for the terminal's own size")
        assertEquals(sizeBefore.cols, t.view.value.cols); assertEquals(sizeBefore.rows, t.view.value.rows)
        assertTrue((0 until t.view.value.rows).any { r -> t.view.value.grid!!.rowText(r).contains("tick") }, "what the pane printed is on the phone's grid")
        t.setViewport(20, 5); delay(700)   // pinching must not reach the pane either
        until("more frames") { t.view.value.frames >= 12 }
        assertEquals(TerminalMode.Observing, t.view.value.mode)
        assertEquals(rowsBefore, viewportRows(pane), "pane get viewport_rows")
        val sizeAfter = ptySize(pane)
        assertEquals(sizeBefore, sizeAfter, "stty size of the pane's terminal"); assertEquals(sizeBefore, sizeDuring)
        assertTrue(env.session.stdinLog.none { "terminal.resize" in it || "terminal.input" in it }, "nothing was written to any terminal channel")
        println("AC-05.3 pane $pane: pane get viewport_rows $rowsBefore -> ${viewportRows(pane)}; stty $sizeBefore -> $sizeAfter; ${t.view.value.frames} frames applied while it printed")
        t.close(); h.stop()
    }

    // ---- AC-05.4 -----------------------------------------------------------------------------------------------

    @Test fun controlIsRefusedWhileTheDesktopOwnsInputTakeoverIsItsOwnStepAndReleaseGivesTheDesktopItsTypingBack() = runBlocking<Unit> {
        val pane = newPane()
        val tid = terminalId(pane)
        val desktop = Desktop(tid)
        assertEquals("true", desktop.ready["alive"]!!.jsonPrimitive.content)
        desktop.type("echo desktop-first")
        until("the desktop's typing") { "desktop-first" in paneText(pane) }
        val size = ptySize(pane)!!
        assertEquals(PtySize(100, 30), size, "attached at the desktop client's size")

        val h = host()
        val t = session(h, pane)
        t.open(40, 25)
        t.waitMode(TerminalMode.Observing)
        assertEquals(size, t.view.value.pty)

        // Control without takeover is refused, in herdr's words, and nothing is left running.
        t.requestControl()
        until("the conflict") { t.view.value.notice is TerminalNotice.Conflict }
        val said = (t.view.value.notice as TerminalNotice.Conflict).herdrSays
        assertTrue("already has an attached client" in said && "--takeover" in said, said)
        t.waitMode(TerminalMode.Observing)
        until("the observer after the refusal to draw its first frame") { t.view.value.frames > 0 && t.view.value.mode == TerminalMode.Observing }
        delay(300)
        assertIs<TerminalNotice.Conflict>(t.view.value.notice, "the refusal is still on the screen once the observer is back")
        assertFalse(t.send("echo must-not-arrive\r".toByteArray()), "keys stay inert after a refusal")
        assertTrue(desktop.alive(), "the desktop client was not disturbed")
        desktop.type("echo desktop-second")
        until("the desktop still types after the refusal") { "desktop-second" in paneText(pane) }
        assertTrue("must-not-arrive" !in paneText(pane))
        assertEquals(size, ptySize(pane), "a refused request resizes nothing")

        // Take over is a second, deliberate command; it keeps the terminal's size.
        t.takeOver()
        t.waitMode(TerminalMode.Controlling)
        assertEquals(size, ptySize(pane), "control was attached at the terminal's own size")
        assertTrue(t.send("echo phone-typed\r".toByteArray()))
        until("the phone's typing") { "phone-typed" in paneText(pane) }
        until("the desktop client to be displaced") { !desktop.alive() }

        // Release: the helper and the channel are gone, and a desktop client attaches and types again.
        t.release()
        t.waitMode(TerminalMode.Observing)
        assertNull(t.view.value.notice)
        assertFalse(t.send("echo after-release\r".toByteArray()))
        val again = Desktop(tid)
        assertEquals("true", again.ready["alive"]!!.jsonPrimitive.content, "a desktop client attaches without takeover after release")
        again.type("echo desktop-again")
        until("the desktop's typing after release") { "desktop-again" in paneText(pane) }
        assertTrue("after-release" !in paneText(pane))
        println("AC-05.4 pane $pane: refusal \"$said\"; takeover then phone-typed reached the pane; desktop displaced; after release a fresh desktop client attached without takeover and typed")
        t.close(); h.stop()
    }

    // ---- AC-05.5 -----------------------------------------------------------------------------------------------

    @Test fun resizeUnderControlChangesThePtyAndWhatHerdrReports() = runBlocking<Unit> {
        val pane = newPane()
        val h = host()
        val t = session(h, pane)
        t.open(40, 25)
        t.waitMode(TerminalMode.Observing)
        val before = ptySize(pane)!!
        val rowsBefore = viewportRows(pane)
        assertFalse(t.view.value.controlWouldResizeDesktop, "the real size is known, so control does not resize on its own")
        t.requestControl()
        t.waitMode(TerminalMode.Controlling)
        assertEquals(before, ptySize(pane), "control at the terminal's own size changes nothing")
        assertEquals(rowsBefore, viewportRows(pane))

        assertTrue(t.resizeToFit(48, 14))
        until("the PTY to follow") { ptySize(pane) == PtySize(48, 14) }
        until("herdr to report the new viewport") { viewportRows(pane) == 14 }
        until("the phone's grid to follow") { t.view.value.cols == 48 && t.view.value.rows == 14 }
        println("AC-05.5 pane $pane: stty $before -> ${ptySize(pane)}; pane get viewport_rows $rowsBefore -> ${viewportRows(pane)}; phone grid ${t.view.value.cols}x${t.view.value.rows}")
        t.release()
        t.waitMode(TerminalMode.Observing)
        t.close(); h.stop()
    }

    // ---- scrollback and the other commands, against the installed herdr --------------------------------------------------

    @Test fun everyEncodedCommandIsAcceptedByTheInstalledHerdrExceptMouse() = runBlocking<Unit> {
        val pane = newPane()
        env.runInPane(pane, "seq 1 200")
        delay(500)
        val argv = scoped("terminal", "session", "control", pane, "--cols", "60", "--rows", "20")
        val p = ProcessBuilder(argv).start()
        val frames = StringBuilder(); val err = StringBuilder()
        Thread { p.inputStream.bufferedReader().forEachLine { synchronized(frames) { frames.appendLine(it) } } }.apply { isDaemon = true; start() }
        Thread { p.errorStream.bufferedReader().forEachLine { synchronized(err) { err.appendLine(it) } } }.apply { isDaemon = true; start() }
        fun send(b: ByteArray) { p.outputStream.write(b); p.outputStream.flush() }
        fun count() = synchronized(frames) { frames.lines().count { "terminal.frame" in it } }
        until("the first frame") { count() >= 1 }
        for (record in listOf(
            ControlEncoder.input("echo shapes\r".toByteArray()),
            ControlEncoder.scroll(ScrollDirection.Up, 5, ScrollSource.Wheel),
            ControlEncoder.scroll(ScrollDirection.Down, 5, ScrollSource.Wheel),
            ControlEncoder.scroll(ScrollDirection.Up, 1, ScrollSource.PageKey),
            ControlEncoder.resize(60, 20),
        )) {
            val seen = count(); send(record)
            until("a frame after ${String(record).trim().take(60)}") { count() > seen }
        }
        assertEquals("", synchronized(err) { err.toString() }, "herdr ignored none of them")
        send(ControlEncoder.mouse(io.github.tuthan.paddock.terminal.MouseAction.Down, io.github.tuthan.paddock.terminal.MouseButton.Left, 1, 1))
        until("herdr's complaint about the mouse record") { synchronized(err) { "terminal.mouse" in err.toString() } }
        assertTrue(p.isAlive, "a record herdr does not know does not end the control session")
        send(ControlEncoder.release())
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "release ends the process")
        assertTrue("\"detached\"" in synchronized(frames) { frames.toString() })
        println("encoder shapes vs herdr 0.9.1: input, scroll (wheel and page_key), resize, release accepted; mouse rejected: ${synchronized(err) { err.toString().trim().take(200) }}")
    }

    /** A desktop-style control request without takeover: accepted when herdr answers with a frame (and then released at once). */
    private suspend fun desktopControlAccepted(pane: String): Boolean = withContext(Dispatchers.IO) {
        val size = PtyProbe.probe(env.session, env.cli, pane)!!
        val p = ProcessBuilder(scoped("terminal", "session", "control", pane, "--cols", size.cols.toString(), "--rows", size.rows.toString())).start()
        try {
            val first = p.inputStream.bufferedReader().readLine()
            val ok = first != null && "terminal.frame" in first
            if (ok) { p.outputStream.write(ControlEncoder.release()); p.outputStream.flush(); delay(200) }
            ok
        } finally { p.destroyForcibly() }
    }

    @Test fun openingAnotherTerminalOnTheHostClosesTheFirstAndReleasesItsControl() = runBlocking<Unit> {
        val a = newPane(); val b = newPane()
        val h = host()
        val ta = session(h, a)
        ta.open(40, 12); ta.waitMode(TerminalMode.Observing)
        ta.requestControl(); ta.waitMode(TerminalMode.Controlling)
        assertFalse(desktopControlAccepted(a), "while the phone controls, a desktop-style request is refused")
        val tb = session(h, b)   // one terminal channel per host: this closes the first, releasing its control
        until("the first session to be closed and cleared") { ta.view.value.grid == null && ta.view.value.mode == TerminalMode.Idle }
        assertFalse(ta.send("echo late\r".toByteArray()))
        until("the pane to be free for the desktop again") { desktopControlAccepted(a) }
        assertTrue(!paneText(a).contains("late"))
        tb.close(); h.stop()
        println("one terminal channel per host: opening a second session closed the first and its control was released")
    }

    @Test fun aPaneThatClosesWhileObservedEndsTheSessionAsGone() = runBlocking<Unit> {
        val pane = newPane()
        val h = host()
        val t = session(h, pane)
        t.open(40, 12)
        t.waitMode(TerminalMode.Observing)
        env.close(pane); extraPanes.remove(pane)
        until("the session to end") { t.view.value.mode is TerminalMode.Ended }
        val mode = t.view.value.mode
        println("pane closed while observed -> $mode")
        assertTrue(mode == TerminalMode.Ended(EndReason.PaneGone) || (mode as TerminalMode.Ended).reason is EndReason.Closed, "ended as $mode")
        assertNotEquals(TerminalMode.Observing, t.view.value.mode)
        t.close(); h.stop()
    }
}
