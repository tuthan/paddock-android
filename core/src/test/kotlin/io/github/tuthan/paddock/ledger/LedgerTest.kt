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
        l.flush()
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
            flush()
        }
        val reloaded = ledger(FileLedgerStore(file))
        assertEquals(1, reloaded.observations().size)
        assertEquals(3L, reloaded.seenLookup(host, "paddock-test", 1).seenSeq("t2"))
        assertFalse(File(file.absolutePath + ".tmp").exists())
    }

    @Test fun anUnreadableFileLoadsEmptyAndIsKeptAsideInsteadOfThrowing() {
        val file = File(tmp.root, "ledger.json").apply { writeText("{ not json") }
        val l = ledger(FileLedgerStore(file))
        assertTrue(l.observations().isEmpty())
        assertEquals("the damaged file is kept for a person to look at", "{ not json", File(file.absolutePath + ".corrupt").readText())
        l.markSeen(key(), 1); l.flush() // and the next write starts a good file
        assertEquals(1, ledger(FileLedgerStore(file)).actions().size)
    }

    @Test fun aShapeKotlinxRejectsWithANonSerializationErrorIsAlsoTreatedAsUnreadable() {
        val file = File(tmp.root, "ledger.json").apply { writeText("""{"observations":[["not","an","object"]]}""") }
        assertTrue(ledger(FileLedgerStore(file)).observations().isEmpty())
    }

    @Test fun rowsOlderThanTheRetentionWindowArePrunedOnLoadNotOnlyOnTheNextWrite() {
        val old = Observation(1, "h1", "paddock-test", "t1", 1, ObservationKind.AgentAppeared, at = clock - Retention.MAX_AGE_MILLIS - 1)
        val l = ledger(InMemoryLedgerStore(LedgerData(nextId = 2, observations = listOf(old))))
        assertTrue(l.observations().isEmpty())
    }

    // ---- epochs: never repeated, and a seen Done survives a reconnect only when a fresh read confirms it -----

    @Test fun epochsAreNeverHandedOutTwiceForAHostAndSessionEvenAcrossARestart() {
        val file = File(tmp.root, "ledger.json")
        val first = ledger(FileLedgerStore(file))
        assertEquals(listOf(1L, 2L, 3L), List(3) { first.allocateEpoch(host, "paddock-test") })
        assertEquals(1L, first.allocateEpoch(host, "other"))
        first.flush()
        assertEquals(4L, ledger(FileLedgerStore(file)).allocateEpoch(host, "paddock-test"))
    }

    @Test fun aSeenDoneCarriesIntoTheNextEpochOnlyWhenTheFreshReadShowsItDoneAtTheSameSeq() {
        val l = ledger()
        l.onInstalled(host, "paddock-test", 1, emptyMap())
        l.markSeen(key("same", 1), 7)
        l.markSeen(key("moved-on", 1), 7)
        l.markSeen(key("gone", 1), 7)
        l.markSeen(key("not-done", 1), 7)
        // The reconnect's first read: "same" is still Done at 7, "moved-on" is Done again at 9, "gone" is absent,
        // and "not-done" is no longer Done (it is not in the map at all).
        l.onInstalled(host, "paddock-test", 4, mapOf("same" to 7L, "moved-on" to 9L))
        val now = l.seenLookup(host, "paddock-test", 4)
        assertEquals(7L, now.seenSeq("same"))
        assertNull(now.seenSeq("moved-on")); assertNull(now.seenSeq("gone")); assertNull(now.seenSeq("not-done"))
        assertEquals("the old fact stays in its own epoch", 7L, l.seenLookup(host, "paddock-test", 1).seenSeq("moved-on"))
    }

    @Test fun carryingStartsFromTheLastInstalledEpochSoEpochsWithNoReadInBetweenLoseNothing() {
        val l = ledger()
        l.onInstalled(host, "paddock-test", 1, emptyMap())
        l.markSeen(key("t1", 1), 5)
        // Two reconnect attempts allocate epochs 2 and 3 but fail before any read; epoch 4 is the first installed.
        repeat(3) { l.allocateEpoch(host, "paddock-test") }
        l.onInstalled(host, "paddock-test", 4, mapOf("t1" to 5L))
        assertEquals(5L, l.seenLookup(host, "paddock-test", 4).seenSeq("t1"))
        l.onInstalled(host, "paddock-test", 4, emptyMap())   // later reads of the same epoch change nothing
        assertEquals(5L, l.seenLookup(host, "paddock-test", 4).seenSeq("t1"))
    }

    @Test fun aFailingStoreKeepsTheInMemoryLedgerAndFlagsIt() {
        val failing = object : LedgerStore {
            override fun load() = LedgerData()
            override fun save(data: LedgerData) = throw java.io.IOException("disk full")
        }
        val l = ledger(failing)
        l.markSeen(key(), 2)
        l.flush()
        assertTrue(l.saveFailed)
        assertEquals(2L, l.seenLookup(host, "paddock-test", 1).seenSeq("t1"))
    }
}
