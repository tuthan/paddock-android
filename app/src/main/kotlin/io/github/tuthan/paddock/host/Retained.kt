package io.github.tuthan.paddock.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps one live item (a terminal session) across the destroy and re-create of its screen, which is what a rotation does,
 * so turning the phone neither releases control nor reconnects. The item is [acquire]d for a key; a [release] that says the
 * screen is only being re-created keeps it for [graceMillis], and the next [acquire] for the same key takes it back. Any
 * other release, an acquire for another key, or the grace running out closes it with [close].
 */
class Retained<T : Any>(private val scope: CoroutineScope, private val graceMillis: Long = 3_000, private val close: (T) -> Unit) {
    private val lock = Any()
    private var key: Any? = null
    private var item: T? = null
    private var opened = false
    private var drop: Job? = null

    fun acquire(key: Any, make: () -> T): T = synchronized(lock) {
        drop?.cancel(); drop = null
        val held = item
        if (held != null && this.key == key) return held
        held?.let(close)
        make().also { item = it; this.key = key; opened = false }
    }

    /** True exactly once per item: the caller starts the item then. */
    fun firstUse(held: T): Boolean = synchronized(lock) { if (held === item && !opened) { opened = true; true } else false }

    fun release(held: T, recreating: Boolean) = synchronized(lock) {
        if (held !== item) return@synchronized
        if (!recreating) { dropNow(); return@synchronized }
        drop?.cancel()
        drop = scope.launch { delay(graceMillis); synchronized(lock) { if (held === item) dropNow() } }
    }

    private fun dropNow() {
        drop?.cancel(); drop = null
        item?.let(close)
        item = null; key = null; opened = false
    }
}
