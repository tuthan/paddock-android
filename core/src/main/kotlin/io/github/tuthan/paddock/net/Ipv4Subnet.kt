package io.github.tuthan.paddock.net

import java.net.Inet4Address
import java.net.InetAddress

/** The IPv4 network a link is on: the phone's own address on it and the link's prefix length. */
class Ipv4Subnet(val address: Inet4Address, val prefix: Int) {
    init { require(prefix in 0..32) { "invalid prefix length" } }

    private val mask: Long get() = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL

    val network: Inet4Address get() = fromValue(value(address) and mask)

    /** The address a directed broadcast to this network goes to (all host bits set). */
    val directedBroadcast: Inet4Address get() = fromValue((value(address) and mask) or (mask.inv() and 0xffff_ffffL))

    fun contains(other: InetAddress): Boolean = other is Inet4Address && (value(other) and mask) == (value(address) and mask)

    /** Whether the finder may enumerate this network: a /24 or smaller, and large enough to hold another machine. */
    val probeable: Boolean get() = prefix in MIN_PROBE_PREFIX..30

    /**
     * Every address a machine other than the phone could have on this network, in order: the network and broadcast
     * addresses and everything in [exclude] are left out. Refuses a network wider than a /24, so a /16 office network is
     * never enumerated by mistake.
     */
    fun hosts(exclude: Set<Inet4Address> = setOf(address)): List<Inet4Address> {
        require(prefix >= MIN_PROBE_PREFIX) { "network is wider than /$MIN_PROBE_PREFIX" }
        if (prefix > 30) return emptyList()
        val first = (value(address) and mask) + 1
        val last = ((value(address) and mask) or (mask.inv() and 0xffff_ffffL)) - 1
        return (first..last).map(::fromValue).filter { it !in exclude }
    }

    override fun equals(other: Any?) = other is Ipv4Subnet && other.address == address && other.prefix == prefix
    override fun hashCode() = address.hashCode() * 31 + prefix
    override fun toString() = "${network.hostAddress}/$prefix"

    companion object {
        const val MIN_PROBE_PREFIX = 24

        fun value(a: Inet4Address): Long = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xffL) }

        fun fromValue(v: Long): Inet4Address =
            InetAddress.getByAddress(ByteArray(4) { i -> ((v shr (24 - 8 * i)) and 0xffL).toByte() }) as Inet4Address

        /** An IPv4 literal such as `192.168.1.20`; never a name, so parsing does no DNS. Null for anything else. */
        fun literal(text: String): Inet4Address? {
            val parts = text.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
            val octets = parts.map { it.toInt() }
            if (octets.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() }) as Inet4Address
        }
    }
}
