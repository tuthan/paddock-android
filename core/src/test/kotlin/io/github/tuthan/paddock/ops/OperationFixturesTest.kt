package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.AgentInfoResult
import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.reconcile.Installed
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.RelayClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The operations against herdr's own replies, captured by `tools/capture-operation-fixtures.sh` and
 * `tools/capture-fixtures.sh` and pinned in `protocol/SOURCE.json`: nothing here is a shape typed by hand. The agent
 * records are the real ones from Phase 00, which carry neither `interactive_ready` nor `launch_pending`, as every
 * agent on herdr 0.9.1 does.
 */
class OperationFixturesTest {
    private val dir = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private fun fixture(name: String) = File(dir, name).readText().trim()
    private fun failure(name: String) = assertIs<Message.Failure>(Envelope.parse(fixture(name)))

    // The agent in agent-get-*.json: terminal term_65cbe353cc3172 in pane w2:p1.
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_65cbe353cc3172"), epoch = 1)
    private val now = 1_000_000L

    /**
     * The operation fixtures were captured from the fake agent's own pane, the agent-get fixtures from another, so their
     * terminal ids differ. A write must be answered for the terminal it meant (herdr names the agent it wrote to, and the
     * app reports any other, see [Misdelivered]), so the harness answers as the terminal under test. The files stay as captured.
     */
    private fun answeredFor(body: String, terminalId: String) = body.replace(Regex("\"terminal_id\"\\s*:\\s*\"[^\"]*\""), "\"terminal_id\":\"$terminalId\"")

    /** Answers `agent.get` with [get] and every other method with [other], each with the request's own id. */
    private fun ops(get: String, other: String?, journal: OperationJournal, requireHints: Boolean = false, methods: MutableList<String> = CopyOnWriteArrayList()): AgentOperations {
        val session = FakeSession(onStream = { s ->
            s.onRequest = { line ->
                val req = Json.parseToJsonElement(line).jsonObject
                val method = req["method"]!!.jsonPrimitive.content
                methods += method
                val body = if (method == "agent.get") get else other?.let { answeredFor(it, key.target.terminalId) } ?: ""
                s.feed(body.replaceFirst(Regex("\"id\":\"[^\"]*\""), "\"id\":\"${req["id"]!!.jsonPrimitive.content}\"") + "\n")
            }
        })
        val installed = Installed(Snapshot("0.9.1", 22, panes = listOf(Pane(paneId = "w2:p1", terminalId = "term_65cbe353cc3172", workspaceId = "w2", tabId = "w2:t1"))), now, 1)
        return AgentOperations(RelayClient(session, "/relay.py", "/sock"), journal, { installed }, { now }, requireHints)
    }

    private fun journal() = OperationJournal(InMemoryJournalStore()) { now }

    @Test fun theCapturedPromptReplyDecodesAsTheAgentThatWasPrompted() {
        val ok = assertIs<Message.Success>(Envelope.parse(fixture("operation-agent-prompt.json")))
        assertEquals("agent_prompted", ok.type)
        val agent = ok.decode<AgentInfoResult>("agent_prompted").agent
        assertEquals("claude", agent.agent)
        assertEquals(null, agent.interactiveReady); assertEquals(null, agent.launchPending)
    }

    @Test fun theCapturedErrorCodesAreTheOnesTheCodeKeysOn() {
        assertEquals("agent_blocked", failure("operation-agent-prompt-blocked.json").code)
        assertTrue("blocked" in failure("operation-agent-prompt-blocked.json").message)
        assertEquals("agent_not_ready", failure("operation-agent-prompt-not-active.json").code)
        assertEquals("agent_not_found", failure("operation-agent-prompt-not-found.json").code)
        assertEquals("invalid_key", failure("operation-send-keys-invalid.json").code)
        val keys = assertIs<Message.Success>(Envelope.parse(fixture("operation-send-keys-ok.json")))
        assertEquals("ok", keys.type)
        assertEquals(setOf("type"), keys.result.keys, "an ok carries nothing but its type: the write is acknowledged, not a receipt")
    }

    @Test fun anIdleAgentHerdrSendsNoHintsForCanBePromptedAndTheStrictReadingRefusesIt() = runBlocking<Unit> {
        val j = journal()
        val methods = CopyOnWriteArrayList<String>()
        val r = ops(fixture("agent-get-idle.json"), fixture("operation-agent-prompt.json"), j, methods = methods).prompt(key, "hello")
        assertIs<OperationResult.Acknowledged<*>>(r)
        assertEquals(listOf("agent.get", "agent.prompt"), methods)

        val strict = ops(fixture("agent-get-idle.json"), fixture("operation-agent-prompt.json"), journal(), requireHints = true)
        assertEquals(NotReadyReason.HintsUnreported.code, assertIs<OperationResult.NotSent>(strict.prompt(key, "hello")).reason)
    }

    @Test fun theRealWorkingBlockedAndUnknownRecordsEachRefuseBeforeAnyPromptIsWritten() = runBlocking<Unit> {
        for ((status, reason) in listOf("working" to NotReadyReason.Working, "blocked" to NotReadyReason.Blocked, "unknown" to NotReadyReason.StatusUnknown)) {
            val methods = CopyOnWriteArrayList<String>()
            val r = ops(fixture("agent-get-$status.json"), fixture("operation-agent-prompt.json"), journal(), methods = methods).prompt(key, "hello")
            assertEquals(reason.code, assertIs<OperationResult.NotSent>(r).reason, status)
            assertEquals(listOf("agent.get"), methods, status)
        }
    }

    @Test fun herdrsOwnBlockedRefusalIsRejectedWithItsCodeAndMessage() = runBlocking<Unit> {
        val j = journal()
        val r = ops(fixture("agent-get-idle.json"), fixture("operation-agent-prompt-blocked.json"), j).prompt(key, "hello")
        val rej = assertIs<OperationResult.Rejected>(r)
        assertEquals("agent_blocked", rej.code)
        assertTrue("requires interactive input" in rej.message)
        assertEquals(OperationOutcome.Rejected, j.records.value.single().outcome)
    }

    @Test fun herdrsNotReadyRefusalIsRejectedToo() = runBlocking<Unit> {
        val r = ops(fixture("agent-get-idle.json"), fixture("operation-agent-prompt-not-active.json"), journal()).prompt(key, "hello")
        assertEquals("agent_not_ready", assertIs<OperationResult.Rejected>(r).code)
    }

    @Test fun escGoesThroughAndAnInvalidKeyIsHerdrsRefusal() = runBlocking<Unit> {
        assertIs<OperationResult.Acknowledged<*>>(ops(fixture("agent-get-blocked.json"), fixture("operation-send-keys-ok.json"), journal()).sendKey(key, OperationKind.Esc))
        assertEquals("invalid_key", assertIs<OperationResult.Rejected>(ops(fixture("agent-get-blocked.json"), fixture("operation-send-keys-invalid.json"), journal()).sendKey(key, OperationKind.Esc)).code)
    }

    @Test fun focusReturnsTheFocusedAgent() = runBlocking<Unit> {
        val r = ops(fixture("agent-get-idle.json"), fixture("operation-agent-focus.json"), journal()).focus(key)
        assertTrue(assertIs<OperationResult.Acknowledged<io.github.tuthan.paddock.herdr.Agent>>(r).value.focused)
    }
}
