package io.github.tuthan.paddock.alerts

/**
 * What Settings says about how alerts reach a locked phone (AC-07.8): measured best effort, never always-on. The measured
 * line is edited together with the evidence report that holds the numbers, and says plainly when there are none.
 */
object AlertDelivery {
    const val MODE = "Through ntfy: the public server or your own"

    const val BEST_EFFORT =
        "Best effort. Delivery depends on the ntfy app, this phone's battery settings and the network, so an alert can be late or missing. It is not an always-on service."

    /**
     * The recorded delivery times, from the Phase 07 evidence report (`paddock-harness/measure-alert-latency.py`, 2026-10-02). They are the
     * relay's own part against a local stand-in for the push server; the phone, the network and the ntfy app are not in them.
     */
    const val MEASURED =
        "Measured delivery: the relay's own part, 20 alerts per mode on a test machine. Median 32 ms and 95th percentile 102 ms with ntfy, " +
            "54 ms and 93 ms with UnifiedPush, none missed. Delivery to a phone is not measured yet."

    const val HOW =
        "A small script on your machine watches herdr and tells your phone through ntfy when an agent becomes blocked or done. " +
            "Paddock can show the alert itself, or the ntfy app can (that also works on an iPhone). " +
            "The message names no agent and carries no prompt text; on Android, tapping an alert opens Paddock, which reads the herd over SSH and shows what is true now."
}

/**
 * One machine's UnifiedPush registration in words. The alert relay screen's status line and the machine page's alert line say the same thing, so
 * the sentences live here once. The relay screen knows whether the address file is on the host now ([onHost]); the machine page does not (it reads
 * nothing over SSH) and passes null.
 */
object PushWords {
    /** No registration for this machine on this phone. Alerts through the ntfy app are configured on the machine and are not seen from here. */
    const val NOT_REGISTERED = "No UnifiedPush registration for this machine on this phone."
    const val FAILED = "Registration failed. Register again."
    const val WAITING = "Waiting for the distributor's address…"

    fun state(hasEndpoint: Boolean, shared: Boolean, failed: Boolean, machine: String, endpointHost: String? = null, onHost: Boolean? = null): String {
        val host = endpointHost?.let { " (host $it)" } ?: ""
        return when {
            hasEndpoint && shared && onHost == false -> "The address was sent, but the file is not on $machine now. Send it again."
            hasEndpoint && shared -> "The address is on $machine$host."
            hasEndpoint -> "The distributor gave this phone an address$host. It is not on $machine yet."
            failed -> FAILED
            else -> WAITING
        }
    }

    /** The machine page's line: [NOT_REGISTERED] without a registration, otherwise [state] without the host's file (not read from the page). */
    fun state(registration: PushRegistration?, machine: String): String = registration?.let {
        state(hasEndpoint = it.endpoint != null, shared = it.sharedAtMillis != null, failed = it.failure != null, machine = machine)
    } ?: NOT_REGISTERED
}
