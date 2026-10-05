package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress

/**
 * A machine on the sleeping host's network that re-broadcasts the magic packet it receives: a router with a wake forwarder, a VPN
 * server, an always-on box running a wake service. An IPv4 literal and a port, nothing else, so saving or waking never does DNS.
 */
@Serializable
data class WakeRelay(val address: String, val port: Int = DEFAULT_PORT) {
    init {
        require(port in 1..65535) { "invalid relay port" }
        require(usable(Ipv4Subnet.literal(address))) { "invalid relay address" }
    }

    fun inet(): InetAddress = Ipv4Subnet.literal(address)!!

    fun format(): String = if (port == DEFAULT_PORT) address else "$address:$port"

    companion object {
        const val DEFAULT_PORT = 9

        private fun usable(a: Inet4Address?): Boolean =
            a != null && !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isMulticastAddress &&
                !a.address.all { it == (-1).toByte() }

        /** `192.168.1.1` or `192.168.1.1:9009`; null for a name, an IPv6 literal, a wildcard, loopback, multicast, broadcast or a bad port. */
        fun parse(text: String): WakeRelay? {
            val t = text.trim()
            val host = t.substringBefore(':')
            val port = if (':' in t) t.substringAfter(':').takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toIntOrNull() ?: return null else DEFAULT_PORT
            if (!usable(Ipv4Subnet.literal(host))) return null
            return if (port in 1..65535) WakeRelay(host, port) else null
        }

        /** A starting point for the relay field: the default gateway of a real LAN, often the router or VPN server. Never used unsaved. */
        fun suggestionFrom(paths: List<LanPath>): WakeRelay? = paths.asSequence()
            .filter { !it.tunnel }
            .flatMap { it.gateways.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { parse(it.hostAddress ?: return@mapNotNull null) }
            .firstOrNull()
    }
}

/** What the host said about waking: words, as read, shown beside the commands that would change them. */
@Serializable
data class WakeReadiness(
    /** `enabled` or `disabled` from the device's `power/wakeup`, or null when the node does not exist. */
    val wakeup: String? = null,
    /** Wi-Fi: what `iw phy <phy> wowlan show` said (for example `enabled: magic packet`), or null. */
    val wowlan: String? = null,
    /** Ethernet: the `Wake-on:` letters from `ethtool` (for example `g`, `d`), `unknown: ethtool is not installed`, or null. */
    val ethtool: String? = null,
    val wifi: Boolean = false,
    val phy: String? = null,
) {
    init {
        require(phy == null || PHY.matches(phy)) { "invalid phy" }
        listOf(wakeup, wowlan, ethtool).forEach { require(it == null || it.length <= 120) { "readiness text too long" } }
    }

    /** `ready`, `not ready` or `unknown`, for the Settings row. */
    val verdict: Verdict get() = when {
        wakeup == "disabled" -> Verdict.NotReady
        wifi -> when {
            wowlan == null -> Verdict.Unknown
            "magic" in wowlan.lowercase() && !wowlan.lowercase().startsWith("disabled") -> Verdict.Ready
            else -> Verdict.NotReady
        }
        ethtool == null || ethtool.startsWith("unknown") -> Verdict.Unknown
        'g' in ethtool -> Verdict.Ready
        else -> Verdict.NotReady
    }

    enum class Verdict { Ready, NotReady, Unknown }

    companion object { private val PHY = Regex("[a-z0-9]{1,16}") }
}

/**
 * What the phone remembers about waking one machine: the hardware address and interface the machine reached the phone on, read
 * over SSH while connected (a phone cannot read another device's address), the source address seen, when it was read, and the
 * relay the user saved. Invalid values never construct, so a hand-edited file fails to load instead of half-loading.
 */
@Serializable
data class WakeTarget(
    val available: Boolean,
    val mac: String? = null,
    val iface: String? = null,
    val sourceAddress: String? = null,
    val capturedAtMillis: Long = 0,
    /** Why wake is not available, in words; null when it is. */
    val reason: String? = null,
    val readiness: WakeReadiness? = null,
    /** The machine's own default gateway when read, offered as a relay suggestion and never used unsaved. */
    val gateway: String? = null,
    val relay: WakeRelay? = null,
) {
    init {
        require(mac == null || MAC.matches(mac)) { "invalid MAC address" }
        require(iface == null || IFACE.matches(iface)) { "invalid interface name" }
        require(sourceAddress == null || Ipv4Subnet.literal(sourceAddress) != null) { "invalid source address" }
        require(gateway == null || Ipv4Subnet.literal(gateway) != null) { "invalid gateway" }
        require(reason == null || reason.length <= 200) { "reason too long" }
        require(!available || (mac != null && iface != null)) { "an available target has a MAC and an interface" }
    }

    companion object {
        private val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        private val IFACE = Regex("[A-Za-z0-9._-]{1,15}")

        fun unavailable(reason: String, capturedAtMillis: Long, relay: WakeRelay? = null, gateway: String? = null) =
            WakeTarget(available = false, capturedAtMillis = capturedAtMillis, reason = reason.take(200), gateway = gateway, relay = relay)
    }
}
