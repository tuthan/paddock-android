package io.github.tuthan.paddock.identity

import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class IdentityTest {
    private val host = HostProfileId("laptop")
    private fun pane(id: String, term: String) = Pane(paneId = id, terminalId = term, workspaceId = "w1", tabId = "w1:t1")
    private fun snap(version: String = "0.9.1", protocol: Int = 22, vararg panes: Pane) = Snapshot(version, protocol, panes = panes.toList())
    private fun key(term: String, epoch: Long) = TerminalKey(TargetRef(host, "main", term), epoch)

    @Test fun aTerminalIsFoundByItsTerminalIdEvenWhenThePaneIdIsRenumbered() {
        val before = PaneResolver(snap(panes = arrayOf(pane("w1:p1", "term_a"))), 1)
        val after = PaneResolver(snap(panes = arrayOf(pane("w1:p7", "term_a"))), 1)
        assertEquals("w1:p1", before.current(key("term_a", 1))); assertEquals("w1:p7", after.current(key("term_a", 1)))
    }

    @Test fun aKeyFromAnOldEpochOrAGoneTerminalResolvesToNothingAndTheCallPathRefuses() {
        val r = PaneResolver(snap(panes = arrayOf(pane("w1:p1", "term_a"))), 2)
        assertNull(r.current(key("term_a", 1)))
        assertNull(r.current(key("term_gone", 2)))
        assertFailsWith<StalePane> { r.require(key("term_a", 1)) }
        assertEquals("w1:p1", r.require(key("term_a", 2)))
    }

    @Test fun outsideReferencesResolveAgainstTheInstalledSnapshotNotAnEpoch() {
        val r = PaneResolver(snap(panes = arrayOf(pane("w1:p1", "term_a"))), 9)
        assertEquals("w1:p1", r.resolve(TargetRef(host, "main", "term_a"), host, "main"))
        assertNull(r.resolve(TargetRef(host, "other", "term_a"), host, "main"))
        assertNull(r.resolve(TargetRef(HostProfileId("desktop"), "main", "term_a"), host, "main"))
        assertNull(r.resolve(TargetRef(host, "main", "term_zz"), host, "main"))
    }

    @Test fun epochsAdvanceOnReconnectAndOnAChangedVersionOrProtocolOnly() {
        val e = EpochTracker()
        assertFalse(e.onSnapshot(snap())); assertEquals(1, e.epoch)             // the first snapshot is a baseline
        assertFalse(e.onSnapshot(snap())); assertEquals(1, e.epoch)
        assertEquals(2, e.onReconnect())
        assertTrue(e.onSnapshot(snap(version = "0.9.2"))); assertEquals(3, e.epoch)
        assertTrue(e.onSnapshot(snap(version = "0.9.2", protocol = 23))); assertEquals(4, e.epoch)
        assertFalse(e.onSnapshot(snap(version = "0.9.2", protocol = 23))); assertEquals(4, e.epoch)
    }
}
