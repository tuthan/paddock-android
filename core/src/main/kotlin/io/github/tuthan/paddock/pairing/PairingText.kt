package io.github.tuthan.paddock.pairing

/** What the pairing page shows for one [PairingState]: words, the time left, and which way on it offers. */
data class PairingView(
    val headline: String,
    val detail: String,
    /** The request is still going: Cancel is offered and the countdown runs. */
    val active: Boolean,
    val secondsLeft: Long? = null,
    /** The window ended unheard: the desktop may have written the key, so Connect is offered to find out. */
    val offerConnect: Boolean = false,
    /** The key was authorized: the app connects by itself. */
    val approved: Boolean = false,
)

/** Sentences for the pairing page. One place, tested, so the page never says more than the state knows. */
object PairingText {
    /** The first eight characters after `SHA256:`: what the owner compares first on the desktop, where the plugin sets them off. */
    fun fingerprintHead(fingerprint: String): String = if (fingerprint.startsWith("SHA256:")) fingerprint.drop(7).take(8) else fingerprint.take(8)

    const val COMPARE = "Approve on the desktop only if it shows the same fingerprint."

    fun view(state: PairingState, nowMillis: Long): PairingView? {
        fun left(p: PendingPairing) = ((p.deadlineMillis - nowMillis).coerceAtLeast(0) + 999) / 1000
        return when (state) {
            PairingState.Idle, PairingState.Cancelled -> null
            is PairingState.Sending -> PairingView("Sending this phone's key to ${state.pending.host}…", COMPARE, active = true, secondsLeft = left(state.pending))
            is PairingState.Waiting -> PairingView("Waiting for approval on the desktop.", COMPARE, active = true, secondsLeft = left(state.pending))
            is PairingState.Unreachable -> PairingView(
                "Cannot reach ${state.pending.host}:${state.pending.port} yet.",
                "Paddock keeps trying until the time runs out. This phone and the desktop need to be on the same network, and the pair popup has to stay open. " +
                    "If ${state.pending.host} is a name the phone cannot resolve to the desktop's LAN address, run the pair action with --host set to that address.",
                active = true, secondsLeft = left(state.pending),
            )
            is PairingState.Approved -> PairingView("Approved.", "The key is authorized on the machine. Paddock connects now.", active = false, approved = true)
            is PairingState.Rejected -> PairingView("Rejected on the desktop.", "Nothing was written there. The desktop keeps this answer for the whole popup, so sending again changes nothing: run the pair action again for a new code, or use the command instead.", active = false)
            is PairingState.Expired -> PairingView("The time ran out.", "The desktop did not approve in time. Run the pair action again for a new code.", active = false)
            is PairingState.Refused -> PairingView("The desktop did not recognize this code.", "It belongs to another popup, or the popup was closed. Scan or paste the pairing link again.", active = false)
            is PairingState.Busy -> PairingView("Another key is being paired at this desktop.", "Wait for it to finish or for that popup to close, then run the pair action again for a new code.", active = false)
            is PairingState.NotConfirmed -> PairingView(
                "No answer before the time ran out.",
                "The desktop may have written the key anyway. Press Connect to find out; if the machine does not accept it, run the pair action again for a new code, or use the command.",
                active = false, offerConnect = true,
            )
            is PairingState.CannotReach -> PairingView(
                "Could not reach ${state.pending.host}:${state.pending.port}.",
                "Check that this phone and the desktop are on the same network and that the pair popup is still open. The command always works instead.",
                active = false,
            )
        }
    }

    /** `m:ss`, as the page shows the time left. */
    fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
