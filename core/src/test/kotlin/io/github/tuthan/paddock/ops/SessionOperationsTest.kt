package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.relay.FakeSession
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Session stop and delete: what is sent, what is refused before any call, and what a lost answer leaves behind. */
class SessionOperationsTest {
    private var now = 5_000_000L
    private val journal = OperationJournal(InMemoryJournalStore()) { now }
    private val host = HostProfileId("h1")
    private val herdr = "/usr/bin/herdr"

    private fun entry(name: String, running: Boolean, default: Boolean = false) =
        SessionEntry(name, default, running, "/home/u/.config/herdr/sessions/$name", "/home/u/.config/herdr/sessions/$name/herdr.sock")

    private fun ops(session: FakeSession) = SessionOperations(session, herdr, journal, host)

    // Captured from herdr 0.9.1 on a disposable session (fixtures/herdr-0.9.1/session-*.json).
    private val stopped = """{"session":{"default":false,"name":"paddock-test-2","running":false,"session_dir":"/home/u/.config/herdr/sessions/paddock-test-2","socket_path":"/home/u/.config/herdr/sessions/paddock-test-2/herdr.sock"},"stopped":true}"""
    private val deleted = """{"deleted":true,"session":{"default":false,"name":"paddock-test-2","running":false,"session_dir":"/home/u/.config/herdr/sessions/paddock-test-2","socket_path":"/home/u/.config/herdr/sessions/paddock-test-2/herdr.sock"}}"""
    private val deleteRunning = """{"error":{"code":"session_delete_failed","message":"session paddock-test-2 is running; stop it before deleting"}}"""
    private val stopNotRunning = """{"error":{"code":"session_stop_failed","message":"session paddock-test-2 is not running or cannot be reached at /x/herdr.sock: No such file or directory (os error 2)"}}"""

    @Test fun stopSendsOneCommandForTheNamedSessionAndRecordsItFirst() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(0, stopped) })
        val run = ops(session).stop(entry("paddock-test-2", running = true))
        val ran = assertIs<SessionRun.Ran>(run)
        assertIs<OperationResult.Acknowledged<*>>(ran.result)
        assertEquals(listOf(listOf(herdr, "session", "stop", "paddock-test-2", "--json")), session.execs.map { it.first })
        val row = journal.records.value.single()
        assertEquals(OperationKind.SessionStop, row.kind)
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertEquals("session:paddock-test-2", row.terminalId)
        assertEquals("paddock-test-2", row.session)
        assertTrue(!row.aboutTerminal)
    }

    @Test fun deleteSendsOneCommandAndAcceptsOnlyADeletedTrueAnswer() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(0, deleted) })
        val run = ops(session).delete(entry("paddock-test-2", running = false))
        assertIs<OperationResult.Acknowledged<*>>((run as SessionRun.Ran).result)
        assertEquals(listOf(herdr, "session", "delete", "paddock-test-2", "--json"), session.execs.single().first)
    }

    @Test fun deleteOfDefaultIsRefusedBeforeAnyCallOrJournalRow() = runBlocking<Unit> {
        val session = FakeSession()
        for (e in listOf(entry("default", running = false, default = true), entry("default", running = true, default = true), entry("default", running = false, default = false))) {
            val run = ops(session).delete(e)
            assertEquals(SessionRules.DEFAULT_DELETE, (run as SessionRun.Refused).reason)
        }
        assertTrue(session.execs.isEmpty(), "no command may leave for the default session")
        assertTrue(journal.records.value.isEmpty())
    }

    @Test fun aRunningSessionIsNotOfferedForDeleteAndAStoppedOneNotForStop() = runBlocking<Unit> {
        val session = FakeSession()
        assertIs<SessionRun.Refused>(ops(session).delete(entry("paddock-test-2", running = true)))
        assertIs<SessionRun.Refused>(ops(session).stop(entry("paddock-test-2", running = false)))
        assertTrue(session.execs.isEmpty() && journal.records.value.isEmpty())
        assertNull(SessionRules.deleteRefusal(entry("paddock-test-2", running = false)))
        assertNull(SessionRules.stopRefusal(entry("paddock-test-2", running = true)))
    }

    @Test fun herdrsOwnRefusalIsShownAndIsCertain() = runBlocking<Unit> {
        // The catalogue was stale: the session started again after the list was read. herdr refuses, the phone says so.
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(1, "", deleteRunning) })
        val r = (ops(session).delete(entry("paddock-test-2", running = false)) as SessionRun.Ran).result
        val rejected = assertIs<OperationResult.Rejected>(r)
        assertEquals("session_delete_failed", rejected.code)
        assertTrue("running" in rejected.message)
        assertEquals(OperationOutcome.Rejected, journal.records.value.single().outcome)
        assertTrue("herdr refused" in SessionCopy.outcome(OperationKind.SessionDelete, "paddock-test-2", r))
        // Certain, so nothing waits for a re-read.
        assertTrue(journal.unresolvedUnknown(ops(session).key("paddock-test-2")).isEmpty())
    }

    @Test fun aStopOfASessionThatIsNotRunningIsHerdrsRefusal() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(1, stopNotRunning) })
        val r = (ops(session).stop(entry("paddock-test-2", running = true)) as SessionRun.Ran).result
        assertEquals("session_stop_failed", assertIs<OperationResult.Rejected>(r).code)
    }

    @Test fun aNonZeroExitWithNothingParseableShowsTheCliText() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(1, "", "herdr: server wedged") })
        val r = (ops(session).stop(entry("paddock-test-2", running = true)) as SessionRun.Ran).result
        val rejected = assertIs<OperationResult.Rejected>(r)
        assertEquals("cli_exit_1", rejected.code)
        assertEquals("herdr: server wedged", rejected.message)
    }

    @Test fun aLinkThatDiesAfterTheRequestLeftLeavesTheRowUnknownAndBlocksTheSession() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> throw IOException("connection reset") })
        val e = entry("paddock-test-2", running = true)
        val r = (ops(session).stop(e) as SessionRun.Ran).result
        assertIs<OperationResult.Unknown>(r)
        assertEquals(OperationOutcome.Unknown, journal.records.value.single().outcome)
        // A second stop does not go out: the first may have happened.
        val again = (ops(session).stop(e) as SessionRun.Ran).result
        assertIs<OperationResult.NeedsReread>(again)
        assertEquals(1, session.execs.size)
        // Re-reading the list frees it; the row stays Unknown.
        assertEquals(1, ops(session).resolve("paddock-test-2"))
        assertEquals(OperationOutcome.Unknown, journal.records.value.single().outcome)
        assertTrue(journal.records.value.single().resolvedAt != null)
        assertTrue("unknown" in SessionCopy.outcome(OperationKind.SessionStop, "paddock-test-2", r))
    }

    @Test fun anExitZeroThatDoesNotSayWhatItDidIsUnknownNotSuccess() = runBlocking<Unit> {
        for (out in listOf("", "not json", """{"stopped":false}""", """{"stopped":true}""", """{"deleted":true,"session":{"name":1}}""")) {
            val j = OperationJournal(InMemoryJournalStore()) { now }
            val o = SessionOperations(FakeSession(onExec = { _, _ -> FakeSession.result(0, out) }), herdr, j, host)
            val r = (o.stop(entry("paddock-test-2", running = true)) as SessionRun.Ran).result
            assertIs<OperationResult.Unknown>(r, "for output <$out>")
        }
    }

    @Test fun twoOperationsOnTheSameSessionDoNotOverlap() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = FakeSession(onExec = { _, _ -> kotlinx.coroutines.runBlocking { gate.await() }; FakeSession.result(0, stopped) })
        val e = entry("paddock-test-2", running = true)
        val first = async(kotlinx.coroutines.Dispatchers.IO) { ops(session).stop(e) }
        while (journal.records.value.isEmpty()) kotlinx.coroutines.delay(5)
        val second = (ops(session).stop(e) as SessionRun.Ran).result
        assertIs<OperationResult.Busy>(second)
        gate.complete(Unit)
        assertIs<OperationResult.Acknowledged<*>>((first.await() as SessionRun.Ran).result)
    }

    @Test fun theSessionNameIsTheOnlyVariableArgumentAndIsChecked() {
        val cli = HerdrCli(herdr, "default")
        assertEquals(listOf(herdr, "session", "stop", "a-b.c_1", "--json"), cli.sessionStop("a-b.c_1"))
        for (bad in listOf("", "-x", "a b", "a;b", "a'b", "a\nb", "../x", "x".repeat(65))) {
            assertFailsWith<IllegalArgumentException>("for <$bad>") { cli.sessionStop(bad) }
            assertFailsWith<IllegalArgumentException>("for <$bad>") { cli.sessionDelete(bad) }
        }
    }

    @Test fun theDialogsNameTheSessionAndTheHost() {
        assertTrue("paddock-test-2" in SessionCopy.stopTitle("paddock-test-2"))
        assertTrue("laptop" in SessionCopy.stopBody("paddock-test-2", "laptop", isDefault = false))
        assertTrue("default session" in SessionCopy.stopBody("default", "laptop", isDefault = true))
        assertTrue("cannot be undone" in SessionCopy.deleteBody("paddock-test-2", "laptop"))
        assertTrue("Restart" in SessionCopy.RESTART_DISABLED)
    }
}
