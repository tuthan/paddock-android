package io.github.tuthan.paddock.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkTrackerTest {
    private var now = 1_000L
    private val tracker = LinkTracker { now }

    @Test
    fun startsDownClosed() {
        assertEquals(LinkState.Down(DownReason.Closed, 1_000L), tracker.link.value)
    }

    @Test
    fun connectUpThenDownRecordsEachTime() {
        now = 2_000; tracker.connecting()
        assertEquals(LinkState.Connecting(2_000), tracker.link.value)
        now = 2_500; tracker.up()
        assertEquals(LinkState.Up(2_500), tracker.link.value)
        now = 40_000; tracker.down(DownReason.Timeout)
        assertEquals(LinkState.Down(DownReason.Timeout, 40_000), tracker.link.value)
    }

    @Test
    fun upIsOnlyReachableFromConnecting() {
        tracker.up()
        assertIs<LinkState.Down>(tracker.link.value)
        now = 5_000; tracker.connecting(); tracker.down(DownReason.Refused)
        now = 6_000; tracker.up()
        assertIs<LinkState.Down>(tracker.link.value)
    }

    @Test
    fun theFirstDownReasonWins() {
        tracker.connecting(); tracker.up()
        now = 10; tracker.down(DownReason.HostKeyChanged)
        now = 20; tracker.down(DownReason.Timeout)
        assertEquals(LinkState.Down(DownReason.HostKeyChanged, 10), tracker.link.value)
    }

    @Test
    fun reconnectAfterDownIsAllowed() {
        tracker.connecting(); tracker.down(DownReason.PermissionDenied)
        now = 7_000; tracker.connecting()
        assertEquals(LinkState.Connecting(7_000), tracker.link.value)
    }
}
