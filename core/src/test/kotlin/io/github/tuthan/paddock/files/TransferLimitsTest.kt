package io.github.tuthan.paddock.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TransferLimitsTest {
    private val mib = 1024L * 1024

    @Test fun theCapsAreX3sStartingValues() {
        assertEquals(25 * mib, TransferLimits.MAX_PUT_BYTES)
        assertEquals(50 * mib, TransferLimits.MAX_GET_BYTES)
    }

    @Test fun theDeadlineHasATwentySecondFloor() {
        for (direction in Direction.entries) {
            assertEquals(20.seconds, TransferLimits.deadline(0, direction))
            assertEquals(20.seconds, TransferLimits.deadline(-5, direction))
            assertTrue(TransferLimits.deadline(1, direction) > 20.seconds)
        }
    }

    @Test fun theDeadlineIsTheFloorPlusTwiceTheTimeAtTheProvisionalRate() {
        // 128 KiB/s to the machine and 256 KiB/s to the phone, until S2 measures them.
        assertEquals(36.seconds, TransferLimits.deadline(mib, Direction.ToMachine))
        assertEquals(28.seconds, TransferLimits.deadline(mib, Direction.ToPhone))
        assertEquals(420.seconds, TransferLimits.deadline(TransferLimits.MAX_PUT_BYTES, Direction.ToMachine))
        assertEquals(420.seconds, TransferLimits.deadline(TransferLimits.MAX_GET_BYTES, Direction.ToPhone))
    }

    @Test fun theDeadlineRoundsUpAndNeverShrinksWithSize() {
        assertEquals(22.seconds, TransferLimits.deadline(1, Direction.ToMachine))
        assertEquals(22.seconds, TransferLimits.deadline(128L * 1024, Direction.ToMachine))
        assertEquals(24.seconds, TransferLimits.deadline(128L * 1024 + 1, Direction.ToMachine))
        for (direction in Direction.entries) {
            var previous = TransferLimits.deadline(0, direction)
            for (bytes in listOf(1L, 1_000L, 100_000L, mib, 10 * mib, 25 * mib, 50 * mib)) {
                val now = TransferLimits.deadline(bytes, direction)
                assertTrue(now >= previous, "$direction $bytes")
                previous = now
            }
        }
    }

    @Test fun aFileToTheMachineGetsLongerThanTheSameFileToThePhone() {
        assertTrue(TransferLimits.deadline(10 * mib, Direction.ToMachine) > TransferLimits.deadline(10 * mib, Direction.ToPhone))
    }

    @Test fun anAbsurdSizeDoesNotOverflow() {
        val huge = TransferLimits.deadline(Long.MAX_VALUE, Direction.ToMachine)
        assertTrue(huge > TransferLimits.deadline(TransferLimits.MAX_GET_BYTES, Direction.ToMachine))
    }
}
