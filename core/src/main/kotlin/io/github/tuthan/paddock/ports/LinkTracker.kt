package io.github.tuthan.paddock.ports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only writer of an adapter's [LinkState]. Starts Down(Closed); Up is reachable only from Connecting,
 * and a Down state is replaced only by Connecting, so a late callback cannot resurrect a dead link.
 */
class LinkTracker(private val clock: Clock) {
    private val state = MutableStateFlow<LinkState>(LinkState.Down(DownReason.Closed, clock.nowMillis()))
    val link: StateFlow<LinkState> get() = state

    fun connecting() {
        if (state.value !is LinkState.Connecting) state.value = LinkState.Connecting(clock.nowMillis())
    }

    fun up() {
        if (state.value is LinkState.Connecting) state.value = LinkState.Up(clock.nowMillis())
    }

    /** The first reason wins; a link that is already Down keeps its original cause and time. */
    fun down(reason: DownReason) {
        if (state.value !is LinkState.Down) state.value = LinkState.Down(reason, clock.nowMillis())
    }
}
