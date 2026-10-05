package io.github.tuthan.paddock.pairing

import io.github.tuthan.paddock.ports.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingState {
    data object Idle : PairingState
    /** The key has been, or is being, sent; the desktop has not answered yet. */
    data class Sending(val pending: PendingPairing) : PairingState
    /** The desktop holds the key and shows its fingerprint; the owner has not decided. */
    data class Waiting(val pending: PendingPairing) : PairingState
    data class Approved(val pending: PendingPairing) : PairingState
    data class Rejected(val pending: PendingPairing) : PairingState
    data class Expired(val pending: PendingPairing) : PairingState
    /** The desktop cannot be reached right now; Paddock keeps trying until the window ends. */
    data class Unreachable(val pending: PendingPairing, val detail: String?) : PairingState
    /** The session handle was refused: the code was not this popup's. Scan it again. */
    data class Refused(val pending: PendingPairing) : PairingState
    /** Another key is already being paired at the desktop. */
    data class Busy(val pending: PendingPairing) : PairingState
    /** The window ended with the desktop reached but never heard from: it may have written the key. Never retried by itself. */
    data class NotConfirmed(val pending: PendingPairing) : PairingState
    /** The window ended and the desktop was never reached. */
    data class CannotReach(val pending: PendingPairing, val detail: String?) : PairingState
    data object Cancelled : PairingState

    val isTerminal: Boolean get() = this is Approved || this is Rejected || this is Expired || this is Refused || this is Busy ||
        this is NotConfirmed || this is CannotReach || this is Cancelled
}

/**
 * Sends the phone's public key to the desktop's `pair` popup and follows the owner's decision there. The key is public, so the
 * exchange can be repeated safely: the desktop answers the same state for the same key, and `status` says whether it ever arrived.
 * That lets a lost reply be settled by asking instead of guessing. Every request is persisted before its first byte, a reply for
 * an older [PendingPairing.generation] is discarded, and Cancel raises the generation first so a late `ok` stores nothing.
 */
class PairingCoordinator(
    private val store: PendingPairingStore,
    private val client: PairingClient,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 2_000,
    private val windowMillis: Long = 120_000,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private val lock = Any()
    @Volatile private var generation = 0L
    private var job: Job? = null

    /** Starts a request. Any earlier one is cancelled first. The record is on disk before anything is sent. */
    suspend fun start(host: String, port: Int, sid: String, keyLine: String, fingerprint: String) {
        val gen = synchronized(lock) { job?.cancel(); ++generation }
        val now = clock.nowMillis()
        val pending = PendingPairing(sid, host, port, fingerprint, keyLine, now, now + windowMillis, gen)
        store.save(pending)
        begin(pending, askStatusFirst = false)
    }

    /** After a restart: reopens the request on disk when its window is still open. Asks `status` first, since the first send may not have arrived. */
    suspend fun resume(): Boolean {
        val pending = store.load() ?: return false
        if (clock.nowMillis() >= pending.deadlineMillis) { store.save(null); return false }
        val gen = synchronized(lock) { job?.cancel(); generation = maxOf(generation, pending.generation); generation }
        begin(pending.copy(generation = gen), askStatusFirst = true)
        return true
    }

    /** Raises the generation first, so a reply that is already on its way changes nothing, then clears the record. */
    suspend fun cancel() {
        synchronized(lock) { generation++; job?.cancel(); _state.value = PairingState.Cancelled }
        store.save(null)
    }

    /** The first successful connect after pairing: the request is done with. */
    suspend fun clear() {
        synchronized(lock) { generation++; job?.cancel(); _state.value = PairingState.Idle }
        store.save(null)
    }

    private fun emit(gen: Long, s: PairingState) { synchronized(lock) { if (gen == generation) _state.value = s } }

    private fun begin(pending: PendingPairing, askStatusFirst: Boolean) {
        emit(pending.generation, PairingState.Sending(pending))
        val j = scope.launch { run(pending, askStatusFirst) }
        synchronized(lock) { if (pending.generation == generation) job = j else j.cancel() }
    }

    private suspend fun run(p: PendingPairing, askStatusFirst: Boolean) {
        var useStatus = askStatusFirst
        var acknowledged = false
        var everConnected = false
        var lastDetail: String? = null
        while (true) {
            if (p.generation != generation) return
            if (clock.nowMillis() >= p.deadlineMillis) {
                emit(p.generation, when {
                    acknowledged -> PairingState.Expired(p)
                    everConnected -> PairingState.NotConfirmed(p)
                    else -> PairingState.CannotReach(p, lastDetail)
                })
                return
            }
            val result = if (useStatus) client.status(p.host, p.port, p.sid) else client.key(p.host, p.port, p.sid, p.keyLine)
            if (p.generation != generation) return
            when (result) {
                is ClientResult.Unreachable -> { lastDetail = result.detail; emit(p.generation, PairingState.Unreachable(p, result.detail)) }
                ClientResult.NoReply -> { everConnected = true; useStatus = true; emit(p.generation, PairingState.Sending(p)) }
                is ClientResult.Reply -> {
                    everConnected = true
                    when (result.reply) {
                        PairingReply.Pending -> { acknowledged = true; useStatus = true; emit(p.generation, PairingState.Waiting(p)) }
                        PairingReply.Ok -> { emit(p.generation, PairingState.Approved(p)); return }
                        PairingReply.Rejected -> { emit(p.generation, PairingState.Rejected(p)); return }
                        PairingReply.Expired -> { emit(p.generation, PairingState.Expired(p)); return }
                        PairingReply.Refused -> { emit(p.generation, PairingState.Refused(p)); return }
                        PairingReply.Busy -> { emit(p.generation, PairingState.Busy(p)); return }
                        // The desktop never received the key: sending it again is safe, since the same key gets the same answer.
                        PairingReply.None -> { acknowledged = false; useStatus = false; emit(p.generation, PairingState.Sending(p)) }
                        PairingReply.Malformed -> { useStatus = true; emit(p.generation, PairingState.Sending(p)) }
                    }
                }
            }
            delay(pollMillis)
        }
    }
}
