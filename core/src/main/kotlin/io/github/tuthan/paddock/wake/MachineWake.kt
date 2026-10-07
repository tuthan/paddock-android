package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.ports.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wake for every saved machine (decision D2: Wake is Free, watched or not). A tap for the watched machine ([watchedId]) is [watched]'s tap: it
 * sends, asks for a reconnect and follows the connection. A tap for any other saved machine only sends: there is no connection to that machine to
 * ask for or follow, and asking the watched machine's connection to reconnect would say nothing about this one. Its facts ([others], by profile id)
 * are the packet line and [WakeFacts.NOT_WATCHED]. Per machine the rules are [WakeTap]'s: a tap while one is still sending for that machine is
 * ignored, and a tap inside the guard of a send that transmitted does nothing. [sendTo] returns null when there is nothing to wake (no such machine,
 * no hardware address read), which records nothing.
 */
class MachineWake(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val watched: WakeTap,
    private val watchedId: () -> String?,
    private val sendTo: suspend (profileId: String) -> WakeSendResult?,
) {
    private val _others = MutableStateFlow<Map<String, WakeFacts>>(emptyMap())
    /** What the last send-only tap for each machine that is not watched came to. A machine becoming the watched one, or being removed, leaves it. */
    val others: StateFlow<Map<String, WakeFacts>> = _others.asStateFlow()

    private val sending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Bumped by [forget]: a send that started before it does not bring its machine's facts back. */
    private val generations = ConcurrentHashMap<String, Long>()

    fun tap(profileId: String) {
        if (watchedId() == profileId) { watched.tap(); return }
        // Facts are set only after the send finishes, so two quick taps would both pass the guard: this flag is what stops the second.
        if (!sending.add(profileId)) return
        // Read at the tap, not in the coroutine: a forget between this line and the coroutine's first step (a removal that finished meanwhile) would
        // otherwise be read as the generation this send belongs to, and the removed machine would come back with a packet line.
        val generation = generations[profileId] ?: 0L
        scope.launch { try { send(profileId, generation) } finally { sending.remove(profileId) } }
    }

    /** What the last tap for [profileId] came to, read against the machine watched now; null before any tap. */
    fun facts(profileId: String): WakeFacts? = if (watchedId() == profileId) watched.facts.value else _others.value[profileId]

    /**
     * [profileId] became the watched machine: the watched tap's facts were about the machine before it and go ([WakeTap.forget]), and this
     * machine's send-only facts go too, because their second line ("Paddock is not watching this machine") is no longer true. Every other
     * machine's facts stay: watching one machine says nothing about a packet sent to another. The guard restarts with the watched tap.
     */
    fun watching(profileId: String) {
        watched.forget()
        forget(profileId)
    }

    /** [profileId] was removed, or became the watched machine: its send-only facts go, and a send still running for it records nothing. */
    fun forget(profileId: String) {
        generations.merge(profileId, 1L, Long::plus)
        _others.update { it - profileId }
    }

    private suspend fun send(profileId: String, generation: Long) {
        if (_others.value[profileId]?.canWakeAgain(clock.nowMillis()) == false) return
        val result = sendTo(profileId) ?: return
        val facts = WakeFacts(clock.nowMillis(), result, notWatched = true)
        _others.update { all -> if ((generations[profileId] ?: 0L) != generation) all else all + (profileId to facts) }
    }
}
