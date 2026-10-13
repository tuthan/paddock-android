package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.files.AttachmentPhrase
import io.github.tuthan.paddock.files.ByteSource
import io.github.tuthan.paddock.files.FileTransfers
import io.github.tuthan.paddock.files.Snapshot
import io.github.tuthan.paddock.files.folder
import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TerminalKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the last operation on a terminal ended, kept until the user dismisses it or starts another. */
data class SendOutcome(val kind: OperationKind, val result: OperationResult<*>)

/**
 * Runs the phone's operations in the host's scope, so turning the phone or leaving the screen never cancels a send that
 * is already on its way (a cancellation after the write would leave it Unknown for nothing). One call per terminal at a
 * time: a second tap while one runs is ignored here, and the journal refuses it too. The newest outcome per terminal is
 * kept for the screen to show.
 */
class SendController(
    private val scope: CoroutineScope,
    private val ops: AgentOperations,
    /** Phase 15: present only where files can be sent; [promptWithAttachment] needs it. */
    private val transfers: FileTransfers? = null,
    private val maxFileBytes: Long = FileTransfers.PROVISIONAL_MAX_BYTES,
    /** How long a put may run, by the file's size in bytes: the app passes the size-scaled rule (spike S2); this default is the provisional flat one. */
    private val transferDeadline: (Long) -> Duration = { FileTransfers.PROVISIONAL_DEADLINE },
) {
    private val _outcomes = MutableStateFlow<Map<String, SendOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<String, SendOutcome>> = _outcomes.asStateFlow()

    /** The job of the call running for each terminal id, for [cancel]; an entry is dropped when its call ends. */
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** Terminal ids with a call from this phone still running. */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    private val _rereads = MutableStateFlow<Map<String, RereadOutcome>>(emptyMap())
    /** The newest re-read per terminal, for the screen to show; a new operation on the terminal clears it. */
    val rereads: StateFlow<Map<String, RereadOutcome>> = _rereads.asStateFlow()

    fun prompt(key: TerminalKey, text: String, keepText: Boolean) { start(key, OperationKind.Prompt) { ops.prompt(key, text, keepText) } }

    /**
     * A prompt with a file (X1, X9, X12): the snapshot [file] is written to the agent's `.paddock/inbox/` first, and only when the machine says it arrived
     * whole is the prompt sent, with [AttachmentPhrase]'s line before [text] and the hash of [text] alone on the row for the draft. A refused, cut or
     * not-sent put sends no prompt: the outcome shown is the put's (kind [OperationKind.Attachment]), and a cut one is Unknown until the inbox is read.
     * [text] may be empty (a photo with no words). The agent's folder is [folder] when the composer has it, else a fresh read of the agent's.
     * A put that succeeded stays in the inbox if the prompt is then refused. Sending the same snapshot again writes nothing when the journal shows that
     * file already there, and only the prompt goes; a name another file holds is passed over for `-2` and on ([FileTransfers.attach]). The prompt
     * names the file as it is in the inbox, and its row keeps that path and the size, so the draft lets go of this file only.
     */
    fun promptWithAttachment(key: TerminalKey, text: String, file: Snapshot, source: ByteSource, keepText: Boolean, folder: String? = null) {
        val files = checkNotNull(transfers) { "this controller was built without file transfers" }
        // A line break after the phrase alone would be a second Enter for an agent without bracketed paste, so a photo with no words is just the phrase.
        fun promptNaming(relPath: String) = AttachmentPhrase.line(relPath) + if (text.isBlank()) "" else "\n$text"
        // Whichever name the inbox gives the file, the prompt that names it fits.
        require(FileTransfers.inboxNames(file.name).all { promptNaming(FileTransfers.inboxRelPath(it)).length <= AgentOperations.MAX_PROMPT_CHARS }) {
            "a prompt is at most ${AgentOperations.MAX_PROMPT_CHARS} characters"
        }
        startOutcome(key) {
            val where = folder ?: try { ops.read(key).agent.folder() }
                catch (e: StalePane) { return@startOutcome SendOutcome(OperationKind.Attachment, OperationResult.Stale(key)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { null }
            // No folder is refused by the put, journaled and said, like any other file the machine would not take.
            val put = files.attach(key, where.orEmpty(), file.name, source, file.size, file.sha256, maxFileBytes, transferDeadline(file.size))
            if (put !is OperationResult.Acknowledged) SendOutcome(OperationKind.Attachment, put)
            else SendOutcome(OperationKind.Prompt, ops.prompt(key, promptNaming(put.value), keepText, textSha256 = OperationJournal.sha256Hex(text), filePath = put.record.path, fileSize = file.size))
        }
    }
    fun sendKey(key: TerminalKey, kind: OperationKind) { start(key, kind) { ops.sendKey(key, kind) } }
    fun focus(key: TerminalKey) { start(key, OperationKind.Focus) { ops.focus(key) } }

    /**
     * The user chose Re-read for a terminal with an unknown outcome. It is one call per terminal like any other (a tap while
     * one is running is ignored), runs in the host's scope, and either frees the waiting rows or leaves them all waiting.
     */
    fun reread(key: TerminalKey) {
        val id = key.target.terminalId
        var accepted = false
        _running.update { if (id in it) it else { accepted = true; it + id } }
        if (!accepted) return
        _rereads.update { it - id }
        scope.launch {
            try {
                val outcome = try {
                    RereadOutcome.Done(ops.rereadAll(key))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StalePane) {
                    RereadOutcome.Failed(gone = true, detail = e.message.orEmpty())
                } catch (e: Exception) {
                    RereadOutcome.Failed(gone = false, detail = e.message ?: e::class.simpleName.orEmpty())
                }
                _rereads.update { it + (id to outcome) }
                // The row is freed: the unknown card from the send itself is out of date and would offer a re-read again.
                if (outcome is RereadOutcome.Done) _outcomes.update { if (it[id]?.result.let { r -> r is OperationResult.Unknown || r is OperationResult.NeedsReread }) it - id else it }
            } finally {
                _running.update { it - id }
            }
        }
    }

    /**
     * The user cancelled the call still running for [terminalId] (a file on its way, say). The call's own cleanup settles its row: a put that had begun
     * ends Unknown, with the cut in its note, until the inbox is read; one that had not ends NotSent. No outcome is kept for the cancelled call,
     * and nothing after the put (the prompt) runs. Nothing running is a no-op.
     */
    fun cancel(terminalId: String) { jobs[terminalId]?.cancel() }

    /** The screen showed the outcome and the user is done with it. */
    fun dismiss(terminalId: String) { _outcomes.update { it - terminalId } }

    fun dismissReread(terminalId: String) { _rereads.update { it - terminalId } }

    private fun start(key: TerminalKey, kind: OperationKind, call: suspend () -> OperationResult<*>) = startOutcome(key) { SendOutcome(kind, call()) }

    /** One call per terminal: [call] runs in the host's scope and its outcome is kept for the screen. */
    private fun startOutcome(key: TerminalKey, call: suspend () -> SendOutcome) {
        val id = key.target.terminalId
        var accepted = false
        _running.update { if (id in it) it else { accepted = true; it + id } }
        if (!accepted) return
        _outcomes.update { it - id }
        _rereads.update { it - id }
        // Started only once it is in [jobs] and its end is watched, so a call that ends at once (or is cancelled before it begins) still clears both.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = call()
            _outcomes.update { it + (id to outcome) }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job); _running.update { it - id } }
        job.start()
    }
}
