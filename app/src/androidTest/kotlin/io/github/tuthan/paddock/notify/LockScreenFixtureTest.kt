package io.github.tuthan.paddock.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.alerts.AlertContent
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.alerts.LocalAlert
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Not a test of anything on its own: each method posts one alert whose agent title is sensitive and leaves it in the shade, for
 * `tools/check-lockscreen.sh` to look at on the lock screen of a phone with a PIN (AC-07.4). The redacted variant must show the
 * generic words only; the other is the control that proves the check can see a title when one is shown.
 */
class LockScreenFixtureTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private val target = TargetRef(HostProfileId("workstation"), "default", "term_lockscreen")

    private fun post(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        val alert = LocalAlert(target, "w1:p1", AlertState.Blocked, 7, System.currentTimeMillis() / 1000, "laptop", "Drop the users table in production?")
        AndroidAlertNotifier(ctx).show(AlertContent.of(alert, hideOnLockScreen = hide))
        val end = System.currentTimeMillis() + 5_000
        while (nm.activeNotifications.none { it.tag == AndroidAlertNotifier.TAG } && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals(1, nm.activeNotifications.count { it.tag == AndroidAlertNotifier.TAG })
    }

    @Test fun postsOneRedactedAlertAndLeavesIt() = post(hide = true)
    @Test fun postsOneUnredactedAlertAndLeavesIt() = post(hide = false)
}
