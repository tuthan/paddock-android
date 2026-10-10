package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.net.EndpointClass
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.net.classifyEndpoint
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.LanPath
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What Paddock is doing about one machine whose saved address stopped answering, for Home's banner. */
sealed interface RelocateState {
    data object Looking : RelocateState
    /**
     * Every place it could look on this network was tried, and no address presented the machine's host key. [searchOff]: the machine's own switch
     * for searching the network ([HostProfile.searchNetwork]) was off, so only its name was followed.
     */
    data class NotFound(val atMillis: Long, val searchOff: Boolean = false) : RelocateState
    /** Found at [address] and moved there; the banner line goes, and the notice says it once ([MachineRelocator.moved]). */
    data class Moved(val address: String, val atMillis: Long) : RelocateState
}

/** One machine Paddock found at another address and moved, until the root has said so. Not a data class: two moves are two notices. */
class Relocation(val id: String, val name: String, val from: String, val to: String)

/** What one address showed when it was asked for a machine's host key ([MachineRelocator.Ports.checkKey]). */
enum class KeyCheck {
    /** The pinned key, and the server proved it holds it. */
    Matches,
    /** Another key: some other machine answers at this address. */
    OtherKey,
    /** No key was shown: nothing answered, the gate refused, or there is no pin. It says nothing about who is there. */
    NoAnswer,
}

/** What a look came to: the address that presented the key, or null; [searchOff]: the steps that reach other machines were left out ([HostProfile.searchNetwork]). */
internal class Look(val address: String?, val searchOff: Boolean = false)

/**
 * Which machines are looked for, where, and which addresses are tried. Pure, so every rule is tested without a network. The hostname and the
 * hardware address only say where to look: anyone on a network can announce either, so a machine is found only by its host key ([MachineRelocator]).
 */
object RelocateRules {
    /**
     * Failures a machine that moved causes at its old address: no answer ([DownReason.Timeout]), nothing listening or no route ([DownReason.Network]),
     * and another key ([DownReason.HostKeyChanged]: a router gave the old address to another SSH machine). A refused sign-in, a missing grant or a
     * bad key are not about the address.
     */
    fun worthLooking(reason: DownReason): Boolean = reason == DownReason.Timeout || reason is DownReason.Network || reason == DownReason.HostKeyChanged

    /**
     * Only a machine saved at a LAN IPv4 address (10/8, 172.16/12, 192.168/16, 169.254/16) is looked for. A name, a VPN or tailnet address
     * (100.64/10 is not a LAN range) or a public one is how the user reaches it from anywhere, and a LAN address found for it would replace that.
     */
    fun movable(profile: HostProfile): Boolean = Ipv4Subnet.literal(profile.host) != null && classifyEndpoint(profile.host) == EndpointClass.Local

    /**
     * The names asked of the phone's resolver: `<hostname>.local` (answered when the machine runs an mDNS responder: Avahi on Linux, always on a
     * Mac) and the bare hostname (answered by a router that names its DHCP clients). None before a bring-up has read the hostname.
     */
    fun names(profile: HostProfile): List<String> {
        val label = profile.detectedName ?: return emptyList()
        return listOf("$label.local", label).filterNot { it.equals(profile.host, ignoreCase = true) }
    }

    /**
     * Whether an mDNS announcement names this machine: a service called by its hostname (Avahi names its SSH service `%h`), or a workstation
     * announcement (`labbox [aa:bb:cc:dd:ee:ff]`, only with Avahi's `publish-workstation=yes`) with its hostname or its hardware address.
     */
    fun announcedFor(service: NsdService, label: String?, mac: String?): Boolean {
        val name = FoundHosts.cleanName(service.name) ?: return false
        if (label != null && (name.equals(label, ignoreCase = true) || name.startsWith("$label [", ignoreCase = true))) return true
        return mac != null && service.nameOnly && name.endsWith("[$mac]", ignoreCase = true)
    }

    /** Whether the phone is on the network the machine was saved on: only there are announcements of other machines tried, and the network scanned. */
    fun onSavedNetwork(profile: HostProfile, subnet: Ipv4Subnet): Boolean = Ipv4Subnet.literal(profile.host)?.let { subnet.contains(it) } == true

    /**
     * The addresses that may be tried, in the order found, each once: on the phone's own subnet and neither the phone, the network nor its broadcast
     * address ([FinderRules.announcedOnNetwork]: a name or an announcement can point anywhere), not the saved address, and not one tried already.
     */
    fun candidates(found: List<Inet4Address>, subnet: Ipv4Subnet, saved: String, tried: Set<String>): List<Inet4Address> =
        found.filter { FinderRules.announcedOnNetwork(subnet, it) && it.hostAddress != saved && it.hostAddress !in tried }.distinctBy { it.hostAddress }
}

/** What Home's banner and the notice say. */
object RelocateCopy {
    fun line(state: RelocateState?, name: String): String? = when (state) {
        RelocateState.Looking -> "Paddock is looking for $name on this network, in case its address changed."
        is RelocateState.NotFound ->
            if (state.searchOff) "Paddock looked for $name by its name only. To search this network for it, turn on \"${MachineCopy.SEARCH_LABEL}\" on its page in Machines, or change its address there."
            else "Paddock did not find $name at another address on this network. If its address changed, change it on its page in Machines."
        is RelocateState.Moved, null -> null
    }

    fun moved(r: Relocation) = "Found ${r.name} at ${r.to} (it was at ${r.from}). Paddock connects there now."
}

/**
 * Finds a saved machine whose LAN address stopped answering, and moves it ([Ports.move]) when another address on this network presents the host key
 * this phone trusted for it. Only the key decides: a candidate is connected to as far as the key exchange (the server proves it holds the key) and
 * never signed in to, and nobody is asked anything ([Ports.checkKey]). Where it looks, cheapest and quietest first:
 *
 * 1. on the network the machine was saved on only, its hostname as `<hostname>.local` and bare ([RelocateRules.names]): a name asked on any other
 *    network would tell that network's resolver, and everyone listening for mDNS, the machine's name;
 * 2. mDNS announcements heard for [listenMillis], and those naming the machine are checked (on any network: nothing leaves the phone but the
 *    platform's own service query);
 * 3. only on the network the machine was saved on, and only when the user has switched it on for this machine ([HostProfile.searchNetwork]): every
 *    other SSH service announced, then a scan of the subnet on the saved port ([SshProbe], as Find on this network does). These are the steps that
 *    reach other machines, so they are the user's to allow.
 *
 * At most [maxChecks] addresses are checked in all, [parallelChecks] at a time. The addresses of the other saved machines are never looked at, and an
 * address that showed another machine's key is not asked again for [rejectMillis]. A machine is looked for once per failure streak, and a look that
 * finds nothing makes the next wait longer: [cooldownMillis] after the first, doubling to [maxCooldownMillis]. A network change, a connection, a
 * changed address and [askAgain] start the waiting over. Nothing runs unless [Ports.mayLook] (Paddock in front, not covered by its lock), on a network
 * with an IPv4 LAN, with the local-network grant, for a machine with a pinned key.
 */
class MachineRelocator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val ports: Ports,
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
    private val maxCooldownMillis: Long = MAX_COOLDOWN_MILLIS,
    private val rejectMillis: Long = REJECT_MILLIS,
    private val resolveMillis: Long = 3_000,
    private val listenMillis: Long = 3_000,
    private val maxChecks: Int = 24,
    private val parallelChecks: Int = 4,
) {
    /** What the relocator needs from the phone. */
    interface Ports {
        /** Paddock is in front and not covered by its lock. */
        val mayLook: Boolean
        fun lanPaths(): List<LanPath>
        /** Android 17 and later without the local-network grant: nothing on the LAN can be reached. */
        fun grantMissing(): Boolean
        /** Whether this phone has a host key pinned for [profile]: without one nothing can say an address is that machine. */
        suspend fun hasPin(profile: HostProfile): Boolean
        /** The phone's resolver, IPv4 only; empty when nothing answers. */
        suspend fun resolve(name: String): List<Inet4Address>
        /** mDNS announcements of SSH services and workstations, for as long as it is collected. */
        fun announcements(): Flow<NsdService>
        /** The subnet scan: TCP to each of [addresses] on [port], bound to [path], one line read. */
        fun scan(path: LanPath, addresses: List<Inet4Address>, port: Int): Flow<ProbeEvent>
        /** The addresses of the machines saved besides [id]: never looked at. They are not this machine, and a phone that keeps knocking on a saved machine's sshd is how it gets banned from it. */
        fun otherAddresses(id: String): Set<String>
        /** Whether [address] on [profile]'s port presents [profile]'s pinned host key: the key exchange only, nothing signed in, nobody asked. */
        suspend fun checkKey(profile: HostProfile, address: String): KeyCheck
        /** Moves [profile] to [address], only while it is still at the address that was looked for. True when it was moved. */
        suspend fun move(profile: HostProfile, address: String): Boolean
    }

    private val _states = MutableStateFlow<Map<String, RelocateState>>(emptyMap())
    /** By machine id: what the relocator is doing or found; a machine it never looked for, or that connected since, has no entry. */
    val states: StateFlow<Map<String, RelocateState>> = _states.asStateFlow()

    private val _moved = MutableStateFlow<Relocation?>(null)
    /** The last move, until the root has said so ([consumeMoved]). State, not a callback, so a rotation in between does not lose it. */
    val moved: StateFlow<Relocation?> = _moved.asStateFlow()

    private val running = ConcurrentHashMap<String, Job>()
    private val lookedAt = ConcurrentHashMap<String, Long>()
    /** How many looks in a row found nothing, by machine: each one doubles the wait for the next. */
    private val misses = ConcurrentHashMap<String, Int>()
    /** Machine id to the addresses that showed another key, and when: not asked again for [rejectMillis]. */
    private val rejected = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    private fun cooldown(id: String): Long {
        val n = misses[id] ?: 0
        return if (n <= 1) cooldownMillis else minOf(maxCooldownMillis, cooldownMillis shl minOf(n - 1, 16))
    }

    /** [profile]'s connection failed for [reason]: look for it elsewhere if that may mean it moved, and if it was not looked for just now. */
    fun lookFor(profile: HostProfile, reason: DownReason) {
        if (!RelocateRules.worthLooking(reason) || !RelocateRules.movable(profile) || !ports.mayLook) return
        val last = lookedAt[profile.id]
        if (last != null && clock.nowMillis() - last < cooldown(profile.id)) return
        val job = scope.launch(start = CoroutineStart.LAZY) { run(profile) }
        // One look per machine at a time; the job made for a second one is never started, so it is cancelled rather than left on the scope.
        if (running.putIfAbsent(profile.id, job) != null) { job.cancel(); return }
        job.invokeOnCompletion { running.remove(profile.id, job) }
        job.start()
    }

    /**
     * [id] is connected again: what the banner said about it goes, and the next look waits the shortest time again. The time of the last look stays,
     * so a flapping link does not look again at once.
     */
    fun connected(id: String) { misses.remove(id); _states.update { if (id in it) it - id else it } }

    /**
     * [id] was moved to another address (by the user or by this relocator): what was said about the old one goes, and the new address gets a look of
     * its own if it fails. A look still running is left to finish: its move is refused if the user moved the machine meanwhile ([Ports.move]).
     */
    fun addressChanged(id: String) {
        lookedAt.remove(id); misses.remove(id); rejected.remove(id)
        _states.update { s -> if (s[id] is RelocateState.Moved) s else s - id }
    }

    /** The phone joined another network: a machine not found on the last one may be on this one, and what answered there says nothing about here. */
    fun networkChanged() { lookedAt.clear(); misses.clear(); rejected.clear() }

    /**
     * The user turned the machine's search on or opened its page: its next failure is looked at without waiting for the cooldown. Starts nothing by
     * itself; a look still begins with a failed connection.
     */
    fun askAgain(id: String) { lookedAt.remove(id); misses.remove(id) }

    /** [id] was removed from the phone: a look for it stops, and nothing about it is kept. */
    fun forget(id: String) {
        running.remove(id)?.cancel()
        lookedAt.remove(id); misses.remove(id); rejected.remove(id)
        _states.update { it - id }
    }

    fun consumeMoved(r: Relocation) { _moved.compareAndSet(r, null) }

    private suspend fun run(profile: HostProfile) {
        val self = currentCoroutineContext()[Job]
        _states.update { it + (profile.id to RelocateState.Looking) }
        val look = try {
            find(profile)
        } catch (e: CancellationException) {
            _states.update { it - profile.id }
            throw e
        } catch (_: Exception) {
            // This runs on the app's scope, which has no handler: a fault ends the look as "not found", never the app.
            Look(null)
        }
        val found = look.address
        lookedAt[profile.id] = clock.nowMillis()
        if (found == null) misses.merge(profile.id, 1, Int::plus)
        val moved = found != null && ports.move(profile, found)
        // Done before the outcome is published, so whoever sees it can ask for the next look at once.
        running.remove(profile.id, self)
        when {
            found == null -> _states.update { it + (profile.id to RelocateState.NotFound(clock.nowMillis(), look.searchOff)) }
            moved -> {
                _states.update { it + (profile.id to RelocateState.Moved(found!!, clock.nowMillis())) }
                _moved.value = Relocation(profile.id, profile.name, profile.host, found!!)
            }
            // The user changed the address meanwhile, or removed the machine: theirs stands.
            else -> _states.update { it - profile.id }
        }
    }

    /** What this network shows of [profile]: the address that presents its pinned key, or null. Internal so the order of the places is tested. */
    internal suspend fun find(profile: HostProfile): Look {
        if (ports.grantMissing() || !ports.hasPin(profile)) return Look(null)
        val lan = ports.lanPaths().filter { !it.tunnel }.firstOrNull { it.hasIpv4 } ?: return Look(null)
        val subnet = lan.subnets.first()
        val saved = RelocateRules.onSavedNetwork(profile, subnet)
        // Not asked again: what this look tried already, the other saved machines, and what showed another machine's key not long ago.
        val skip = HashSet<String>().apply { addAll(ports.otherAddresses(profile.id)); addAll(recentlyRejected(profile.id)) }
        var budget = maxChecks

        suspend fun firstWithKey(addresses: List<Inet4Address>): String? {
            val todo = RelocateRules.candidates(addresses, subnet, profile.host, skip).take(budget)
            budget -= todo.size
            todo.forEach { skip += it.hostAddress }
            for (chunk in todo.chunked(parallelChecks)) {
                val hit = coroutineScope { chunk.map { a -> async { a.hostAddress.takeIf { keyAt(profile, it) } } }.awaitAll() }.firstOrNull { it != null }
                if (hit != null) return hit
            }
            return null
        }

        // The machine's name goes to this network's resolver and, for `.local`, to everyone listening for mDNS: only the network it lives on is told.
        val named = if (saved) RelocateRules.names(profile).flatMap { name -> withTimeoutOrNull(resolveMillis) { quietly { ports.resolve(name) } } ?: emptyList() } else emptyList()
        firstWithKey(named)?.let { return Look(it) }

        val heard = listen()
        val (forIt, others) = heard.partition { RelocateRules.announcedFor(it, profile.detectedName, profile.wake?.mac) }
        firstWithKey(forIt.map { it.address })?.let { return Look(it) }
        // Elsewhere only the machine's own name is followed: the other machines of a network it was not saved on are none of Paddock's business.
        if (!saved) return Look(null)
        // What follows reaches machines that are not this one, so the user allows it, machine by machine.
        if (!profile.searchNetwork) return Look(null, searchOff = true)
        firstWithKey(others.filter { !it.nameOnly }.map { it.address })?.let { return Look(it) }

        // The scan is the one step another machine on the network notices: not when Paddock went to the background meanwhile.
        if (!subnet.probeable || budget <= 0 || !ports.mayLook) return Look(null)
        val answered = ArrayList<Inet4Address>()
        ports.scan(lan, subnet.hosts(setOf(subnet.address)), profile.port).collect { if (it is ProbeEvent.Hit) answered += it.hit.address }
        return Look(firstWithKey(answered))
    }

    private fun recentlyRejected(id: String): Set<String> {
        val mine = rejected[id] ?: return emptySet()
        val now = clock.nowMillis()
        mine.entries.removeIf { now - it.value >= rejectMillis }
        return mine.keys.toSet()
    }

    private suspend fun keyAt(profile: HostProfile, address: String): Boolean {
        val shown = quietly { ports.checkKey(profile, address) } ?: KeyCheck.NoAnswer
        if (shown == KeyCheck.OtherKey) rejected.getOrPut(profile.id) { ConcurrentHashMap() }[address] = clock.nowMillis()
        return shown == KeyCheck.Matches
    }

    private suspend fun listen(): List<NsdService> {
        val heard = ArrayList<NsdService>()
        // A listener that fails (no permission, the platform refusing) leaves the names and the scan; it never ends the look.
        quietly { withTimeoutOrNull(listenMillis) { ports.announcements().collect { synchronized(heard) { heard += it } } } }
        return synchronized(heard) { heard.toList() }
    }

    /** [block], with any failure but cancellation as null. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        /**
         * The wait after a look that found nothing, doubling per miss up to [MAX_COOLDOWN_MILLIS]. Five minutes, not two: a scan puts two lines in the
         * log of every SSH server (a connection that sent nothing, then one that left after the key exchange), and fail2ban's aggressive mode bans after
         * five in ten minutes; at five minutes no ten minutes hold more than two looks.
         */
        const val COOLDOWN_MILLIS = 5 * 60_000L
        const val MAX_COOLDOWN_MILLIS = 60 * 60_000L
        /** How long an address that showed another machine's key is left alone. */
        const val REJECT_MILLIS = 30 * 60_000L
    }
}
