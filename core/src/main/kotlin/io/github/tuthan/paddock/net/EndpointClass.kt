package io.github.tuthan.paddock.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

enum class EndpointClass { Local, NotLocal }

/**
 * Whether an endpoint counts as "local network" for Android 17's ACCESS_LOCAL_NETWORK grant, from the text alone.
 * Literals and `.local` names only; no DNS lookup ever happens here. A plain DNS name is NotLocal here because the
 * text does not say where it goes; [classifyResolved] adds the resolved addresses, and the gate uses that.
 *
 * Local: RFC 1918, 169.254.0.0/16, fe80::/10, fc00::/7, `*.local`, and IPv4-mapped forms of those.
 * NotLocal: everything else, including loopback, tailnet and other VPN ranges (100.64.0.0/10).
 */
fun classifyEndpoint(host: String): EndpointClass {
    val h = normalized(host)
    if (h.isEmpty()) return EndpointClass.NotLocal
    if (h.endsWith(".local") && h.length > ".local".length) return EndpointClass.Local
    val address = parseLiteral(h) ?: return EndpointClass.NotLocal
    return if (isLocal(address)) EndpointClass.Local else EndpointClass.NotLocal
}

/** Resolves a host name to its addresses. Blocking; injected so tests never touch DNS. */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>

    companion object {
        val System = HostResolver { InetAddress.getAllByName(it).toList() }
    }
}

/**
 * [classifyEndpoint], plus where a plain name resolves. Android 17 enforces the grant on the address a socket connects to,
 * so a LAN name (`nas.lan`, `x.home.arpa`, a bare hostname such as `devbox`) that resolves to 192.168.x.x is Local
 * even though its text is not. Literals and `.local` names are decided without DNS. The lookup runs on its own thread and
 * is bounded by [timeout] (a blocking lookup cannot be cancelled); a name that does not resolve, or not in time, is NotLocal,
 * and the connect then fails on its own.
 */
suspend fun classifyResolved(host: String, resolver: HostResolver, timeout: Duration = 3.seconds): EndpointClass {
    if (classifyEndpoint(host) == EndpointClass.Local) return EndpointClass.Local
    val h = normalized(host)
    if (h.isEmpty() || parseLiteral(h) != null) return EndpointClass.NotLocal
    val result = CompletableDeferred<List<InetAddress>>()
    thread(isDaemon = true, name = "paddock-resolve") { result.complete(runCatching { resolver.resolve(h) }.getOrDefault(emptyList())) }
    val addresses = withTimeoutOrNull(timeout) { result.await() } ?: return EndpointClass.NotLocal
    return if (addresses.any(::isLocal)) EndpointClass.Local else EndpointClass.NotLocal
}

private fun normalized(host: String) = host.trim().removeSurrounding("[", "]").substringBefore('%').lowercase()

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
