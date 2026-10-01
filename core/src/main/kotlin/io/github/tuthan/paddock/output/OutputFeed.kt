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

    private suspend fun poll() {
        while (true) {
            combine(visible, _following) { v, f -> v && f }.first { it }
            if (!live.value) {
                (_state.value as? OutputState.Showing)?.let { if (!it.stale) _state.value = it.copy(stale = true) }
                live.first { it }
            }
            val snapshot = installed.first { it != null }!!.snapshot
            val pane = snapshot.panes.firstOrNull { it.terminalId == target.terminalId } ?: return goneAndStop()
            reads++
            val outcome = try {
                read(pane.paneId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                OutputRead.Failed(e.message ?: e.javaClass.simpleName)
            }
            val stillThatPane = live.value && installed.value?.snapshot?.panes?.firstOrNull { it.terminalId == target.terminalId }?.paneId == pane.paneId
            when (outcome) {
                is OutputRead.Text -> if (stillThatPane) _state.value = OutputState.Showing(Ansi.parse(outcome.text, maxLines), clock.nowMillis(), stale = false)
                OutputRead.PaneMissing -> if (stillThatPane) return goneAndStop()
                OutputRead.AgentMissing -> if (stillThatPane) { _state.value = OutputState.AgentGone; jobs.forEach { it.cancel() }; return }
                is OutputRead.Failed -> _state.value = when (val last = _state.value) {
                    is OutputState.Showing -> last.copy(stale = true)
                    else -> OutputState.Unavailable(outcome.message)
                }
            }
            // A read for a pane that changed under it is retried at once with the new mapping; anything else waits.
            if (stillThatPane || outcome is OutputRead.Failed) sleep(intervalMillis)
        }
    }

    companion object {
        const val LINES = 200
    }
}
