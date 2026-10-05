package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.ports.Sockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger

/** An address that answered with an SSH identification line. Nothing about it is trusted or stored. */
data class ProbeHit(val address: Inet4Address, val port: Int, val banner: String) {
    /** `OpenSSH 9.9` for `SSH-2.0-OpenSSH_9.9p1 Debian`; the bare software token otherwise. */
    val software: String get() = BannerReader.software(banner)
}

sealed interface ProbeEvent {
    data class Progress(val done: Int, val total: Int) : ProbeEvent
    data class Hit(val hit: ProbeHit) : ProbeEvent
}

object BannerReader {
    /** The identification line of an SSH server (RFC 4253 section 4.2): `SSH-protoversion-softwareversion [comments]`, at most 255 bytes. */
    fun accept(line: String?): String? {
        if (line == null || line.length > 253 || !line.startsWith("SSH-")) return null
        if (line.any { it.code < 0x20 || it.code > 0x7e }) return null
        return line
    }

    fun software(banner: String): String {
        val token = banner.substringAfter("SSH-", "").substringAfter('-', "").substringBefore(' ')
        if (token.isEmpty()) return banner
        val name = token.substringBefore('_')
        val version = token.substringAfter('_', "").takeWhile { it.isDigit() || it == '.' }
        return if (version.isEmpty()) name else "$name $version"
    }
}

/**
 * Asks each address whether an SSH server answers on a port: a TCP connect, then at most one line read, then close. There is no
 * key exchange, so the machine sees one connection that sent nothing, and nothing is pinned or trusted. It is bounded by its
 * per-address timeouts and [parallelism]; cancelling the flow starts no new address, and one already running ends within
 * `connectTimeoutMillis + bannerTimeoutMillis`.
 */
class SshProbe(
    private val sockets: Sockets,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_MILLIS,
    private val bannerTimeoutMillis: Int = DEFAULT_BANNER_MILLIS,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    companion object {
        // Measured on the API 36 emulator (Phase 14 spike S1): the first plan values (300 ms, 500 ms, 32 at a time) found nothing on 10.0.2.0/24,
        // because the emulator's NAT answers a connect to the host late while many are in flight; these find the host every time in about 5 s.
        // A real LAN answers a live host in milliseconds, so the margin is for Wi-Fi power saving; the scan stays under about 5 s on a quiet /24.
        const val DEFAULT_CONNECT_MILLIS = 600
        const val DEFAULT_BANNER_MILLIS = 800
        const val DEFAULT_PARALLELISM = 64
    }

    fun scan(addresses: List<Inet4Address>, ports: List<Int>): Flow<ProbeEvent> = channelFlow {
        val targets = addresses.flatMap { a -> ports.distinct().map { p -> a to p } }
        val done = AtomicInteger(0)
        val gate = Semaphore(parallelism)
        send(ProbeEvent.Progress(0, targets.size))
        for ((address, port) in targets) {
            launch {
                gate.withPermit {
                    val hit = withContext(Dispatchers.IO) { probeOne(address, port) }
                    if (hit != null) send(ProbeEvent.Hit(hit))
                    send(ProbeEvent.Progress(done.incrementAndGet(), targets.size))
                }
            }
        }
    }

    private fun probeOne(address: Inet4Address, port: Int): ProbeHit? = try {
        sockets.tcp().use { c ->
            c.connect(address, port, connectTimeoutMillis)
            BannerReader.accept(c.readLine(255, bannerTimeoutMillis))?.let { ProbeHit(address, port, it) }
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
