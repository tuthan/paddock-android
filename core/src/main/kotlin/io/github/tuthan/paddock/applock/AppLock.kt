package io.github.tuthan.paddock.applock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long Paddock may be out of sight before it asks again. The first is "as soon as you have really left" (see [AppLock.MIN_AWAY_MILLIS]). */
enum class LockTimeout(val seconds: Int, val label: String) {
    Immediately(0, "Immediately"), OneMinute(60, "1 minute"), FiveMinutes(300, "5 minutes"), FifteenMinutes(900, "15 minutes");

    companion object {
        const val DEFAULT_SECONDS = 60

        /** The option for a saved number of seconds; one this build does not offer (a hand-edited file) falls to the default rather than to "never". */
        fun of(seconds: Int): LockTimeout = entries.firstOrNull { it.seconds == seconds } ?: OneMinute
    }
}

/**
 * Whether Paddock's screens are covered, and when they are covered again. It holds only that decision: who the user is, and how that is proved, belong to
 * the phone's own lock (PIN, pattern, password, fingerprint or face), which the app asks and never replaces, so Paddock keeps no secret of its own to lose.
 *
 * - **Closed until the saved choice is known.** A cold start is locked when the lock is on, and the first frames before the settings are read are covered
 *   too, so the herd never flashes before the lock does.
 * - **Away long enough.** Leaving the app (every activity stopped) starts a stopwatch; coming back covers the screens again when the time away reached
 *   the chosen timeout, and never before [MIN_AWAY_MILLIS]: a rotation destroys and recreates the activity within a fraction of a second, and a system
 *   dialog or the camera's grant can bring the app to the back for a moment; neither is "leaving".
 * - **Not while asking.** The phone's credential screen is another activity, so the app goes to the back while the user types the PIN. Between
 *   [authenticating] and [unlocked] (or [cancelled]) nothing is counted as leaving, or a right PIN would lock the app again on the way back.
 * - **Turning it on does not lock.** The user proved themselves to turn it on, so the app stays open; turning it off opens it.
 *
 * [elapsedMillis] must be a monotonic clock that runs through sleep (`SystemClock.elapsedRealtime`), so changing the date neither shortens nor stretches the timeout.
 */
class AppLock(private val elapsedMillis: () -> Long) {
    private val lock = Any()
    private val _locked = MutableStateFlow(true)
    /** True while the screens are covered. True before [start]. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var started = false
    private var leftAt: Long? = null
    private var asking = false

    /** The saved choice is known: covered if the lock is on, open if it is off. Once; the first call wins. */
    fun start(enabled: Boolean) = synchronized(lock) {
        if (started) return@synchronized
        started = true
        _locked.value = enabled
    }

    /** The lock was turned on or off in Settings (after the user proved themselves). Off opens the screens; on leaves them as they are. */
    fun enabledChanged(enabled: Boolean) = synchronized(lock) {
        if (!enabled) { _locked.value = false; leftAt = null; asking = false }
    }

    /** Every activity of the app is stopped: the stopwatch starts, unless the phone's own credential screen is what took the app to the back. */
    fun hidden() = synchronized(lock) {
        if (!started || asking || leftAt != null) return@synchronized
        leftAt = elapsedMillis()
    }

    /** An activity is started again. Covers the screens when the lock is on and the time away reached the timeout. */
    fun shown(enabled: Boolean, afterSeconds: Int) = synchronized(lock) {
        val left = leftAt
        leftAt = null
        if (!started || asking || left == null || !enabled) return@synchronized
        val away = elapsedMillis() - left
        if (away >= maxOf(MIN_AWAY_MILLIS, afterSeconds * 1000L)) _locked.value = true
    }

    /** The user asked to lock now. */
    fun lockNow() = synchronized(lock) { if (started) _locked.value = true }

    /** The phone's credential or biometric screen is about to be shown: the app going to the back is not leaving. */
    fun authenticating() = synchronized(lock) { asking = true; leftAt = null }

    /** The phone said it was the user. */
    fun unlocked() = synchronized(lock) { asking = false; leftAt = null; _locked.value = false }

    /** The user gave up, or the phone could not check: still covered, and leaving counts again. */
    fun cancelled() = synchronized(lock) { asking = false; leftAt = null }

    companion object {
        /** A rotation or a system dialog takes well under this; going to another app and back takes longer. */
        const val MIN_AWAY_MILLIS = 3_000L
    }
}

/** What the phone can do about asking for a credential, in the words Settings and the lock screen use. */
enum class DeviceLock {
    /** The phone has a screen lock (PIN, pattern, password) or an enrolled biometric: it can be asked. */
    Ready,

    /** The phone has no screen lock, so there is nothing to ask; the lock cannot be turned on, and one that is on cannot be satisfied. */
    NotSet,
}

object AppLockCopy {
    const val TITLE = "Lock Paddock"
    const val DETAIL = "Ask for your fingerprint, face, PIN, pattern or password (the ones you use to unlock this phone) when you open Paddock. " +
        "Paddock keeps no code of its own. While it is on, screenshots and the recent-apps preview are blocked everywhere in the app."
    const val AFTER = "Ask again after"
    const val AFTER_DETAIL = "How long Paddock may be out of sight before it asks. Immediately still ignores a rotation or a system dialog."
    const val NOT_SET = "This phone has no screen lock. Set a PIN, pattern, password or fingerprint in Android's settings first; Paddock uses the one the phone has."
    const val OPEN_SECURITY = "Open security settings"
    const val SCOPE = "The lock covers Paddock's screens. Home-screen widgets and notifications are not covered; the lock-screen setting under Alerts decides what a notification shows."

    const val LOCKED_TITLE = "Paddock is locked"
    const val LOCKED_DETAIL = "Unlock with the way you unlock this phone."
    const val UNLOCK = "Unlock"
    const val UNLOCK_TITLE = "Unlock Paddock"
    const val TURN_ON_TITLE = "Turn on the lock"
    const val TURN_OFF_TITLE = "Turn off the lock"
    const val CONFIRM_SUBTITLE = "Confirm it is you"

    /** The lock was on, and the phone's screen lock was removed meanwhile: nothing can be asked, so the app opens and the lock is off. */
    const val DROPPED = "Paddock's lock is off because this phone no longer has a screen lock."
    const val FAILED = "That did not work. Try again."
    fun failed(why: String?) = if (why.isNullOrBlank()) FAILED else "$FAILED ($why)"
}
