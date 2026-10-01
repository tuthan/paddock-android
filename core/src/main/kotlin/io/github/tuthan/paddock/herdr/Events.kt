package io.github.tuthan.paddock.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Events are invalidations, not state (Phase 03): a lifecycle event says "something about this scope changed, read
 * again". Only the ids are kept; payloads such as the `pane` inside `pane_created` are deliberately not trusted.
 */
data class LifecycleEvent(val kind: String, val workspaceId: String?, val tabId: String?, val paneId: String?)

/** `pane.agent_status_changed`. The status is what the event says; the reconciler still confirms it with a read. */
@Serializable
data class StatusEvent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val title: String? = null,
    @SerialName("state_labels") val stateLabels: List<String>? = null,
)

/** What an event line turned into. [Ignored] carries the name so it can be counted. */
sealed interface EventOutcome {
    data class Lifecycle(val event: LifecycleEvent) : EventOutcome
    data class Status(val event: StatusEvent) : EventOutcome
    /** The server dropped events for this subscriber; the client must mark itself stale and reconcile. */
    data object EventsLost : EventOutcome
    data class Ignored(val name: String) : EventOutcome
}

/**
 * Two naming schemes coexist in 0.9.1 (Phase 00): lifecycle events arrive underscored (`pane_created`), status
 * events dotted (`pane.agent_status_changed`). Only names herdr's schema lists are accepted; the status event is
 * also accepted underscored because the schema lists it that way.
 */
object EventMapper {
    /** `EventKind` of protocol 22, minus the status kind which has its own model. */
    val LIFECYCLE_KINDS: Set<String> = setOf(
        "workspace_created", "workspace_updated", "workspace_metadata_updated", "workspace_closed", "workspace_renamed",
        "workspace_moved", "workspace_reordered", "workspace_focused", "worktree_created", "worktree_opened", "worktree_removed",
        "tab_created", "tab_closed", "tab_renamed", "tab_moved", "tab_focused",
        "pane_created", "pane_closed", "pane_updated", "pane_focused", "pane_moved", "pane_output_changed", "pane_exited",
        "pane_agent_detected", "layout_updated",
    )
    private val STATUS_NAMES = setOf("pane.agent_status_changed", "pane_agent_status_changed")
    /**
     * The mapping note says an `events_lost` closes the stream. Its wire form is not in the schema and no fixture
     * captured one, so it is accepted both as an event name and (see [isEventsLost]) as an error code. Unobserved
     * until a test can provoke it.
     */
    const val EVENTS_LOST = "events_lost"

    fun isEventsLost(failure: Message.Failure) = failure.code == EVENTS_LOST

    fun map(event: Message.Event): EventOutcome {
        val name = event.name
        return when {
            name == EVENTS_LOST -> EventOutcome.EventsLost
            name in STATUS_NAMES -> try {
                EventOutcome.Status(PaddockJson.decodeFromJsonElement(StatusEvent.serializer(), event.data))
            } catch (e: SerializationException) { throw ProtocolError.Decode(name, e) }
            name in LIFECYCLE_KINDS -> EventOutcome.Lifecycle(
                LifecycleEvent(
                    name,
                    event.data.str("workspace_id") ?: event.data.pane()?.str("workspace_id"),
                    event.data.str("tab_id") ?: event.data.pane()?.str("tab_id"),
                    event.data.str("pane_id") ?: event.data.pane()?.str("pane_id"),
                ),
            )
            else -> EventOutcome.Ignored(name)
        }
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    /** `pane_created` and `pane_updated` carry the pane object instead of bare ids. */
    private fun JsonObject.pane(): JsonObject? = get("pane") as? JsonObject
}
