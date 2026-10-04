package io.github.tuthan.paddock.notify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.alerts.NotificationAccess
import io.github.tuthan.paddock.alerts.NotificationAccessRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The notification permission as the system has it right now, which this test never changes: revoking a runtime permission kills
 * the app's process, so `paddock-harness/run-alerts-e2e.sh` revokes it with `pm revoke` and then runs this class, and again after `pm grant`.
 * The recovery row the permission state leads to is the same one Settings draws (SettingsTest draws each).
 */
class NotifyPermissionTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val granted get() = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Test fun theReaderAgreesWithTheSystemAboutTheGrant() {
        val reader = NotificationAccessReader(ctx)
        if (granted) assertEquals(NotificationAccess.Allowed, reader.read(null, askedBefore = true))
        else {
            assertEquals(NotificationAccess.NeedsPermission(canAsk = true), reader.read(null, askedBefore = false))
            // asked once and no rationale (there is no activity here to give one): the system will not show its dialog again
            assertEquals(NotificationAccess.NeedsPermission(canAsk = false), reader.read(null, askedBefore = true))
        }
    }

    @Test fun aRevokedGrantHasARecoveryThatEitherAsksOrSendsTheUserToSettings() {
        assumeTrue("needs the permission revoked first", !granted)
        val reader = NotificationAccessReader(ctx)
        val ask = NotificationAccessRules.recovery(reader.read(null, askedBefore = false))
        assertNotNull(ask)
        assertNull(reader.settingsIntent(ask!!, reader.read(null, askedBefore = false)))                 // the dialog, not a page
        val away = NotificationAccessRules.recovery(reader.read(null, askedBefore = true))!!
        val intent = reader.settingsIntent(away, reader.read(null, askedBefore = true))
        assertNotNull(intent)
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent!!.action)
        assertEquals(ctx.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }
}
