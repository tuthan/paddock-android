package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.relay.HostRefusal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a read made just before a send decided: go, remembering the agent's `state_change_seq`, or refuse with a code. */
sealed interface Preflight {
    data class Go(val seq: Long? = null) : Preflight
    data class Refuse(val code: String, val message: String) : Preflight
}

/**
 * How an operation ended, for the screen to show as it is. [Acknowledged] means herdr answered success: the write was
 * accepted, which is not a receipt for any turn. [Unknown] must be drawn as unknown. Every other case is certain:
 * [Rejected] is herdr's own refusal, and [NotSent], [Stale], [Busy], [NeedsReread], [JournalFailed] and [JournalUnreadable]
 * mean nothing reached the host.
 */
sealed interface OperationResult<out T> {
    data class Acknowledged<T>(val record: OperationRecord, val value: T) : OperationResult<T>
    data class Rejected(val record: OperationRecord, val code: String, val message: String) : OperationResult<Nothing>
    data class NotSent(val record: OperationRecord, val reason: String, val message: String) : OperationResult<Nothing>
    data class Unknown(val record: OperationRecord, val cause: String) : OperationResult<Nothing>
    /** The terminal has an operation running; [first] is its status. Nothing was written. */
    data class Busy(val first: OperationRecord) : OperationResult<Nothing>
    /** An earlier outcome on this terminal is unknown and not yet re-read. Nothing was written. */
    data class NeedsReread(val unknown: OperationRecord) : OperationResult<Nothing>
    /** The pane for the terminal could not be resolved in the epoch the screen opened in. Nothing was written. */
    data class Stale(val key: TerminalKey) : OperationResult<Nothing>
    /** The journal row could not be made durable, so the send did not happen. */
    data class JournalFailed(val cause: String) : OperationResult<Nothing>
    /** The saved journal cannot be read, so no earlier outcome can be ruled out. Nothing was written; see [OperationJournal.resetUnreadable]. */
    data class JournalUnreadable(val reason: String) : OperationResult<Nothing>
}

/**
 * One journaled mutation, start to finish: resolve the pane now, write the row, check readiness against a fresh read,
 * mark the row Sent immediately before the first byte, send once, and classify what came back. It never retries and
 * never resends: a failure after the write leaves the row Unknown and says so.
 *
 * Outcomes are decided from the journal row's own state after a failure, not from a flag in the caller: a timeout
 * that lands between the row write and the write itself reads as Unknown, the cautious side.
 */
object Operation {
    /**
     * [resolveTarget] names the pane the terminal has in the current snapshot, or throws [StalePane] when the epoch the
     * screen opened in is gone or the terminal is. [preflight] reads the agent again and decides whether to go.
     * [send] must call its `beforeWrite` argument once, immediately before the request's first byte, and return herdr's
     * success value or throw (a [HerdrError] for herdr's refusal, anything else for a transport failure).
     */
    suspend fun <T> run(
        journal: OperationJournal,
        key: TerminalKey,
        kind: OperationKind,
        payload: String? = null,
        keepText: Boolean = false,
        requestId: String? = null,
        resolveTarget: () -> String,
        preflight: suspend (paneId: String) -> Preflight = { Preflight.Go() },
        send: suspend (paneId: String, beforeWrite: suspend () -> Unit) -> T,
    ): OperationResult<T> {
        val pane = try { resolveTarget() } catch (e: StalePane) { return OperationResult.Stale(e.key) }

        val row = try {
            when (val b = io { journal.begin(key, kind, payload, keepText, requestId) }) {
                is Begin.Started -> b.record
                is Begin.InFlight -> return OperationResult.Busy(b.first)
                is Begin.NeedsReread -> return OperationResult.NeedsReread(b.unknown)
                is Begin.Unreadable -> return OperationResult.JournalUnreadable(b.reason)
            }
        } catch (e: JournalWriteFailed) { return OperationResult.JournalFailed(e.message.orEmpty()) }

        val go = try { preflight(pane) }
        catch (e: CancellationException) { settle { journal.notSent(row.id, "cancelled") }; throw e }
        catch (e: Throwable) { return notSent(journal, row, "preflight_failed", e.message ?: e::class.simpleName.orEmpty()) }
        if (go is Preflight.Refuse) return notSent(journal, row, go.code, go.message)
        val seq = (go as Preflight.Go).seq

        return try {
            val value = send(pane) { io { journal.markSent(row.id, pane, seq) } }
            val now = journal.get(row.id)
            if (now?.outcome != OperationOutcome.Sent) {
                // send returned without reporting its write: a bug in the caller, kept cautious.
                settle { if (now?.outcome == OperationOutcome.Requested) journal.notSent(row.id, "no_write", "send returned without writing") }
                error("send returned success without calling beforeWrite for operation ${row.id}")
            }
            OperationResult.Acknowledged(settle { journal.acknowledged(row.id) }, value)
        } catch (e: JournalWriteFailed) {
            settle { journal.notSent(row.id, "journal_unwritable", e.message.orEmpty()) }
            OperationResult.JournalFailed(e.message.orEmpty())
        } catch (e: CancellationException) {
            settle { lose(journal, row, e) }
            throw e
        } catch (e: HostRefusal) {
            val state = journal.get(row.id)?.outcome
            if (state == OperationOutcome.Sent) OperationResult.Rejected(settle { journal.rejected(row.id, e.code, e.message.orEmpty()) }, e.code, e.message.orEmpty())
            else notSent(journal, row, e.code, e.message.orEmpty())
        } catch (e: Throwable) {
            val after = settle { lose(journal, row, e) }
            if (after.outcome == OperationOutcome.Unknown) OperationResult.Unknown(after, cause(e))
            else OperationResult.NotSent(after, "transport_failed", cause(e))
        }
    }

    /** After a failure: Sent becomes Unknown, Requested (nothing written) becomes NotSent. */
    private fun lose(journal: OperationJournal, row: OperationRecord, e: Throwable): OperationRecord =
        when (journal.get(row.id)?.outcome) {
            OperationOutcome.Sent -> journal.unknown(row.id, cause(e))
            OperationOutcome.Requested -> journal.notSent(row.id, "transport_failed", cause(e))
            else -> journal.get(row.id) ?: row
        }

    private suspend fun notSent(journal: OperationJournal, row: OperationRecord, code: String, message: String): OperationResult<Nothing> =
        OperationResult.NotSent(settle { journal.notSent(row.id, code, message) }, code, message)

    private fun cause(e: Throwable) = (e.message ?: e::class.simpleName).orEmpty().lineSequence().first().take(OperationJournal.MAX_NOTE)

    private suspend fun <R> io(block: () -> R): R = withContext(Dispatchers.IO) { block() }

    /** Journal moves after a send or a cancel must finish even when the caller was cancelled. */
    private suspend fun <R> settle(block: () -> R): R = withContext(NonCancellable + Dispatchers.IO) { block() }
}
