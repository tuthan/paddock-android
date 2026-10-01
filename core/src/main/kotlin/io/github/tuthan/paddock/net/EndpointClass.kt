package io.github.tuthan.paddock.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

enum class EndpointClass { Local, NotLocal }

/**
 * Whether an endpoint counts as "local network" for Android 17's ACCESS_LOCAL_NETWORK grant.
 * Literals and `.local` names only; no DNS lookup ever happens here. A plain DNS name is NotLocal
 * because the phone cannot know where it resolves, and a name that resolves to a LAN address is
 * re-classified by the caller from the resolved address.
 *
 * Local: RFC 1918, 169.254.0.0/16, fe80::/10, fc00::/7, `*.local`, and IPv4-mapped forms of those.
 * NotLocal: everything else, including loopback, tailnet and other VPN ranges (100.64.0.0/10).
 */
fun classifyEndpoint(host: String): EndpointClass {
    val h = host.trim().removeSurrounding("[", "]").substringBefore('%').lowercase()
    if (h.isEmpty()) return EndpointClass.NotLocal
    if (h.endsWith(".local") && h.length > ".local".length) return EndpointClass.Local
    val address = parseLiteral(h) ?: return EndpointClass.NotLocal
    return if (isLocal(address)) EndpointClass.Local else EndpointClass.NotLocal
}

private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/** Parses an IP literal without touching DNS; returns null for anything that is not a literal. */
private fun parseLiteral(h: String): InetAddress? {
    IPV4.matchEntire(h)?.let { m ->
        val octets = m.groupValues.drop(1).map { it.toInt() }
        if (octets.any { it > 255 }) return null
        return InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
    }
    // Only strings with a colon go to the JDK parser, which does not resolve names for IPv6 literals.
    if (':' in h && h.all { it in "0123456789abcdef:." }) {
        return try { InetAddress.getByName(h) } catch (_: Exception) { null }
    }
    return null
}

private fun isLocal(a: InetAddress): Boolean = when (a) {
    is Inet4Address -> isLocalV4(a.address)
    is Inet6Address -> {
        val b = a.address
        val mapped = b.take(10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        when {
            mapped -> isLocalV4(b.copyOfRange(12, 16))
            (b[0].toInt() and 0xff) == 0xfe && (b[1].toInt() and 0xc0) == 0x80 -> true // fe80::/10
            (b[0].toInt() and 0xfe) == 0xfc -> true                                    // fc00::/7
            else -> false
        }
    }
    else -> false
}

private fun isLocalV4(b: ByteArray): Boolean {
    val o0 = b[0].toInt() and 0xff
    val o1 = b[1].toInt() and 0xff
    return o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) || (o0 == 169 && o1 == 254)
}
