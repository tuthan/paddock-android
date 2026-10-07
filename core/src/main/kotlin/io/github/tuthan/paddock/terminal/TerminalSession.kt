package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.ports.TerminalEngine
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.relay.lines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// --- what the screen sees ---

sealed interface TerminalMode {
    /** Nothing is open: not started, closed, or the app is in the background. */
    data object Idle : TerminalMode
    /** A channel is being opened. [control] says which kind; the last grid, if any, stays on screen meanwhile. */
    data class Connecting(val control: Boolean) : TerminalMode
    /** Read-only. Keys and the keyboard do nothing. */
    data object Observing : TerminalMode
    /** This phone owns the terminal's input (and its size). */
    data object Controlling : TerminalMode
    /** Release was sent; waiting for herdr to say it is done. */
    data object Releasing : TerminalMode
    /** Nothing more will arrive on this session; the screen offers the way back. */
    data class Ended(val reason: EndReason) : TerminalMode
}

sealed interface EndReason {
    data object PaneGone : EndReason
    data object LinkLost : EndReason
    /** herdr closed the stream and said why. Text from herdr, shown as data. */
    data class Closed(val reason: String) : EndReason
    data class Failed(val message: String) : EndReason
}

/** A one-line fact the screen shows until something replaces it. Text inside is data from herdr or the host. */
sealed interface TerminalNotice {
    /** A lost frame was repaired with a fresh full frame. */
    data object Resynced : TerminalNotice
    /** Another client owns input. The screen explains it and offers a separate Take over. */
    data class Conflict(val herdrSays: String) : TerminalNotice
    data class ControlRefused(val herdrSays: String) : TerminalNotice
    /** Control ended without the user asking (taken over from the desktop, or the stream broke). */
    data class ControlEnded(val herdrSays: String) : TerminalNotice
    /** Control needs the pinned helper on the host; the user has not agreed to install it yet. */
    data class HelperNeeded(val destination: String, val sha256: String, val replacing: Boolean) : TerminalNotice
    data object HelperUnavailable : TerminalNotice
}

data class TerminalView(
    val mode: TerminalMode = TerminalMode.Idle,
    val grid: Grid? = null,
    val cursor: Cursor? = null,
    /** The geometry of the frames being shown. */
    val cols: Int = 0,
    val rows: Int = 0,
    /** The terminal's real size when it could be read; null otherwise. */
    val pty: PtySize? = null,
    val notice: TerminalNotice? = null,
    /** Frames applied since the session started. A counter, so a test or a screen can wait for progress. */
    val frames: Long = 0,
    val lastFrameAtMillis: Long? = null,
    /** `System.nanoTime()` when the last applied frame's line arrived from the channel; for measuring how long drawing takes after it. */
    val lastFrameArrivedNanos: Long = 0,
) {
    /** True when requesting control would set the terminal to the phone's size, because the real size is unknown. */
    val controlWouldResizeDesktop: Boolean get() = pty == null
}

data class TerminalOptions(
    /** The host releases control when the phone has been silent this long. */
    val leaseSeconds: Int = 15,
    val pingEvery: Duration = 5.seconds,
    /** How long a release gets to be acknowledged before the channel is simply closed. */
    val releaseWait: Duration = 2.seconds,
    /** How long a control stream gets to answer a resync request with a full frame. */
    val resyncWait: Duration = 3.seconds,
    /** A viewport change that sizes the observer is applied after it has been still this long. */
    val viewportSettle: Duration = 400.milliseconds,
)

/** The host's pinned `paddock-control.py`: where it lives, whether it is the pinned file, and the install the user agreed to. */
interface ControlHelperPort {
    val expectedSha256: String
    /** The path to run, only when the host copy is the pinned script; null when it is missing or different. */
    suspend fun verifiedPath(): String?
    suspend fun destination(): String
    suspend fun isReplacing(): Boolean
    suspend fun install()
}

class SshControlHelper(private val installer: RelayInstaller) : ControlHelperPort {
    override val expectedSha256: String get() = installer.expectedSha256
    private var home: String? = null
    private suspend fun home(): String = home ?: installer.homeDirectory().also { home = it }
    override suspend fun verifiedPath(): String? = if (installer.state(home()) == RelayState.Current) installer.destination(home()) else null
    override suspend fun destination(): String = installer.destination(home())
    override suspend fun isReplacing(): Boolean = installer.state(home()) is RelayState.Mismatch
    override suspend fun install() = installer.install(home())
}

/**
 * One terminal on one host: the read-only observer, the request for control, its release, and everything that can go
 * wrong in between. It owns the only terminal channel of its host: at most one observer or controller is open, and
 * switching closes the first before opening the second. Commands from the screen are queued and handled one at a time on
 * one coroutine, so the engine, the channel and the state are never touched concurrently.
 *
 * Rules kept here (Phase 05):
 *  - Observing never changes the terminal's size; the observer is drawn for the terminal's real size when it can be read,
 *    else for the phone's viewport.
 *  - Control is requested without takeover; a refusal because another client owns input is surfaced as [TerminalNotice.Conflict]
 *    and the session goes back to observing. [takeOver] is a separate, deliberate command.
 *  - Control always runs through the pinned helper, which releases it when the phone stops pinging, so a phone that loses
 *    its link cannot hold the desktop out. Without a verified helper control is not attempted.
 *  - Keys and the keyboard reach the terminal only while controlling; [send] is otherwise a no-op that says so.
 *  - Control ends on [release], on [suspend] (the app left the foreground), on [close], and when the link drops. Coming back
 *    never regains control by itself.
 *  - Frames and grids live in memory only and are cleared on [close] and [suspend].
 */
class TerminalSession(
    private val scope: CoroutineScope,
    private val ssh: SshSession,
    private val cli: HerdrCli,
    /** The terminal's current pane id, resolved fresh for each channel; null when the terminal is gone. */
    private val paneOf: () -> String?,
    private val helper: ControlHelperPort?,
    private val clock: Clock,
    private val engine: TerminalEngine = VtEngine(),
    private val options: TerminalOptions = TerminalOptions(),
    private val python: String = "python3",
) {
    private val _view = MutableStateFlow(TerminalView())
    val view: StateFlow<TerminalView> = _view.asStateFlow()

    /** Set by the actor, read by [send] on the caller's thread. */
    @Volatile private var controlling = false

    private sealed interface Cmd {
        data class Open(val cols: Int, val rows: Int) : Cmd
        data class Viewport(val cols: Int, val rows: Int) : Cmd
        data class Control(val takeover: Boolean) : Cmd
        data object InstallThenControl : Cmd
        data object Release : Cmd
        class Input(val bytes: ByteArray) : Cmd
        data class Resize(val cols: Int, val rows: Int) : Cmd
        data object Suspend : Cmd
        data object DismissNotice : Cmd
        data object Resume : Cmd
        data class Line(val gen: Int, val text: String, val arrivedNanos: Long = System.nanoTime()) : Cmd
        data class StreamEnded(val gen: Int, val error: Throwable?) : Cmd
        data class Ping(val gen: Int) : Cmd
        data class ReleaseTimeout(val gen: Int) : Cmd
        data class ResyncTimeout(val gen: Int) : Cmd
        data class ViewportSettled(val token: Int) : Cmd
        data object LinkDown : Cmd
    }

    private enum class Kind { Observe, Control }

    /** What the screen and the timers ask for. Small and rare, so unbounded; never a frame. */
    private val commands = Channel<Cmd>(Channel.UNLIMITED)
    /**
     * [Cmd.Line] and [Cmd.StreamEnded] from the stream collector, in order. Bounded: a full queue suspends the collector, which
     * stops reading the SSH channel, whose window then closes on the host, so output faster than the screen can draw waits
     * on the host instead of piling up here. Kept apart from [commands] so that bounding it never drops a release, a key or a
     * suspend, and so that those overtake queued frames.
     */
    private val stream = Channel<Cmd>(TerminalLimits.STREAM_QUEUE_LINES)
    private val actor: Job
    private val linkWatch: Job

    // --- actor state: touched only by the actor, except where marked ---

    private var kind: Kind? = null
    private var gen = 0
    @Volatile private var channel: StreamChannel? = null
    private val jobs = ArrayList<Job>()
    @Volatile private var stderrTail = ""
    private var established = false
    private var releasing = false
    private var awaitingFull = false
    private var closedRecordSeen = false
    private val sequencer = FrameSequencer()
    private var viewport = Pair(80, 24)
    /** The viewport the open observer was sized for, so a viewport that wandered and came back does not reconnect it. */
    private var observedViewport = viewport
    private var paused = false
    private var protocolErrors = 0
    private var arrivedNanos = 0L
    private var settleToken = 0
    private var lastProbeAtMillis = Long.MIN_VALUE

    init {
        // However the actor ends (close, a cancelled scope), the channel is closed and the screen forgotten.
        actor = scope.launch {
            try {
                while (true) {
                    // Commands first: a queue of frames never delays a release, a keystroke or the app leaving the foreground.
                    val cmd = commands.tryReceive().getOrNull() ?: select<Cmd?> {
                        commands.onReceiveCatching { it.getOrNull() }
                        stream.onReceiveCatching { it.getOrNull() }
                    } ?: break
                    handle(cmd)
                }
            } finally { withContext(NonCancellable) { tearDown(); engine.clear(); _view.value = TerminalView() } }
        }
        linkWatch = scope.launch { ssh.link.collect { if (it is LinkState.Down) commands.trySend(Cmd.LinkDown) } }
    }

    // --- commands from the screen ---

    /** Starts observing, drawn for the phone's viewport of [cols] x [rows] cells unless the terminal's real size is known. */
    fun open(cols: Int, rows: Int) { commands.trySend(Cmd.Open(cols, rows)) }

    /** The viewport changed (pinch, rotation). Only matters while the terminal's real size is unknown. */
    fun setViewport(cols: Int, rows: Int) { commands.trySend(Cmd.Viewport(cols, rows)) }

    /** Asks for control without taking it from another owner. */
    fun requestControl() { commands.trySend(Cmd.Control(takeover = false)) }

    /** The user saw who owns input and chose to take it. A separate command, never sent by [requestControl]. */
    fun takeOver() { commands.trySend(Cmd.Control(takeover = true)) }

    /** The user agreed to install the helper shown in [TerminalNotice.HelperNeeded]; control is requested once it is there. */
    fun installHelper() { commands.trySend(Cmd.InstallThenControl) }

    fun release() { commands.trySend(Cmd.Release) }

    /** The user closed the notice (or it timed out on the screen). Changes nothing else: a refused request stays refused. */
    fun dismissNotice() { commands.trySend(Cmd.DismissNotice) }

    /** Sends [bytes] to the terminal as input. Returns false, sending nothing, unless this phone is controlling. */
    fun send(bytes: ByteArray): Boolean {
        if (!controlling || bytes.isEmpty()) return false
        commands.trySend(Cmd.Input(bytes))
        return true
    }

    /** Changes the terminal's size to [cols] x [rows], for every client including the desktop. The screen warns first. */
    fun resizeToFit(cols: Int, rows: Int): Boolean {
        if (!controlling || !TerminalLimits.validGeometry(cols, rows)) return false
        commands.trySend(Cmd.Resize(cols, rows))
        return true
    }

    /** The app left the foreground: control is released, the channel closed and the screen forgotten. */
    fun suspend() { commands.trySend(Cmd.Suspend) }

    /** The app is back: observing again, never control. */
    fun resume() { commands.trySend(Cmd.Resume) }

    /** Ends the session for good: releases control, closes the channel, clears the engine and the grid. */
    fun close() {
        controlling = false
        linkWatch.cancel()
        commands.close()
        actor.cancel()
    }

    // --- the actor ---

    private suspend fun handle(cmd: Cmd) {
        try {
            when (cmd) {
                is Cmd.Open -> { viewport = cmd.cols to cmd.rows; if (kind == null && !paused) connect(Kind.Observe, probe = true) }
                is Cmd.Viewport -> viewportChanged(cmd.cols, cmd.rows)
                is Cmd.ViewportSettled ->
                    if (cmd.token == settleToken && kind == Kind.Observe && _view.value.pty == null && viewport != observedViewport) connect(Kind.Observe, probe = false)
                is Cmd.Control -> requestControl(cmd.takeover)
                Cmd.InstallThenControl -> installThenControl()
                Cmd.Release -> release0()
                is Cmd.Input -> input(cmd.bytes)
                is Cmd.Resize -> if (kind == Kind.Control && established) write(ControlEncoder.resize(cmd.cols, cmd.rows))
                Cmd.Suspend -> suspend0()
                Cmd.DismissNotice -> notice(null)
                Cmd.Resume -> if (paused) { paused = false; connect(Kind.Observe, probe = true) }
                is Cmd.Line -> if (cmd.gen == gen) { arrivedNanos = cmd.arrivedNanos; line(cmd.text) }
                is Cmd.StreamEnded -> if (cmd.gen == gen) streamEnded(cmd.error)
                is Cmd.Ping -> if (cmd.gen == gen && kind == Kind.Control) write(PING)
                is Cmd.ReleaseTimeout -> if (cmd.gen == gen && releasing) { releasing = false; backToObserving(null) }
                is Cmd.ResyncTimeout -> if (cmd.gen == gen && awaitingFull) {
                    // The controller never produced the full frame it was asked for: give the terminal back rather than guess.
                    awaitingFull = false
                    backToObserving(TerminalNotice.ControlEnded("the control stream stopped answering"))
                }
                Cmd.LinkDown -> if (kind != null || _view.value.mode is TerminalMode.Connecting) end(EndReason.LinkLost)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An unexpected failure ends this session visibly, never the app. The message is the exception's class and text only.
            end(EndReason.Failed("${e.javaClass.simpleName}: ${e.message ?: ""}".take(200)))
        }
    }

    private fun viewportChanged(cols: Int, rows: Int) {
        if (viewport == cols to rows) return
        viewport = cols to rows
        // With the terminal's real size known the observer does not depend on the phone's viewport at all.
        if (kind == Kind.Observe && _view.value.pty == null) {
            val token = ++settleToken
            scope.launch { delay(options.viewportSettle); commands.trySend(Cmd.ViewportSettled(token)) }
        }
    }

    private suspend fun requestControl(takeover: Boolean) {
        if (kind == Kind.Control || paused || _view.value.mode is TerminalMode.Ended) return
        val h = helper
        if (h == null) { notice(TerminalNotice.HelperUnavailable); return }
        if (h.verifiedPath() == null) {
            notice(TerminalNotice.HelperNeeded(h.destination(), h.expectedSha256, h.isReplacing()))
            return
        }
        notice(null)   // a new request replaces the answer to the last one
        connect(Kind.Control, probe = true, takeover = takeover)
    }

    private suspend fun installThenControl() {
        val h = helper ?: return notice(TerminalNotice.HelperUnavailable)
        h.install()
        requestControl(takeover = false)
    }

    private suspend fun release0() {
        when {
            kind == Kind.Control && established && !releasing -> {
                releasing = true; controlling = false
                setMode(TerminalMode.Releasing)
                write(ControlEncoder.release())
                val g = gen
                scope.launch { delay(options.releaseWait); commands.trySend(Cmd.ReleaseTimeout(g)) }
            }
            kind == Kind.Control && !established -> backToObserving(null) // withdraw a request that has not been answered
        }
    }

    private suspend fun input(bytes: ByteArray) {
        if (kind != Kind.Control || !established || releasing) return
        var at = 0
        while (at < bytes.size) {
            val n = minOf(TerminalLimits.INPUT_BYTES, bytes.size - at)
            write(ControlEncoder.input(bytes.copyOfRange(at, at + n)))
            at += n
        }
    }

    private suspend fun suspend0() {
        paused = true
        tearDown()
        engine.clear()
        _view.value = TerminalView(pty = _view.value.pty)
    }

    // --- channels ---

    /** Closes whatever is open and opens a channel of [newKind]. The last grid stays on screen while it connects. */
    private suspend fun connect(newKind: Kind, probe: Boolean, takeover: Boolean = false) {
        tearDown()
        val pane = paneOf()
        if (pane == null) { end(EndReason.PaneGone); return }
        setMode(TerminalMode.Connecting(control = newKind == Kind.Control))
        if (probe || newKind == Kind.Control || clock.nowMillis() - lastProbeAtMillis > PROBE_MAX_AGE_MS) {
            val size = PtyProbe.probe(ssh, cli, pane)
            lastProbeAtMillis = clock.nowMillis()
            // A failed probe keeps the last size: the terminal does not change size on its own often, and a stale size is
            // the better guess than none. A fresh success replaces it.
            if (size != null) _view.update { it.copy(pty = size) }
        }
        observedViewport = viewport
        val size = _view.value.pty ?: PtySize(viewport.first, viewport.second)
        val cols = size.cols.coerceIn(1, TerminalLimits.MAX_COLS)
        val rows = size.rows.coerceIn(1, TerminalLimits.MAX_ROWS)
        val geometryOk = TerminalLimits.validGeometry(cols, rows)
        val g = if (geometryOk) cols else viewport.first.coerceIn(1, 200)
        val r = if (geometryOk) rows else viewport.second.coerceIn(1, 100)
        val argv = when (newKind) {
            Kind.Observe -> cli.terminalObserve(pane, g, r)
            Kind.Control -> {
                val path = helper?.verifiedPath()
                if (path == null) { backToObserving(TerminalNotice.HelperUnavailable); return }
                cli.terminalControl(python, path, options.leaseSeconds, pane, g, r, takeover)
            }
        }
        val ch = try { ssh.openStream(argv) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (newKind == Kind.Control) { backToObserving(TerminalNotice.ControlRefused("could not open the control channel")); return }
            end(if (ssh.link.value is LinkState.Down) EndReason.LinkLost else EndReason.Failed("could not open the terminal channel")); return
        }
        gen++
        val mine = gen
        channel = ch; kind = newKind; established = false; releasing = false; awaitingFull = false; closedRecordSeen = false; stderrTail = ""
        sequencer.reset()
        jobs += scope.launch {
            var error: Throwable? = null
            // send, not trySend: when the actor is behind, this suspends and the SSH channel is not read until it catches up.
            try { ch.stdout.lines(TerminalLimits.LINE_BYTES).collect { stream.send(Cmd.Line(mine, it)) } }
            catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e }
            stream.send(Cmd.StreamEnded(mine, error))
        }
        jobs += scope.launch {
            try { ch.stderr.collect { stderrTail = (stderrTail + it.toString(Charsets.UTF_8)).takeLast(STDERR_KEEP) } } catch (e: CancellationException) { throw e } catch (_: Throwable) { }
        }
        if (newKind == Kind.Control) {
            jobs += scope.launch { while (true) { delay(options.pingEvery); commands.trySend(Cmd.Ping(mine)) } }
        }
    }

    /** Closes the open channel, if any. Idempotent; for a controller, closing stdin makes the helper release at once. */
    private suspend fun tearDown() {
        controlling = false
        val ch = channel; channel = null
        gen++   // records already queued from the closed channel carry the old number and are ignored
        jobs.forEach { it.cancel() }; jobs.clear()
        kind = null; established = false; releasing = false; awaitingFull = false
        if (ch != null) withContext(NonCancellable) { runCatching { ch.close() } }
    }

    private suspend fun backToObserving(note: TerminalNotice?) {
        connect(Kind.Observe, probe = false)
        if (note != null && _view.value.mode !is TerminalMode.Ended) notice(note)
    }

    private suspend fun end(reason: EndReason) {
        tearDown()
        setMode(TerminalMode.Ended(reason))
    }

    private suspend fun write(bytes: ByteArray) {
        val ch = channel ?: return
        try { ch.write(bytes) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the reader sees the broken stream */ }
    }

    // --- records ---

    private suspend fun line(text: String) {
        val record = try { FrameDecoder.decode(text) } catch (e: TerminalProtocolError) { return protocolFailure(e) }
        when (record) {
            is TerminalRecord.Frame -> frame(record)
            is TerminalRecord.Closed -> closed(record.reason)
            is TerminalRecord.Unknown -> Unit
        }
    }

    private suspend fun protocolFailure(e: TerminalProtocolError) {
        // Nothing of the record is shown. A stream that keeps producing them is given up on.
        if (++protocolErrors > MAX_PROTOCOL_ERRORS) {
            if (kind == Kind.Control) backToObserving(TerminalNotice.ControlEnded("the control stream was unreadable"))
            else end(EndReason.Failed("the terminal stream is unreadable (${e.javaClass.simpleName})"))
            return
        }
        if (kind == Kind.Observe) connect(Kind.Observe, probe = false)
    }

    private suspend fun frame(f: TerminalRecord.Frame) {
        if (f.full) awaitingFull = false
        else if (awaitingFull) return
        when (val verdict = sequencer.accept(f)) {
            FrameSequencer.Verdict.Drop -> return
            is FrameSequencer.Verdict.Resync -> return resync()
            FrameSequencer.Verdict.Apply -> Unit
        }
        if (f.full) {
            if (engine.cols != f.width || engine.rows != f.height) engine.resize(f.width, f.height)
            engine.clear()
        } else if (engine.cols != f.width || engine.rows != f.height) return resync()
        engine.feed(f.bytes)
        protocolErrors = 0
        val first = !established
        established = true
        val mode = when (kind) { Kind.Control -> if (releasing) TerminalMode.Releasing else TerminalMode.Controlling; else -> TerminalMode.Observing }
        controlling = kind == Kind.Control && !releasing
        _view.update {
            it.copy(
                mode = mode, grid = engine.grid(), cursor = engine.cursor, cols = engine.cols, rows = engine.rows,
                frames = it.frames + 1, lastFrameAtMillis = clock.nowMillis(), lastFrameArrivedNanos = arrivedNanos,
                // Only a granted request clears the notice that led to it. The observer that is opened after a refusal also
                // sends a first frame, and must not wipe the refusal the user has not read yet.
                notice = if (first && kind == Kind.Control) null else it.notice,
            )
        }
    }

    /** A frame was lost. An observer reconnects for a fresh full frame; a controller asks for one by resizing to its own size. */
    private suspend fun resync() {
        when (kind) {
            Kind.Observe -> { connect(Kind.Observe, probe = false); notice(TerminalNotice.Resynced) }
            Kind.Control -> {
                if (awaitingFull) return
                awaitingFull = true
                write(ControlEncoder.resize(engine.cols.coerceAtLeast(1), engine.rows.coerceAtLeast(1)))
                notice(TerminalNotice.Resynced)
                val g = gen
                scope.launch { delay(options.resyncWait); commands.trySend(Cmd.ResyncTimeout(g)) }
            }
            null -> Unit
        }
    }

    private suspend fun closed(reason: String) {
        closedRecordSeen = true
        when (kind) {
            Kind.Control -> {
                val note: TerminalNotice? = when {
                    !established -> if (isOwnerConflict(reason)) TerminalNotice.Conflict(reason) else TerminalNotice.ControlRefused(reason)
                    releasing -> null
                    else -> TerminalNotice.ControlEnded(reason)
                }
                backToObserving(note)
            }
            Kind.Observe -> end(if (reason.contains("not found")) EndReason.PaneGone else EndReason.Closed(reason))
            null -> Unit
        }
    }

    private suspend fun streamEnded(error: Throwable?) {
        if (closedRecordSeen) return
        val wasControl = kind == Kind.Control
        if (ssh.link.value is LinkState.Down) return end(EndReason.LinkLost)
        val exit = withTimeoutOrNull(1.seconds) { runCatching { channel?.awaitExit() }.getOrNull() }
        val said = stderrTail.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
        val why = buildString {
            append("herdr ended the terminal stream")
            if (exit != null) append(" (exit $exit)")
            if (said != null) append(": $said") else if (error != null && error !is TerminalProtocolError) append(" (${error.javaClass.simpleName})")
        }
        if (wasControl) backToObserving(TerminalNotice.ControlEnded(why)) else end(EndReason.Failed(why))
    }

    private fun isOwnerConflict(reason: String) = reason.contains("already has an attached client") || reason.contains("--takeover")

    private fun setMode(m: TerminalMode) { _view.update { it.copy(mode = m) } }
    private fun notice(n: TerminalNotice?) { _view.update { it.copy(notice = n) } }

    private companion object {
        val PING = """{"type":"paddock.lease"}""".plus("\n").toByteArray()
        const val STDERR_KEEP = 2048
        const val MAX_PROTOCOL_ERRORS = 3
        const val PROBE_MAX_AGE_MS = 30_000L
    }
}
