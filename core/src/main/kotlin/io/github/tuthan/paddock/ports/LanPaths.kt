package io.github.tuthan.paddock.ports

import io.github.tuthan.paddock.net.Ipv4Subnet
import java.net.Inet4Address
import java.net.InetAddress

/**
 * One network the phone is on. [tunnel] is a VPN: it reaches the machine through a route, never by broadcast. [id] names the
 * network to the platform when a socket has to be bound to it.
 */
data class LanPath(
    val id: String,
    val interfaceName: String,
    val tunnel: Boolean,
    val subnets: List<Ipv4Subnet>,
    val gateways: List<InetAddress> = emptyList(),
) {
    val hasIpv4: Boolean get() = subnets.isNotEmpty()
    val broadcasts: List<Inet4Address> get() = subnets.map { it.directedBroadcast }
}

/** The networks the phone is on right now, as the platform reports them. Never the "active network" alone: that may be the VPN. */
fun interface LanPaths {
    fun paths(): List<LanPath>
}
