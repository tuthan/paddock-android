package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.LanPaths
import io.github.tuthan.paddock.ports.Sockets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Wake for every saved machine (decision D2: Wake is Free, watched or not): the watched machine through [tap], any other as a send only, from its stored
 * address and relay ([MachineWake]). [watched] is the machine the phone watches now; [profile] reads a saved machine as the store has it now (a Wake
 * tap uses the stored copy, which carries a relay saved after the watched copy was taken); [link] and [links] are the watched machine's connection as it
 * is and as it changes; [reconnect] asks for one at once. Nothing here reads anything over SSH.
 */
class WakeService(
    private val scope: CoroutineScope,
    clock: Clock,
    private val lanPaths: LanPaths,
    private val sockets: Sockets,
    private val permissionMissing: () -> Boolean,
    private val watched: StateFlow<HostProfile?>,
    private val profile: suspend (String) -> HostProfile?,
    link: () -> WakeLink,
    links: Flow<WakeLink>,
    reconnect: () -> Unit,
) {
    /** The Wake tap on the watched machine (see [wake]). */
    private val watchedTap = WakeTap(
        scope, clock,
        send = { watched.value?.let { sendWake(profile(it.id) ?: it) } },
        link = link, links = links, reconnect = reconnect,
    )

    /** Wake for every saved machine (decision D2): the watched one through [watchedTap], any other as a send only, from its stored address and relay. */
    private val machineWake = MachineWake(scope, clock, watchedTap, watchedId = { watched.value?.id }, sendTo = { id -> profile(id)?.let { sendWake(it) } })

    /** One magic-packet send for [profile] from where the phone is now; null when its hardware address was never read. Nothing is read over SSH. */
    private suspend fun sendWake(profile: HostProfile): WakeSendResult? {
        val target = profile.wake ?: return null
        return withContext(Dispatchers.IO) {
            val candidates = WakePaths.candidates(lanPaths.paths(), profile.host, target.iface)
            WakeSender(sockets, permissionMissing = permissionMissing).send(target, candidates, target.relay)
        }
    }

    /** What the last Wake tap on the watched machine came to; null before any tap and after switching machines. */
    val watchedFacts: StateFlow<WakeFacts?> get() = watchedTap.facts

    /** One flow per saved machine, made once: a screen asks for it in composition, and a removed machine's goes with it ([forget]). */
    private val factsByMachine = ConcurrentHashMap<String, Pair<Job, StateFlow<WakeFacts?>>>()

    /**
     * What the last Wake tap for [profileId] came to, watched or not: the watched machine's facts while it is the watched one ([watchedFacts], with the
     * two later facts), otherwise its send-only facts (the packet line and "Paddock is not watching this machine"). Null before any tap.
     */
    fun facts(profileId: String): StateFlow<WakeFacts?> = factsByMachine.computeIfAbsent(profileId) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val flow = combine(watched, watchedTap.facts, machineWake.others) { now, mine, others -> if (now?.id == profileId) mine else others[profileId] }
            .stateIn(CoroutineScope(scope.coroutineContext + job), SharingStarted.WhileSubscribed(5_000), machineWake.facts(profileId))
        job to flow
    }.second

    /**
     * True when this phone could send a wake packet for [profile] from where it is now: its hardware address is read, and either a
     * network holding the machine can carry a broadcast or a VPN can carry the packet to a saved relay. A relay alone is not enough
     * (cellular and a non-VPN network away from the machine never use it), and neither is a network the machine is not on.
     */
    fun ready(profile: HostProfile): Boolean {
        val target = profile.wake?.takeIf { it.available } ?: return false
        val candidates = WakePaths.candidates(lanPaths.paths(), profile.host, target.iface)
        return WakePaths.canSend(candidates, target.relay)
    }

    /**
     * One Wake tap on the watched machine: send, show the three facts, ask for a reconnect at once. A tap while one is sending, or
     * inside the guard of one that sent something, does nothing.
     */
    fun wake() = watchedTap.tap()

    /**
     * One Wake tap for any saved machine (decision D2, Free): the watched one as [wake]; any other only sends, to its stored address or saved relay,
     * and its facts say that whether it answered is not observed here. That send never reconnects and never follows a connection: the connection
     * is the watched machine's. A tap while one is sending for that machine, or inside the guard of one that sent something, does nothing.
     */
    fun wake(profileId: String) = machineWake.tap(profileId)

    /** [profileId] is the watched machine now (a switch, or a fresh controller for it): the watched tap's facts were about the machine before, and its own send-only facts say it is not watched. */
    fun watching(profileId: String) = machineWake.watching(profileId)

    /** The watched machine is no longer watched (its connection stopped): what its last tap came to goes. */
    fun forgetWatched() = watchedTap.forget()

    /** A machine was removed: its Wake facts and their flow go with it, and a later machine with the same id starts with none. */
    fun forget(profileId: String) {
        machineWake.forget(profileId)
        factsByMachine.remove(profileId)?.first?.cancel()
    }
}
