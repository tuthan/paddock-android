package io.github.tuthan.paddock.ledger

import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.SagaRecord

enum class ActivityFilter { All, StateChanges, Connection, PhoneActions }

sealed interface ActivityItem {
    val at: Long

    data class Observed(val observation: Observation) : ActivityItem { override val at get() = observation.at }
    data class Acted(val action: PhoneAction) : ActivityItem { override val at get() = action.at }

    /** Something this phone asked a host to do (a prompt, a key, desktop focus), from the operation journal. [at] is when the user asked. */
    data class Operation(val record: OperationRecord) : ActivityItem { override val at get() = record.requestedAt }

    /** A start-agent saga (Phase 09), with every id it created; one row for the whole saga, beside the row of each step's operation. [at] is when it began. */
    data class Saga(val record: SagaRecord) : ActivityItem { override val at get() = record.startedAt }

    /** Time the phone had no link to the host. [to] is null while the host is still disconnected. */
    data class Gap(val from: Long, val to: Long?, val host: String = "", val session: String = "") : ActivityItem { override val at get() = from }
}

/** The Activity screen's list, newest first. Only observations, phone actions and journal rows appear, plus gaps for disconnected time. */
object Activity {
    private val stateKinds = setOf(ObservationKind.AgentAppeared, ObservationKind.StateChanged, ObservationKind.AgentGone)
    private val connectionKinds = setOf(ObservationKind.Connected, ObservationKind.Disconnected)

    fun build(observations: List<Observation>, actions: List<PhoneAction>, filter: ActivityFilter, operations: List<OperationRecord> = emptyList(), sagas: List<SagaRecord> = emptyList()): List<ActivityItem> {
        val items = ArrayList<ActivityItem>()
        val kinds = when (filter) {
            ActivityFilter.All -> ObservationKind.entries.toSet()
            ActivityFilter.StateChanges -> stateKinds
            ActivityFilter.Connection -> connectionKinds
            ActivityFilter.PhoneActions -> emptySet()
        }
        observations.filter { it.kind in kinds }.mapTo(items) { ActivityItem.Observed(it) }
        if (filter == ActivityFilter.All || filter == ActivityFilter.Connection) items += gaps(observations)
        if (filter == ActivityFilter.All || filter == ActivityFilter.PhoneActions) {
            actions.mapTo(items) { ActivityItem.Acted(it) }
            operations.mapTo(items) { ActivityItem.Operation(it) }
            sagas.mapTo(items) { ActivityItem.Saga(it) }
        }
        return items.sortedByDescending { it.at }
    }

    /**
     * A gap opens at a Disconnected and closes at the next Connected for the same host and session. A Connected that
     * follows another Connected with no Disconnected between means the app stopped without recording one (the process
     * ended): that gap runs from the last thing the phone recorded for that host and session.
     */
    fun gaps(observations: List<Observation>): List<ActivityItem.Gap> {
        val open = HashMap<Pair<String, String>, Long>()
        val watching = HashSet<Pair<String, String>>()
        val lastAt = HashMap<Pair<String, String>, Long>()
        val out = ArrayList<ActivityItem.Gap>()
        for (o in observations.sortedWith(compareBy<Observation> { it.at }.thenBy { it.id })) {
            val k = o.host to o.session
            when (o.kind) {
                ObservationKind.Disconnected -> { open.putIfAbsent(k, o.at); watching -= k }
                ObservationKind.Connected -> {
                    val from = open.remove(k) ?: lastAt[k]?.takeIf { k in watching }
                    if (from != null) out += ActivityItem.Gap(from, o.at, o.host, o.session)
                    watching += k
                }
                else -> Unit
            }
            lastAt[k] = o.at
        }
        open.forEach { (k, from) -> out += ActivityItem.Gap(from, null, k.first, k.second) }
        return out
    }
}
