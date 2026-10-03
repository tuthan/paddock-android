package io.github.tuthan.paddock.alerts

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class NotificationAccessTest {
    private fun eval(sdk: Int = 36, granted: Boolean = true, enabled: Boolean = true, blocked: Set<String> = emptySet(), asked: Boolean = false, rationale: Boolean = false) =
        NotificationAccessRules.evaluate(sdk, granted, enabled, blocked, asked, rationale)

    @Test fun allowedWhenNothingIsInTheWay() {
        assertEquals(NotificationAccess.Allowed, eval())
        assertNull(NotificationAccessRules.recovery(NotificationAccess.Allowed))
    }

    @Test fun beforeAndroid13ThePermissionIsNotAThing() {
        assertEquals(NotificationAccess.Allowed, eval(sdk = 32, granted = false))
        assertEquals(NotificationAccess.AppBlocked, eval(sdk = 32, granted = false, enabled = false))
    }

    @Test fun androidThirteenNeedsThePermissionAndCanAskUntilTheSystemStopsShowingTheDialog() {
        assertEquals(NotificationAccess.NeedsPermission(canAsk = true), eval(granted = false))                                      // never asked
        assertEquals(NotificationAccess.NeedsPermission(canAsk = true), eval(granted = false, asked = true, rationale = true))      // denied once
        assertEquals(NotificationAccess.NeedsPermission(canAsk = false), eval(granted = false, asked = true, rationale = false))    // the system will not ask again
    }

    @Test fun anAppTurnedOffInSystemSettingsIsItsOwnState() {
        assertEquals(NotificationAccess.AppBlocked, eval(enabled = false))
    }

    @Test fun aSilencedAlertChannelIsNamedAndOtherChannelsDoNotMatter() {
        assertEquals(NotificationAccess.ChannelsBlocked(listOf(AlertChannel.NeedsYou)), eval(blocked = setOf("needs_you")))
        assertEquals(NotificationAccess.ChannelsBlocked(listOf(AlertChannel.NeedsYou, AlertChannel.Done)), eval(blocked = setOf("done", "needs_you", "watching")))
        assertEquals(NotificationAccess.Allowed, eval(blocked = setOf("machines", "watching")))
    }

    @Test fun everyBlockedStateHasARecoveryThatSaysWhatIsWrongAndWhatToDo() {
        val ask = NotificationAccessRules.recovery(NotificationAccess.NeedsPermission(true))!!
        assertEquals(AccessRecovery.Action.AskPermission, ask.action); assertEquals("Allow notifications", ask.actionLabel)
        val settings = NotificationAccessRules.recovery(NotificationAccess.NeedsPermission(false))!!
        assertEquals(AccessRecovery.Action.OpenAppSettings, settings.action)
        assertEquals(AccessRecovery.Action.OpenAppSettings, NotificationAccessRules.recovery(NotificationAccess.AppBlocked)!!.action)
        val one = NotificationAccessRules.recovery(NotificationAccess.ChannelsBlocked(listOf(AlertChannel.NeedsYou)))!!
        assertEquals(AccessRecovery.Action.OpenChannelSettings, one.action)
        assertTrue("\"Needs you\" channel is turned off" in one.message, one.message)
        val two = NotificationAccessRules.recovery(NotificationAccess.ChannelsBlocked(listOf(AlertChannel.NeedsYou, AlertChannel.Done)))!!
        assertTrue("\"Needs you\" and \"Done\" channel are turned off" in two.message, two.message)
    }
}
