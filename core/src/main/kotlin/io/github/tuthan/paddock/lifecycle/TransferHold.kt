package io.github.tuthan.paddock.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * X11: while the user is in a system picker or the camera, Paddock is not in front, and a watch that follows the foreground would close the connection
 * the attachment is about to use. The picker's caller holds the watch for as long as the trip lasts, at most [MAX_MILLIS]: a trip the user abandons
 * must not keep a connection open on its own. The hold feeds only the watch (`AppGraph`'s combine). The app lock's stopwatch, the heartbeat and every other
 * reader of the foreground keep their own rule, so the lock still counts the time away and a trip reads nothing until the return's re-read.
 */
class TransferHold(private val scope: CoroutineScope, private val cap: Long = MAX_MILLIS) {
    private val state = MutableStateFlow(false)
    private var timer: Job? = null
    private val lock = Any()

    val active: StateFlow<Boolean> = state.asStateFlow()

    /** Starts holding, or restarts the limit when a hold is already on. Ends by itself after [maxMillis]. */
    fun hold(maxMillis: Long = MAX_MILLIS) {
        synchronized(lock) {
            timer?.cancel()
            state.value = true
            timer = scope.launch {
                delay(maxMillis.coerceIn(1, cap))
                synchronized(lock) { state.value = false; timer = null }
            }
        }
    }

    /** Ends the hold: the picker returned, or the user backed out. Safe when nothing is held. */
    fun release() {
        synchronized(lock) {
            timer?.cancel()
            timer = null
            state.value = false
        }
    }

    companion object {
        const val MAX_MILLIS = 120_000L
    }
}
