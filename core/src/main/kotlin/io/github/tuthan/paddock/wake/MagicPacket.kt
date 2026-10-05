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
     * The network whose subnet holds the machine first (a broadcast only helps on the machine's own network), then any tunnel
     * (reached only through a relay). When the machine is addressed by a name or its address is on none of them, every network
     * is tried, the one named like the machine's recorded interface first.
     */
    fun candidates(paths: List<LanPath>, hostAddress: String, preferredInterface: String?): List<LanPath> {
        val host = Ipv4Subnet.literal(hostAddress)
        val lan = if (host == null) emptyList() else paths.filter { p -> !p.tunnel && p.subnets.any { it.contains(host) } }
        val tunnels = paths.filter { it.tunnel }
        if (lan.isNotEmpty() || tunnels.isNotEmpty()) return lan + tunnels
        return paths.sortedBy { it.interfaceName != preferredInterface }
    }

    /** The limited broadcast every LAN send also goes to, besides the directed one of each subnet. */
    val LIMITED_BROADCAST: Inet4Address = Ipv4Subnet.fromValue(0xffff_ffffL)
}
