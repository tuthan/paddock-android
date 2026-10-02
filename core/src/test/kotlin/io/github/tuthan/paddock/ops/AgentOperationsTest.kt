package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.StalePane
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.RelayClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Test

/** The prompt, key and focus operations against a scripted host: what is written, in what order, and what is never repeated. */
class AgentOperationsTest {
    private var now = 9_000_000L
    private val store = InMemoryJournalStore()
    private val journal = OperationJournal(store) { now }
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 2)

    private sealed interface Reply { data class Line(val body: String) : Reply; data object Hold : Reply; data object Drop : Reply }

    private class Host {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val streams = CopyOnWriteArrayList<FakeStream>()
        @Volatile var held: FakeStream? = null
        var handler: (method: String, params: JsonObject, id: String) -> Reply = { _, _, _ -> Reply.Drop }
        fun methods() = requests.map { it["method"]!!.jsonPrimitive.content }
        fun count(method: String) = methods().count { it == method }
        fun requestFor(method: String) = requests.first { it["method"]!!.jsonPrimitive.content == method }["params"]!!.jsonObject

        fun session(openFailsOnCall: Int? = null): SshSession {
            var opened = 0
            val inner = FakeSession(onStream = { s ->
                streams += s
                s.onRequest = { line ->
                    val req = Json.parseToJsonElement(line).jsonObject
                    requests += req
                    when (val r = handler(req["method"]!!.jsonPrimitive.content, req["params"]!!.jsonObject, req["id"]!!.jsonPrimitive.content)) {
                        is Reply.Line -> s.feed(r.body + "\n")
                        Reply.Hold -> held = s
                        Reply.Drop -> s.end()
                    }
                }
            })
            return object : SshSession by inner {
                override suspend fun openStream(argv: List<String>): StreamChannel {
                    if (openFailsOnCall != null && ++opened == openFailsOnCall) throw IOException("no channel")
                    return inner.openStream(argv)
                }
            }
        }
    }

    private val host = Host()

    private fun agentJson(id: String, terminal: String = "term_1", status: String = "idle", extra: String = "", focused: Boolean = false) =
        """{"terminal_id":"$terminal","agent":"claude","agent_status":"$status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":$focused,"revision":1,"state_change_seq":7$extra}"""

    private fun ok(id: String, type: String, agent: String) = Reply.Line("""{"id":"$id","result":{"type":"$type","agent":$agent}}""")
    private fun err(id: String, code: String, message: String) = Reply.Line("""{"id":"$id","error":{"code":"$code","message":"$message"}}""")

    /** An agent that reads as [status]/[extra] and takes whatever else the test scripts. */
    private fun script(status: String = "idle", extra: String = "", terminal: String = "term_1", rest: (String, JsonObject, String) -> Reply = { _, _, _ -> Reply.Drop }) {
        host.handler = { m, p, id -> if (m == "agent.get") ok(id, "agent_info", agentJson(id, terminal, status, extra)) else rest(m, p, id) }
    }

    private fun snapshot(epoch: Long = 2, terminal: String = "term_1") =
        Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w1:p1", terminalId = terminal, workspaceId = "w1", tabId = "w1:t1"))), now, epoch)

    private fun ops(installed: Installed? = snapshot(), requireHints: Boolean = false, session: SshSession = host.session()): AgentOperations =
        AgentOperations(RelayClient(session, "/relay.py", "/sock") , journal, { installed }, { now }, requireHints)

    // ---- prompt ------------------------------------------------------------------------------------------------------

    @Test fun anAnswerForAnotherTerminalIsNeverAcknowledgedAndSaysWhereTheWriteWent() = runBlocking<Unit> {
        // The pane id changed hands after the read: herdr takes the prompt for the agent now behind it and names that terminal.
        script(rest = { m, _, id -> if (m == "agent.prompt") ok(id, "agent_prompted", agentJson(id, terminal = "term_other")) else Reply.Drop })
        val r = ops().prompt(key, "do the thing")
        assertIs<OperationResult.Unknown>(r)
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.Unknown, row.outcome)
        assertTrue(row.note.startsWith(Misdelivered.PREFIX) && "term_other" in row.note && "term_1" in row.note, row.note)
        val line = io.github.tuthan.paddock.ops.OperationPresenter().line(OperationKind.Prompt, r)
        assertTrue("different agent" in line.text, line.text)
        // It blocks this terminal until the person looks, like any unknown outcome.
        assertTrue(journal.begin(key, OperationKind.Prompt, "again") is Begin.NeedsReread)
    }

    @Test fun focusAnsweredForAnotherTerminalIsReportedTheSameWay() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.focus") ok(id, "agent_info", agentJson(id, terminal = "term_other")) else Reply.Drop })
        assertIs<OperationResult.Unknown>(ops().focus(key))
        assertTrue(journal.records.value.single().note.startsWith(Misdelivered.PREFIX))
    }

    @Test fun aPromptReadsFirstThenWritesOneRequestWithItsTextInsideTheJson() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.prompt") ok(id, "agent_prompted", agentJson(id)) else Reply.Drop })
        val text = "first line\nsecond \"quoted\" line with ü and a tab\t"
        val r = ops().prompt(key, text)
        assertIs<OperationResult.Acknowledged<*>>(r)
        assertEquals(listOf("agent.get", "agent.prompt"), host.methods())
        assertEquals(text, host.requestFor("agent.prompt")["text"]!!.jsonPrimitive.content)
        assertEquals("w1:p1", host.requestFor("agent.prompt")["target"]!!.jsonPrimitive.content)
        val row = journal.records.value.single()
        assertEquals(OperationOutcome.Acknowledged, row.outcome)
        assertEquals(7L, row.seqAtSend)
        assertEquals(OperationJournal.sha256Hex(text), row.payloadSha256)
        assertNull(row.promptText)
        // Each stream's argv is the interpreter, the relay path and the socket path, and nothing else.
        assertTrue(host.streams.all { it.argv == listOf("python3", "/relay.py", "/sock") })
        assertTrue(host.streams.none { s -> s.argv.any { "first line" in it } })
    }

    @Test fun theTextIsKeptInTheJournalOnlyWhenTheSettingIsOn() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.prompt") ok(id, "agent_prompted", agentJson(id)) else Reply.Drop })
        ops().prompt(key, "keep me", keepText = true)
        assertEquals("keep me", journal.records.value.single().promptText)
    }

    @Test fun aWorkingAgentIsRefusedAfterTheReadAndNothingIsWritten() = runBlocking<Unit> {
        script(status = "working")
        val r = ops().prompt(key, "hello")
        val n = assertIs<OperationResult.NotSent>(r)
        assertEquals(NotReadyReason.Working.code, n.reason)
        assertEquals(listOf("agent.get"), host.methods())
        assertEquals(OperationOutcome.NotSent, journal.records.value.single().outcome)
    }

    @Test fun anExplicitNotInteractiveHintIsRefusedAndNamed() = runBlocking<Unit> {
        script(extra = ""","interactive_ready":false,"launch_pending":false""")
        assertEquals(NotReadyReason.NotInteractive.code, assertIs<OperationResult.NotSent>(ops().prompt(key, "hello")).reason)
        assertEquals(1, host.methods().size)
    }

    @Test fun theStrictReadingRefusesAnAgentThatReportsNoHints() = runBlocking<Unit> {
        script()
        assertEquals(NotReadyReason.HintsUnreported.code, assertIs<OperationResult.NotSent>(ops(requireHints = true).prompt(key, "hello")).reason)
        assertEquals(listOf("agent.get"), host.methods())
    }

    @Test fun aReadThatTakesLongerThanTwoSecondsIsTooOldToSendOn() = runBlocking<Unit> {
        host.handler = { m, _, id -> if (m == "agent.get") { now += 3_000; ok(id, "agent_info", agentJson(id)) } else Reply.Drop }
        assertEquals(NotReadyReason.StaleRead.code, assertIs<OperationResult.NotSent>(ops().prompt(key, "hello")).reason)
        assertEquals(1, host.methods().size)
    }

    @Test fun aPaneThatNowHoldsAnotherTerminalIsRefusedBeforeTheWrite() = runBlocking<Unit> {
        script(terminal = "term_other")
        assertEquals("pane_moved", assertIs<OperationResult.NotSent>(ops().prompt(key, "hello")).reason)
        assertEquals(listOf("agent.get"), host.methods())
    }

    @Test fun anEpochThatMovedOnIsStaleAndNothingAtAllIsSent() = runBlocking<Unit> {
        script()
        assertIs<OperationResult.Stale>(ops(installed = snapshot(epoch = 3)).prompt(key, "hello"))
        assertTrue(host.requests.isEmpty())
        assertTrue(journal.records.value.isEmpty())
    }

    @Test fun noSnapshotYetIsStaleToo() = runBlocking<Unit> {
        assertIs<OperationResult.Stale>(ops(installed = null).prompt(key, "hello"))
        assertTrue(host.requests.isEmpty())
    }

    @Test fun anAgentThatBlockedBetweenTheReadAndTheSendIsHerdrsRefusalAndIsNeverRetried() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.prompt") err(id, "agent_blocked", "agent w1:p1 is blocked and requires interactive input") else Reply.Drop })
        val r = ops().prompt(key, "hello")
        val rej = assertIs<OperationResult.Rejected>(r)
        assertEquals("agent_blocked", rej.code)
        assertEquals(1, host.count("agent.prompt"))
        assertEquals(OperationOutcome.Rejected, journal.records.value.single().outcome)
        // The row is final: it does not block the terminal, and the next tap is a new, deliberate operation.
        script(rest = { m, _, id -> if (m == "agent.prompt") ok(id, "agent_prompted", agentJson(id)) else Reply.Drop })
        assertIs<OperationResult.Acknowledged<*>>(ops().prompt(key, "hello"))
        assertEquals(2, host.count("agent.prompt"))
    }

    @Test fun aLinkThatDropsAfterTheWriteIsUnknownAndOnlyOnePromptWasWritten() = runBlocking<Unit> {
        script(rest = { m, _, _ -> if (m == "agent.prompt") Reply.Drop else Reply.Drop })
        val o = ops()
        val r = o.prompt(key, "hello")
        val u = assertIs<OperationResult.Unknown>(r)
        assertEquals(OperationOutcome.Unknown, u.record.outcome)
        assertEquals(1, host.count("agent.prompt"))
        // Nothing resends it, whoever asks.
        assertIs<OperationResult.NeedsReread>(o.prompt(key, "hello"))
        assertIs<OperationResult.NeedsReread>(o.sendKey(key, OperationKind.Esc))
        assertIs<OperationResult.NeedsReread>(o.focus(key))
        assertEquals(1, host.count("agent.prompt"))
    }

    @Test fun aChannelThatCouldNotBeOpenedForTheSendIsNotSent() = runBlocking<Unit> {
        script()
        val r = ops(session = host.session(openFailsOnCall = 2)).prompt(key, "hello")   // call 1 is the read, call 2 the prompt
        val n = assertIs<OperationResult.NotSent>(r)
        assertEquals("transport_failed", n.reason)
        assertEquals(listOf("agent.get"), host.methods())
        assertNull(journal.records.value.single().sentAt)
    }

    @Test fun aDuplicateTapWhileTheFirstWaitsForItsAnswerWritesNothingMore() = runBlocking<Unit> {
        script(rest = { m, _, _ -> if (m == "agent.prompt") Reply.Hold else Reply.Drop })
        val o = ops()
        val first = async { o.prompt(key, "hello") }
        while (host.count("agent.prompt") == 0) kotlinx.coroutines.delay(5)
        val second = o.prompt(key, "hello")
        assertEquals(OperationOutcome.Sent, assertIs<OperationResult.Busy>(second).first.outcome)
        assertEquals(1, host.count("agent.prompt"))
        val id = host.requests.last()["id"]!!.jsonPrimitive.content
        host.held!!.feed("""{"id":"$id","result":{"type":"agent_prompted","agent":${agentJson(id)}}}""" + "\n")
        assertIs<OperationResult.Acknowledged<*>>(first.await())
        assertEquals(1, host.count("agent.prompt"))
        assertEquals(1, journal.records.value.size)
    }

    @Test fun anEmptyOrOversizedPromptIsNeverStarted() = runBlocking<Unit> {
        val o = ops()
        assertFailsWith<IllegalArgumentException> { o.prompt(key, "   ") }
        assertFailsWith<IllegalArgumentException> { o.prompt(key, "x".repeat(AgentOperations.MAX_PROMPT_CHARS + 1)) }
        assertTrue(host.requests.isEmpty() && journal.records.value.isEmpty())
    }

    // ---- keys and focus ----------------------------------------------------------------------------------------------

    @Test fun escAndCtrlCEachGoOutAsOneJournaledSendKeys() = runBlocking<Unit> {
        script(status = "blocked", rest = { m, _, id -> if (m == "agent.send_keys") Reply.Line("""{"id":"$id","result":{"type":"ok"}}""") else Reply.Drop })
        val o = ops()
        assertIs<OperationResult.Acknowledged<*>>(o.sendKey(key, OperationKind.Esc))
        assertIs<OperationResult.Acknowledged<*>>(o.sendKey(key, OperationKind.CtrlC))
        val keys = host.requests.filter { it["method"]!!.jsonPrimitive.content == "agent.send_keys" }
            .map { it["params"]!!.jsonObject["keys"]!!.jsonArray.map { k -> k.jsonPrimitive.content } }
        assertEquals(listOf(listOf("esc"), listOf("ctrl+c")), keys)
        assertEquals(listOf(OperationKind.Esc, OperationKind.CtrlC), journal.records.value.map { it.kind })
        assertTrue(journal.records.value.all { it.outcome == OperationOutcome.Acknowledged })
    }

    @Test fun aKeyIsAllowedWhileTheAgentIsBlockedBecauseThatIsWhatManualInputIsFor() = runBlocking<Unit> {
        script(status = "blocked", rest = { m, _, id -> if (m == "agent.send_keys") Reply.Line("""{"id":"$id","result":{"type":"ok"}}""") else Reply.Drop })
        assertIs<OperationResult.Acknowledged<*>>(ops().sendKey(key, OperationKind.Esc))
    }

    @Test fun aKeyToAPaneThatMovedIsRefused() = runBlocking<Unit> {
        script(terminal = "term_other")
        assertEquals("pane_moved", assertIs<OperationResult.NotSent>(ops().sendKey(key, OperationKind.Esc)).reason)
        assertEquals(0, host.count("agent.send_keys"))
    }

    @Test fun aKeyAnswerOfTheWrongTypeAfterTheWriteIsUnknown() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.send_keys") Reply.Line("""{"id":"$id","result":{"type":"pong"}}""") else Reply.Drop })
        assertIs<OperationResult.Unknown>(ops().sendKey(key, OperationKind.Esc))
    }

    @Test fun onlyEscAndCtrlCAreKeys() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { ops().sendKey(key, OperationKind.Prompt) }
    }

    @Test fun focusIsJournaledAndReturnsTheFocusedAgent() = runBlocking<Unit> {
        script(rest = { m, _, id -> if (m == "agent.focus") ok(id, "agent_info", agentJson(id, focused = true)) else Reply.Drop })
        val r = ops().focus(key)
        assertTrue(assertIs<OperationResult.Acknowledged<io.github.tuthan.paddock.herdr.Agent>>(r).value.focused)
        assertEquals(OperationKind.Focus, journal.records.value.single().kind)
        assertEquals("w1:p1", host.requestFor("agent.focus")["target"]!!.jsonPrimitive.content)
    }

    // ---- reading again -----------------------------------------------------------------------------------------------

    @Test fun reReadingAfterAnUnknownOutcomeResolvesItAndFreesTheTerminal() = runBlocking<Unit> {
        script(rest = { m, _, _ -> if (m == "agent.prompt") Reply.Drop else Reply.Drop })
        val o = ops()
        val unknown = assertIs<OperationResult.Unknown>(o.prompt(key, "hello")).record
        val read = o.reread(key, unknown.id)
        assertEquals("term_1", read.agent.terminalId)
        assertTrue(journal.get(unknown.id)!!.resolvedAt != null)
        assertEquals(OperationOutcome.Unknown, journal.get(unknown.id)!!.outcome)
        script(rest = { m, _, id -> if (m == "agent.prompt") ok(id, "agent_prompted", agentJson(id)) else Reply.Drop })
        assertIs<OperationResult.Acknowledged<*>>(o.prompt(key, "hello again"))
    }

    @Test fun aReReadThatFailsLeavesTheRowUnresolved() = runBlocking<Unit> {
        script(rest = { _, _, _ -> Reply.Drop })
        val o = ops()
        val unknown = assertIs<OperationResult.Unknown>(o.prompt(key, "hello")).record
        host.handler = { _, _, _ -> Reply.Drop }                       // now the read itself gets no answer
        assertFailsWith<Exception> { o.reread(key, unknown.id) }
        assertNull(journal.get(unknown.id)!!.resolvedAt)
    }

    @Test fun readOfAPaneThatNowHoldsAnotherTerminalIsStale() = runBlocking<Unit> {
        script(terminal = "term_other")
        assertFailsWith<StalePane> { ops().read(key) }
    }


    @Test fun rereadAllReadsOnceFreesEveryWaitingRowAndReportsTheStateHerdrGivesNow() = runBlocking<Unit> {
        script(rest = { _, _, _ -> Reply.Drop })                        // idle, so the prompt goes; its answer never comes
        val o = ops()
        val unknown = assertIs<OperationResult.Unknown>(o.prompt(key, "hello", keepText = true)).record
        script(status = "working")                                       // by the re-read the agent has moved on
        val readsBefore = host.count("agent.get")
        now += 40_000
        val report = o.rereadAll(key)
        assertEquals(1, host.count("agent.get") - readsBefore, "one fresh read")
        assertEquals(listOf(unknown.id), report.resolved.map { it.id })
        assertEquals(io.github.tuthan.paddock.herdr.AgentStatus.Working, report.status)
        assertEquals(now, report.readAtMillis)
        assertEquals(OperationOutcome.Unknown, journal.get(unknown.id)!!.outcome, "the row stays unknown")
        assertEquals("hello", journal.get(unknown.id)!!.promptText, "and keeps what it kept")
        assertTrue(journal.unresolvedUnknown(key).isEmpty())
    }

    @Test fun rereadAllWithNothingWaitingStillReadsAndFreesNothing() = runBlocking<Unit> {
        script()
        val report = ops().rereadAll(key)
        assertTrue(report.resolved.isEmpty())
        assertEquals(1, host.count("agent.get"))
    }

    @Test fun rereadAllAfterARestartUsesTheEpochNowInstalledNotTheOneTheRowWasWrittenIn() = runBlocking<Unit> {
        script(rest = { _, _, _ -> Reply.Drop })
        val unknown = assertIs<OperationResult.Unknown>(ops().prompt(key, "hello")).record
        assertEquals(2L, unknown.epoch)
        script()
        val newKey = TerminalKey(key.target, epoch = 5)          // the connection after a restart is a new epoch
        val report = ops(installed = snapshot(epoch = 5)).rereadAll(newKey)
        assertEquals(listOf(unknown.id), report.resolved.map { it.id })
        assertFailsWith<StalePane> { ops(installed = snapshot(epoch = 5)).rereadAll(key) }
    }

    @Test fun rereadAllThatCannotReadLeavesEveryRowWaiting() = runBlocking<Unit> {
        script(rest = { _, _, _ -> Reply.Drop })
        val o = ops()
        val unknown = assertIs<OperationResult.Unknown>(o.prompt(key, "hello")).record
        host.handler = { _, _, _ -> Reply.Drop }
        assertFailsWith<Exception> { o.rereadAll(key) }
        assertTrue(journal.get(unknown.id)!!.awaitsReread)
    }
}
