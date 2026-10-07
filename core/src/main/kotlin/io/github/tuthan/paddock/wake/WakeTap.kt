package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One foreground Wake tap on the watched machine, and what it came to ([facts]). A tap sends, shows the three facts, asks for a
 * reconnect at once and follows the connection for [followMillis]; a tap while one is still sending, or inside the guard of one
 * that sent something, does nothing. [send] returns null when there is nothing to wake (no machine, no hardware address read).
 * [link] is the connection as it is now, [links] as it changes.
 */
class WakeTap(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val send: suspend () -> WakeSendResult?,
    private val link: () -> WakeLink,
    private val links: Flow<WakeLink>,
    private val reconnect: () -> Unit,
    private val followMillis: Long = FOLLOW_MILLIS,
) {
    private val _facts = MutableStateFlow<WakeFacts?>(null)
    /** What the last tap came to; null before any tap and after [forget]. */
    val facts: StateFlow<WakeFacts?> = _facts.asStateFlow()

    private val sending = AtomicBoolean(false)
    @Volatile private var following: Job? = null
    /** Bumped by [forget], under [lock] with the clearing, so a tap can tell whether the machine it sent for is still the watched one. */
    private var generation = 0L
    private val lock = Any()

    fun tap() {
        // Facts are set only after the send finishes, so two quick taps would both pass the guard: this flag is what stops the second.
        if (!sending.compareAndSet(false, true)) return
        scope.launch { try { run() } finally { sending.set(false) } }
    }

    /** Another machine is watched: what the last tap came to is not about it, and a tap still sending records nothing (see [run]). */
    fun forget() = synchronized(lock) {
        generation++
        following?.cancel()
        following = null
        _facts.value = null
    }

    private suspend fun run() {
        if (_facts.value?.canWakeAgain(clock.nowMillis()) == false) return
        val mine = synchronized(lock) { generation }
        val before = link()
        val result = send() ?: return
        // A machine that is already live says so; the two later facts would be true at the tap and mean nothing about this packet.
        val facts = WakeFacts(clock.nowMillis(), result, alreadyLive = before.reachable)
        // Another machine became the watched one while this packet was being sent ([forget]): its facts, the reconnect and the follow would all land
        // on that machine, whose connection says nothing about this packet. The check and the write share the lock, so a forget cannot fall between them.
        val current = synchronized(lock) { (generation == mine).also { if (it) _facts.value = facts } }
        if (!current || !facts.transmitted) return
        if (synchronized(lock) { generation != mine }) return
        reconnect()
        if (!facts.alreadyLive) follow(facts, before)
    }

    /**
     * Fills in the two later facts as the connection comes up. Only a change from how the connection looked at the tap counts: what
     * was already true then (or looks true from a view that has not caught up) is not an answer to this packet.
     */
    private fun follow(first: WakeFacts, before: WakeLink) {
        following?.cancel()
        following = scope.launch {
            withTimeoutOrNull(followMillis) {
                links.distinctUntilChanged().dropWhile { it == before }.firstOrNull { l ->
                    var done = false
                    _facts.update { f ->
                        // A newer tap, or another machine: this follow is over.
                        if (f == null || f.sentAtMillis != first.sentAtMillis) { done = true; f }
                        else f.observed(l, clock.nowMillis()).also { done = it.settled }
                    }
                    done
                }
            }
        }
    }

    companion object {
        /** How long after a tap the connection is followed to fill in "the machine answered" and "herdr reachable". */
        const val FOLLOW_MILLIS = 180_000L
    }
}
