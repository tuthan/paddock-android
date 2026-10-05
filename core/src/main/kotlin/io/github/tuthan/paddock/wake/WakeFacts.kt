package io.github.tuthan.paddock.wake

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
) {
    /** True once something left the phone, so the other two facts mean something. */
    val transmitted: Boolean get() = result is WakeSendResult.Sent || result is WakeSendResult.Partial

    fun lines(clockLabel: (Long) -> String): List<String> {
        val first = sentence(result)
        if (!transmitted) return listOf(first)
        return listOf(
            first,
            "The machine answered: " + (answeredAtMillis?.let { "at ${clockLabel(it)}" } ?: "not yet"),
            "herdr reachable: " + (reachableAtMillis?.let { "at ${clockLabel(it)}" } ?: "not yet"),
        )
    }

    /** Another Wake is offered only after the guard: a second tap changes nothing a magic packet has not already said. */
    fun canWakeAgain(nowMillis: Long): Boolean = nowMillis - sentAtMillis >= GUARD_MILLIS

    fun secondsUntilAgain(nowMillis: Long): Long = ((GUARD_MILLIS - (nowMillis - sentAtMillis)).coerceAtLeast(0) + 999) / 1000

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
