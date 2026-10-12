package io.github.tuthan.paddock.output

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.cli.ReadSource
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Installed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One `agent read`, already classified. */
sealed interface OutputRead {
    data class Text(val text: String) : OutputRead
    /** herdr says the pane is not there. */
    data object PaneMissing : OutputRead
    /** The pane is there but no agent runs in it any more (`agent_not_found`): the shell is left. */
    data object AgentMissing : OutputRead
    data class Failed(val message: String) : OutputRead
}

/** What the Output tab shows. */
sealed interface OutputState {
    data object Loading : OutputState

    /** [stale] is true when the latest read failed: the text is the last good one and [readAtMillis] says how old it is. */
    data class Showing(val lines: List<AnsiLine>, val readAtMillis: Long, val stale: Boolean) : OutputState

    /** The terminal is no longer in the session. The screen says so and offers back; it never shows another pane's text. */
    data object PaneGone : OutputState

    /** The terminal is still there but its agent has exited. The screen says so; the last text it showed stays out. */
    data object AgentGone : OutputState

    /** Nothing has been read yet and the read is failing. */
    data class Unavailable(val message: String) : OutputState
}

/**
 * Runs `agent read --source <source> --lines N --format ansi` over [session] (recent-unwrapped by default) and classifies
 * the answer. A usage error (exit 2) means this build and herdr disagree about the command: the reader stops sending it
 * and answers every later read with the same failure until the app is updated, as the Phase 03 CLI rule asks.
 */
class AgentOutputReader(
    private val session: SshSession,
    private val cli: HerdrCli,
    private val lines: Int = OutputFeed.LINES,
    private val source: ReadSource = ReadSource.RecentUnwrapped,
) {
    @Volatile private var disabled: OutputRead.Failed? = null

    suspend fun read(paneId: String): OutputRead {
        disabled?.let { return it }
        val result = session.exec(cli.agentRead(paneId, source, lines, ansi = true), limits = ExecLimits.default)
        return when (val outcome = CliResult.classify(result)) {
            is CliOutcome.Ok -> OutputRead.Text(outcome.stdout)
            is CliOutcome.Failure -> when {
                outcome.code == "agent_not_found" -> OutputRead.AgentMissing
                outcome.code.endsWith("_not_found") -> OutputRead.PaneMissing
                else -> OutputRead.Failed("${outcome.code}: ${outcome.message}".take(200))
            }
            is CliOutcome.ClientBug -> OutputRead.Failed("This herdr does not accept Paddock's read command; update Paddock.").also { disabled = it }
            is CliOutcome.Unparsed -> OutputRead.Failed("unreadable answer (exit ${outcome.exit})")
        }
    }
}

/**
 * The live tail of one terminal. The pane id is looked up from the installed snapshot by `terminal_id` on every poll,
 * so a renumbered pane is followed and a vanished one ends the feed instead of reading whatever now holds that id.
 * It polls every [intervalMillis] only while the screen is visible, following and [live]; scrolling up pauses it (the
 * text stays, nothing is read) and [resumeFollowing] reads again at once. The CLI's answer names no pane, so a read is
 * kept only if the installed snapshot still maps the terminal to the pane it was sent to; while the monitor is not
 * live (reconnecting, so the mapping cannot be trusted) nothing is read and the last text is marked stale.
 *
 * Following is looked at again when an answer lands: a text that arrives after the user paused (a drag, a long press) is dropped, so the lines
 * a menu was opened on never change under it, and it is read again when following resumes. [readOnce] is the one read the user asks for, whatever
 * the poll's two gates say; it never overlaps the poll's own read.
 */
class OutputFeed(
    private val scope: CoroutineScope,
    private val target: TargetRef,
    private val installed: StateFlow<Installed?>,
    private val read: suspend (paneId: String) -> OutputRead,
    private val clock: Clock,
    private val intervalMillis: Long = 1_000,
    private val maxLines: Int = LINES,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val live: StateFlow<Boolean> = MutableStateFlow(true),
) {
    private val _state = MutableStateFlow<OutputState>(OutputState.Loading)
    val state: StateFlow<OutputState> = _state.asStateFlow()

    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()

    private val visible = MutableStateFlow(false)
    private val jobs = ArrayList<Job>()
    private val readLock = Mutex()

    /** Reads performed so far, for tests and the debug screen. */
    @Volatile var reads = 0; private set

    fun start() {
        jobs += scope.launch { poll() }
        jobs += scope.launch {
            // A pane that disappears while polling is paused (scrolled up) is still reported.
            installed.collect { i ->
                if (i != null && i.snapshot.panes.none { it.terminalId == target.terminalId }) goneAndStop()
            }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear() }

    fun setVisible(value: Boolean) { visible.value = value }
    fun userScrolledUp() { _following.value = false }
    fun resumeFollowing() { _following.value = true }

    private fun goneAndStop() { _state.value = OutputState.PaneGone; jobs.forEach { it.cancel() } }

    /** What the poll does after a read: wait an interval, go round at once (the pane changed under the read), or stop. */
    private enum class After { Wait, Retry, End }

    private class Step(val after: After, val fresh: Boolean)

    private suspend fun poll() {
        while (true) {
            combine(visible, _following) { v, f -> v && f }.first { it }
            if (!live.value) {
                (_state.value as? OutputState.Showing)?.let { if (!it.stale) _state.value = it.copy(stale = true) }
                live.first { it }
            }
            val snapshot = installed.first { it != null }!!.snapshot
            val pane = snapshot.panes.firstOrNull { it.terminalId == target.terminalId } ?: return goneAndStop()
            val step = readLocked(pane.paneId, requireFollowing = true)
            when (step.after) {
                After.End -> return
                // A read for a pane that changed under it is retried at once with the new mapping; anything else waits.
                After.Wait -> sleep(intervalMillis)
                After.Retry -> Unit
            }
        }
    }

    /**
     * One read of the pane on [paneId] and what follows from its answer, under [readLock] so the poll and [readOnce] never overlap. A text is shown only
     * if the mapping still holds and, for the poll ([requireFollowing]), the user has not paused since the read was sent.
     */
    private suspend fun readLocked(paneId: String, requireFollowing: Boolean): Step = readLock.withLock {
        reads++
        val outcome = try {
            read(paneId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            OutputRead.Failed(e.message ?: e.javaClass.simpleName)
        }
        val stillThatPane = live.value && installed.value?.snapshot?.panes?.firstOrNull { it.terminalId == target.terminalId }?.paneId == paneId
        var fresh = false
        when (outcome) {
            is OutputRead.Text -> if (stillThatPane && (!requireFollowing || _following.value)) {
                _state.value = OutputState.Showing(Ansi.parse(outcome.text, maxLines), clock.nowMillis(), stale = false)
                fresh = true
            }
            OutputRead.PaneMissing -> if (stillThatPane) { goneAndStop(); return@withLock Step(After.End, false) }
            OutputRead.AgentMissing -> if (stillThatPane) { _state.value = OutputState.AgentGone; jobs.forEach { it.cancel() }; return@withLock Step(After.End, false) }
            is OutputRead.Failed -> _state.value = when (val last = _state.value) {
                is OutputState.Showing -> last.copy(stale = true)
                else -> OutputState.Unavailable(outcome.message)
            }
        }
        Step(if (stillThatPane || outcome is OutputRead.Failed) After.Wait else After.Retry, fresh)
    }

    /**
     * One read on request (the Select text view's Re-read), whatever the screen's visibility and following say; it is counted in [reads] and keeps the
     * poll's pane rule. True when a fresh text was shown. A failure marks the text stale, as the poll's does, and a pane or agent that is gone says so.
     */
    suspend fun readOnce(): Boolean {
        val snapshot = installed.value?.snapshot ?: return false
        val pane = snapshot.panes.firstOrNull { it.terminalId == target.terminalId }
        if (pane == null) { goneAndStop(); return false }
        return readLocked(pane.paneId, requireFollowing = false).fresh
    }

    companion object {
        const val LINES = 200
    }
}
