package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ui.screens.WakeCommandRow
import io.github.tuthan.paddock.ui.screens.WakeWords
import io.github.tuthan.paddock.wake.WakeCommands
import io.github.tuthan.paddock.wake.WakeFacts
import io.github.tuthan.paddock.wake.WakeRelay

/** Turns what the phone knows about waking a machine into what Settings shows. Pure: the same inputs always give the same words. */
object WakeWordsMapper {
    const val NOT_READ = "Not read yet. Connect to this machine once while it is awake, and Paddock reads how to wake it."

    /**
     * [wakeReady] says a packet can be sent from here (a read hardware address, and a LAN path or a saved relay); [phoneSuggestion] is the
     * default gateway of the phone's own LAN, offered beside the machine's gateway as a place to start. Neither suggestion is ever saved
     * by itself.
     */
    fun words(
        profile: HostProfile, facts: WakeFacts?, nowMillis: Long, wakeReady: Boolean, phoneSuggestion: WakeRelay?, clockLabel: (Long) -> String,
    ): WakeWords {
        val target = profile.wake
        val saved = target?.relay
        val suggestions = listOfNotNull(target?.gateway?.let { WakeRelay.parse(it) }, phoneSuggestion)
            .filter { it != saved }.distinct().map { it.format() }
        val status = if (target == null) NOT_READ else WakeCommands.status(target, clockLabel(target.capturedAtMillis))
        return WakeWords(
            status = status,
            commands = target?.let { WakeCommands.forTarget(it).map { c -> WakeCommandRow(c.label, c.text) } }.orEmpty(),
            relay = saved?.format(),
            suggestions = suggestions,
            canWake = wakeReady && facts?.canWakeAgain(nowMillis) != false,
            lines = facts?.lines(clockLabel).orEmpty(),
        )
    }
}
