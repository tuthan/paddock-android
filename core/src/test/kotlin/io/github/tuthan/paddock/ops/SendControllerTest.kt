package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.RelayClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SendControllerTest {
    private val journal = OperationJournal(InMemoryJournalStore()) { 5_000_000L }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 1)
    private val methods = CopyOnWriteArrayList<String>()
    @Volatile private var held: FakeStream? = null
    @Volatile private var hold = false
    @Volatile private var status = "idle"

    private fun agent(id: String) = """{"terminal_id":"term_1","agent":"claude","agent_status":"$status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":false,"revision":1,"state_change_seq":4}"""

    private val session = FakeSession(onStream = { s ->
        s.onRequest = { line ->
            val req = Json.parseToJsonElement(line).jsonObject
            val id = req["id"]!!.jsonPrimitive.content
            val method = req["method"]!!.jsonPrimitive.content
            methods += method
            when {
                method == "agent.get" -> s.feed("""{"id":"$id","result":{"type":"agent_info","agent":${agent(id)}}}""" + "\n")
                hold -> held = s
                method == "agent.prompt" -> s.feed("""{"id":"$id","result":{"type":"agent_prompted","agent":${agent(id)}}}""" + "\n")
                else -> s.feed("""{"id":"$id","result":{"type":"ok"}}""" + "\n")
            }
        }
    })

    private val installed = Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1"))), 5_000_000L, 1)
    private val ops = AgentOperations(RelayClient(session, "/relay.py", "/sock"), journal, { installed }, { 5_000_000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun aPromptRunsInTheControllersScopeAndItsOutcomeIsKeptUntilDismissed() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.prompt(key, "hello", keepText = false)
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val o = c.outcomes.value.getValue("term_1")
        assertEquals(OperationKind.Prompt, o.kind)
        assertIs<OperationResult.Acknowledged<*>>(o.result)
        assertTrue(c.running.value.isEmpty())
        c.dismiss("term_1")
        assertNull(c.outcomes.value["term_1"])
        scope.cancel()
    }

    @Test fun aSecondTapWhileOneRunsNeverStartsAnotherCall() = runBlocking<Unit> {
        hold = true
        val c = SendController(scope, ops)
        c.prompt(key, "hello", false)
        until("the prompt to be written") { methods.contains("agent.prompt") }
        assertEquals(setOf("term_1"), c.running.value)
        c.prompt(key, "hello", false)
        c.prompt(key, "hello again", false)
        delay(150)
        assertEquals(1, methods.count { it == "agent.prompt" })
        val id = held!!.written.last().toString(Charsets.UTF_8).let { Json.parseToJsonElement(it.trim()).jsonObject["id"]!!.jsonPrimitive.content }
        held!!.feed("""{"id":"$id","result":{"type":"agent_prompted","agent":${agent(id)}}}""" + "\n")
        until("the outcome") { c.outcomes.value["term_1"] != null }
        assertEquals(1, journal.records.value.size)
        assertEquals(1, methods.count { it == "agent.prompt" })
        scope.cancel()
    }

    @Test fun aRefusedPromptIsKeptAsItsOutcome() = runBlocking<Unit> {
        status = "working"
        val c = SendController(scope, ops)
        c.prompt(key, "hello", false)
        until("the outcome") { c.outcomes.value["term_1"] != null }
        val r = assertIs<OperationResult.NotSent>(c.outcomes.value.getValue("term_1").result)
        assertEquals(NotReadyReason.Working.code, r.reason)
        assertEquals(0, methods.count { it == "agent.prompt" })
        scope.cancel()
    }

    @Test fun startingAnotherOperationClearsTheEarlierOutcomeWhileItRuns() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.sendKey(key, OperationKind.Esc)
        until("the Esc outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Esc }
        hold = true
        c.sendKey(key, OperationKind.CtrlC)
        until("the Ctrl+C to be written") { methods.count { it == "agent.send_keys" } == 2 }
        assertNull(c.outcomes.value["term_1"], "the old card is gone while the new send runs")
        scope.cancel()
    }

    @Test fun focusIsAControllerOperationToo() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        c.focus(key)
        until("the outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Focus }
        assertTrue(methods.contains("agent.focus"))
        scope.cancel()
    }


    private suspend fun makeUnknown(c: SendController): OperationRecord {
        hold = true
        c.prompt(key, "hello", keepText = true)
        until("the prompt to be written") { methods.contains("agent.prompt") }
        held!!.end()                                                    // the link dies with the answer still owed
        until("the unknown outcome") { c.outcomes.value["term_1"]?.result is OperationResult.Unknown }
        hold = false
        return journal.records.value.single()
    }

    @Test fun aRereadFreesTheWaitingRowAndItsReportIsKeptUntilDismissed() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        val unknown = makeUnknown(c)
        assertTrue(unknown.awaitsReread)
        c.reread(key)
        until("the re-read") { c.rereads.value["term_1"] != null }
        val done = assertIs<RereadOutcome.Done>(c.rereads.value.getValue("term_1"))
        assertEquals(listOf(unknown.id), done.report.resolved.map { it.id })
        assertTrue(journal.records.value.single().resolvedAt != null)
        assertTrue(c.running.value.isEmpty())
        assertNull(c.outcomes.value["term_1"], "the unknown card from the send is gone: it would offer a re-read for a row that is freed")
        assertEquals(1, methods.count { it == "agent.prompt" }, "a re-read writes nothing")
        c.dismissReread("term_1")
        assertNull(c.rereads.value["term_1"])
        scope.cancel()
    }

    @Test fun aRereadOfAnAgentThatIsGoneSaysSoAndLeavesTheRowWaiting() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        val unknown = makeUnknown(c)
        val gone = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 99)
        c.reread(gone)
        until("the re-read") { c.rereads.value["term_1"] != null }
        assertTrue(assertIs<RereadOutcome.Failed>(c.rereads.value.getValue("term_1")).gone)
        assertTrue(journal.get(unknown.id)!!.awaitsReread)
        scope.cancel()
    }

    @Test fun aTapOnRereadWhileAnotherCallRunsIsIgnoredAndANewOperationClearsTheReport() = runBlocking<Unit> {
        val c = SendController(scope, ops)
        makeUnknown(c)
        c.reread(key)
        until("the re-read") { c.rereads.value["term_1"] != null }
        c.sendKey(key, OperationKind.Esc)
        until("the Esc outcome") { c.outcomes.value["term_1"]?.kind == OperationKind.Esc }
        assertNull(c.rereads.value["term_1"], "starting another operation clears the earlier report")
        scope.cancel()
    }
}
