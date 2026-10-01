package io.github.tuthan.paddock.ledger

enum class ActivityFilter { All, StateChanges, Connection, PhoneActions }

sealed interface ActivityItem {
    val at: Long

    data class Observed(val observation: Observation) : ActivityItem { override val at get() = observation.at }
    data class Acted(val action: PhoneAction) : ActivityItem { override val at get() = action.at }

    /** Time the phone had no link to the host. [to] is null while the host is still disconnected. */
    data class Gap(val from: Long, val to: Long?, val host: String = "", val session: String = "") : ActivityItem { override val at get() = from }
}

/** The Activity screen's list, newest first. Only observations and phone actions appear, plus gaps for disconnected time. */
object Activity {
    private val stateKinds = setOf(ObservationKind.AgentAppeared, ObservationKind.StateChanged, ObservationKind.AgentGone)
    private val connectionKinds = setOf(ObservationKind.Connected, ObservationKind.Disconnected)

    fun build(observations: List<Observation>, actions: List<PhoneAction>, filter: ActivityFilter): List<ActivityItem> {
        val items = ArrayList<ActivityItem>()
        val kinds = when (filter) {
            ActivityFilter.All -> ObservationKind.entries.toSet()
            ActivityFilter.StateChanges -> stateKinds
            ActivityFilter.Connection -> connectionKinds
            ActivityFilter.PhoneActions -> emptySet()
        }
        observations.filter { it.kind in kinds }.mapTo(items) { ActivityItem.Observed(it) }
        if (filter == ActivityFilter.All || filter == ActivityFilter.Connection) items += gaps(observations)
        if (filter == ActivityFilter.All || filter == ActivityFilter.PhoneActions) actions.mapTo(items) { ActivityItem.Acted(it) }
        return items.sortedByDescending { it.at }
    }

    /** A gap opens at a Disconnected and closes at the next Connected for the same host and session. */
    fun gaps(observations: List<Observation>): List<ActivityItem.Gap> {
        val open = HashMap<Pair<String, String>, Long>()
        val out = ArrayList<ActivityItem.Gap>()
        for (o in observations.filter { it.kind in connectionKinds }.sortedBy { it.at }) {
            val k = o.host to o.session
            when (o.kind) {
                ObservationKind.Disconnected -> open.putIfAbsent(k, o.at)
                ObservationKind.Connected -> open.remove(k)?.let { out += ActivityItem.Gap(it, o.at, o.host, o.session) }
                else -> Unit
            }
        }
        open.forEach { (k, from) -> out += ActivityItem.Gap(from, null, k.first, k.second) }
        return out
    }
}
