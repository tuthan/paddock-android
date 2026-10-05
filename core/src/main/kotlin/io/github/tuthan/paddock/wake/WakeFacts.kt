package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.reconcile.Freshness
import java.net.InetAddress

/**
 * What one Wake tap came to, as three separate facts and never one: the packet was sent (or was not), the machine answered the SSH
 * connect, herdr answered the first read. "Sent" is never read as "awake". [clockLabel] turns a time into the screen's words.
 */
data class WakeFacts(
    val sentAtMillis: Long,
    val result: WakeSendResult,
    val answeredAtMillis: Long? = null,
    val reachableAtMillis: Long? = null,
    /**
     * The connection was already up and herdr already live when the tap came. The two later facts would then be true at the tap and
     * say nothing about this packet, so they are not recorded and the screen says what it saw instead.
     */
    val alreadyLive: Boolean = false,
) {
    /** True once something left the phone, so the other two facts mean something. */
    val transmitted: Boolean get() = result is WakeSendResult.Sent || result is WakeSendResult.Partial

    /** Both later facts are known, or there is nothing to wait for: the connection need not be followed any more. */
    val settled: Boolean get() = alreadyLive || (answeredAtMillis != null && reachableAtMillis != null)

    /** What a later look at the connection adds: a fact once seen is kept, a fact not yet seen is recorded at [nowMillis]. */
    fun observed(link: WakeLink, nowMillis: Long): WakeFacts = copy(
        answeredAtMillis = answeredAtMillis ?: nowMillis.takeIf { link.answered },
        reachableAtMillis = reachableAtMillis ?: nowMillis.takeIf { link.reachable },
    )

    fun lines(clockLabel: (Long) -> String): List<String> {
        val first = sentence(result)
        if (!transmitted) return listOf(first)
        if (alreadyLive) return listOf(first, "The machine was already connected, and herdr already live, when you tapped.")
        return listOf(
            first,
            "The machine answered: " + (answeredAtMillis?.let { "at ${clockLabel(it)}" } ?: "not yet"),
            "herdr reachable: " + (reachableAtMillis?.let { "at ${clockLabel(it)}" } ?: "not yet"),
        )
    }

    /**
     * Another Wake is offered only after the guard: a second tap changes nothing a magic packet has not already said. The guard
     * follows what left the phone: a tap that sent nothing (no grant, no relay, no network) leaves the action where it was.
     */
    fun canWakeAgain(nowMillis: Long): Boolean = !transmitted || nowMillis - sentAtMillis >= GUARD_MILLIS

    fun secondsUntilAgain(nowMillis: Long): Long =
        if (!transmitted) 0 else ((GUARD_MILLIS - (nowMillis - sentAtMillis)).coerceAtLeast(0) + 999) / 1000

    /** Settings should open the relay field when the failure says a relay is the fix. */
    val needsRelay: Boolean get() = (result as? WakeSendResult.Failed)?.reason == WakeSendFailure.RelayRequired

    companion object {
        const val GUARD_MILLIS = 30_000L

        fun sentence(result: WakeSendResult): String = when (result) {
            is WakeSendResult.Sent ->
                if (result.viaRelay) "Wake packet sent to the relay ${list(result.destinations)}. The relay must re-broadcast it; nothing reached the machine's network from here."
                else "Wake packet sent to ${list(result.destinations)}."
            is WakeSendResult.Partial ->
                "Wake packets were sent to ${list(result.destinations)}, then sending stopped: ${reason(result.reason, result.detail)}. Tap Wake to try again."
            is WakeSendResult.Failed -> "No wake packet was sent: ${reason(result.reason, result.detail)}."
        }

        private fun reason(r: WakeSendFailure, detail: String?): String = when (r) {
            WakeSendFailure.TargetMissing -> "Paddock has not read this machine's hardware address yet. Connect to it once while it is awake"
            WakeSendFailure.NetworkMissing -> "this phone is on no network that can reach the machine"
            WakeSendFailure.Permission -> "local-network access is off. Turn it on in the app's settings"
            WakeSendFailure.SendFailed -> "the network refused it" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            WakeSendFailure.RelayRequired -> "waking from away needs a relay on the machine's network. Save one in Settings"
        }

        private fun list(a: List<InetAddress>): String {
            val names = a.map { it.hostAddress.orEmpty() }
            return when (names.size) { 0 -> "no address"; 1 -> names[0]; else -> names.dropLast(1).joinToString(", ") + " and " + names.last() }
        }
    }
}

/**
 * What the phone sees of the connection to the machine, reduced to what the two later facts are about. Any phase past Connecting
 * means the SSH connect itself answered; only a live read means herdr did.
 */
data class WakeLink(val answered: Boolean, val reachable: Boolean) {
    companion object {
        val DOWN = WakeLink(answered = false, reachable = false)

        fun of(phase: HostPhase?, freshness: Freshness?) = WakeLink(
            answered = phase is HostPhase.InstallingRelay || phase is HostPhase.NeedsRelayInstall || phase is HostPhase.Problem || phase is HostPhase.Monitoring,
            reachable = phase is HostPhase.Monitoring && freshness == Freshness.Live,
        )
    }
}
