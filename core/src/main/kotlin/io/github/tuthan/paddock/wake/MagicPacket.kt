package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath
import java.net.Inet4Address

object MagicPacket {
    /** Six bytes of `FF` then the hardware address sixteen times: 102 bytes. [mac] is `aa:bb:cc:dd:ee:ff`. */
    fun bytes(mac: String): ByteArray {
        require(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(mac)) { "invalid MAC address" }
        val hardware = mac.split(':').map { it.toInt(16).toByte() }
        return ByteArray(102) { i -> if (i < 6) 0xff.toByte() else hardware[(i - 6) % 6] }
    }
}

/** Which of the phone's networks to try, and in what order, to reach a machine at [hostAddress]. */
object WakePaths {
    /**
     * For an IPv4 address: the network whose subnet holds the machine first (a broadcast only helps on the machine's own network),
     * then any tunnel (reached only through a relay). An address on none of the phone's subnets is never broadcast to an unrelated
     * network (a hotel's Wi-Fi cannot reach the machine's), so with no tunnel the list is empty.
     *
     * For a name (never resolved here, so the machine's network cannot be told) every non-tunnel network comes first, then the
     * tunnels: an always-on VPN must not hide the LAN the phone is on. Among those networks the one named like the machine's
     * recorded interface goes first; that is a tiebreak only, because the two names belong to two devices and match only by
     * coincidence (a Raspberry Pi's `wlan0` and the phone's).
     */
    fun candidates(paths: List<LanPath>, hostAddress: String, preferredInterface: String?): List<LanPath> {
        val host = Ipv4Subnet.literal(hostAddress)
        val tunnels = paths.filter { it.tunnel }
        if (host == null) return paths.filter { !it.tunnel }.sortedBy { it.interfaceName != preferredInterface } + tunnels
        return paths.filter { p -> !p.tunnel && p.subnets.any { it.contains(host) } } + tunnels
    }

    /**
     * True when a packet could leave on one of [candidates]: a network with an IPv4 address to broadcast on, or a tunnel with a
     * relay saved (a tunnel without one sends nothing). What Home and Settings check before offering Wake.
     */
    fun canSend(candidates: List<LanPath>, relay: WakeRelay?): Boolean = candidates.any { if (it.tunnel) relay != null else it.hasIpv4 }

    /** The limited broadcast every LAN send also goes to, besides the directed one of each subnet. */
    val LIMITED_BROADCAST: Inet4Address = Ipv4Subnet.fromValue(0xffff_ffffL)
}
