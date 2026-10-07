package io.github.tuthan.paddock.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.alerts.AlertChannel
import io.github.tuthan.paddock.alerts.AlertContent
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.alerts.LocalAlert
import io.github.tuthan.paddock.alerts.NotificationAccess
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the platform makes of Paddock's channels and notifications (Phase 07 slice 3): importance, redaction, actions, permission state. */
class NotifyTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm: NotificationManager get() = ctx.getSystemService(NotificationManager::class.java)
    private val target = TargetRef(HostProfileId("workstation"), "default", "term_notifytest")
    private fun alert(state: AlertState = AlertState.Blocked) = LocalAlert(target, "w1:p1", state, 7, System.currentTimeMillis() / 1000, "laptop", "Drop the users table in production?")

    @Before fun clean() {
        // The test grants the permission through the shell identity, so it adds no dependency (androidx.test:rules would need a review).
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
    }
    @After fun cleanUp() { nm.cancelAll() }

    /** Posting is asynchronous inside the system: wait until [count] of Paddock's notifications are active (or fail after 5 s). */
    private fun mine(count: Int = 1): List<android.service.notification.StatusBarNotification> {
        val end = System.currentTimeMillis() + 5_000
        var found = nm.activeNotifications.filter { it.tag == AndroidAlertNotifier.TAG }
        while (found.size != count && System.currentTimeMillis() < end) { Thread.sleep(50); found = nm.activeNotifications.filter { it.tag == AndroidAlertNotifier.TAG } }
        return found
    }

    @Test fun theFourChannelsExistWithTheDesignedImportance() {
        Channels.ensure(ctx)
        val expected = mapOf(
            AlertChannel.NeedsYou to NotificationManager.IMPORTANCE_HIGH, AlertChannel.Done to NotificationManager.IMPORTANCE_DEFAULT,
            AlertChannel.Machines to NotificationManager.IMPORTANCE_LOW, AlertChannel.Watching to NotificationManager.IMPORTANCE_MIN,
        )
        for ((channel, importance) in expected) {
            val c = nm.getNotificationChannel(channel.id)
            assertNotNull(channel.id, c)
            assertEquals(channel.title, channel.title, c.name.toString())
            assertEquals(channel.id, importance, c.importance)
        }
        // Done is silent and groups per machine
        assertNull(nm.getNotificationChannel("done").sound)
        Channels.ensure(ctx)  // a second call changes nothing
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel("needs_you").importance)
    }

    @Test fun aRedactedNotificationIsPrivateWithAGenericPublicVersionAndOnlyOpenAndReview() {
        AndroidAlertNotifier(ctx).show(AlertContent.of(alert(), hideOnLockScreen = true))
        val n = mine().single().notification
        assertEquals("needs_you", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Drop the users table in production?", n.extras.getString(Notification.EXTRA_TITLE))
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Paddock: attention on laptop", public.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("An agent needs you.", public.extras.getString(Notification.EXTRA_TEXT))
        assertFalse("users" in public.extras.getString(Notification.EXTRA_TITLE).orEmpty() + public.extras.getString(Notification.EXTRA_TEXT).orEmpty())
        // Open and Review, nothing else, neither able to take text
        assertEquals(listOf("Open", "Review"), n.actions.map { it.title.toString() })
        assertTrue(n.actions.all { it.remoteInputs == null || it.remoteInputs.isEmpty() })
        assertNotNull(n.contentIntent)
        assertTrue(n.contentIntent.isActivity)
        assertTrue(n.actions.all { it.actionIntent.isActivity })
    }

    @Test fun withTheRedactionOffTheFullVersionIsPublic() {
        AndroidAlertNotifier(ctx).show(AlertContent.of(alert(), hideOnLockScreen = false))
        val n = mine().single().notification
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertNull(n.publicVersion)
    }

    @Test fun doneGoesToTheDoneChannelInAStackWithASummary() {
        AndroidAlertNotifier(ctx).show(AlertContent.of(alert(AlertState.Done), true))
        val all = mine(2)
        assertEquals(2, all.size)
        assertTrue(all.all { it.notification.group == "paddock-done-workstation" })
        assertEquals(1, all.count { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
        val child = all.single { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.notification
        assertEquals("done", child.channelId)
        assertEquals("An agent finished.", child.publicVersion.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun aNewStateOfTheSameTerminalReplacesTheOldNotification() {
        val notifier = AndroidAlertNotifier(ctx)
        notifier.show(AlertContent.of(alert(AlertState.Blocked), true))
        notifier.show(AlertContent.of(alert(AlertState.Blocked), true))
        Thread.sleep(500)
        assertEquals(1, mine().size)
    }

    @Test fun cancelAllRemovesOnlyPaddocksAlerts() {
        AndroidAlertNotifier(ctx).show(AlertContent.of(alert(), true))
        assertEquals(1, mine().size)
        AndroidAlertNotifier(ctx).cancelAll()
        assertEquals(0, mine(0).size)
    }

    /** Coming to the front clears the notifications of the machine now on screen, never another machine's (the screen shows one machine's herd). */
    @Test fun cancelForClearsOnlyThatMachinesNotificationsAndLeavesNoOrphanedDoneSummary() {
        fun on(machine: String, terminal: String, state: AlertState) =
            LocalAlert(TargetRef(HostProfileId(machine), "default", terminal), "w1:p1", state, 7, System.currentTimeMillis() / 1000, machine, "A title that must not matter")
        val n = AndroidAlertNotifier(ctx)
        n.show(AlertContent.of(on("workstation", "term_a", AlertState.Blocked), true))
        n.show(AlertContent.of(on("server", "term_b", AlertState.Blocked), true))
        n.show(AlertContent.of(on("server", "term_c", AlertState.Done), true))        // a Done child and its stack summary
        assertEquals(4, mine(4).size)
        n.cancelFor("workstation")
        assertEquals("only the workstation's Needs-you notification went", 3, mine(3).size)
        assertTrue(mine(3).all { it.notification.extras.getString("paddock.link").orEmpty().isEmpty() || "h=server" in it.notification.extras.getString("paddock.link").orEmpty() })
        n.cancelFor("nobody")
        assertEquals(3, mine(3).size)
        n.cancelFor("server")
        assertEquals("the server's notifications and its Done summary are gone", 0, mine(0).size)
    }

    @Test fun theAccessReaderSeesAllowedWhenTheGrantIsGiven() {
        assertEquals(NotificationAccess.Allowed, NotificationAccessReader(ctx).read(null, askedBefore = true))
    }
}
