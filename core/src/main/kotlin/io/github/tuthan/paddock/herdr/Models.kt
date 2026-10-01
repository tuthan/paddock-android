package io.github.tuthan.paddock.herdr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/** `done` is detection-only (a report accepts only the other four); unrecognised values become [Unknown] so a newer herdr cannot crash an older phone. */
@Serializable(with = AgentStatusSerializer::class)
enum class AgentStatus(val wire: String) {
    Idle("idle"), Working("working"), Blocked("blocked"), Done("done"), Unknown("unknown");

    companion object { fun fromWire(s: String?): AgentStatus = entries.firstOrNull { it.wire == s } ?: Unknown }
}

object AgentStatusSerializer : KSerializer<AgentStatus> {
    override val descriptor = PrimitiveSerialDescriptor("AgentStatus", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = AgentStatus.fromWire(decoder.decodeString())
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
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val revision: Long = 0,
    val scroll: Scroll? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
)

/**
 * An agent is a pane with a detected agent. Fields herdr 0.9.1 does not send yet (`message`, `agent_session`,
 * `interactive_ready`, `launch_pending`, `display_agent`, `state_labels`) are optional here and stay null; reported
 * message text is absent in 0.9.1 (Phase 00 finding), so the UI never depends on it.
 */
@Serializable
data class Agent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    /** The agent's name as herdr reports it, for example `claude`. */
    val agent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val revision: Long = 0,
    @SerialName("state_change_seq") val stateChangeSeq: Long? = null,
    val focused: Boolean = false,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("agent_session") val agentSession: String? = null,
    @SerialName("interactive_ready") val interactiveReady: Boolean? = null,
    @SerialName("launch_pending") val launchPending: Boolean? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("state_labels") val stateLabels: List<String>? = null,
    val message: String? = null,
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
