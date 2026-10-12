package io.github.tuthan.paddock.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferHoldTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hold = TransferHold(scope)

    @After fun stop() = scope.cancel()

    @Test fun itIsOffUntilHeld() = assertFalse(hold.active.value)

    @Test fun holdingTurnsItOnAndReleasingTurnsItOff() {
        hold.hold()
        assertTrue(hold.active.value)
        hold.release()
        assertFalse(hold.active.value)
    }

    @Test fun releasingWhenNothingIsHeldChangesNothing() {
        hold.release()
        assertFalse(hold.active.value)
    }

    @Test fun anAbandonedTripEndsByItself() = runBlocking {
        hold.hold(maxMillis = 60)
        assertTrue(hold.active.value)
        withTimeout(5_000) { hold.active.first { !it } }
        Unit
    }

    @Test fun theLimitIsTwoMinutes() = assertEquals(120_000L, TransferHold.MAX_MILLIS)

    @Test fun aRequestOverTheLimitIsBoundedByIt() = runBlocking {
        val capped = TransferHold(scope, cap = 80)
        capped.hold(maxMillis = Long.MAX_VALUE)
        assertTrue(capped.active.value)
        withTimeout(5_000) { capped.active.first { !it } }
        Unit
    }

    @Test fun holdingAgainRestartsTheLimitAndAnOldTimerDoesNotReleaseTheNewHold() = runBlocking {
        hold.hold(maxMillis = 80)
        delay(40)
        hold.hold(maxMillis = 400)
        delay(120) // past the first timer's limit
        assertTrue(hold.active.value, "the first hold's timer must not end the second hold")
        withTimeout(5_000) { hold.active.first { !it } }
        Unit
    }

    @Test fun aReleaseBeforeTheLimitMeansTheTimerCannotTurnALaterHoldOff() = runBlocking {
        hold.hold(maxMillis = 80)
        hold.release()
        hold.hold(maxMillis = 2_000)
        delay(160)
        assertTrue(hold.active.value)
        hold.release()
    }
}
