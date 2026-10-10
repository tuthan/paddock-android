package io.github.tuthan.paddock.net

import android.content.Context
import io.github.tuthan.paddock.discovery.KeyCheck
import io.github.tuthan.paddock.discovery.MachineRelocator
import io.github.tuthan.paddock.discovery.NsdService
import io.github.tuthan.paddock.discovery.ProbeEvent
import io.github.tuthan.paddock.discovery.SshProbe
import io.github.tuthan.paddock.hostkey.HostKeyStore
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ssh.SshlibConnector
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * What the relocator ([MachineRelocator]) uses on the phone: the phone's networks and grant, the pins, the system resolver, mDNS ([NsdBrowser]), a
 * scan ([SshProbe]) on sockets of its own bound to the network scanned, and the connector's key-only check. [inFront], [othersOf] and [moveTo] come from
 * the graph.
 */
class AndroidRelocatorPorts(
    private val context: Context,
    private val lanPaths: AndroidLanPaths,
    private val gate: LocalNetworkGate,
    private val pins: HostKeyStore,
    private val connector: SshlibConnector,
    private val inFront: () -> Boolean,
    /** The addresses of the saved machines besides the one with this id. */
    private val othersOf: (String) -> Set<String>,
    private val moveTo: suspend (HostProfile, String) -> Boolean,
) : MachineRelocator.Ports {
    override val mayLook: Boolean get() = inFront()
    override fun lanPaths(): List<LanPath> = lanPaths.paths()
    override fun grantMissing(): Boolean = gate.lanAccessMissing()
    override suspend fun hasPin(profile: HostProfile): Boolean = runCatching { pins.find(profile.id) != null }.getOrDefault(false)

    override suspend fun resolve(name: String): List<Inet4Address> {
        // A blocking lookup cannot be cancelled: it runs on its own thread, and the relocator stops waiting for it at its deadline.
        val result = CompletableDeferred<List<Inet4Address>>()
        thread(isDaemon = true, name = "paddock-relocate-resolve") {
            result.complete(runCatching { InetAddress.getAllByName(name).filterIsInstance<Inet4Address>() }.getOrDefault(emptyList()))
        }
        return result.await()
    }

    override fun announcements(): Flow<NsdService> = NsdBrowser(context).browse()

    // Its own sockets: Find on this network pins the shared ones to the network it scans, and the two must not move each other's pin.
    override fun scan(path: LanPath, addresses: List<Inet4Address>, port: Int): Flow<ProbeEvent> =
        SshProbe(AndroidSockets(lanPaths).apply { tcpPathId = path.id }).scan(addresses, listOf(port))

    override fun otherAddresses(id: String): Set<String> = othersOf(id)

    override suspend fun checkKey(profile: HostProfile, address: String): KeyCheck = connector.checkPinnedKey(profile.copy(host = address).toTarget())

    override suspend fun move(profile: HostProfile, address: String): Boolean = moveTo(profile, address)
}
