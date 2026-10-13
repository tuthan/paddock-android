package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.Subject
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where a send of a shared file with no agent is (Phase 15, slice 11): nothing, the bytes going, or how it ended. A send and its result belong to the item
 * that was sent: [item] is its snapshot's id, and the chooser shows a state only while that item is the one waiting ([forItem]).
 */
sealed interface ShareSend {
    /** The snapshot id of the shared file this state is about; none when nothing is sent. */
    val item: String?

    data object Idle : ShareSend { override val item: String? get() = null }
    data class Sending(override val item: String, val name: String, val sent: Long, val total: Long) : ShareSend

    /** [path] is where the file is on the machine, only when it arrived: a refusal or a cut has none and says why in [message]. */
    data class Done(override val item: String, val ok: Boolean, val message: String, val path: String? = null) : ShareSend
}

/** This state when it is about [waiting], the item the chooser shows; null otherwise, so an earlier share's send or result never shows under a newer one. */
fun ShareSend.forItem(waiting: SharedItem?): ShareSend? = takeIf { it.item != null && it.item == (waiting as? SharedItem.File)?.snapshot?.id }

object ShareSendCopy {
    const val GONE = "The file is no longer on this phone."
    fun refusedName(matched: String) = "Paddock does not put a file called $matched on a machine, where an agent could take it for its own instructions. Nothing was sent."
    const val SENT = "The file is on the machine at:"
}

/**
 * Sends a shared file to a folder's inbox or to the machine's drop folder, with no agent and no prompt (X10, X13). It runs in the graph's scope, so turning the phone does
 * not cut a send; one send at a time. The row is an `Attachment` of the host profile's subject ([Subject.drop]), which holds no terminal and never holds a later send.
 *
 * A drop writes under the folder the machine resolved ([FileTransfers.resolveDrop] answers the real path or its own refusal), under [FileNames.dropName]'s name of the
 * sender's name: an instruction file (`CLAUDE.md`, `AGENTS.md`, `.mcp.json`...) is refused before a byte leaves. A name that is already there is retried as `name-2`, `name-3`…
 * up to [MAX_TRIES]: the script never overwrites. A folder send keeps the snapshot's timestamped name, which cannot collide.
 *
 * Each send is a run for one item. Only the run in hand writes [state]: once it is cancelled, a read that was already under way, and anything it says after,
 * changes nothing, so a cancelled send's progress or result never lands on the next send. [cancel] and [clear] name the item they are about.
 */
class ShareSender(
    private val scope: CoroutineScope,
    /** The bytes of a snapshot by its id, or null when it is gone: `IncomingFiles::source`. */
    private val sourceOf: (String) -> ByteSource?,
    private val presenter: OperationPresenter = OperationPresenter(),
    private val maxBytes: Long = TransferLimits.MAX_PUT_BYTES,
    private val deadline: (Long) -> Duration = { TransferLimits.deadline(it, Direction.ToMachine) },
) {
    private val _state = MutableStateFlow<ShareSend>(ShareSend.Idle)
    val state: StateFlow<ShareSend> = _state.asStateFlow()

    private val lock = Any()

    // Guarded by [lock]: the latest run's job and the item it sends, and the number of the run whose writes reach [state].
    private var job: Job? = null
    private var runItem: String? = null
    private var run = 0L

    val busy: Boolean get() = synchronized(lock) { job?.isActive == true }

    private fun subjectKey(profile: HostProfileId, session: String) = TerminalKey(TargetRef(profile, session, Subject.drop(profile.value)), epoch = 0)

    /** One send's way to [state]: a write lands only while this is still the run in hand. */
    private inner class Run(val id: Long, val item: String) {
        fun post(s: ShareSend) = synchronized(lock) { if (run == id) _state.value = s }
        fun progress(n: Long) = synchronized(lock) { if (run == id) _state.update { if (it is ShareSend.Sending) it.copy(sent = n) else it } }
    }

    private fun start(item: String, body: suspend Run.() -> Unit) {
        synchronized(lock) {
            if (job?.isActive == true) return
            val r = Run(++run, item)
            runItem = item
            job = scope.launch { r.body() }
        }
    }

    /** [folder] is an absolute folder herdr knows; the file goes to its `.paddock/inbox/` under the snapshot's own name. */
    fun toFolder(transfers: FileTransfers, profile: HostProfileId, session: String, folder: String, snapshot: Snapshot) = start(snapshot.id) {
        val source = sourceOf(snapshot.id) ?: return@start post(ShareSend.Done(item, false, ShareSendCopy.GONE))
        post(ShareSend.Sending(item, snapshot.name, 0, snapshot.size))
        post(finish(source) { counting ->
            val r = transfers.put(subjectKey(profile, session), folder, snapshot.name, counting, snapshot.size, snapshot.sha256, maxBytes, deadline(snapshot.size))
            // The value of a put is the path under the folder (`.paddock/inbox/<name>`); the person is told the whole path.
            r to if (r is OperationResult.Acknowledged<String>) "${folder.trimEnd('/')}/${r.value}" else null
        })
    }

    /** [value] is the drop folder as the profile holds it (`~/Paddock`); the machine resolves it. [senderName] is the sender's own name for the file. */
    fun toDrop(transfers: FileTransfers, profile: HostProfileId, session: String, value: String, senderName: String?, snapshot: Snapshot) = start(snapshot.id) {
        val shared = senderName ?: snapshot.name.removePrefix(snapshot.name.take(STAMP_CHARS).takeIf { STAMP.matches(it) } ?: "")
        val base = when (val n = FileNames.dropName(shared)) {
            is DropName.Refused -> return@start post(ShareSend.Done(item, false, ShareSendCopy.refusedName(n.matched)))
            is DropName.Accepted -> n.name
        }
        val source = sourceOf(snapshot.id) ?: return@start post(ShareSend.Done(item, false, ShareSendCopy.GONE))
        post(ShareSend.Sending(item, base, 0, snapshot.size))
        val real = when (val r = transfers.resolveDrop(value)) {
            is TransferReply.Ok -> r.value
            is TransferReply.Refused -> return@start post(ShareSend.Done(item, false, r.refusal.sentence))
            is TransferReply.Failed -> return@start post(ShareSend.Done(item, false, "Could not reach the machine to check the folder: ${r.message}."))
        }
        post(finish(source) { counting ->
            var result: OperationResult<String>? = null
            var name = base
            for (n in 1..MAX_TRIES) {
                if (n > 1) name = FileNames.retryName(base, n)
                val r = transfers.drop(profile, real, name, counting, snapshot.size, snapshot.sha256, maxBytes, deadline(snapshot.size))
                result = r
                // The script never overwrites: a name that is taken is the only refusal worth another try, under the next name.
                if (!(r is OperationResult.Rejected && r.code == TransferRefusal.Exists.code)) break
            }
            val r = result!!
            r to if (r is OperationResult.Acknowledged<String>) r.value else null
        })
    }

    /** Runs [attempt] with the source counted for this run's progress and turns what it came to into the state shown. */
    private suspend fun Run.finish(source: ByteSource, attempt: suspend (ByteSource) -> Pair<OperationResult<String>, String?>): ShareSend {
        val (result, path) = attempt(CountingSource(source, this::progress))
        return if (result is OperationResult.Acknowledged<String> && path != null) ShareSend.Done(item, true, ShareSendCopy.SENT, path)
        else ShareSend.Done(item, false, presenter.line(OperationKind.Attachment, result).text)
    }

    /**
     * Stops the send of [item], if it is the one running: the row ends Unknown (a part may be on the machine), which holds nothing. What it said is forgotten,
     * and nothing it says later is heard. Another item's send is not touched.
     */
    fun cancel(item: String) {
        synchronized(lock) {
            if (runItem == item) {
                job?.cancel()
                job = null
                runItem = null
                run++
            }
            if (_state.value.item == item) _state.value = ShareSend.Idle
        }
    }

    /** [item]'s result was read. A running send, or another item's result, is not touched. */
    fun clear(item: String) {
        synchronized(lock) {
            val s = _state.value
            if (s is ShareSend.Done && s.item == item) _state.value = ShareSend.Idle
        }
    }

    /**
     * Keeps the send to the item waiting in [pending]: a send whose file is no longer the one waiting (a newer share took its place, or it was used or
     * dismissed) is stopped and its result forgotten, so neither shows under another item nor acts on it, and its snapshot, which the inbox deletes with
     * it, is not opened again. Runs until the caller's scope ends.
     */
    suspend fun follow(pending: Flow<SharedItem?>) {
        combine(pending, state) { waiting, s -> (waiting as? SharedItem.File)?.snapshot?.id to s.item }
            .distinctUntilChanged()
            .collect { (waiting, _) ->
                synchronized(lock) { listOfNotNull(runItem, _state.value.item).distinct().filter { it != waiting }.forEach { cancel(it) } }
            }
    }

    companion object {
        const val MAX_TRIES = 5
        private const val STAMP_CHARS = 18 // `2026-10-11-143205-`
        private val STAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-")
    }
}
