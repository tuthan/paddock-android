package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.EpochTracker
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** An authoritative read and when the phone made it. [epoch] is the phone-local epoch it belongs to. */
data class Installed(val snapshot: Snapshot, val readAtMillis: Long, val epoch: Long)

/**
 * Backoff for reads and streams: 1 s, 2 s, 4 s, then jittered up to 120 s. The schedule restarts only after 60 s of
 * continuous success ([succeeded] marks its start), so a flapping link keeps its long delays and a long wait between
 * failures is not mistaken for health.
 */
class Backoff(private val random: () -> Double = Math::random, private val healthyResetMillis: Long = 60_000) {
    private var attempt = 0
    private var healthySince: Long? = null

    /** The delay before the next retry after a failure at [nowMillis]. */
    fun nextDelayMillis(nowMillis: Long): Long {
        if (healthySince?.let { nowMillis - it >= healthyResetMillis } == true) attempt = 0
        healthySince = null
        val n = attempt++
        return when {
            n < 3 -> 1_000L shl n
            else -> {
                // 8 s doubling to the 120 s cap, with the upper half randomised so many phones do not retry in step.
                val cap = minOf(120_000L, 8_000L shl minOf(n - 3, 4))
                (cap / 2 + (cap / 2 * random()).toLong()).coerceAtMost(120_000L)
            }
        }
    }

    /** A read or stream worked at [nowMillis]. Only the first success of a streak starts the healthy clock. */
    fun succeeded(nowMillis: Long) { if (healthySince == null) healthySince = nowMillis }

    fun reset() { attempt = 0; healthySince = null }
}

/**
 * Events are invalidations, never state. [invalidate] sets a dirty flag; one loop performs the reads, so no read is
 * ever concurrent with another, and a flag set during a read causes exactly one more read. Every completed read is
 * authoritative for its own `readAt` and is installed; `age` is measured from `readAt`, never from the last event.
 */
class Reconciler(
    private val clock: Clock,
    private val host: HostProfileId,
    private val session: String,
    private val read: suspend () -> Snapshot,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val backoff: Backoff = Backoff(),
    private val epochs: EpochTracker = EpochTracker(),
) {
    private val dirty = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _installed = MutableStateFlow<Installed?>(null)
    val installed: StateFlow<Installed?> = _installed.asStateFlow()

    private val _failure = MutableStateFlow<Throwable?>(null)
    /** The last read failure, cleared by the next success. The owner shows it as a stale banner. */
    val lastFailure: StateFlow<Throwable?> = _failure.asStateFlow()

    private val _observations = MutableSharedFlow<Observation>(extraBufferCapacity = 256)
    val observations: SharedFlow<Observation> = _observations.asSharedFlow()

    /** Total reads started; a test and the debug screen both want this. */
    @Volatile var reads = 0L; private set

    fun invalidate(@Suppress("UNUSED_PARAMETER") reason: String) { dirty.set(true); wake.trySend(Unit) }

    /** A reconnect starts a new epoch and a new baseline: nothing observed before it is compared with what comes after. */
    fun onReconnect() { epochs.onReconnect(); previous = null; invalidate("reconnect") }

    /** Milliseconds since the installed read was made, or null before the first one. */
    fun ageMillis(): Long? = _installed.value?.let { clock.nowMillis() - it.readAtMillis }

    @Volatile private var previous: Installed? = null

    /** The read loop. Runs until cancelled. */
    suspend fun run() {
        for (signal in wake) {
            while (dirty.getAndSet(false)) {
                reads++
                try {
                    install(read())
                    backoff.succeeded(clock.nowMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _failure.value = e
                    dirty.set(true)
                    sleep(backoff.nextDelayMillis(clock.nowMillis()))
                }
            }
        }
    }

    /** Heartbeat: a liveness read every [periodMillis] while [foreground] is true; the owner flips it on lifecycle. */
    fun heartbeat(scope: CoroutineScope, foreground: StateFlow<Boolean>, periodMillis: Long = 30_000) = scope.launch {
        while (isActive) {
            sleep(periodMillis)
            if (foreground.value) invalidate("heartbeat")
        }
    }

    private fun install(snapshot: Snapshot) {
        val newEpoch = epochs.onSnapshot(snapshot)
        if (newEpoch) previous = null
        val now = Installed(snapshot, clock.nowMillis(), epochs.epoch)
        val before = previous
        _installed.value = now
        _failure.value = null
        previous = now
        if (before != null && before.epoch == now.epoch) Diff.observe(before.snapshot, snapshot, clock.nowMillis(), host, session, now.epoch).forEach { _observations.tryEmit(it) }
    }
}

/** What changed between two authoritative reads. Stamped with the phone's clock and the terminal's key. */
sealed interface Observation {
    val at: Long
    val key: TerminalKey

    data class StatusChanged(override val at: Long, override val key: TerminalKey, val from: AgentStatus, val to: AgentStatus) : Observation {
        val newlyBlocked get() = to == AgentStatus.Blocked && from != AgentStatus.Blocked
        val newlyDone get() = to == AgentStatus.Done && from != AgentStatus.Done
    }
    data class PaneAppeared(override val at: Long, override val key: TerminalKey, val paneId: String) : Observation
    data class PaneVanished(override val at: Long, override val key: TerminalKey, val paneId: String) : Observation
    data class TitleChanged(override val at: Long, override val key: TerminalKey, val from: String?, val to: String?) : Observation
}

object Diff {
    /**
     * Terminals are matched by `terminal_id`, so a renumbered pane is not a disappearance. A repeated sample of the
     * same state yields nothing: only a difference between two installed reads is an observation, which is also why
     * a baseline (no previous read) produces none.
     */
    fun observe(previous: Snapshot, next: Snapshot, at: Long, host: HostProfileId, session: String, epoch: Long): List<Observation> {
        fun key(p: Pane) = TerminalKey(TargetRef(host, session, p.terminalId), epoch)
        val before = previous.panes.associateBy { it.terminalId }
        val after = next.panes.associateBy { it.terminalId }
        val out = ArrayList<Observation>()
        for ((id, pane) in after) {
            val old = before[id]
            if (old == null) { out += Observation.PaneAppeared(at, key(pane), pane.paneId); continue }
            if (old.agentStatus != pane.agentStatus) out += Observation.StatusChanged(at, key(pane), old.agentStatus, pane.agentStatus)
            if (old.terminalTitleStripped != pane.terminalTitleStripped) out += Observation.TitleChanged(at, key(pane), old.terminalTitleStripped, pane.terminalTitleStripped)
        }
        for ((id, pane) in before) if (id !in after) out += Observation.PaneVanished(at, key(pane), pane.paneId)
        return out
    }
}
