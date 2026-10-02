package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus

/**
 * Why a prompt may not go out. [sentence] is the whole explanation the composer shows next to the disabled Send, and
 * [code] is what the journal keeps when the send is refused here. The codes are the phone's own and are distinct from
 * herdr's error codes: `not_ready_blocked` is this check, `agent_blocked` is herdr's refusal.
 */
enum class NotReadyReason(val code: String, val sentence: String) {
    StaleRead("not_ready_stale", "The agent's state was read more than 2 seconds ago."),
    Working("not_ready_working", "The agent is working; a prompt would land in the middle of its turn."),
    Blocked("not_ready_blocked", "The agent is blocked and needs an answer. Use the terminal."),
    StatusUnknown("not_ready_status_unknown", "herdr cannot tell what this agent is doing."),
    NotInteractive("not_ready_not_interactive", "herdr says the agent is not accepting input yet."),
    LaunchPending("not_ready_launch_pending", "The agent is still starting."),
    HintsUnreported("not_ready_hints_unreported", "herdr did not report whether the agent accepts input."),
}

sealed interface Readiness {
    /** [hintsUnreported]: herdr sent neither `interactive_ready` nor `launch_pending`, so only the status backs this. */
    data class Ready(val hintsUnreported: Boolean) : Readiness

    /** Every failing condition, the most basic first; [primary] is the one to name. */
    data class NotReady(val reasons: List<NotReadyReason>) : Readiness { val primary get() = reasons.first() }
}

/** How old a read may be when a prompt is sent. */
const val READINESS_FRESH_MILLIS = 2_000L

/**
 * The one rule for typing a prompt at an agent: the record was read within the last 2 s ([readAtMillis] is when that
 * read started), `agentStatus` is `idle` or `done`, `interactiveReady` is not false and `launchPending` is not true.
 *
 * Both hints are optional in herdr 0.9.1's schema and herdr omits them for every agent seen so far, real Claude Code
 * and Codex panes included. Absence therefore does not fail the check (it is reported on [Readiness.Ready] so the
 * screen can say what backs the send), the same reading as Phase 04's Ready row. An explicit `false` or `true` fails.
 * [requireHints] is the strict reading, which needs `interactiveReady == true` and `launchPending == false`: with it,
 * an agent that does not report them is never ready.
 */
fun isReady(agent: Agent, readAtMillis: Long, nowMillis: Long, requireHints: Boolean = false): Readiness {
    val why = ArrayList<NotReadyReason>(3)
    if (nowMillis - readAtMillis > READINESS_FRESH_MILLIS) why += NotReadyReason.StaleRead
    when (agent.agentStatus) {
        AgentStatus.Idle, AgentStatus.Done -> Unit
        AgentStatus.Working -> why += NotReadyReason.Working
        AgentStatus.Blocked -> why += NotReadyReason.Blocked
        AgentStatus.Unknown -> why += NotReadyReason.StatusUnknown
    }
    if (agent.interactiveReady == false) why += NotReadyReason.NotInteractive
    if (agent.launchPending == true) why += NotReadyReason.LaunchPending
    val unreported = agent.interactiveReady == null && agent.launchPending == null
    if (requireHints && (agent.interactiveReady == null || agent.launchPending == null)) why += NotReadyReason.HintsUnreported
    return if (why.isEmpty()) Readiness.Ready(hintsUnreported = unreported) else Readiness.NotReady(why)
}
