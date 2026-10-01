package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.EventOutcome
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.RelayClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The subscription requests Paddock sends, built from the 0.9.1 schema's `Subscription` list. */
object Subscriptions {
    /** Every subscription type that takes no parameters: session-wide lifecycle, one request. */
    val LIFECYCLE_TYPES = listOf(
        "workspace.created", "workspace.updated", "workspace.metadata_updated", "workspace.renamed", "workspace.moved",
        "workspace.reordered", "workspace.closed", "workspace.focused", "worktree.created", "worktree.opened", "worktree.removed",
        "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
        "pane.created", "pane.closed", "pane.updated", "pane.focused", "pane.moved", "pane.exited", "pane.agent_detected",
        "layout.updated",
    )

    fun lifecycle(): List<JsonObject> = LIFECYCLE_TYPES.map { buildJsonObject { put("type", it) } }

    /** One entry per pane: in 0.9.1 `pane.agent_status_changed` without `pane_id` is refused. */
    fun status(paneIds: Collection<String>): List<JsonObject> =
        paneIds.sorted().map { buildJsonObject { put("type", "pane.agent_status_changed"); put("pane_id", it) } }
}

/** A running stream: its collector and the moment herdr acknowledged it. */
class StreamHandle(val job: Job, val acknowledged: CompletableDeferred<Unit>) {
    fun cancel() = job.cancel()
}

/**
 * Starts [subscriptions] on a dedicated stream. [onOutcome] sees every mapped event. [onEnded] runs exactly once
 * when the stream ends for any reason but cancellation; its argument names why (`EventsLost`, `RelayUnavailable`,
 * `HerdrError`, ...), and the owner marks itself stale and reconciles. [acknowledged] fails with the same cause when
 * the stream ends before herdr accepted it.
 */
fun RelayClient.start(
    scope: CoroutineScope,
    subscriptions: List<JsonObject>,
    onOutcome: (EventOutcome) -> Unit,
    onEnded: (Throwable) -> Unit,
): StreamHandle {
    val ack = CompletableDeferred<Unit>()
    val job = scope.launch {
        try {
            subscribe(subscriptions, onAcknowledged = { ack.complete(Unit) }).collect { onOutcome(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            ack.cancel(e); throw e
        } catch (e: Throwable) {
            ack.completeExceptionally(e)
            onEnded(e)
        }
    }
    return StreamHandle(job, ack)
}

/**
 * Keeps one status stream covering the current agent panes. When the set changes the whole stream is replaced: the
 * new one is started and acknowledged first, and only then is the old one closed, so no event is missed in between.
 * A refusal (a pane closed between the read and the request makes herdr reject the whole request) is retried once
 * with a fresh pane set from [currentPanes]; a second refusal is reported through [onEnded].
 */
class StatusStreams(
    private val relay: RelayClient,
    private val scope: CoroutineScope,
    private val currentPanes: suspend () -> Set<String>,
    private val onOutcome: (EventOutcome) -> Unit,
    private val onEnded: (Throwable) -> Unit,
    /**
     * Called after a stream covering a changed pane set is acknowledged. A pane that was added had no subscription
     * between the read that showed it and this acknowledgement, so a status change in that window was never delivered;
     * the owner reads again (subscribe, then read) to close it.
     */
    private val onCovered: () -> Unit = {},
) {
    private val lock = Mutex()
    private var active: StreamHandle? = null
    private var covered: Set<String> = emptySet()

    /** The panes the running stream covers, for tests and the debug screen. */
    suspend fun coveredPanes(): Set<String> = lock.withLock { covered }

    suspend fun update(wanted: Set<String>) = lock.withLock {
        if (wanted == covered && active?.job?.isActive == true) return@withLock
        if (wanted.isEmpty()) { active?.cancel(); active = null; covered = emptySet(); return@withLock }
        var set = wanted
        var retried = false
        while (true) {
            // A candidate that fails before promotion is handled below; only the promoted stream reports through onEnded.
            var self: StreamHandle? = null
            val next = relay.start(scope, Subscriptions.status(set), onOutcome) { cause -> if (self != null && active === self) onEnded(cause) }
            self = next
            try {
                next.acknowledged.await()
                active?.cancel(); active = next; covered = set
                onCovered()
                return@withLock
            } catch (e: HerdrError) {
                next.cancel()
                if (retried) { onEnded(e); return@withLock }
                retried = true
                set = currentPanes()
                if (set.isEmpty()) { active?.cancel(); active = null; covered = emptySet(); return@withLock }
            } catch (e: kotlinx.coroutines.CancellationException) {
                next.cancel(); throw e
            } catch (e: Throwable) {
                next.cancel(); onEnded(e); return@withLock
            }
        }
    }

    fun stop() { active?.cancel(); active = null; covered = emptySet() }
}
