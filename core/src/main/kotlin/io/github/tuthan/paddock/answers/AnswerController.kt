package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.PaneResolver
import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.Operation
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.Preflight
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.reconcile.Installed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The two calls the phone makes on the host: read the pane's requests, and write one decision. [AnswerHost] is the SSH implementation. */
interface AnswerPort {
    suspend fun list(paneId: String): RequestListing
    suspend fun decide(paneId: String, claudeSessionId: String, requestId: String, behavior: Behavior, beforeWrite: suspend () -> Unit)
}

/** The last answer this phone gave for a terminal: how the operation ended, which request it was for, and when. */
data class AnswerResult(val requestId: String, val behavior: Behavior, val result: OperationResult<*>, val atMillis: Long)

/**
 * What the decision sheet knows about one terminal. [shown] is the request the user was given and every button acts on: a newer
 * request never takes its place under a finger (the sheet says it was replaced and offers the new one). [outcome] is how [shown]
 * stands in the host's files now. [workingObserved]: after the hook consumed the decision, herdr reported the agent past `blocked`.
 */
data class AnswerView(
    val listing: RequestListing? = null,
    val shown: PendingRequest? = null,
    val outcome: RequestOutcome? = null,
    val answerability: Answerability? = null,
    val result: AnswerResult? = null,
    /** A pending request newer than [shown]: the sheet offers it, and never swaps it in under a finger. */
    val newer: PendingRequest? = null,
    val error: String? = null,
    val workingObserved: Boolean = false,
    val reading: Boolean = false,
    /** When [shown] became the request on the sheet (the controller's clock); null before there is one. */
    val shownSince: Long? = null,
) {
    /** A decision is written or on its way and the host has not settled it yet: the screen reads faster until it has. */
    val settling: Boolean get() = shown != null && (outcome is RequestOutcome.Sent || (result?.result is OperationResult.Acknowledged<*> && (outcome == null || outcome is RequestOutcome.Waiting)))
}

/**
 * Runs the phone's Yes and No for Claude Code permission requests. Reading is a poll of `paddock-decide.py list` while a screen
 * watches a blocked agent; answering is one journaled [Operation]: the pane is resolved from the installed snapshot, the row is
 * written first, a fresh list proves the request on screen is still the newest pending one with time left, and one exec writes
 * the decision. Nothing here can send a key: the controller holds no herdr client at all.
 *
 * The request id an answer carries comes from the listing's own file, so the answer is bound file to file; herdr's status takes
 * no part in it beyond "workingObserved", a label shown after the hook has consumed the answer.
 */
class AnswerController(
    private val scope: CoroutineScope,
    private val port: AnswerPort,
    private val journal: OperationJournal,
    private val installed: () -> Installed?,
    private val agentStatus: (terminalId: String) -> AgentStatus?,
    private val clock: Clock,
    private val pollMillis: Long = POLL_MILLIS,
    private val settlingPollMillis: Long = SETTLING_POLL_MILLIS,
    private val errorPollMillis: Long = ERROR_POLL_MILLIS,
) {
    private val _views = MutableStateFlow<Map<String, AnswerView>>(emptyMap())
    val views: StateFlow<Map<String, AnswerView>> = _views.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Terminal ids with an answer from this phone still on its way. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val lock = Any()
    private val watchers = HashMap<String, Watch>()
    private class Watch(val key: TerminalKey, var job: Job?, var count: Int)

    private fun resolve(key: TerminalKey): String {
        val i = installed() ?: throw StalePane(key)
        return PaneResolver(i.snapshot, i.epoch).require(key)
    }

    /** A screen that shows this terminal's requests is up; it may be called by more than one screen, each [unwatch]ed once. */
    fun watch(key: TerminalKey) {
        val id = key.target.terminalId
        synchronized(lock) {
            val w = watchers.getOrPut(id) { Watch(key, null, 0) }
            w.count++
            if (w.job == null) w.job = scope.launch { poll(key) }
        }
    }

    fun unwatch(terminalId: String) {
        synchronized(lock) {
            val w = watchers[terminalId] ?: return
            if (--w.count > 0) return
            w.job?.cancel()
            watchers.remove(terminalId)
        }
    }

    /** One read now, for the sheet opening and for "check again"; the result lands in [views]. */
    fun refresh(key: TerminalKey) { scope.launch { readOnce(key) } }

    /**
     * The user asked to review the newest pending request: it becomes the one on the sheet. Nothing is answered by this.
     * A request already answered, expired or replaced stays on the sheet only until the user does this.
     */
    fun review(terminalId: String) {
        _views.update { m ->
            val v = m[terminalId] ?: return@update m
            val candidate = v.listing?.candidate?.request ?: return@update m
            if (candidate.requestId == v.shown?.requestId) m else m + (terminalId to v.copy(shown = candidate, result = null, workingObserved = false, shownSince = clock.nowMillis()))
        }
        recompute(terminalId)
    }

    /** Dismisses the outcome line after the user has read it. */
    fun dismiss(terminalId: String) { _views.update { m -> m[terminalId]?.let { m + (terminalId to it.copy(result = null)) } ?: m } }

    private suspend fun poll(key: TerminalKey) {
        val id = key.target.terminalId
        try {
            while (true) {
                val view = _views.value[id]
                val settling = view?.settling == true
                // A machine without the script (or an unreadable one) fails the same way every time: ask again slowly, never at the blocked-agent pace.
                if (settling || agentStatus(id) == AgentStatus.Blocked || view?.listing == null && view?.error == null) readOnce(key)
                delay(when { settling -> settlingPollMillis; _views.value[id]?.error != null -> errorPollMillis; else -> pollMillis })
            }
        } finally {
            synchronized(lock) { watchers[id]?.job = null }
        }
    }

    private suspend fun readOnce(key: TerminalKey) {
        val id = key.target.terminalId
        _views.update { m -> m + (id to (m[id] ?: AnswerView()).copy(reading = true)) }
        val listing = try {
            port.list(resolve(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: StalePane) {
            _views.update { m -> m + (id to (m[id] ?: AnswerView()).copy(reading = false, error = "This agent is no longer where the screen opened it.")) }
            return
        } catch (e: Throwable) {
            _views.update { m -> m + (id to (m[id] ?: AnswerView()).copy(reading = false, error = describe(e))) }
            return
        }
        _views.update { m -> m + (id to (m[id] ?: AnswerView()).copy(listing = listing, reading = false, error = null)) }
        recompute(id)
    }

    /**
     * Works out what the sheet says from the newest listing. The first request seen is adopted; after that a newer one is only
     * offered ([AnswerView.newer]), except when the request on the sheet is gone or expired and nothing of the user's is on it.
     */
    private fun recompute(terminalId: String) {
        val now = clock.nowMillis()
        _views.update { m ->
            val v = m[terminalId] ?: return@update m
            val listing = v.listing ?: return@update m
            val candidate = listing.candidate?.request
            var shown = v.shown
            val nothingToAct = shown != null && v.result == null && AnswerRules.outcome(listing, shown.requestId, now).let { it is RequestOutcome.Gone || it is RequestOutcome.Expired }
            if (candidate != null && (shown == null || nothingToAct)) shown = candidate
            val outcome = shown?.let { AnswerRules.outcome(listing, it.requestId, now) }
            val moved = agentStatus(terminalId).let { it == AgentStatus.Working || it == AgentStatus.Idle || it == AgentStatus.Done }
            m + (terminalId to v.copy(
                shown = shown, shownSince = if (shown?.requestId != v.shown?.requestId) now else v.shownSince,
                outcome = outcome, answerability = AnswerRules.answerability(listing, now),
                newer = candidate?.takeIf { shown != null && it.requestId != shown.requestId },
                workingObserved = v.workingObserved || (outcome is RequestOutcome.Consumed && moved),
            ))
        }
    }

    /**
     * Yes or No for the request on the sheet, as one journaled operation. Only the request the user was shown can be answered, and
     * only if a read made now still finds it the newest pending one with time to spare; otherwise nothing is sent and the row says why.
     * A second tap while one answer is running is ignored here and refused by the journal. [requestId] is the request the tap was drawn
     * for: when the request on the sheet is no longer that one (it was replaced a moment before the tap), nothing is sent and the row says why.
     */
    fun answer(key: TerminalKey, behavior: Behavior, requestId: String) {
        val id = key.target.terminalId
        val shown = _views.value[id]?.shown ?: return
        var accepted = false
        _running.update { if (id in it) it else { accepted = true; it + id } }
        if (!accepted) return
        scope.launch {
            try {
                val result = run(key, shown, requestId, behavior)
                _views.update { m -> m + (id to (m[id] ?: AnswerView()).copy(result = AnswerResult(shown.requestId, behavior, result, clock.nowMillis()), workingObserved = false)) }
            } finally {
                // Sending ends when the answer's outcome is known, not when the follow-up read does: on a dead link that read can hang, and the
                // sheet would go on saying "Sending…" over an outcome it already knows. A second tap is shut out by the result itself.
                _running.update { it - id }
            }
            runCatching { readOnce(key) }
        }
    }

    private suspend fun run(key: TerminalKey, shown: PendingRequest, requestId: String, behavior: Behavior): OperationResult<Unit> {
        val kind = if (behavior == Behavior.Allow) OperationKind.Allow else OperationKind.Deny
        return Operation.run(journal, key, kind, requestId = requestId,
            resolveTarget = { resolve(key) },
            preflight = { pane ->
                // The tap names the request the screen drew. If the sheet has since moved on to another one, that tap is not an answer to it.
                if (shown.requestId != requestId) return@run Preflight.Refuse(NotAnswerable.Replaced.code, NotAnswerable.Replaced.sentence)
                val listing = port.list(pane)
                _views.update { m -> m + (key.target.terminalId to (m[key.target.terminalId] ?: AnswerView()).copy(listing = listing)) }
                refusal(listing, shown)?.let { Preflight.Refuse(it.code, it.sentence) } ?: Preflight.Go()
            },
            send = { pane, before -> port.decide(pane, shown.claudeSessionId, shown.requestId, behavior, before) })
    }

    /** Why [shown] may not be answered by this listing, or null when it may. */
    internal fun refusal(listing: RequestListing, shown: PendingRequest): NotAnswerable? {
        val now = clock.nowMillis()
        return when (val a = AnswerRules.answerability(listing, now)) {
            is Answerability.Yes -> if (a.request.requestId == shown.requestId) null else NotAnswerable.Replaced
            is Answerability.No -> when (val o = AnswerRules.outcome(listing, shown.requestId, now)) {
                is RequestOutcome.Replaced -> NotAnswerable.Replaced
                is RequestOutcome.Expired -> NotAnswerable.Expired
                else -> if (a.why == NotAnswerable.NothingPending || o is RequestOutcome.Gone || o is RequestOutcome.Consumed || o is RequestOutcome.Sent) NotAnswerable.NothingPending else a.why
            }
        }
    }

    /**
     * After an unknown outcome (the link dropped after the answer went out): reads the host's files and frees every waiting Yes or No
     * row of the terminal, recording what the files say. The rows stay Unknown; the files name what became of each request.
     */
    suspend fun settle(key: TerminalKey): List<Pair<OperationRecord, RequestOutcome>> {
        val waiting = journal.unresolvedUnknown(key).filter { it.kind == OperationKind.Allow || it.kind == OperationKind.Deny }
        if (waiting.isEmpty()) return emptyList()
        val listing = port.list(resolve(key))
        val now = clock.nowMillis()
        return waiting.map { row ->
            val outcome = row.requestId?.let { AnswerRules.outcome(listing, it, now) } ?: RequestOutcome.Gone
            journal.resolve(row.id, "re-read: " + describe(outcome)) to outcome
        }.also { _views.update { m -> m + (key.target.terminalId to (m[key.target.terminalId] ?: AnswerView()).copy(listing = listing)) }; recompute(key.target.terminalId) }
    }

    private fun describe(o: RequestOutcome) = when (o) {
        RequestOutcome.Waiting -> "still pending"
        is RequestOutcome.Sent -> "written, not taken yet"
        is RequestOutcome.Consumed -> "taken by the hook"
        RequestOutcome.Expired -> "expired"
        RequestOutcome.Replaced -> "replaced"
        RequestOutcome.Gone -> "gone"
    }

    private fun describe(e: Throwable): String = (e.message ?: e::class.simpleName).orEmpty().lineSequence().first().take(160)

    companion object {
        const val POLL_MILLIS = 2_000L
        const val SETTLING_POLL_MILLIS = 600L
        const val ERROR_POLL_MILLIS = 15_000L
    }
}
