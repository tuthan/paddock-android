package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId

/** Why an alert's agent cannot be shown. */
enum class Unobserved {
    /** The terminal is not an agent in the fresh read: closed, exited, or no longer detected as an agent. */
    Absent,
    /** The alert named another herdr session than the one this phone watches on that machine. */
    OtherSession,
    /** The alert named a machine this phone does not have. */
    UnknownMachine,
}

/** What an alert turns into once it is read against a fresh snapshot. Only the first two open an agent. */
sealed interface AlertOutcome {
    /** The terminal is an agent, still in the state the alert named. */
    data class Current(val terminalId: String, val state: AlertState) : AlertOutcome

    /** The terminal is an agent in another state now. The alert's state is not offered as an action. */
    data class Changed(val terminalId: String, val now: AgentStatus) : AlertOutcome

    /** Lands on the herd with the notice; never an answer surface. */
    data class NoLongerObserved(val why: Unobserved) : AlertOutcome
}

/** What a push for a whole machine found on a fresh read: how many agents are blocked, how many are done. */
data class MachineOutcome(val blocked: Int, val done: Int)

/**
 * The rule for an arriving hint (a tapped notification, a deep link): the app connects, reads, and resolves the target
 * against that fresh snapshot. The state in the link is what the sender saw; the read is what is true. The hint's pane id is
 * never used (pane ids can be renumbered; the terminal id is the identity), and its sequence and time are not consulted here.
 */
object AlertResolver {
    fun resolve(hint: AlertHint, host: HostProfileId, session: String, snapshot: Snapshot): AlertOutcome {
        if (hint.target.host != host) return AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine)
        if (hint.target.session != session) return AlertOutcome.NoLongerObserved(Unobserved.OtherSession)
        val agent = snapshot.agents.firstOrNull { it.terminalId == hint.target.terminalId }
            ?: return AlertOutcome.NoLongerObserved(Unobserved.Absent)
        val linked = when (hint.state) { AlertState.Blocked -> AgentStatus.Blocked; AlertState.Done -> AgentStatus.Done }
        return if (agent.agentStatus == linked) AlertOutcome.Current(agent.terminalId, hint.state) else AlertOutcome.Changed(agent.terminalId, agent.agentStatus)
    }

    /** A push that named no terminal is answered by what the fresh read shows on that machine, and opens nothing in particular. */
    fun resolveMachine(snapshot: Snapshot) = MachineOutcome(
        blocked = snapshot.agents.count { it.agentStatus == AgentStatus.Blocked },
        done = snapshot.agents.count { it.agentStatus == AgentStatus.Done },
    )
}

/** The words an alert's arrival is shown in. */
object AlertCopy {
    /** "just now", "12 min ago"; null when the sender's clock is too far ahead of this phone's for a number to mean anything. */
    fun age(nowMillis: Long, atSeconds: Long): String? {
        val delta = nowMillis - atSeconds * 1000
        return if (delta < -5 * 60_000L) null else AgeText.span(delta)
    }

    /** The notice shown with the screen the alert opened. [machine] is the phone's name for the host; the link's own text is never shown. */
    fun notice(outcome: AlertOutcome, hint: AlertHint, nowMillis: Long, machine: String): String {
        val sent = age(nowMillis, hint.atSeconds)?.let { " The alert was sent $it." }.orEmpty()
        return when (outcome) {
            is AlertOutcome.Current -> "Opened from an alert. This agent is still ${outcome.state.wire}.$sent"
            is AlertOutcome.Changed -> "State changed since the alert: this agent is ${nowWord(outcome.now)} now.$sent"
            is AlertOutcome.NoLongerObserved -> when (outcome.why) {
                Unobserved.Absent -> "No longer observed: that agent is not on $machine now.$sent"
                Unobserved.OtherSession -> "No longer observed: that alert named another herdr session on $machine, which this phone is not watching.$sent"
                Unobserved.UnknownMachine -> UNKNOWN_MACHINE + sent
            }
        }
    }

    /** The notice for a push: the counts from the fresh read, in words of our own. The push said nothing about any agent. */
    fun machineNotice(outcome: MachineOutcome, machine: String): String {
        fun agents(n: Int) = if (n == 1) "1 agent" else "$n agents"
        return when {
            outcome.blocked > 0 && outcome.done > 0 -> "Opened from an alert: ${agents(outcome.blocked)} ${if (outcome.blocked == 1) "needs" else "need"} you on $machine, and ${outcome.done} ${if (outcome.done == 1) "is" else "are"} done."
            outcome.blocked > 0 -> "Opened from an alert: ${agents(outcome.blocked)} ${if (outcome.blocked == 1) "needs" else "need"} you on $machine."
            outcome.done > 0 -> "Opened from an alert: ${agents(outcome.done)} ${if (outcome.done == 1) "is" else "are"} done on $machine."
            else -> "No longer observed: no agent on $machine needs you now."
        }
    }

    private fun nowWord(s: AgentStatus) = when (s) {
        AgentStatus.Blocked -> "blocked"; AgentStatus.Done -> "done"; AgentStatus.Working -> "working"; AgentStatus.Idle -> "ready"; AgentStatus.Unknown -> "unknown"
    }

    /** An alert for a machine this phone does not have. */
    const val UNKNOWN_MACHINE = "No longer observed: that alert named a machine this phone does not have."

    /** The notice for a link that did not parse. Nothing from the link is repeated. */
    const val INVALID_LINK = "That alert link is not valid, so it was ignored."

    /** Shown while the app connects and reads before it can say what the alert's agent is doing. */
    const val OPENING = "Opening the alert. Paddock is reading the herd first."

    /** The read did not come in time. The alert is not kept: the herd shown is whatever the connection has, and the user opens the agent from it. */
    fun unreachable(machine: String) = "Could not read the herd on $machine to check that alert. Open the agent from the herd once the connection is back."
}
