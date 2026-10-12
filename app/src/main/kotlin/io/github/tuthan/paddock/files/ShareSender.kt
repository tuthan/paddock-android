package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.Subject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a send of a shared file with no agent is (Phase 15, slice 11): nothing, the bytes going, or how it ended. */
sealed interface ShareSend {
    data object Idle : ShareSend
    data class Sending(val name: String, val sent: Long, val total: Long) : ShareSend

    /** [path] is where the file is on the machine, only when it arrived: a refusal or a cut has none and says why in [message]. */
    data class Done(val ok: Boolean, val message: String, val path: String? = null) : ShareSend
}

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
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    private fun subjectKey(profile: HostProfileId, session: String) = TerminalKey(TargetRef(profile, session, Subject.drop(profile.value)), epoch = 0)

    /** [folder] is an absolute folder herdr knows; the file goes to its `.paddock/inbox/` under the snapshot's own name. */
    fun toFolder(transfers: FileTransfers, profile: HostProfileId, session: String, folder: String, snapshot: Snapshot) {
        if (busy) return
        job = scope.launch {
            val source = sourceOf(snapshot.id) ?: run { _state.value = ShareSend.Done(false, ShareSendCopy.GONE); return@launch }
            _state.value = ShareSend.Sending(snapshot.name, 0, snapshot.size)
            _state.value = finish(snapshot) { counting ->
                val r = transfers.put(subjectKey(profile, session), folder, snapshot.name, counting, snapshot.size, snapshot.sha256, maxBytes, deadline(snapshot.size))
                // The value of a put is the path under the folder (`.paddock/inbox/<name>`); the person is told the whole path.
                r to if (r is OperationResult.Acknowledged<String>) "${folder.trimEnd('/')}/${r.value}" else null
            }(source)
        }
    }

    /** [value] is the drop folder as the profile holds it (`~/Paddock`); the machine resolves it. [senderName] is the sender's own name for the file. */
    fun toDrop(transfers: FileTransfers, profile: HostProfileId, session: String, value: String, senderName: String?, snapshot: Snapshot) {
        if (busy) return
        job = scope.launch {
            val shared = senderName ?: snapshot.name.removePrefix(snapshot.name.take(STAMP_CHARS).takeIf { STAMP.matches(it) } ?: "")
            val base = when (val n = FileNames.dropName(shared)) {
                is DropName.Refused -> { _state.value = ShareSend.Done(false, ShareSendCopy.refusedName(n.matched)); return@launch }
                is DropName.Accepted -> n.name
            }
            val source = sourceOf(snapshot.id) ?: run { _state.value = ShareSend.Done(false, ShareSendCopy.GONE); return@launch }
            _state.value = ShareSend.Sending(base, 0, snapshot.size)
            val real = when (val r = transfers.resolveDrop(value)) {
                is TransferReply.Ok -> r.value
                is TransferReply.Refused -> { _state.value = ShareSend.Done(false, r.refusal.sentence); return@launch }
                is TransferReply.Failed -> { _state.value = ShareSend.Done(false, "Could not reach the machine to check the folder: ${r.message}."); return@launch }
            }
            _state.value = finish(snapshot) { counting ->
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
            }(source)
        }
    }

    /** Runs [attempt] with the source counted for progress and turns what it came to into the state shown. */
    private fun finish(snapshot: Snapshot, attempt: suspend (ByteSource) -> Pair<OperationResult<String>, String?>): suspend (ByteSource) -> ShareSend = { source ->
        val counting = CountingSource(source) { n -> _state.update { if (it is ShareSend.Sending) it.copy(sent = n) else it } }
        val (result, path) = try { attempt(counting) } catch (e: CancellationException) { throw e }
        if (result is OperationResult.Acknowledged<String> && path != null) ShareSend.Done(true, ShareSendCopy.SENT, path)
        else ShareSend.Done(false, presenter.line(OperationKind.Attachment, result).text)
    }

    /** Stops the send in progress: the row ends Unknown (a part may be on the machine), which holds nothing. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ShareSend.Idle
    }

    /** The result was read. */
    fun clear() { if (!busy) _state.value = ShareSend.Idle }

    companion object {
        const val MAX_TRIES = 5
        private const val STAMP_CHARS = 18 // `2026-10-11-143205-`
        private val STAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}-")
    }
}
