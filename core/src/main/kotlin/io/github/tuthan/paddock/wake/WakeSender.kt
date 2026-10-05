package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.Sockets
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetAddress

sealed interface WakeSendResult {
    /** [viaRelay]: the packet went to a relay, which has to re-broadcast it; nothing reached the machine's network from here. */
    data class Sent(val destinations: List<InetAddress>, val viaRelay: Boolean = false) : WakeSendResult

    /** Some packets may have left the phone and a later one failed. Only a new tap may send again. */
    data class Partial(val destinations: List<InetAddress>, val reason: WakeSendFailure, val detail: String? = null) : WakeSendResult

    data class Failed(val reason: WakeSendFailure, val detail: String? = null) : WakeSendResult
}

enum class WakeSendFailure { TargetMissing, NetworkMissing, Permission, SendFailed, RelayRequired }

/**
 * One foreground Wake tap. It sends the magic packet and never reads "sent" as "the machine is awake": that is the SSH connect and
 * the first read, shown as separate facts by the screen. Three identical packets are sent a short gap apart, because a single
 * UDP datagram is easily lost on Wi-Fi.
 */
class WakeSender(
    private val sockets: Sockets,
    private val permissionMissing: () -> Boolean = { false },
    private val packets: Int = 3,
    private val gapMillis: Long = 100,
) {
    /**
     * Tries [candidates] in order until one transmits. A path that cannot be used must not hide one that works; an unusable
     * tunnel without a relay is reported ahead of a LAN failure, because adding the relay is the fix.
     */
    suspend fun send(target: WakeTarget, candidates: List<LanPath>, relay: WakeRelay?): WakeSendResult {
        if (!target.available || target.mac == null) return WakeSendResult.Failed(WakeSendFailure.TargetMissing)
        var last: WakeSendResult.Failed? = null
        var relayNeeded: WakeSendResult.Failed? = null
        for (path in candidates) {
            when (val r = sendOn(target.mac, path, relay)) {
                is WakeSendResult.Sent, is WakeSendResult.Partial -> return r
                is WakeSendResult.Failed -> when (r.reason) {
                    WakeSendFailure.TargetMissing, WakeSendFailure.Permission -> return r
                    WakeSendFailure.RelayRequired -> relayNeeded = r
                    else -> last = r
                }
            }
        }
        return relayNeeded ?: last ?: WakeSendResult.Failed(WakeSendFailure.NetworkMissing)
    }

    private suspend fun sendOn(mac: String, path: LanPath, relay: WakeRelay?): WakeSendResult {
        if (permissionMissing()) return WakeSendResult.Failed(WakeSendFailure.Permission)
        if (path.tunnel && relay == null) return WakeSendResult.Failed(WakeSendFailure.RelayRequired)
        val destinations: List<InetAddress> = if (path.tunnel) listOf(relay!!.inet()) else if (!path.hasIpv4) emptyList() else (path.broadcasts + WakePaths.LIMITED_BROADCAST).distinct()
        if (destinations.isEmpty()) return WakeSendResult.Failed(WakeSendFailure.NetworkMissing)
        val port = if (path.tunnel) relay!!.port else LAN_PORT
        val payload = MagicPacket.bytes(mac)
        val sent = LinkedHashSet<InetAddress>()
        var failure: WakeSendFailure? = null
        var detail: String? = null
        var denied = false
        try {
            sockets.udp(path).use { udp ->
                for (round in 0 until packets) {
                    if (round > 0) delay(gapMillis)
                    for (address in destinations) {
                        if (permissionMissing()) { failure = WakeSendFailure.Permission; break }
                        // A failed send did not transmit that packet; the next destination is still tried (some networks refuse the
                        // limited broadcast or the directed one, rarely both).
                        try {
                            udp.send(payload, address, port, broadcast = !path.tunnel)
                            sent += address
                        } catch (e: IOException) {
                            if (isDenied(e)) denied = true
                            if (failure == null) { failure = WakeSendFailure.SendFailed; detail = describe(e) }
                        }
                    }
                    if (failure == WakeSendFailure.Permission) break
                }
            }
        } catch (e: SecurityException) {
            return failed(sent, WakeSendFailure.Permission, describe(e))
        } catch (e: IOException) {
            return failed(sent, if (isDenied(e)) WakeSendFailure.Permission else WakeSendFailure.SendFailed, describe(e))
        }
        val f = failure
        // Nothing left the phone and the system said EPERM: that is the local-network grant being off (spike S3), not a network that refused.
        // One destination refused with EPERM while another went out is not that, and stays a send failure.
        if (f == WakeSendFailure.SendFailed && denied && sent.isEmpty()) return WakeSendResult.Failed(WakeSendFailure.Permission, detail)
        return if (f != null) failed(sent, f, detail) else WakeSendResult.Sent(sent.toList(), viaRelay = path.tunnel)
    }

    private fun failed(sent: Set<InetAddress>, reason: WakeSendFailure, detail: String?): WakeSendResult =
        if (sent.isEmpty()) WakeSendResult.Failed(reason, detail) else WakeSendResult.Partial(sent.toList(), reason, detail)

    companion object {
        const val LAN_PORT = 9

        /** Android 17 without ACCESS_LOCAL_NETWORK fails a send with `IOException: sendto failed: EPERM (Operation not permitted)`, not with a SecurityException. */
        private fun isDenied(e: IOException) = "EPERM" in e.message.orEmpty()

        fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim().trimEnd(':').take(120)
    }
}
