package io.github.tuthan.paddock.hostprofile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What reached the app as a pairing link, waiting for the screen layer. Nothing from a refused link is kept but why. */
sealed interface PairingEvent {
    data class Link(val link: PairingLink) : PairingEvent
    data class Invalid(val reason: PairingRejection) : PairingEvent
}

/**
 * The one place a pairing link enters the app, whether it was tapped (a VIEW intent) or pasted. [offer] parses; the screen
 * layer shows Add machine pre-filled and calls [consume]. A newer link replaces one still waiting. Opening one connects
 * nothing and trusts nothing: it only fills in the form.
 */
class PairingInbox {
    private val _event = MutableStateFlow<PairingEvent?>(null)
    val event: StateFlow<PairingEvent?> = _event.asStateFlow()

    fun offer(text: String?) {
        _event.value = when (val parsed = PairingLinks.parse(text)) {
            is PairingResult.Valid -> PairingEvent.Link(parsed.link)
            is PairingResult.Rejected -> PairingEvent.Invalid(parsed.reason)
        }
    }

    /** Clears [event] only if it is still the one that was handled. */
    fun consume(event: PairingEvent) { _event.compareAndSet(event, null) }
}

/** What the notice says about a pairing link that could not be used. It never repeats the link. */
object PairingCopy {
    fun invalid(reason: PairingRejection): String = when (reason) {
        PairingRejection.NotAPairingLink -> "That is not a Paddock pairing link. Nothing was filled in."
        PairingRejection.UnsupportedVersion -> "This pairing link is from a newer Paddock. Update the app, or type the machine in."
        else -> "That pairing link is damaged or not valid, so nothing was filled in. Make a new one on the machine, or type the machine in."
    }

    const val FILLED_IN = "Filled in from a pairing link. Use only a link you made yourself on your own machine: whoever makes the link chooses the machine. " +
        "When you connect, the machine's host key is compared with the fingerprint in the link, and you still choose whether to trust it."
    const val FIELDS_CHANGED = "The host or port no longer match the pairing link, so its fingerprint is not compared. The first connection asks as usual."
}
