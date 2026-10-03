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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A first-contact key waiting for the user. [id] names this question; an answer must name the question it answers.
 * [linkFingerprints] is set when the machine was added from a pairing link and the presented key is one of the link's: the
 * dialog then says so. It never answers the question: the user still chooses.
 */
data class FirstTrustRequest(val id: Long, val profileId: String, val endpoint: String, val presented: PresentedHostKey, val linkFingerprints: List<String>? = null)

/**
 * A machine added from a pairing link presented a key that is none of the link's fingerprints. Nothing was trusted and the
 * connect failed; [expected] is what the link said and [presented] what the machine offered, for the dialog to show side by side.
 */
data class PairingRefusal(val profileId: String, val endpoint: String, val expected: List<String>, val presented: PresentedHostKey)

/**
 * A pinned machine that presented a different key. Nothing was authenticated. [id] names the failed attempt that recorded
 * it: a person who approved this key approved this attempt, and [HostKeyBroker.takeChanged] refuses an id that is no longer
 * the one on record.
 */
data class ChangedKey(val id: Long, val profileId: String, val endpoint: String, val pin: PinnedHostKey, val presented: PresentedHostKey)

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

    private val expectations = HashMap<String, List<String>>()
    private val _pairingRefused = MutableStateFlow<PairingRefusal?>(null)

    /** The latest refusal of a key that did not match a pairing link, until the user closes it ([clearPairingRefusal]). */
    val pairingRefused: StateFlow<PairingRefusal?> = _pairingRefused.asStateFlow()

    private val _changed = MutableStateFlow<Map<String, ChangedKey>>(emptyMap())
    val changed: StateFlow<Map<String, ChangedKey>> = _changed.asStateFlow()

    /**
     * Records what a pairing link said about [profileId]'s host key, or forgets it when [fingerprints] is null or empty. Every
     * Connect from Add machine sets or clears it, so a link's fingerprints never outlive the form that carried them. It is a
     * comparison, not a trust decision: a match only adds a line to the dialog, and the user still taps Trust.
     */
    fun expectPairing(profileId: String, fingerprints: List<String>?) {
        synchronized(pendingLock) { if (fingerprints.isNullOrEmpty()) expectations.remove(profileId) else expectations[profileId] = fingerprints.toList() }
    }

    fun clearPairingRefusal() { _pairingRefused.value = null }

    /**
     * One question at a time: a second connect waits its turn instead of replacing the first dialog. A machine that has a
     * pairing link's fingerprints on record and presents none of them is refused here, before any dialog: the link said what
     * this machine's key is, and a different one is not something to ask a person to wave through.
     */
    suspend fun askFirstTrust(profileId: String, endpoint: String, presented: PresentedHostKey): Boolean = askLock.withLock {
        val expected = synchronized(pendingLock) { expectations[profileId] }
        if (expected != null && presented.fingerprint !in expected) {
            _pairingRefused.value = PairingRefusal(profileId, endpoint, expected, presented)
            return@withLock false
        }
        val request = FirstTrustRequest(ids.incrementAndGet(), profileId, endpoint, presented, linkFingerprints = expected)
        val answer = CompletableDeferred<Boolean>()
        synchronized(pendingLock) { pending = Pending(request.id, answer) }
        _firstTrust.value = request
        try {
            answer.await().also { accepted -> if (accepted) synchronized(pendingLock) { expectations.remove(profileId) } }
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

    /** A later failed attempt replaces the earlier warning, and with it the id an approval of the earlier one would name. */
    fun recordChanged(profileId: String, endpoint: String, failure: ConnectFailure.HostKeyChanged) {
        val next = ChangedKey(ids.incrementAndGet(), profileId, endpoint, failure.pin, failure.presented)
        _changed.update { it + (profileId to next) }
    }

    /** Drops the warning for [profileId] whichever attempt it names: the host has since presented its pinned key. */
    fun clearChanged(profileId: String) { _changed.update { it - profileId } }

    /**
     * Removes and returns the warning for [profileId] only while it is still attempt [id], the one the person was shown. Null
     * when it was cleared or a later attempt replaced it: an approval of a key must never apply to another one.
     */
    fun takeChanged(profileId: String, id: Long): ChangedKey? {
        var taken: ChangedKey? = null
        _changed.update { cur ->
            taken = cur[profileId]?.takeIf { it.id == id }
            if (taken != null) cur - profileId else cur
        }
        return taken
    }

    /** Puts back a warning [takeChanged] removed when acting on it failed, unless a later attempt has recorded one meanwhile. */
    fun restoreChanged(c: ChangedKey) { _changed.update { if (c.profileId in it) it else it + (c.profileId to c) } }
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
