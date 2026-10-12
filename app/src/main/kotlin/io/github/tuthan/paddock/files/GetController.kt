package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.output.SafeText
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one file on the machine looked like when it was looked at (X2): the stat's answer, the folder it was judged against, and the names a person and the
 * cache see. [shownName] is the machine's name through [SafeText] (what the sheet shows, with [nameChanged] saying it was changed); [cachedName] is
 * [FileNames.cachedName]'s, which is the only name the phone ever writes (SEC-6).
 */
data class GetLook(
    val stat: RemoteStat, val folder: String, val shownName: String, val nameChanged: Boolean, val cachedName: String, val kind: OpenableTypes.Kind,
) {
    /** The folder the file is in, as a person reads it. */
    val shownFolder: String get() = SafeText.clean(stat.path.substringBeforeLast('/').ifEmpty { "/" })
}

/** Where a Get is, from the tap to the file in hand. */
sealed interface GetState {
    /** The stat is out: [shown] is what was tapped. */
    data class Looking(val shown: String) : GetState

    /** The stat answered; nothing has been read. */
    data class Offer(val look: GetLook) : GetState

    /** [read] of the stat's size has arrived. */
    data class Reading(val look: GetLook, val read: Long) : GetState

    /** The whole file is in the app's cache at [file]. [apk] is what an APK says about itself ([ApkNotes]); [note] is the last thing Save said. */
    data class Ready(val look: GetLook, val file: File, val apk: List<String> = emptyList(), val note: String? = null) : GetState

    /** Nothing was kept. [sentence] says why in a person's words. */
    data class Failed(val sentence: String) : GetState
}

/**
 * One Get at a time for one agent's screen (Phase 15, slice 12): a tap on a path or an outbox row looks at the file ([look]), the person decides ([get]), and
 * the whole file lands in the app's cache under a name of Paddock's own ([TransferCache.fetchFile]). It lives in a `Retained` holder so turning the phone
 * does not cancel a read, and is closed (its scope cancelled, an unfinished read's part deleted) when the screen goes for good.
 *
 * Nothing here reads a thing it was not asked to: the stat is the machine's judgement of the real path (X2), the read is capped and has a deadline that
 * scales with the size ([TransferLimits]), and the count that arrives must equal the stat's size ([FileTransfers.get]).
 */
class GetController(
    private val scope: CoroutineScope,
    private val transfers: FileTransfers,
    private val cache: TransferCache,
    private val machineId: String,
    /** What an APK in the cache says about itself; the default says nothing (a test or a build without it). */
    private val apk: (File) -> List<String> = { emptyList() },
    private val maxBytes: Long = TransferLimits.MAX_GET_BYTES,
    private val deadline: (Long) -> Duration = { TransferLimits.deadline(it, Direction.ToPhone) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow<GetState?>(null)
    val state: StateFlow<GetState?> = _state.asStateFlow()
    private var job: Job? = null
    private var key: TerminalKey? = null

    /** Whether a look or a read is running now: the next tap waits. */
    val busy: Boolean get() = job?.isActive == true

    /**
     * Looks at the file [token] names under [folder] (a path as the agent printed it, resolved as text first, [PathTokens.resolve]). [key] is the agent's, for
     * the journal row a finished read gets. A second call while one runs is ignored.
     */
    fun look(folder: String, key: TerminalKey, token: String) {
        if (busy) return
        val absolute = PathTokens.resolve(folder, token)
        if (absolute == null) { _state.value = GetState.Failed(NOT_UNDER_FOLDER); return }
        lookAt(folder, key, absolute, SafeText.clean(token).take(SHOWN))
    }

    /** Looks at [absolute], a path the app built itself (an outbox row), which is still judged by the machine. */
    fun lookAtPath(folder: String, key: TerminalKey, absolute: String) {
        if (busy) return
        lookAt(folder, key, absolute, SafeText.clean(absolute.substringAfterLast('/')).take(SHOWN))
    }

    private fun lookAt(folder: String, key: TerminalKey, absolute: String, shown: String) {
        this.key = key
        _state.value = GetState.Looking(shown)
        job = scope.launch {
            _state.value = when (val r = transfers.stat(folder, absolute, maxBytes)) {
                is TransferReply.Ok -> GetState.Offer(lookOf(folder, r.value))
                is TransferReply.Refused -> GetState.Failed(r.refusal.sentence)
                is TransferReply.Failed -> GetState.Failed("Could not look at the file: ${r.message}.")
            }
        }
    }

    /** Reads the file the sheet offers. Cancelled by [cancel]; a part never stays. */
    fun get() {
        val offer = (_state.value as? GetState.Offer)?.look ?: return
        val key = key ?: return
        if (busy) return
        _state.value = GetState.Reading(offer, 0)
        job = scope.launch {
            val id = newId()
            val dest = try { cache.fetchFile(machineId, id, offer.cachedName) } catch (_: IllegalArgumentException) {
                _state.value = GetState.Failed("Paddock cannot keep that file."); return@launch
            }
            try {
                val sink = ByteSink { CountingOutput(FileOutputStream(dest)) { n -> _state.update { if (it is GetState.Reading) GetState.Reading(offer, n) else it } } }
                when (val r = transfers.get(key, offer.folder, offer.stat.path, offer.stat.size, sink, maxBytes, deadline(offer.stat.size))) {
                    is GetResult.Fetched -> {
                        val facts = if (offer.kind == OpenableTypes.Kind.Apk) withContext(Dispatchers.IO) { apk(dest) } else emptyList()
                        _state.value = GetState.Ready(offer, dest, facts)
                    }
                    is GetResult.Refused -> { forget(id); _state.value = GetState.Failed(r.refusal.sentence) }
                    is GetResult.Failed -> { forget(id); _state.value = GetState.Failed("The file did not arrive: ${r.message}.") }
                }
            } catch (e: CancellationException) {
                forget(id)
                throw e
            } catch (e: Exception) {
                forget(id)
                _state.value = GetState.Failed("The file did not arrive: ${(e.message ?: e::class.simpleName).orEmpty().lineSequence().first().take(SHOWN)}.")
            }
        }
    }

    /** Stops a look or a read: the part is deleted and the sheet goes. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** The sheet is closed. A read in progress is cancelled with it. */
    fun dismiss() = cancel()

    /** What Save (or Share) said, kept on the ready sheet. */
    fun say(note: String) { (_state.value as? GetState.Ready)?.let { _state.value = it.copy(note = note) } }

    /** The screen is gone for good. */
    fun close() {
        job?.cancel()
        scope.cancel()
    }

    private fun lookOf(folder: String, stat: RemoteStat): GetLook {
        val raw = stat.path.substringAfterLast('/')
        val shown = SafeText.clean(raw)
        return GetLook(stat, folder, shown.take(SHOWN), nameChanged = shown != raw || shown.length > SHOWN, cachedName = FileNames.cachedName(raw), kind = OpenableTypes.kindOf(raw))
    }

    private suspend fun forget(id: String) = withContext(NonCancellable + Dispatchers.IO) { cache.deleteFetched(machineId, id) }

    companion object {
        const val NOT_UNDER_FOLDER = "That path is not under this agent's folder, so Paddock will not look for it."
        private const val SHOWN = 100
    }
}

/** Counts what passes through to the file, for the progress bar. */
private class CountingOutput(private val out: OutputStream, private val onBytes: (Long) -> Unit) : OutputStream() {
    private var total = 0L
    override fun write(b: Int) { out.write(b); total++; onBytes(total) }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); total += len; onBytes(total) }
    override fun flush() = out.flush()
    override fun close() = out.close()
}
