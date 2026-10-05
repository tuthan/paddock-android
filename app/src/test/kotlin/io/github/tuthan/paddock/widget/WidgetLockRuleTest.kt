package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.EntitlementState
import io.github.tuthan.paddock.billing.Entitlements
import io.github.tuthan.paddock.billing.ProStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the widgets are locked has one rule, [Entitlements.hasPro], and the widget process applies it with the flavor's constants (review F5, F12).
 * Before, the widget re-implemented it as `status == PRO`, so a PRO left in `entitlement.json` by a play install of the same application id unlocked a
 * foss build's widgets for good, a build that has no store to correct or revoke it. The file-reading half is `WidgetLockTest` in androidTest.
 */
class WidgetLockRuleTest {
    private fun saved(status: ProStatus) = EntitlementState(status = status, verifiedAtMillis = 1L, acknowledged = true)

    @Test fun aFossBuildNeverHonoursALeftoverProFile() {
        // Fails on the old `status == PRO` rule.
        assertTrue(PaddockWidgets.lockedBy(saved(ProStatus.PRO), unlockedBuild = false, sellsPro = false))
    }

    @Test fun aBuildThatSellsProUnlocksOnlyOnAVerifiedPurchase() {
        assertFalse(PaddockWidgets.lockedBy(saved(ProStatus.PRO), unlockedBuild = false, sellsPro = true))
        assertFalse("bought and not yet acknowledged still holds Pro", PaddockWidgets.lockedBy(EntitlementState(ProStatus.PRO, 1L, acknowledged = false), unlockedBuild = false, sellsPro = true))
        for (s in listOf(ProStatus.UNKNOWN, ProStatus.FREE, ProStatus.PENDING, ProStatus.REVOKED)) assertTrue("$s", PaddockWidgets.lockedBy(saved(s), unlockedBuild = false, sellsPro = true))
    }

    @Test fun anUnlockedBuildNeverLocksWhateverTheFileSays() {
        for (s in ProStatus.entries) for (sells in listOf(false, true)) assertFalse("$s sells=$sells", PaddockWidgets.lockedBy(saved(s), unlockedBuild = true, sellsPro = sells))
    }

    @Test fun theDefaultsAreTheFlavorsOwnConstantsAndTheSameRuleTheAppUses() {
        for (s in ProStatus.entries) assertEquals("$s", !Entitlements.hasPro(saved(s), Distribution.UNLOCKED, Distribution.SELLS_PRO), PaddockWidgets.lockedBy(saved(s)))
    }
}
