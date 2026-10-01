package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.Clock
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What the phone asked herdr to do to a terminal. [wire] is what the Activity screen and the journal show. */
@Serializable
enum class OperationKind(val wire: String) {
    Prompt("prompt"),
    Esc("Esc"),
    CtrlC("Ctrl+C"),
    Focus("desktop focus"),
}

/**
 * Where an operation stands. [Requested] is written before anything else happens and [Sent] before the first byte
 * leaves, so a record left in [Requested] by a dead process never reached the host and one left in [Sent] may have.
 * [NotSent] and [Rejected] are certain; [Unknown] is the honest state when the link went away after the write.
 */
@Serializable
enum class OperationOutcome { Requested, NotSent, Sent, Acknowledged, Rejected, Unknown }

/**
 * One journal row. The terminal is named by host, session, terminal id and the phone-local epoch it was resolved in;
 * [paneIdAtSend] is the pane id that was actually written to. [payloadSha256] is the hash of the prompt text, and
 * [promptText] is present only when the user turned on "keep prompt text" before the send. [seqAtSend] is herdr's
 * `state_change_seq` for the agent when it was last read before the send: a later read with another value is a state
 * change observed after the send, and the same value five seconds on is "no progress observed".
 * [code] is herdr's error code for [OperationOutcome.Rejected] and the reason for [OperationOutcome.NotSent].
 * [resolvedAt] is set once the user re-read the terminal after an [OperationOutcome.Unknown]; the outcome itself stays.
 */
@Serializable
data class OperationRecord(
    val id: Long,
    val host: String,
    val session: String,
    val terminalId: String,
    val epoch: Long,
    val kind: OperationKind,
    val requestedAt: Long,
    val outcome: OperationOutcome = OperationOutcome.Requested,
    val paneIdAtSend: String? = null,
    val payloadSha256: String? = null,
    val promptText: String? = null,
    val seqAtSend: Long? = null,
    val sentAt: Long? = null,
    val code: String? = null,
    val resolvedAt: Long? = null,
    val note: String = "",
) {
    val inFlight get() = outcome == OperationOutcome.Requested || outcome == OperationOutcome.Sent
    val awaitsReread get() = outcome == OperationOutcome.Unknown && resolvedAt == null
    fun sameTerminal(other: TerminalKey) = host == other.target.host.value && session == other.target.session && terminalId == other.target.terminalId
}

@Serializable
data class JournalData(val nextId: Long = 1, val records: List<OperationRecord> = emptyList())

/** The journal row could not be made durable, so nothing was sent: the write-ahead rule holds only if the row is on disk. */
class JournalWriteFailed(cause: Throwable) : Exception("the operation journal could not be written: ${cause.message}", cause)

/** An illegal move between outcomes, which is a bug in the caller and never a host fact. */
class IllegalOutcomeTransition(val id: Long, val from: OperationOutcome, val to: OperationOutcome) :
    IllegalStateException("operation $id cannot go from $from to $to")

/** What [OperationJournal.begin] decided. Only [Started] means a row was written and the caller may go on. */
sealed interface Begin {
    data class Started(val record: OperationRecord) : Begin
    /** A second operation on a terminal that has one running: refused with the first's status. */
    data class InFlight(val first: OperationRecord) : Begin
    /** The terminal has an unknown outcome the user has not re-read yet: nothing new goes out until they do. */
    data class NeedsReread(val unknown: OperationRecord) : Begin
}

/**
 * The record of what the phone asked of a terminal, written before it asks. It never replays anything: a row whose
 * outcome was lost with the link stays [OperationOutcome.Unknown] until the user re-reads, and is never deleted by the
 * app. Writes are synchronous and durable; a [begin] or [markSent] that cannot reach the disk throws
 * [JournalWriteFailed] with nothing sent, while later moves keep memory right and retry on the next write, because the
 * disk already holds the more cautious state.
 *
 * On start, a [OperationOutcome.Requested] row (the process died before any write) becomes [OperationOutcome.NotSent]
 * and a [OperationOutcome.Sent] row (it died with a write possibly out) becomes [OperationOutcome.Unknown].
 */
class OperationJournal(private val store: JournalStore, private val clock: Clock) {
    private val lock = Any()
    private var data: JournalData
    private val _records = MutableStateFlow<List<OperationRecord>>(emptyList())

    /** Every row, oldest first. */
    val records: StateFlow<List<OperationRecord>> = _records.asStateFlow()

    /** True when the last best-effort write failed. Memory is still right. */
    @Volatile var saveFailed: Boolean = false; private set

    init {
        val loaded = store.load()
        val recovered = recover(loaded)
        data = pruned(recovered)
        _records.value = data.records
        if (recovered != loaded || data != recovered) persistQuietly(data)
    }

    /**
     * Writes a [OperationOutcome.Requested] row unless the terminal is busy. [payload] is hashed; the text is kept
     * only when [keepText] is true.
     */
    fun begin(key: TerminalKey, kind: OperationKind, payload: String? = null, keepText: Boolean = false, seqAtSend: Long? = null): Begin = synchronized(lock) {
        data.records.firstOrNull { it.sameTerminal(key) && it.inFlight }?.let { return Begin.InFlight(it) }
        data.records.firstOrNull { it.sameTerminal(key) && it.awaitsReread }?.let { return Begin.NeedsReread(it) }
        val row = OperationRecord(
            id = data.nextId, host = key.target.host.value, session = key.target.session, terminalId = key.target.terminalId, epoch = key.epoch,
            kind = kind, requestedAt = clock.nowMillis(),
            payloadSha256 = payload?.let(::sha256Hex), promptText = payload?.takeIf { keepText }, seqAtSend = seqAtSend,
        )
        commit(data.copy(nextId = data.nextId + 1, records = data.records + row), mustPersist = true)
        Begin.Started(row)
    }

    /** The write is about to start: the row says so first. Throws [JournalWriteFailed] when it cannot, and then nothing is written. */
    fun markSent(id: Long, paneId: String): OperationRecord = move(id, OperationOutcome.Sent, mustPersist = true) { it.copy(paneIdAtSend = paneId, sentAt = clock.nowMillis()) }

    /** Refused before the write (not ready, stale pane, host unreachable). Certain: nothing reached the host. */
    fun notSent(id: Long, code: String, note: String = ""): OperationRecord = move(id, OperationOutcome.NotSent, mustPersist = false) { it.copy(code = code, note = note.take(MAX_NOTE)) }

    fun acknowledged(id: Long): OperationRecord = move(id, OperationOutcome.Acknowledged, mustPersist = false) { it }

    /** herdr answered with an error. The code is herdr's. */
    fun rejected(id: Long, code: String, note: String = ""): OperationRecord = move(id, OperationOutcome.Rejected, mustPersist = false) { it.copy(code = code, note = note.take(MAX_NOTE)) }

    /** The request was written and the answer was lost with the link. */
    fun unknown(id: Long, note: String = ""): OperationRecord = move(id, OperationOutcome.Unknown, mustPersist = false) { it.copy(note = note.take(MAX_NOTE)) }

    /** The user re-read the terminal after an unknown outcome. The row stays unknown; it no longer blocks the terminal. */
    fun resolve(id: Long, note: String = ""): OperationRecord = synchronized(lock) {
        val row = find(id)
        if (row.outcome != OperationOutcome.Unknown) throw IllegalOutcomeTransition(id, row.outcome, row.outcome)
        if (row.resolvedAt != null) return row
        val next = row.copy(resolvedAt = clock.nowMillis(), note = note.take(MAX_NOTE).ifEmpty { row.note })
        commit(replace(next), mustPersist = false)
        next
    }

    /** Rows for one terminal, oldest first. */
    fun forTerminal(key: TerminalKey): List<OperationRecord> = _records.value.filter { it.sameTerminal(key) }

    /** The unknown rows of a terminal that still wait for a re-read. */
    fun unresolvedUnknown(key: TerminalKey): List<OperationRecord> = forTerminal(key).filter { it.awaitsReread }

    fun get(id: Long): OperationRecord? = _records.value.firstOrNull { it.id == id }

    private fun move(id: Long, to: OperationOutcome, mustPersist: Boolean, edit: (OperationRecord) -> OperationRecord): OperationRecord = synchronized(lock) {
        val row = find(id)
        if (!allowed(row.outcome, to)) throw IllegalOutcomeTransition(id, row.outcome, to)
        val next = edit(row).copy(outcome = to)
        commit(replace(next), mustPersist)
        next
    }

    private fun find(id: Long) = data.records.firstOrNull { it.id == id } ?: throw NoSuchElementException("no operation $id")
    private fun replace(next: OperationRecord) = data.copy(records = data.records.map { if (it.id == next.id) next else it })

    private fun commit(next: JournalData, mustPersist: Boolean) {
        if (mustPersist) { try { store.save(next) } catch (e: IOException) { throw JournalWriteFailed(e) } }
        else persistQuietly(next)
        data = next
        _records.value = next.records
    }

    private fun persistQuietly(next: JournalData) {
        saveFailed = try { store.save(next); false } catch (_: IOException) { true }
    }

    private fun recover(d: JournalData) = d.copy(records = d.records.map {
        when (it.outcome) {
            OperationOutcome.Requested -> it.copy(outcome = OperationOutcome.NotSent, code = "app_restarted", note = "the app ended before anything was written")
            OperationOutcome.Sent -> it.copy(outcome = OperationOutcome.Unknown, note = "the app ended while the request may have been going out")
            else -> it
        }
    })

    /** Rows older than 30 days or beyond 5,000 go, oldest first; an unknown row, resolved or not, is never dropped. */
    private fun pruned(d: JournalData): JournalData {
        val now = clock.nowMillis()
        val keepers = d.records.filter { it.outcome == OperationOutcome.Unknown }.map { it.id }.toSet()
        val fresh = d.records.filter { it.id in keepers || now - it.requestedAt <= MAX_AGE_MILLIS }
        val removable = fresh.count { it.id !in keepers } - MAX_ROWS
        if (removable <= 0) return d.copy(records = fresh)
        var skip = removable
        return d.copy(records = fresh.filter { r -> if (r.id in keepers || skip <= 0) true else { skip--; false } })
    }

    companion object {
        const val MAX_NOTE = 160
        const val MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        const val MAX_ROWS = 5_000

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun allowed(from: OperationOutcome, to: OperationOutcome) = when (from) {
            OperationOutcome.Requested -> to == OperationOutcome.NotSent || to == OperationOutcome.Sent
            OperationOutcome.Sent -> to == OperationOutcome.Acknowledged || to == OperationOutcome.Rejected || to == OperationOutcome.Unknown
            else -> false
        }
    }
}
