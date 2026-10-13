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
    /** Phase 08: the phone's Yes or No for one agent permission request, written by `paddock-decide.py`; never a key. */
    Allow("Yes"),
    Deny("No"),
    /** Phase 09: operations on a session, a workspace, a tab or a saga rather than on one terminal; see [Subject]. */
    SessionStop("session stop"),
    SessionDelete("session delete"),
    Rename("rename"),
    FocusWorkspace("workspace focus"),
    FocusTab("tab focus"),
    WorktreeCreate("worktree create"),
    TabCreate("tab create"),
    AgentStart("agent start"),
    CloseWorkspace("workspace close"),
    CloseTab("tab close"),
    ClosePane("pane close"),
    /**
     * Phase 15: one file written to the machine (an agent's `.paddock/inbox/` or the drop folder). Written before the first byte, with the
     * destination path, the size and the file's SHA-256. A cut one is Unknown, which holds only a prompt that would name the file
     * ([OperationRecord.awaitsInboxCheck]), never the terminal's keys or focus, and is settled by reading the inbox.
     */
    Attachment("attachment"),
    /** Phase 15: a file that arrived from the machine, recorded when it is whole. Never Requested, Sent or Unknown; see [OperationJournal.recordFetch]. */
    Fetch("fetch"),
}

/**
 * What a Phase 09 row is about when it is not one terminal. The journal's identity is host, session and a string that names
 * the thing; for a terminal that string is its terminal id (`term_…`, never containing a colon before its digits), and for
 * these it is a prefixed token, so the one in-flight and unknown-outcome rule covers them all: a second stop of the same
 * session waits for the first, and an unknown `session:x` blocks `session:x` until it is looked at.
 */
object Subject {
    fun session(name: String) = "session:$name"
    fun workspace(id: String) = "workspace:$id"
    fun tab(id: String) = "tab:$id"
    fun pane(id: String) = "pane:$id"
    /** One start-agent saga: its steps run one after another, so one subject serializes them. */
    fun saga(id: String) = "saga:$id"
    /** A file dropped in a machine's drop folder: no terminal and no session, one subject per host profile, so two drops there never overlap. */
    fun drop(profileId: String) = "drop:$profileId"
    private val PREFIXES = listOf("session:", "workspace:", "tab:", "pane:", "saga:", "drop:")
    fun isSubject(terminalId: String) = PREFIXES.any { terminalId.startsWith(it) }
    /** The part after the prefix: the session name, the workspace id, and so on. Null for a terminal id. */
    fun name(terminalId: String): String? = PREFIXES.firstOrNull { terminalId.startsWith(it) }?.let { terminalId.removePrefix(it) }
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
 * [requestId] is the hook-minted id of the permission request an [OperationKind.Allow] or [OperationKind.Deny] answered, so
 * a lost answer can be settled later from the host's request files; it is null for every other kind.
 * [path] and [size] describe the file of an [OperationKind.Attachment] or [OperationKind.Fetch] (where it was written or read, and how long it is;
 * its SHA-256 is [payloadSha256]); they are never the content. On a prompt that carries an attachment they are the file its phrase names, so the
 * draft can tell which file went. [textSha256] is, on a prompt that carries an attachment, the hash of the user's
 * own text without the phrase line before it (X12): the draft compares that one, because [payloadSha256] covers the phrase too.
 * [inboxSize] is what reading the inbox found for a cut attachment: the size of the file under [path], null when none was there or it was not read.
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
    val requestId: String? = null,
    val path: String? = null,
    val size: Long? = null,
    val textSha256: String? = null,
    val inboxSize: Long? = null,
) {
    val inFlight get() = outcome == OperationOutcome.Requested || outcome == OperationOutcome.Sent
    /** True when the row is about a terminal (an agent); false for a session, workspace, tab, pane, saga or drop row (Phase 09, Phase 15). */
    val aboutTerminal get() = !Subject.isSubject(terminalId)
    /**
     * An unknown outcome the user has not re-read the terminal for. An [OperationKind.Attachment] is left out (X9): what it leaves unknown is whether
     * a file is on the machine, which a re-read of the agent cannot say, and it holds nothing but the prompt that would name the file.
     */
    val awaitsReread get() = outcome == OperationOutcome.Unknown && resolvedAt == null && kind != OperationKind.Attachment
    /** A cut [OperationKind.Attachment] whose inbox has not been read since: it holds only the prompt that would name the file ([ComposerRules.gate]). */
    val awaitsInboxCheck get() = kind == OperationKind.Attachment && outcome == OperationOutcome.Unknown && resolvedAt == null
    /** The hash the draft compares when this prompt is accepted: the user's own text when the row says it, else the whole payload (X12). */
    val userTextSha256 get() = textSha256 ?: payloadSha256
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
    /** The saved journal cannot be read, so no earlier outcome can be ruled out: nothing goes out until it is restored or reset. */
    data class Unreadable(val reason: String) : Begin
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
 *
 * A saved journal that cannot be read is not an empty one: the unknown rows in it are what stops a second prompt after a
 * lost answer. The journal then holds nothing, writes nothing (the file is never overwritten) and [begin] answers
 * [Begin.Unreadable] until [retryLoad] reads it or [resetUnreadable], the user's explicit choice, sets it aside.
 */
class OperationJournal(private val store: JournalStore, private val clock: Clock) {
    private val lock = Any()
    private var data: JournalData
    private val _records = MutableStateFlow<List<OperationRecord>>(emptyList())
    private val _unreadable = MutableStateFlow<String?>(null)

    /** Every row, oldest first. */
    val records: StateFlow<List<OperationRecord>> = _records.asStateFlow()

    /** Why the saved journal cannot be read, or null when it can. While set, nothing is sent. */
    val unreadable: StateFlow<String?> = _unreadable.asStateFlow()

    /** True when the last best-effort write failed. Memory is still right. */
    @Volatile var saveFailed: Boolean = false; private set

    init {
        val loaded = try { store.load() } catch (e: JournalUnreadable) { _unreadable.value = e.message ?: "the saved journal is unreadable"; null }
        data = if (loaded == null) JournalData() else adopt(loaded)
    }

    /** Recovers and prunes a journal just read, shows it, and saves it back when that changed anything. */
    private fun adopt(loaded: JournalData): JournalData {
        val recovered = recover(loaded)
        val kept = pruned(recovered)
        _records.value = kept.records
        if (recovered != loaded || kept != recovered) persistQuietly(kept)
        return kept
    }

    /** Reads the saved journal again after it was unreadable. True when it is readable now (or never was unreadable). */
    fun retryLoad(): Boolean = synchronized(lock) {
        if (_unreadable.value == null) return true
        try {
            data = adopt(store.load())
            _unreadable.value = null
            true
        } catch (e: JournalUnreadable) {
            _unreadable.value = e.message ?: "the saved journal is unreadable"
            false
        }
    }

    /**
     * The user's explicit reset of an unreadable journal: the saved file is kept aside, the journal starts empty and sends
     * work again. Every unknown outcome in the lost file is forgotten with it, which is why only a person calls this.
     * Throws an [java.io.IOException] and stays unreadable when the file cannot be set aside.
     */
    fun resetUnreadable(): Unit = synchronized(lock) {
        if (_unreadable.value == null) return
        store.discardUnreadable()
        data = JournalData()
        _records.value = emptyList()
        _unreadable.value = null
    }

    /**
     * Writes a [OperationOutcome.Requested] row unless the terminal is busy. [payload] is hashed; the text is kept
     * only when [keepText] is true. A prompt that carries an attachment passes [textSha256], the hash of the user's own text (X12).
     * An [OperationKind.Attachment] passes [path], [size] and [fileSha256] instead of a payload: the hash of what leaves is the file's.
     * A second attachment to a terminal waits for the inbox of a cut one to be read ([OperationRecord.awaitsInboxCheck]); nothing else does, and a file sent with no agent never waits for one.
     */
    fun begin(
        key: TerminalKey, kind: OperationKind, payload: String? = null, keepText: Boolean = false, requestId: String? = null,
        textSha256: String? = null, path: String? = null, size: Long? = null, fileSha256: String? = null,
    ): Begin = synchronized(lock) {
        _unreadable.value?.let { return Begin.Unreadable(it) }
        data.records.firstOrNull { it.sameTerminal(key) && it.inFlight }?.let { return Begin.InFlight(it) }
        data.records.firstOrNull { it.sameTerminal(key) && it.awaitsReread }?.let { return Begin.NeedsReread(it) }
        // A cut file holds the prompt that would name it (X9), so only a terminal's next attachment waits for its inbox to be read. A file sent with no agent (a drop, or a
        // folder's inbox) has no prompt to hold and no screen to read its folder from: its Unknown row stays in the Activity log and never blocks the next send.
        if (kind == OperationKind.Attachment && !Subject.isSubject(key.target.terminalId)) data.records.firstOrNull { it.sameTerminal(key) && it.awaitsInboxCheck }?.let { return Begin.NeedsReread(it) }
        val row = OperationRecord(
            id = data.nextId, host = key.target.host.value, session = key.target.session, terminalId = key.target.terminalId, epoch = key.epoch,
            kind = kind, requestedAt = clock.nowMillis(),
            payloadSha256 = fileSha256 ?: payload?.let(::sha256Hex), promptText = payload?.takeIf { keepText }, requestId = requestId,
            path = path, size = size, textSha256 = textSha256,
        )
        commit(data.copy(nextId = data.nextId + 1, records = data.records + row), mustPersist = true)
        Begin.Started(row)
    }

    /**
     * A file arrived from the machine, whole and counted: one finished row (Acknowledged, resolved, never in flight and never Unknown), written
     * without [begin] because there is nothing to hold back: it never blocks the terminal, whatever else is running. [sha256] is the received
     * file's. Best effort like the later moves; returns [NOT_RECORDED] when the saved journal is unreadable (nothing may be written over it).
     */
    fun recordFetch(key: TerminalKey, path: String, size: Long, sha256: String): Long = synchronized(lock) {
        if (_unreadable.value != null) return NOT_RECORDED
        val now = clock.nowMillis()
        val row = OperationRecord(
            id = data.nextId, host = key.target.host.value, session = key.target.session, terminalId = key.target.terminalId, epoch = key.epoch,
            kind = OperationKind.Fetch, requestedAt = now, outcome = OperationOutcome.Acknowledged, sentAt = now, resolvedAt = now,
            payloadSha256 = sha256, path = path, size = size,
        )
        commit(data.copy(nextId = data.nextId + 1, records = data.records + row), mustPersist = false)
        row.id
    }

    /**
     * The write is about to start: the row says so first. [seqAtSend] is the agent's `state_change_seq` in the read that
     * allowed the send; [paneId] is null for what is not written to a pane (a file). Throws [JournalWriteFailed] when it cannot,
     * and then nothing is written.
     */
    fun markSent(id: Long, paneId: String? = null, seqAtSend: Long? = null): OperationRecord =
        move(id, OperationOutcome.Sent, mustPersist = true) { it.copy(paneIdAtSend = paneId, seqAtSend = seqAtSend, sentAt = clock.nowMillis()) }

    /** Refused before the write (not ready, stale pane, host unreachable). Certain: nothing reached the host. */
    fun notSent(id: Long, code: String, note: String = ""): OperationRecord = move(id, OperationOutcome.NotSent, mustPersist = false) { it.copy(code = code, note = note.take(MAX_NOTE)) }

    fun acknowledged(id: Long): OperationRecord = move(id, OperationOutcome.Acknowledged, mustPersist = false) { it }

    /** herdr answered with an error. The code is herdr's. */
    fun rejected(id: Long, code: String, note: String = ""): OperationRecord = move(id, OperationOutcome.Rejected, mustPersist = false) { it.copy(code = code, note = note.take(MAX_NOTE)) }

    /** The request was written and the answer was lost with the link. */
    fun unknown(id: Long, note: String = ""): OperationRecord = move(id, OperationOutcome.Unknown, mustPersist = false) { it.copy(note = note.take(MAX_NOTE)) }

    /**
     * The user re-read the terminal after an unknown outcome. The row stays unknown; it no longer blocks the terminal. A cut attachment is settled by
     * reading its inbox instead, and [inboxSize] is the size of the file found there, if one was ([OperationRecord.inboxSize]).
     */
    fun resolve(id: Long, note: String = "", inboxSize: Long? = null): OperationRecord = synchronized(lock) {
        val row = find(id)
        if (row.outcome != OperationOutcome.Unknown) throw IllegalOutcomeTransition(id, row.outcome, row.outcome)
        if (row.resolvedAt != null) return row
        val next = row.copy(resolvedAt = clock.nowMillis(), note = note.take(MAX_NOTE).ifEmpty { row.note }, inboxSize = inboxSize)
        commit(replace(next), mustPersist = false)
        next
    }

    /** Rows for one terminal, oldest first. */
    fun forTerminal(key: TerminalKey): List<OperationRecord> = _records.value.filter { it.sameTerminal(key) }

    /** The unknown rows of a terminal that still wait for a re-read. */
    fun unresolvedUnknown(key: TerminalKey): List<OperationRecord> = forTerminal(key).filter { it.awaitsReread }

    /** The cut attachments of a terminal whose inbox has not been read since (X9). A re-read of the agent does not free these. */
    fun unresolvedAttachments(key: TerminalKey): List<OperationRecord> = forTerminal(key).filter { it.awaitsInboxCheck }

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

    private fun commit(state: JournalData, mustPersist: Boolean) {
        check(_unreadable.value == null) { "the saved journal is unreadable; nothing may be written over it" }
        // Retention is a property of what is kept, not of the next load: a process that stays up for weeks must not grow past the cap, and an old kept prompt must not outlive its age.
        val next = pruned(state)
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

    /** Rows older than 30 days or beyond 5,000 go, oldest first; an unknown row, resolved or not, and a row still in flight are never dropped. */
    private fun pruned(d: JournalData): JournalData {
        val now = clock.nowMillis()
        val keepers = d.records.filter { it.outcome == OperationOutcome.Unknown || it.inFlight }.map { it.id }.toSet()
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
        /** What [recordFetch] returns when it wrote nothing. */
        const val NOT_RECORDED = -1L

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun allowed(from: OperationOutcome, to: OperationOutcome) = when (from) {
            OperationOutcome.Requested -> to == OperationOutcome.NotSent || to == OperationOutcome.Sent
            OperationOutcome.Sent -> to == OperationOutcome.Acknowledged || to == OperationOutcome.Rejected || to == OperationOutcome.Unknown
            else -> false
        }
    }
}
