package io.github.tuthan.paddock.applock

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private var now = 1_000_000L
    private val lock = AppLock { now }

    private fun leaveFor(seconds: Long, enabled: Boolean = true, after: Int = 60) {
        lock.hidden(); now += seconds * 1000; lock.shown(enabled, after)
    }

    @Test fun everythingIsCoveredUntilTheSavedChoiceIsKnown() {
        assertTrue(lock.locked.value, "the first frames, before the settings are read")
        lock.start(enabled = false)
        assertFalse(lock.locked.value, "lock off: opens as soon as that is known")
    }

    @Test fun aColdStartIsLockedWhenTheLockIsOn() {
        lock.start(enabled = true)
        assertTrue(lock.locked.value)
    }

    @Test fun theFirstChoiceWinsAndALateSecondOneChangesNothing() {
        lock.start(enabled = false); lock.start(enabled = true)
        assertFalse(lock.locked.value)
    }

    @Test fun backWithinTheTimeoutStaysOpenAndAfterItCovers() {
        lock.start(false); lock.enabledChanged(true)
        leaveFor(59); assertFalse(lock.locked.value, "59 s of 60")
        leaveFor(60); assertTrue(lock.locked.value, "60 s of 60 covers")
    }

    @Test fun theLongerTimeoutsHoldToo() {
        for (after in listOf(300, 900)) {
            lock.start(false); lock.unlocked(); lock.enabledChanged(true)
            leaveFor(after - 1L, after = after); assertFalse(lock.locked.value, "$after - 1")
            leaveFor(after.toLong(), after = after); assertTrue(lock.locked.value, "$after")
            lock.unlocked()
        }
    }

    @Test fun immediatelyStillIgnoresARotationOrADialog() {
        lock.start(false); lock.enabledChanged(true)
        lock.hidden(); now += 400; lock.shown(true, afterSeconds = 0)
        assertFalse(lock.locked.value, "an activity recreated in under a second is not leaving")
        lock.hidden(); now += AppLock.MIN_AWAY_MILLIS - 1; lock.shown(true, 0)
        assertFalse(lock.locked.value)
        lock.hidden(); now += AppLock.MIN_AWAY_MILLIS; lock.shown(true, 0)
        assertTrue(lock.locked.value, "three seconds away is leaving")
    }

    @Test fun aLockThatIsOffNeverCovers() {
        lock.start(false)
        leaveFor(3600, enabled = false)
        assertFalse(lock.locked.value)
    }

    @Test fun turningItOffOpensTheScreensAndTurningItOnDoesNotCoverThem() {
        lock.start(true)
        assertTrue(lock.locked.value)
        lock.enabledChanged(false); assertFalse(lock.locked.value)
        lock.enabledChanged(true); assertFalse(lock.locked.value, "the user just proved themselves; covering now would ask twice")
    }

    @Test fun thePhonesCredentialScreenTakingTheAppToTheBackDoesNotCoverItAgain() {
        lock.start(true); lock.authenticating()
        lock.hidden(); now += 20_000; lock.shown(true, afterSeconds = 0)   // the credential activity came and went
        lock.unlocked()
        assertFalse(lock.locked.value, "a right PIN must not lock the app on the way back")
        leaveFor(1); assertFalse(lock.locked.value, "and the stopwatch starts fresh after it")
    }

    @Test fun givingUpLeavesItCoveredAndLeavingCountsAgain() {
        lock.start(true); lock.authenticating(); lock.cancelled()
        assertTrue(lock.locked.value)
        lock.unlocked()
        leaveFor(61); assertTrue(lock.locked.value)
    }

    @Test fun leavingTwiceWithoutComingBackKeepsTheFirstTime() {
        lock.start(false); lock.enabledChanged(true)
        lock.hidden(); now += 50_000; lock.hidden(); now += 20_000; lock.shown(true, 60)
        assertTrue(lock.locked.value, "70 s since the first leave, not 20 since the second")
    }

    @Test fun anEventBeforeStartIsIgnored() {
        lock.hidden(); now += 100_000; lock.shown(true, 60)
        assertTrue(lock.locked.value)
        lock.start(false)
        assertFalse(lock.locked.value, "and the choice, once known, is not overridden by what came before it")
    }

    @Test fun lockNowCoversAtOnce() {
        lock.start(false); lock.enabledChanged(true)
        lock.lockNow()
        assertTrue(lock.locked.value)
    }

    @Test fun aSavedTimeoutThisBuildDoesNotOfferFallsToOneMinuteNotToNever() {
        assertEquals(LockTimeout.OneMinute, LockTimeout.of(7))
        assertEquals(LockTimeout.Immediately, LockTimeout.of(0))
        assertEquals(LockTimeout.FifteenMinutes, LockTimeout.of(900))
        assertEquals(LockTimeout.DEFAULT_SECONDS, LockTimeout.OneMinute.seconds)
    }
}
