package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.ports.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What reached the app from outside, waiting to be shown. [arrivedAtMillis] is when the phone received it, on the phone's clock. */
sealed interface AlertEvent {
    val arrivedAtMillis: Long
    data class Arrived(val hint: AlertHint, override val arrivedAtMillis: Long) : AlertEvent
    /** A push for a machine that named no terminal: the app reads that machine's herd and says what it found. */
    data class MachineWoke(val hint: MachineHint, override val arrivedAtMillis: Long) : AlertEvent
    /** A link that did not pass [DeepLink.parse]. Nothing from it is kept. */
    data class Invalid(override val arrivedAtMillis: Long) : AlertEvent
}

/**
 * The one place an alert enters the app. [offer] parses; the screen layer takes the [event], resolves it against a fresh read
 * and calls [consume]. A newer alert replaces one still waiting. The same relay message delivered twice in quick succession
 * (a tap that fires both the click and the action, a redelivered intent) is one arrival: dedupe is by profile and relay
 * sequence (or a push's nonce) within [DEDUPE_MILLIS], and by nothing else.
 */
class AlertInbox(private val clock: Clock) {
    private val _event = MutableStateFlow<AlertEvent?>(null)
    val event: StateFlow<AlertEvent?> = _event.asStateFlow()
    private var lastKey: Pair<String, String>? = null
    private var lastAt = 0L

    fun offer(text: String?) {
        val now = clock.nowMillis()
        when (val parsed = DeepLink.parse(text)) {
            is DeepLinkResult.Rejected -> _event.value = AlertEvent.Invalid(now)
            is DeepLinkResult.Valid -> synchronized(this) {
                val key = parsed.hint.target.host.value to "seq:${parsed.hint.sequence}"
                if (key == lastKey && now - lastAt < DEDUPE_MILLIS) return
                lastKey = key; lastAt = now
                _event.value = AlertEvent.Arrived(parsed.hint, now)
            }
            is DeepLinkResult.Machine -> synchronized(this) {
                val key = parsed.hint.host.value to "nonce:${parsed.hint.nonce}"
                if (key == lastKey && now - lastAt < DEDUPE_MILLIS) return
                lastKey = key; lastAt = now
                _event.value = AlertEvent.MachineWoke(parsed.hint, now)
            }
        }
    }

    /** Clears [event] only if it is still the one that was handled; a newer arrival stays. */
    fun consume(event: AlertEvent) { _event.compareAndSet(event, null) }

    companion object { const val DEDUPE_MILLIS = 5_000L }
}
