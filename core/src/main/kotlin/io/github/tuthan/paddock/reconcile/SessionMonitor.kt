package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.Budgets
import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.herdr.SnapshotResult
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.RelayClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Whether what the UI shows can be trusted right now. Mutations are enabled only in [Live]. */
enum class Freshness {
    /** Connecting, or reconnecting before the first authoritative read of this connection. */
    Starting,
    /** The lifecycle stream is acknowledged and a read that started after the acknowledgement is installed. */
    Live,
    /** A stream ended (including `events_lost`) or a read failed: cached data is old, mutations are off, a reconnect is under way. [SessionMonitor.lastLoss] says why. */
    Stale,
}

/**
 * Ties the pieces of one session together: a lifecycle stream and a status stream feed [Reconciler.invalidate],
 * reads install authoritative snapshots, and any loss of a stream marks the session stale, backs off, starts a new
 * epoch and reconciles again before going live. Nothing in here trusts an event payload.
 */
class SessionMonitor(
    private val scope: CoroutineScope,
    private val relay: RelayClient,
    val reconciler: Reconciler,
    private val foreground: StateFlow<Boolean>,
    private val clock: Clock,
    private val backoff: Backoff = Backoff(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Runs before every reconnect (not the first connect, which the caller has just checked). Throwing refuses the
     * attempt: it is how the relay is re-verified on the host before each new connection uses it.
     */
    private val beforeReconnect: suspend () -> Unit = {},
) {
    private val _freshness = MutableStateFlow(Freshness.Starting)
    val freshness: StateFlow<Freshness> = _freshness.asStateFlow()

    /** Why the last connection ended, for the debug screen and tests. */
    private val _lastLoss = MutableStateFlow<Throwable?>(null)
    val lastLoss: StateFlow<Throwable?> = _lastLoss.asStateFlow()

    @Volatile private var lost: CompletableDeferred<Throwable> = CompletableDeferred()
    private val jobs = ArrayList<Job>()
    private val ignored = java.util.concurrent.atomic.AtomicLong()

    /** Event lines whose names this build does not map. Counted, never acted on (they do not trigger a read). */
    val ignoredEvents: Long get() = ignored.get()

    private val onEvent: (EventOutcome) -> Unit = { outcome ->
        if (outcome is EventOutcome.Ignored) ignored.incrementAndGet() else reconciler.invalidate("event")
    }

    private val status = StatusStreams(
        relay, scope,
        currentPanes = { freshAgentPanes() },
        onOutcome = onEvent,
        onEnded = { lost.complete(it) },
        onCovered = { reconciler.invalidate("status-covered") },
    )

    fun start() {
        jobs += scope.launch { reconciler.run() }
        jobs += reconciler.heartbeat(scope, foreground)
        jobs += scope.launch { connectLoop() }
        jobs += scope.launch { reconciler.installed.collect { if (_freshness.value == Freshness.Live) keepStatusCovering(it?.snapshot) } }
        // A foreground return reads at once rather than waiting for the next event or heartbeat.
        jobs += scope.launch { foreground.collect { if (it) reconciler.invalidate("foreground") } }
    }

    fun stop() { status.stop(); jobs.forEach { it.cancel() }; jobs.clear() }

    private suspend fun keepStatusCovering(snapshot: Snapshot?) {
        try { status.update(agentPanes(snapshot)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { lost.complete(e) }
    }

    private suspend fun connectLoop() {
        var first = true
        while (scope.isActive) {
            val attempt = CompletableDeferred<Throwable>().also { lost = it }
            _freshness.value = Freshness.Starting
            if (!first) {
                try { beforeReconnect() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    _lastLoss.value = e; _freshness.value = Freshness.Stale
                    sleep(backoff.nextDelayMillis(clock.nowMillis())); continue
                }
                reconciler.onReconnect()
            }
            first = false
            // A failed read is a loss like a dropped stream: what is installed can no longer be called live.
            val failureBefore = reconciler.lastFailure.value
            val readFailure = scope.launch { attempt.complete(reconciler.lastFailure.first { it != null && it !== failureBefore }!!) }
            val lifecycle = relay.start(scope, Subscriptions.lifecycle(), onEvent) { attempt.complete(it) }
            try {
                try {
                    lifecycle.acknowledged.await()
                } catch (e: kotlinx.coroutines.CancellationException) { if (!scope.isActive) throw e; attempt.complete(e) } catch (e: Throwable) { attempt.complete(e) }
                if (!attempt.isCompleted) {
                    val ackedAt = clock.nowMillis()
                    reconciler.invalidate("subscribed")
                    // Live only once a read made after the acknowledgement is installed: nothing can have been missed since.
                    val live = scope.launch {
                        reconciler.installed.first { it != null && it.readAtMillis >= ackedAt }
                        if (!attempt.isCompleted) { _freshness.value = Freshness.Live; keepStatusCovering(reconciler.installed.value?.snapshot); backoff.succeeded(clock.nowMillis()) }
                    }
                    val failure = attempt.await()
                    live.cancel()
                    _lastLoss.value = failure
                } else _lastLoss.value = attempt.await()
            } finally {
                status.stop(); lifecycle.cancel(); readFailure.cancel()
            }
            if (!scope.isActive) return
            _freshness.value = Freshness.Stale
            sleep(backoff.nextDelayMillis(clock.nowMillis()))
        }
    }

    private fun agentPanes(s: Snapshot?): Set<String> = s?.agents?.map { it.paneId }?.toSet().orEmpty()

    /** The agent panes from a read that started after this call, so a refused status request is retried with the session as it is now. */
    private suspend fun freshAgentPanes(): Set<String> {
        val asked = clock.nowMillis()
        reconciler.invalidate("status-refused")
        val fresh = withTimeoutOrNull(15_000) { reconciler.installed.first { it != null && it.readAtMillis >= asked } }
        return agentPanes((fresh ?: reconciler.installed.value)?.snapshot)
    }

    companion object {
        /** The authoritative read: one `session.snapshot` over the relay, with the snapshot's own 8 MiB budget. */
        fun snapshotReader(relay: RelayClient): suspend () -> Snapshot = {
            relay.call("session.snapshot", budget = Budgets.SNAPSHOT_LINE).decode<SnapshotResult>("session_snapshot").snapshot
        }
    }
}
