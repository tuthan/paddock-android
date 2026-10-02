package io.github.tuthan.paddock.ledger

import kotlinx.serialization.Serializable

/** What the phone saw. Host-level kinds ([Connected], [Disconnected]) carry no terminal id. */
enum class ObservationKind { AgentAppeared, StateChanged, AgentGone, Connected, Disconnected }

/**
 * A fact the phone observed, stored under the identity and epoch it was observed in. [detail] is metadata only
 * ("idle -> blocked"); prompt and output text never enter the ledger and [detail] is bounded to [Ledger.MAX_DETAIL].
 */
@Serializable
data class Observation(
    val id: Long,
    val host: String,
    val session: String,
    val terminalId: String? = null,
    val epoch: Long,
    val kind: ObservationKind,
    val at: Long,
    val detail: String = "",
)

/**
 * The user acknowledged a Done at this `state_change_seq`. Scoped to the epoch it was acknowledged in; a new epoch
 * inherits it only when its first read still shows that terminal Done at the same seq ([Ledger.onInstalled]).
 */
@Serializable
data class SeenEntry(
    val host: String,
    val session: String,
    val terminalId: String,
    val epoch: Long,
    val stateChangeSeq: Long,
    val at: Long,
)

enum class ActionKind { MarkSeen }

/**
 * How a ledger action ended. A mark-as-seen is local and finishes at once, so only [Ok] is written today. Sends to a
 * host (prompt, keys, focus) are not ledger actions: they live in the operation journal, whose Unknown outcome is shown
 * in Activity as an [ActivityItem.Operation].
 */
enum class ActionOutcome { Ok, Failed, Unknown }

@Serializable
data class PhoneAction(
    val id: Long,
    val host: String,
    val session: String,
    val terminalId: String,
    val epoch: Long,
    val kind: ActionKind,
    val at: Long,
    val outcome: ActionOutcome,
)

/** Epoch bookkeeping for one host and session: the last epoch handed out and the epoch of the last installed read. */
@Serializable
data class EpochMark(val host: String, val session: String, val allocated: Long, val installed: Long? = null)

@Serializable
data class LedgerData(
    val nextId: Long = 1,
    val observations: List<Observation> = emptyList(),
    val seen: List<SeenEntry> = emptyList(),
    val actions: List<PhoneAction> = emptyList(),
    val epochs: List<EpochMark> = emptyList(),
)

/** Observations 30 days or 5,000 rows, whichever first. Rows are in insertion order, so the newest are kept. */
object Retention {
    const val MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    const val MAX_ROWS: Int = 5_000

    fun <T> prune(rows: List<T>, now: Long, at: (T) -> Long): List<T> {
        val fresh = rows.filter { now - at(it) <= MAX_AGE_MILLIS }
        return if (fresh.size > MAX_ROWS) fresh.takeLast(MAX_ROWS) else fresh
    }
}
