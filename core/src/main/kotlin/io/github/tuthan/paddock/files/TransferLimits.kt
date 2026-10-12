package io.github.tuthan.paddock.files

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class Direction { ToMachine, ToPhone }

/**
 * The caps are safety bounds (SEC-8), the same for everyone (X3, X6): the phone refuses before it opens a channel and
 * the script refuses before it reads a byte. The deadline scales with the size so a slow link is not cut while it is
 * moving, and a stalled one still is.
 *
 * The two rates are PROVISIONAL until spike S2 measures them on the phone, on the LAN and over a VPN, in both
 * directions. The plan's rule is then the same with the slower measured rate per direction; the caps follow S2 too
 * (25 MB and 50 MB are X3's starting values).
 */
object TransferLimits {
    const val MAX_PUT_BYTES: Long = 25L * 1024 * 1024
    const val MAX_GET_BYTES: Long = 50L * 1024 * 1024

    private val FLOOR = 20.seconds

    // Provisional, in bytes per second: sshlib's 30,000-byte window over a 100 ms path is about 300 KB/s, so these are
    // slower than that. Replace with S2's numbers.
    private const val PUT_RATE = 128L * 1024
    private const val GET_RATE = 256L * 1024

    /** A 20 s floor plus twice the time [bytes] take at the slower rate for [direction], whole seconds, rounded up. */
    fun deadline(bytes: Long, direction: Direction): Duration {
        val rate = if (direction == Direction.ToMachine) PUT_RATE else GET_RATE
        val n = bytes.coerceAtLeast(0)
        val seconds = n / rate + if (n % rate == 0L) 0 else 1
        return FLOOR + (2 * seconds).seconds
    }
}
