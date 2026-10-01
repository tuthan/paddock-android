package io.github.tuthan.paddock.ports

/** Wall-clock source, injected so state machines and timeouts are testable without sleeping. */
fun interface Clock {
    fun nowMillis(): Long
}
