package io.github.tuthan.paddock.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tuthan.paddock.ShareEvents
import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.files.ShareCopy
import io.github.tuthan.paddock.files.ShareInbox
import io.github.tuthan.paddock.files.Snapshot
import io.github.tuthan.paddock.files.SharedItem
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.nav.NavState
import io.github.tuthan.paddock.nav.Route
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * When something another app shared is let in front of the person (Phase 15, slice 11): only once the app is unlocked and a machine is saved. While the lock is up
 * the item waits, already a private snapshot, and the chooser opens after the unlock; with no machine it is dropped with a sentence; what the intake refused is said once.
 */
class ShareEventsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val discarded = mutableListOf<SharedItem>()
    private val shares = ShareInbox { discarded += it }
    private val notice = MutableStateFlow<String?>(null)
    private val lock = AppLock { 0L }
    private val nav = NavState()
    private val boot = MutableStateFlow(Boot.Ready)
    private val item = SharedItem.File(Snapshot("s1", "2026-10-11-143205-a.txt", "text/plain", 5, "0".repeat(64)), senderName = "a.txt")

    private fun show() = rule.setContent {
        val b by boot.collectAsState()
        ShareEvents(shares, notice, lock, nav, b)
    }

    @Test fun anItemOpensTheChooserOnceTheAppIsUnlockedAndAMachineIsSaved() {
        lock.start(enabled = false)
        show()
        shares.offer(item)
        rule.waitForIdle()
        assertEquals(Route.Share, nav.route)
        assertTrue("the item stays in the inbox until it is used or dropped", shares.pending.value === item)
    }

    @Test fun whileTheLockIsUpTheItemWaitsAndTheChooserOpensAfterTheUnlock() {
        lock.start(enabled = true)
        show()
        shares.offer(item)
        rule.waitForIdle()
        assertEquals("the screens stay where they were behind the lock", Route.Home, nav.route)
        assertTrue(shares.pending.value === item)
        assertTrue("nothing was discarded while it waited", discarded.isEmpty())
        lock.unlocked()
        rule.waitForIdle()
        assertEquals(Route.Share, nav.route)
    }

    @Test fun withNoMachineSavedTheItemIsDroppedWithItsSnapshotAndTheNoticeSaysWhy() {
        lock.start(enabled = false)
        boot.value = Boot.NoMachines
        show()
        shares.offer(item)
        rule.waitForIdle()
        assertEquals(Route.Home, nav.route)
        assertNull(shares.pending.value)
        assertEquals("its snapshot goes with it", listOf<SharedItem>(item), discarded)
        assertEquals(ShareCopy.NO_MACHINE, nav.alertNotice)
    }

    @Test fun whileTheMachinesAreStillLoadingNothingHappens() {
        lock.start(enabled = false)
        boot.value = Boot.Loading
        show()
        shares.offer(item)
        rule.waitForIdle()
        assertEquals(Route.Home, nav.route)
        assertTrue(shares.pending.value === item)
        boot.value = Boot.Ready
        rule.waitForIdle()
        assertEquals(Route.Share, nav.route)
    }

    @Test fun aRefusedShareIsSaidOnceAndClearedFromTheGraphsFlow() {
        lock.start(enabled = false)
        show()
        notice.value = ShareCopy.NOTHING
        rule.waitForIdle()
        assertEquals(ShareCopy.NOTHING, nav.alertNotice)
        assertNull(notice.value)
        assertNull(shares.pending.value)
        assertNotNull(nav)
    }
}
