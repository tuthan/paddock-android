package io.github.tuthan.paddock.ports

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BoundedBytesTest {
    @Test
    fun underTheLimitIsNotTruncated() {
        val b = BoundedBytes(10).apply { append(byteArrayOf(1, 2, 3)); append(byteArrayOf(4)) }
        assertContentEquals(byteArrayOf(1, 2, 3, 4), b.toByteArray())
        assertFalse(b.truncated)
    }

    @Test
    fun exactlyTheLimitIsNotTruncated() {
        val b = BoundedBytes(3).apply { append(byteArrayOf(1, 2, 3)) }
        assertFalse(b.truncated)
    }

    @Test
    fun overTheLimitKeepsTheHeadAndFlags() {
        val b = BoundedBytes(4).apply { append(byteArrayOf(1, 2, 3)); append(byteArrayOf(4, 5, 6)); append(byteArrayOf(7)) }
        assertContentEquals(byteArrayOf(1, 2, 3, 4), b.toByteArray())
        assertTrue(b.truncated)
    }

    @Test
    fun partialChunkLengthIsHonoured() {
        val b = BoundedBytes(2).apply { append(byteArrayOf(9, 9, 9, 9), length = 2) }
        assertEquals(2, b.toByteArray().size)
        assertFalse(b.truncated)
    }

    @Test
    fun defaultLimitsMatchThePhaseNote() {
        val d = ExecLimits.default
        assertEquals(1 shl 20, d.stdoutMax)
        assertEquals(64 shl 10, d.stderrMax)
        assertEquals(20.seconds, d.deadline)
    }
}
