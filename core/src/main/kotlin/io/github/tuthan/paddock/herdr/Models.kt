package io.github.tuthan.paddock.herdr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** `done` is detection-only (a report accepts only the other four); unrecognised values become [Unknown] so a newer herdr cannot crash an older phone. */
@Serializable(with = AgentStatusSerializer::class)
enum class AgentStatus(val wire: String) {
    Idle("idle"), Working("working"), Blocked("blocked"), Done("done"), Unknown("unknown");

    companion object { fun fromWire(s: String?): AgentStatus = entries.firstOrNull { it.wire == s } ?: Unknown }
}

object AgentStatusSerializer : KSerializer<AgentStatus> {
    override val descriptor = PrimitiveSerialDescriptor("AgentStatus", PrimitiveKind.STRING)
    /** A value that is not a string at all is as unrecognised as an unknown word: [AgentStatus.Unknown], not a failed read. */
    override fun deserialize(decoder: Decoder): AgentStatus {
        val json = decoder as? JsonDecoder ?: return AgentStatus.fromWire(decoder.decodeString())
        val element = json.decodeJsonElement()
        return AgentStatus.fromWire((element as? JsonPrimitive)?.takeIf { it.isString }?.content)
    }
    override fun serialize(encoder: Encoder, value: AgentStatus) = encoder.encodeString(value.wire)
}

@Serializable data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class Scroll(
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Int = 0,
    @SerialName("offset_from_bottom") val offsetFromBottom: Int = 0,
    @SerialName("viewport_rows") val viewportRows: Int = 0,
)

@Serializable
data class Workspace(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
)

@Serializable
data class Tab(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
)

@Serializable
data class Pane(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    @Serializable(with = LenientString::class) val cwd: String? = null,
    @Serializable(with = LenientString::class) @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val revision: Long = 0,
    @Serializable(with = LenientScroll::class) val scroll: Scroll? = null,
    @Serializable(with = LenientString::class) @SerialName("terminal_title") val terminalTitle: String? = null,
    @Serializable(with = LenientString::class) @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @Serializable(with = LenientString::class) val title: String? = null,
)

/**
 * An agent is a pane with a detected agent. Types follow schema 22's `AgentInfo`: `agent_session` is an object
 * (real claude and codex panes carry one) and `state_labels` a string map. Everything that describes rather than
 * identifies the agent is [Lenient]: a wrong shape there drops that field, never the read. Reported message text
 * is absent in 0.9.1 (Phase 00 finding), so the UI never depends on `message`.
 */
@Serializable
data class Agent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    /** The agent's name as herdr reports it, for example `claude`. */
    @Serializable(with = LenientString::class) val agent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val revision: Long = 0,
    /** A Done is acknowledged at this value; a wrong shape disables that, it does not fail the read. */
    @Serializable(with = LenientLong::class) @SerialName("state_change_seq") val stateChangeSeq: Long? = null,
    val focused: Boolean = false,
    @Serializable(with = LenientString::class) val cwd: String? = null,
    @Serializable(with = LenientString::class) @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @Serializable(with = LenientString::class) @SerialName("terminal_title") val terminalTitle: String? = null,
    @Serializable(with = LenientString::class) @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    /** The presentation title an integration set (`pane report-metadata --title`); first in the title chain. */
    @Serializable(with = LenientString::class) val title: String? = null,
    @Serializable(with = LenientAgentSession::class) @SerialName("agent_session") val agentSession: AgentSession? = null,
    @Serializable(with = LenientBoolean::class) @SerialName("interactive_ready") val interactiveReady: Boolean? = null,
    @Serializable(with = LenientBoolean::class) @SerialName("launch_pending") val launchPending: Boolean? = null,
    @Serializable(with = LenientString::class) @SerialName("display_agent") val displayAgent: String? = null,
    @Serializable(with = LenientLabels::class) @SerialName("state_labels") val stateLabels: Map<String, String>? = null,
    @Serializable(with = LenientString::class) val message: String? = null,
)

/** Schema 22 `AgentSessionInfo`, for example `{agent: claude, kind: id, source: herdr:claude, value: …}`. Not shown or stored. */
@Serializable
data class AgentSession(
    val agent: String? = null,
    val kind: String? = null,
    val source: String? = null,
    val value: String? = null,
)

@Serializable data class LayoutPane(@SerialName("pane_id") val paneId: String, val focused: Boolean = false, val rect: Rect)

@Serializable
data class Layout(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val area: Rect,
    val panes: List<LayoutPane> = emptyList(),
    /** The split tree is not used by the monitor; kept raw so it can be shown or ignored without a model. */
    val splits: List<JsonElement> = emptyList(),
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val zoomed: Boolean = false,
)

/** `result.snapshot` of a `session_snapshot`. `version` and `protocol` are required: without them nothing else can be trusted. */
@Serializable
data class Snapshot(
    val version: String,
    val protocol: Int,
    val workspaces: List<Workspace> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val panes: List<Pane> = emptyList(),
    val layouts: List<Layout> = emptyList(),
    val agents: List<Agent> = emptyList(),
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
)

/** The `result` of `session_snapshot`: the snapshot sits one level down. */
@Serializable data class SnapshotResult(val snapshot: Snapshot)

@Serializable data class AgentInfoResult(val agent: Agent)
@Serializable data class AgentListResult(val agents: List<Agent> = emptyList())
@Serializable data class PaneInfoResult(val pane: Pane)
@Serializable data class TabListResult(val tabs: List<Tab> = emptyList())
@Serializable data class WorkspaceListResult(val workspaces: List<Workspace> = emptyList())

@Serializable
data class Capabilities(
    @SerialName("live_handoff") val liveHandoff: Boolean = false,
    @SerialName("detached_server_daemon") val detachedServerDaemon: Boolean = false,
    @SerialName("endpoint_protocol_generation") val endpointProtocolGeneration: Int = 0,
    @SerialName("surface_interest") val surfaceInterest: Boolean = false,
    @SerialName("health_check") val healthCheck: Boolean = false,
)

@Serializable data class Pong(val version: String, val protocol: Int, val capabilities: Capabilities = Capabilities())
