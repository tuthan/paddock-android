package io.github.tuthan.paddock.hostkey

import io.github.tuthan.paddock.ssh.ConnectFailure
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A first-contact key waiting for the user. [id] names this question; an answer must name the question it answers. */
data class FirstTrustRequest(val id: Long, val profileId: String, val endpoint: String, val presented: PresentedHostKey)

/** A pinned machine that presented a different key. Nothing was authenticated. */
data class ChangedKey(val profileId: String, val endpoint: String, val pin: PinnedHostKey, val presented: PresentedHostKey)

/**
 * The hand-off between a connect that is waiting on a person and the screen that asks them. The connector calls
 * [askFirstTrust] and suspends; the UI shows [firstTrust] and calls [answerFirstTrust] with that request's id. A cancelled
 * connect withdraws its own question (see [askFromCallbackThread] for the SSH library's thread), so a dialog never outlives
 * the attempt that raised it, and an answer meant for a withdrawn question never lands on the next one. Changed keys are
 * not questions: the connect has already failed, so they are recorded here for the host screen to offer "keep" or
 * "replace" on its own terms.
 */
class HostKeyBroker {
    private val askLock = Mutex()
    private val ids = AtomicLong()
    private val pendingLock = Any()
    private var pending: Pending? = null

    private class Pending(val id: Long, val answer: CompletableDeferred<Boolean>)

    private val _firstTrust = MutableStateFlow<FirstTrustRequest?>(null)
    val firstTrust: StateFlow<FirstTrustRequest?> = _firstTrust.asStateFlow()

    private val _changed = MutableStateFlow<Map<String, ChangedKey>>(emptyMap())
    val changed: StateFlow<Map<String, ChangedKey>> = _changed.asStateFlow()

    /** One question at a time: a second connect waits its turn instead of replacing the first dialog. */
    suspend fun askFirstTrust(profileId: String, endpoint: String, presented: PresentedHostKey): Boolean = askLock.withLock {
        val request = FirstTrustRequest(ids.incrementAndGet(), profileId, endpoint, presented)
        val answer = CompletableDeferred<Boolean>()
        synchronized(pendingLock) { pending = Pending(request.id, answer) }
        _firstTrust.value = request
        try {
            answer.await()
        } finally {
            synchronized(pendingLock) { if (pending?.id == request.id) pending = null }
            _firstTrust.compareAndSet(request, null)
        }
    }

    /**
     * Answers question [requestId]. Returns false and changes nothing when that question is no longer open (already answered,
     * withdrawn by a cancelled connect, or never asked), so a late or second tap cannot answer a different question.
     */
    fun answerFirstTrust(requestId: Long, accept: Boolean): Boolean {
        val open = synchronized(pendingLock) { pending?.takeIf { it.id == requestId }?.also { pending = null } } ?: return false
        return open.answer.complete(accept)
    }

    fun recordChanged(profileId: String, endpoint: String, failure: ConnectFailure.HostKeyChanged) {
        _changed.value = _changed.value + (profileId to ChangedKey(profileId, endpoint, failure.pin, failure.presented))
    }

    fun clearChanged(profileId: String) { _changed.value = _changed.value - profileId }
}

/**
 * Runs [ask] on the calling thread, which belongs to the SSH library (its host-key callback) and to no coroutine, as a child
 * of [owner]. The connector passes a job tied to the connect call, so cancelling the connect (or the connector giving up when
 * its key-exchange timer expires) cancels [ask], and a question the broker is showing is withdrawn. Returns null when
 * cancelled, which the caller treats as "not accepted"; other exceptions propagate.
 */
fun <T : Any> askFromCallbackThread(owner: Job, ask: suspend () -> T): T? {
    val child = SupervisorJob(owner)
    return try {
        runBlocking(child) { ask() }
    } catch (_: CancellationException) {
        null
    } finally {
        child.complete()
    }
}
