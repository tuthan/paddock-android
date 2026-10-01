package io.github.tuthan.paddock.ledger

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val host = HostProfileId("h1")
    private fun key(id: String = "t1", epoch: Long = 1) = TerminalKey(TargetRef(host, "paddock-test", id), epoch)
    private var clock = 1_000_000L
    private fun ledger(store: LedgerStore = InMemoryLedgerStore()) = Ledger(store) { clock }

    @Test fun markSeenIsVisibleThroughLookupAndRecordsAPhoneAction() {
        val l = ledger()
        assertNull(l.seenLookup(host, "paddock-test", 1).seenSeq("t1"))
        l.markSeen(key(), 7)
        assertEquals(7L, l.seenLookup(host, "paddock-test", 1).seenSeq("t1"))
        val a = l.actions().single()
        assertEquals(ActionKind.MarkSeen, a.kind)
        assertEquals(ActionOutcome.Ok, a.outcome)
        assertEquals("t1", a.terminalId)
    }

    @Test fun markSeenKeepsTheHighestSeq() {
        val store = InMemoryLedgerStore()
        val l = ledger(store)
        l.markSeen(key(), 9)
        l.markSeen(key(), 4)
        assertEquals(9L, l.seenLookup(host, "paddock-test", 1).seenSeq("t1"))
        assertEquals(1, store.load().seen.size)
    }

    @Test fun aFactFromAnotherEpochDoesNotApply() {
        val l = ledger()
        l.markSeen(key(epoch = 1), 7)
        assertNull(l.seenLookup(host, "paddock-test", 2).seenSeq("t1"))
    }

    @Test fun detailIsBoundedAndHostEventsCarryNoTerminal() {
        val l = ledger()
        l.observe(ObservationKind.StateChanged, key(), "x".repeat(500))
        l.observeHost(ObservationKind.Disconnected, host, "paddock-test", 1)
        val (state, conn) = l.observations()
        assertEquals(Ledger.MAX_DETAIL, state.detail.length)
        assertEquals("t1", state.terminalId)
        assertNull(conn.terminalId)
    }

    @Test fun retentionDropsRowsOlderThanThirtyDays() {
        val l = ledger()
        l.observe(ObservationKind.AgentAppeared, key("old"))
        clock += Retention.MAX_AGE_MILLIS + 1
        l.observe(ObservationKind.AgentAppeared, key("new"))
        assertEquals(listOf("new"), l.observations().map { it.terminalId })
    }

    @Test fun retentionCapsRowsAtFiveThousandKeepingTheNewest() {
        val rows = (1..Retention.MAX_ROWS + 10).map { it }
        val kept = Retention.prune(rows, now = 0) { 0L }
        assertEquals(Retention.MAX_ROWS, kept.size)
        assertEquals(11, kept.first())
        assertEquals(Retention.MAX_ROWS + 10, kept.last())
    }

    @Test fun theFileStoreSurvivesAReload() {
        val file = File(tmp.root, "ledger/ledger.json")
        ledger(FileLedgerStore(file)).apply {
            observe(ObservationKind.StateChanged, key(), "idle -> blocked")
            markSeen(key("t2"), 3)
        }
        val reloaded = ledger(FileLedgerStore(file))
        assertEquals(1, reloaded.observations().size)
        assertEquals(3L, reloaded.seenLookup(host, "paddock-test", 1).seenSeq("t2"))
        assertFalse(File(file.absolutePath + ".tmp").exists())
    }

    @Test fun anUnreadableFileLoadsEmptyInsteadOfThrowing() {
        val file = File(tmp.root, "ledger.json").apply { writeText("{ not json") }
        val l = ledger(FileLedgerStore(file))
        assertTrue(l.observations().isEmpty())
        l.markSeen(key(), 1) // and the next write replaces the bad file
        assertEquals(1, ledger(FileLedgerStore(file)).actions().size)
    }

    @Test fun aFailingStoreKeepsTheInMemoryLedgerAndFlagsIt() {
        val failing = object : LedgerStore {
            override fun load() = LedgerData()
            override fun save(data: LedgerData) = throw java.io.IOException("disk full")
        }
        val l = ledger(failing)
        l.markSeen(key(), 2)
        assertTrue(l.saveFailed)
        assertEquals(2L, l.seenLookup(host, "paddock-test", 1).seenSeq("t1"))
    }
}
