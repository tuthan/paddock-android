package io.github.tuthan.paddock.billing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one sentence a deferred tap on a Pro control shows ([ProGate.deferNotice]), and how long it stays (review F6): a tap that does nothing and says
 * nothing reads as a broken app. The screen shows [text] while it is set; it goes by itself after [showMillis], so it is never about an operation that
 * has since finished, and the user can dismiss it sooner. This is the whole of what the graph does with a gate decision besides opening the sheet.
 */
class GateNotice(private val scope: CoroutineScope, private val showMillis: Long = SHOW_MILLIS) {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()
    private val lock = Any()
    private var clear: Job? = null

    /** What a decision means for the notice: a deferral says why, a sheet replaces any earlier sentence, and a capability that proceeds changes nothing. */
    fun decided(decision: GateDecision, context: GateContext) {
        when (decision) {
            GateDecision.DEFER -> ProGate.deferNotice(context)?.let(::show)
            GateDecision.SHOW_GATE -> dismiss()
            GateDecision.PROCEED -> Unit
        }
    }

    /** Shows [sentence]; a second one while it shows restarts the wait instead of cutting the sentence short. */
    fun show(sentence: String) = synchronized(lock) {
        clear?.cancel()
        _text.value = sentence
        // The clear only counts while it is still the current one: a cancel that lost the race with the delay must not wipe the sentence that replaced it.
        clear = scope.launch { delay(showMillis); synchronized(lock) { if (clear === coroutineContext[Job]) { _text.value = null; clear = null } } }
    }

    fun dismiss() = synchronized(lock) { clear?.cancel(); clear = null; _text.value = null }

    companion object {
        /** Long enough to read twice, short enough that it is not still up when the operation it names has finished. */
        const val SHOW_MILLIS = 8_000L
    }
}
