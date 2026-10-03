package io.github.tuthan.paddock.alerts

/**
 * What Settings says about how alerts reach a locked phone (AC-07.8): measured best effort, never always-on. The measured
 * line is edited together with the evidence report that holds the numbers, and says plainly when there are none.
 */
object AlertDelivery {
    const val MODE = "Alerts via ntfy app"

    const val BEST_EFFORT =
        "Best effort. Delivery depends on the ntfy app, this phone's battery settings and the network, so an alert can be late or missing. It is not an always-on service."

    /**
     * The recorded delivery times, from the Phase 07 evidence report (`tools/measure-alert-latency.py`, 2026-10-02). They are the
     * relay's own part against a local stand-in for the push server; the phone, the network and the ntfy app are not in them.
     */
    const val MEASURED =
        "Measured delivery: the relay's own part, 20 alerts per mode on a test machine. Median 32 ms and 95th percentile 102 ms with ntfy, " +
            "54 ms and 93 ms with UnifiedPush, none missed. Delivery to a phone is not measured yet."

    const val HOW =
        "A small script on your machine watches herdr and posts one generic message to your ntfy topic when an agent becomes blocked or done. " +
            "The ntfy app shows it; tapping it opens Paddock, which reads the herd over SSH and shows what is true now. " +
            "The message names no agent and carries no prompt text."
}
