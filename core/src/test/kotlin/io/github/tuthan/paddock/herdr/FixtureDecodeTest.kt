package io.github.tuthan.paddock.herdr

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** AC-03.1 and the envelope table: every Phase 00 fixture decodes into the typed models. */
class FixtureDecodeTest {
    private val dir = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private fun text(name: String) = File(dir, name).readText().trim()
    private fun lines(name: String) = File(dir, name).readLines().filter { it.isNotBlank() }
    private fun success(name: String) = assertIs<Message.Success>(Envelope.parse(text(name)))

    @Test fun snapshotDecodes() {
        val s = success("snapshot.json").decode<SnapshotResult>("session_snapshot").snapshot
        assertEquals("0.9.1", s.version); assertEquals(22, s.protocol)
        assertEquals("w2:p1", s.focusedPaneId)
        assertEquals(1, s.workspaces.size); assertEquals("paddock-test", s.workspaces[0].label)
        assertEquals(1, s.tabs.size); assertEquals(1, s.panes.size); assertEquals(1, s.layouts.size)
        assertEquals(40, s.panes[0].scroll!!.viewportRows)
        assertEquals(120, s.layouts[0].area.width); assertEquals("w2:p1", s.layouts[0].panes[0].paneId)
        assertTrue(s.agents.isEmpty())
    }

    @Test fun socketSnapshotLineDecodesWithItsOwnBudget() {
        val line = lines("socket-snapshot.jsonl").single()
        val msg = Envelope.parse(line, Budgets.SNAPSHOT_LINE) as Message.Success
        assertEquals("fx-snap", msg.id)
        assertEquals(22, msg.decode<SnapshotResult>("session_snapshot").snapshot.protocol)
    }

    @Test fun everyAgentFixtureDecodesWithItsStatus() {
        val expected = mapOf("blocked" to AgentStatus.Blocked, "working" to AgentStatus.Working, "idle" to AgentStatus.Idle, "unknown" to AgentStatus.Unknown)
        for ((name, status) in expected) {
            val get = success("agent-get-$name.json").decode<AgentInfoResult>("agent_info").agent
            val list = success("agent-list-$name.json").decode<AgentListResult>("agent_list").agents.single()
            assertEquals(status, get.agentStatus, name); assertEquals(status, list.agentStatus, name)
            assertEquals("fake", get.agent); assertEquals("w2:p1", get.paneId)
            assertNull(get.message, "0.9.1 reports no message text")
        }
        assertTrue(success("agent-get-blocked.json").decode<AgentInfoResult>("agent_info").agent.stateChangeSeq!! > 0)
    }

    @Test fun theRichAgentCaptureDecodesWithLabelsAndDisplayName() {
        val get = success("agent-get-rich.json").decode<AgentInfoResult>("agent_info").agent
        assertEquals(mapOf("idle" to "waiting"), get.stateLabels); assertEquals("Fixture agent", get.displayAgent)
        assertEquals(AgentStatus.Blocked, get.agentStatus)
        val snap = success("snapshot-rich.json").decode<SnapshotResult>("session_snapshot").snapshot
        assertEquals(mapOf("idle" to "waiting"), snap.agents.single().stateLabels)
        val events = lines("events-status-rich.jsonl").drop(1).map { assertIs<EventOutcome.Status>(EventMapper.map(Envelope.parse(it) as Message.Event)).event }
        assertEquals(listOf(AgentStatus.Idle, AgentStatus.Blocked), events.map { it.agentStatus })
        assertTrue(events.all { it.stateLabels == mapOf("idle" to "waiting") })
    }

    /**
     * Real claude and codex panes carry `agent_session` as an object (seen on 2026-10-01; herdr fills it from its own
     * detection, so a synthetic report cannot produce it). The object is inserted here with schema 22's shape.
     */
    @Test fun aRealAgentsSessionObjectDecodes() {
        val session = buildJsonObject { put("agent", "claude"); put("kind", "id"); put("source", "herdr:claude"); put("value", "fixture-session") }
        val raw = PaddockJson.parseToJsonElement(text("snapshot-rich.json")).jsonObject
        val result = raw.getValue("result").jsonObject
        val snapshot = result.getValue("snapshot").jsonObject
        fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
        val withSession = snapshot.with("agents", JsonArray(snapshot.getValue("agents").jsonArray.map { it.jsonObject.with("agent_session", session) }))
            .with("panes", JsonArray(snapshot.getValue("panes").jsonArray.map { it.jsonObject.with("agent_session", session) }))
        val line = raw.with("result", result.with("snapshot", withSession)).toString()
        val s = (Envelope.parse(line, Budgets.SNAPSHOT_LINE) as Message.Success).decode<SnapshotResult>("session_snapshot").snapshot
        assertEquals("herdr:claude", s.agents.single().agentSession?.source)
        assertEquals(AgentStatus.Blocked, s.agents.single().agentStatus)
    }

    @Test fun aDecorativeFieldOfTheWrongShapeDropsOnlyThatField() {
        val line = """{"id":"x","result":{"type":"agent_info","agent":{"pane_id":"p","terminal_id":"t","workspace_id":"w","tab_id":"t1","focused":false,"revision":1,
            "agent_status":7,"agent_session":"just-a-string","state_labels":["a","b"],"interactive_ready":"yes","launch_pending":{},"title":{"x":1},
            "display_agent":[1],"terminal_title_stripped":42,"state_change_seq":"eleven","cwd":null}}}"""
        val a = (Envelope.parse(line.lines().joinToString("")) as Message.Success).decode<AgentInfoResult>("agent_info").agent
        assertEquals("p", a.paneId); assertEquals(AgentStatus.Unknown, a.agentStatus)
        assertNull(a.agentSession); assertNull(a.stateLabels); assertNull(a.interactiveReady); assertNull(a.launchPending)
        assertNull(a.title); assertNull(a.displayAgent); assertNull(a.stateChangeSeq); assertNull(a.cwd)
    }

    @Test fun anIdentityFieldOfTheWrongShapeStillFailsTheRead() {
        val line = """{"id":"x","result":{"type":"agent_info","agent":{"pane_id":{"no":1},"terminal_id":"t","workspace_id":"w","tab_id":"t1"}}}"""
        assertFailsWith<ProtocolError.Decode> { (Envelope.parse(line) as Message.Success).decode<AgentInfoResult>("agent_info") }
    }

    @Test fun paneTabWorkspaceListsDecode() {
        assertEquals("w2:p1", success("pane-get.json").decode<PaneInfoResult>("pane_info").pane.paneId)
        assertEquals("w2:t1", success("tab-list.json").decode<TabListResult>("tab_list").tabs.single().tabId)
        assertEquals("w2", success("workspace-list.json").decode<WorkspaceListResult>("workspace_list").workspaces.single().workspaceId)
    }

    @Test fun pongAndSubscriptionAck() {
        val pong = Envelope.parse(lines("socket-ping.jsonl").single()) as Message.Success
        val p = pong.decode<Pong>("pong")
        assertEquals(22, p.protocol); assertTrue(p.capabilities.healthCheck)
        for (f in listOf("subscribe-lifecycle-ack.jsonl", "subscribe-status-ack.jsonl")) {
            val ack = Envelope.parse(lines(f).first()) as Message.Success
            assertEquals("subscription_started", ack.type)
        }
    }

    @Test fun errorsDecodeAsFailuresIncludingTheEmptyId() {
        val f = assertIs<Message.Failure>(Envelope.parse(text("error-agent-get.json")))
        assertEquals("agent_not_found", f.code); assertEquals("cli:agent:get", f.id)
        val bad = assertIs<Message.Failure>(Envelope.parse(text("error-socket-badjson.json")))
        assertEquals("", bad.id); assertEquals("invalid_request", bad.code)
        val missing = assertIs<Message.Failure>(Envelope.parse(lines("subscribe-status-missing-pane.jsonl").single()))
        assertTrue(missing.message.contains("pane_id"))
    }

    @Test fun lifecycleEventsMapWithUnderscoredNames() {
        val events = lines("events-lifecycle.jsonl").drop(1).map { Envelope.parse(it) }.map { assertIs<Message.Event>(it) }
        val outcomes = events.map { EventMapper.map(it) }.map { assertIs<EventOutcome.Lifecycle>(it).event }
        assertEquals(listOf("pane_agent_detected", "pane_created", "pane_updated", "pane_closed"), outcomes.map { it.kind })
        assertEquals(listOf("w2:p1", "w2:p4", "w2:p4", "w2:p4"), outcomes.map { it.paneId }) // created/updated carry the pane object
        assertTrue(outcomes.all { it.workspaceId == "w2" })
    }

    @Test fun statusEventsMapWithDottedNames() {
        val statuses = lines("events-status.jsonl").drop(1).map { EventMapper.map(Envelope.parse(it) as Message.Event) }.map { assertIs<EventOutcome.Status>(it).event }
        assertEquals(listOf(AgentStatus.Blocked, AgentStatus.Working, AgentStatus.Idle, AgentStatus.Unknown), statuses.map { it.agentStatus })
        assertTrue(statuses.all { it.paneId == "w2:p1" && it.agent == "fake" })
    }

    @Test fun everyFixtureEventNameIsMapped() {
        val names = (lines("events-lifecycle.jsonl") + lines("events-status.jsonl")).map { Envelope.parse(it) }.filterIsInstance<Message.Event>().map { it.name }.toSet()
        assertTrue(names.all { it in EventMapper.LIFECYCLE_KINDS || it == "pane.agent_status_changed" }, names.toString())
    }

    @Test fun unmappedEventNamesAreIgnoredAndNamed() {
        val e = Envelope.parse("""{"event":"pane.something_new","data":{}}""") as Message.Event
        assertEquals(EventOutcome.Ignored("pane.something_new"), EventMapper.map(e))
    }

    // ---- hostile input fails closed with a named error ------------------------------------------------------

    @Test fun truncatedLineIsNotJson() { assertFailsWith<ProtocolError.NotJson> { Envelope.parse(text("snapshot.json").take(80)) } }

    @Test fun oversizeLineIsRefusedBeforeDecoding() {
        val e = assertFailsWith<ProtocolError.LineTooLong> { Envelope.parse("x".repeat(Budgets.LINE + 1)) }
        assertEquals(Budgets.LINE, e.limit)
        // The snapshot budget is larger, so the same size is then judged on its content, not its length.
        assertFailsWith<ProtocolError.NotJson> { Envelope.parse("x".repeat(Budgets.LINE + 1), Budgets.SNAPSHOT_LINE) }
    }

    @Test fun wrongResultTypeIsNamed() {
        val e = assertFailsWith<ProtocolError.WrongResultType> { success("agent-get-idle.json").decode<SnapshotResult>("session_snapshot") }
        assertEquals("agent_info", e.actual)
    }

    @Test fun aLineThatIsNeitherResponseNorEventIsAProtocolError() {
        assertFailsWith<ProtocolError.UnknownShape> { Envelope.parse("""{"hello":1}""") }
        assertFailsWith<ProtocolError.UnknownShape> { Envelope.parse("""{"id":"1","error":"flat string"}""") }
        assertFailsWith<ProtocolError.NotJson> { Envelope.parse("[1,2]") }
        assertFailsWith<ProtocolError.NotJson> { Envelope.parse("") }
    }

    @Test fun aMissingRequiredFieldNamesTheFieldAndDoesNotThrowOtherwise() {
        val line = """{"id":"x","result":{"type":"session_snapshot","snapshot":{"protocol":22}}}"""
        val e = assertFailsWith<ProtocolError.Decode> { (Envelope.parse(line) as Message.Success).decode<SnapshotResult>("session_snapshot") }
        assertTrue(e.message!!.contains("version"), e.message)
    }

    @Test fun unknownFieldsAndUnknownStatusesAreTolerated() {
        val line = """{"id":"x","result":{"type":"agent_info","agent":{"pane_id":"p","terminal_id":"t","workspace_id":"w","tab_id":"t1","agent_status":"hovering","brand_new":{"a":1}}}}"""
        val a = (Envelope.parse(line) as Message.Success).decode<AgentInfoResult>("agent_info").agent
        assertEquals(AgentStatus.Unknown, a.agentStatus)
    }

    @Test fun eventsLostIsRecognisedAsAnEventOrAnErrorCode() {
        assertEquals(EventOutcome.EventsLost, EventMapper.map(Envelope.parse("""{"event":"events_lost","data":{}}""") as Message.Event))
        assertTrue(EventMapper.isEventsLost(Envelope.parse("""{"id":"","error":{"code":"events_lost","message":"x"}}""") as Message.Failure))
    }

    @Test fun uiStringsAreBoundedByCodePointsWithoutSplittingASurrogatePair() {
        val s = "😀".repeat(600).boundedForUi(512)
        assertEquals(513, s.codePointCount(0, s.length)); assertTrue(s.endsWith("…"))
        assertFalse(s.indices.any { s[it].isHighSurrogate() && (it + 1 >= s.length || !s[it + 1].isLowSurrogate()) })
        assertEquals("short", "short".boundedForUi())
    }
}
