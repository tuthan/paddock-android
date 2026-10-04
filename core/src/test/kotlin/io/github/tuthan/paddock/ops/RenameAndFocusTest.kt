package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.herdr.Tab
import io.github.tuthan.paddock.herdr.Workspace
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.RelayClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** `agent rename`, `workspace focus` and `tab focus`: one journaled request each, resolved against the snapshot, ids only on the wire. */
class RenameAndFocusTest {
    private var now = 3_000_000L
    private val journal = OperationJournal(InMemoryJournalStore()) { now }
    private val h = HostProfileId("h1")
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private var reply: (method: String, id: String) -> String = { _, id -> """{"id":"$id","result":{"type":"ok"}}""" }

    private fun agentJson(terminal: String = "term_1", name: String = "renamed") =
        """{"terminal_id":"$terminal","agent":"claude","name":"$name","agent_status":"idle","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":false,"revision":1,"state_change_seq":7}"""

    private val session = FakeSession(onStream = { s: FakeStream ->
        s.onRequest = { line ->
            val req = Json.parseToJsonElement(line).jsonObject
            requests += req
            val m = req["method"]!!.jsonPrimitive.content; val id = req["id"]!!.jsonPrimitive.content
            s.feed(reply(m, id) + "\n")
        }
    })
    private val relay = RelayClient(session, "/relay.py", "/sock")
    private val installed = Installed(Snapshot("0.9.1", 22,
        workspaces = listOf(Workspace("w1")), tabs = listOf(Tab("w1:t1", "w1")),
        panes = listOf(Pane(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1"))), now, 4)
    private val agents = AgentOperations(relay, journal, { installed }, { now })
    private val spaces = SpaceOperations(relay, journal, { installed }, h, "paddock-test-2")
    private val key = TerminalKey(TargetRef(h, "paddock-test-2", "term_1"), 4)

    private fun methods() = requests.map { it["method"]!!.jsonPrimitive.content }

    @Test fun renameReadsTheAgentThenSendsOneRequestWithTheNewNameAsJson() = runBlocking<Unit> {
        reply = { m, id -> if (m == "agent.get") """{"id":"$id","result":{"type":"agent_info","agent":${agentJson()}}}""" else """{"id":"$id","result":{"type":"agent_info","agent":${agentJson(name = "fresh")}}}""" }
        val r = agents.rename(key, "fresh")
        assertIs<OperationResult.Acknowledged<*>>(r)
        assertEquals(listOf("agent.get", "agent.rename"), methods())
        val params = requests.last()["params"]!!.jsonObject
        assertEquals("w1:p1", params["target"]!!.jsonPrimitive.content)
        assertEquals("fresh", params["name"]!!.jsonPrimitive.content)
        assertEquals(OperationKind.Rename, journal.records.value.single().outcome.let { journal.records.value.single().kind })
        assertTrue(!journal.records.value.single().let { it.promptText != null }, "a name is not prompt text and is not kept in the row")
    }

    @Test fun clearingTheNameSendsAnExplicitNull() = runBlocking<Unit> {
        reply = { m, id -> """{"id":"$id","result":{"type":"agent_info","agent":${agentJson()}}}""" }
        assertIs<OperationResult.Acknowledged<*>>(agents.rename(key, null))
        assertEquals(JsonNull, requests.last()["params"]!!.jsonObject["name"])
    }

    @Test fun aBadNameNeverReachesTheHostAndAnotherAgentsAnswerIsNotAcknowledged() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { agents.rename(key, "has space") }
        assertTrue(requests.isEmpty() && journal.records.value.isEmpty())
        reply = { m, id -> """{"id":"$id","result":{"type":"agent_info","agent":${agentJson(terminal = if (m == "agent.get") "term_1" else "term_other")}}}""" }
        assertIs<OperationResult.Unknown>(agents.rename(key, "fresh"))
    }

    @Test fun herdrsRefusalOfATakenNameIsShownAsHerdrs() = runBlocking<Unit> {
        reply = { m, id -> if (m == "agent.get") """{"id":"$id","result":{"type":"agent_info","agent":${agentJson()}}}""" else """{"id":"$id","error":{"code":"agent_name_taken","message":"agent name fresh is already used"}}""" }
        val r = agents.rename(key, "fresh")
        assertEquals("agent_name_taken", assertIs<OperationResult.Rejected>(r).code)
    }

    @Test fun focusingAWorkspaceSendsItsIdOnlyAndRecordsItOnAWorkspaceSubject() = runBlocking<Unit> {
        reply = { _, id -> """{"id":"$id","result":{"type":"workspace_info","workspace":{"workspace_id":"w1"}}}""" }
        assertIs<OperationResult.Acknowledged<*>>(spaces.focusWorkspace("w1"))
        assertEquals(listOf("workspace.focus"), methods())
        assertEquals("w1", requests.single()["params"]!!.jsonObject["workspace_id"]!!.jsonPrimitive.content)
        val row = journal.records.value.single()
        assertEquals(OperationKind.FocusWorkspace, row.kind)
        assertEquals("workspace:w1", row.terminalId)
    }

    @Test fun focusingATabSendsItsIdOnly() = runBlocking<Unit> {
        reply = { _, id -> """{"id":"$id","result":{"type":"tab_info","tab":{"tab_id":"w1:t1","workspace_id":"w1"}}}""" }
        assertIs<OperationResult.Acknowledged<*>>(spaces.focusTab("w1:t1"))
        assertEquals("w1:t1", requests.single()["params"]!!.jsonObject["tab_id"]!!.jsonPrimitive.content)
        assertEquals("tab:w1:t1", journal.records.value.single().terminalId)
    }

    @Test fun anIdTheSnapshotDoesNotHoldIsStaleAndNothingIsSent() = runBlocking<Unit> {
        assertIs<OperationResult.Stale>(spaces.focusWorkspace("w99"))
        assertIs<OperationResult.Stale>(spaces.focusTab("w9:t9"))
        assertTrue(requests.isEmpty())
        val none = SpaceOperations(relay, journal, { null }, h, "paddock-test-2")
        assertIs<OperationResult.Stale>(none.focusWorkspace("w1"))
    }

    @Test fun aFocusThatHerdrRefusesIsRejectedAndALostLinkIsUnknown() = runBlocking<Unit> {
        reply = { _, id -> """{"id":"$id","error":{"code":"workspace_not_found","message":"no such workspace"}}""" }
        assertEquals("workspace_not_found", assertIs<OperationResult.Rejected>(spaces.focusWorkspace("w1")).code)
        val dropping = FakeSession(onStream = { it.onRequest = { _ -> it.end() } })
        val lost = SpaceOperations(RelayClient(dropping, "/relay.py", "/sock"), journal, { installed }, h, "paddock-test-2")
        assertIs<OperationResult.Unknown>(lost.focusTab("w1:t1"))
        assertIs<OperationResult.NeedsReread>(lost.focusTab("w1:t1"))
    }
}
