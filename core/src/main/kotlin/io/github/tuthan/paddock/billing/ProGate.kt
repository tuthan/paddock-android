package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.ops.OperationRecord

/**
 * What the user is in the middle of when they choose a capability. A Pro gate is an interruption, so it appears only
 * from [IDLE]: never over a pending answer, a manual-input session or an operation that has not finished.
 */
enum class GateContext { IDLE, PENDING_ANSWER, MANUAL_INPUT, OPERATION_IN_FLIGHT }

enum class GateDecision {
    /** Free capability, or Pro is held: run it. */
    PROCEED,

    /** A gated capability without Pro, chosen from [GateContext.IDLE]: show the gate, with the Free path beside it. */
    SHOW_GATE,

    /** A gated capability without Pro, chosen while the user is busy: show nothing now; the control carries its Pro label and does not run. */
    DEFER,
}

/**
 * A capability Pro unlocks. [id] is what the gate is asked about, [label] the words the gate sheet and Settings use for it (a noun phrase that
 * ends "is a Pro capability"), and [built] whether anything in the app asks the gate for it yet: a reserved id is gated but never named to the
 * user as something Pro covers, because nothing exists to cover.
 */
data class ProCapability(val id: String, val label: String, val built: Boolean = true)

/**
 * Vault decision M8, taken by the user on 2026-10-04: bundle C (guarded answers, operations, widgets) plus several hosts. Free keeps
 * everything that watches (agent status, readable output, manual input, snippets, alerts, the machine picker and adding a machine) and the
 * safety and recovery paths; Pro is the phone-side work that mutates or extends it. Counts never appear here: only what the user chooses.
 */
object ProCapabilities {
    /** Setting up native Yes/No answers (Settings, the hook install) and the answer entry above the output. Never the Yes/No of a request already on screen. */
    val GUARDED_ANSWERS = ProCapability("answers.guarded", "Answering permission requests with Yes and No")

    /** The start-agent form and its saga, with an optional worktree. */
    val START_AGENT = ProCapability("operations.start", "Starting an agent")

    /** Stop and delete a herdr session. */
    val MANAGE_SESSIONS = ProCapability("operations.manage", "Stopping and deleting sessions")

    /** The three home-screen widgets: a locked widget says so and draws no count. */
    val WIDGETS = ProCapability("widgets", "Home-screen widgets")

    /** One attention queue across several machines. Reserved: nothing merges machines yet, and connecting a second machine stays free (vault Free/Pro rules). */
    val HOSTS_MERGED = ProCapability("hosts.merged", "Watching several machines in one list", built = false)

    val ALL: List<ProCapability> = listOf(GUARDED_ANSWERS, START_AGENT, MANAGE_SESSIONS, WIDGETS, HOSTS_MERGED)

    fun byId(id: String): ProCapability? = ALL.firstOrNull { it.id == id }
}

object ProGate {
    /**
     * The capability ids the Play build gates, and the foss build when it is not a source build with every capability on (M4). The one
     * place that turns a capability into a Pro one is [ProCapabilities.ALL] (M8, 2026-10-04).
     */
    val GATED: Set<String> = ProCapabilities.ALL.map { it.id }.toSet()

    /** Counts are never a boundary: this takes a capability id and the situation, nothing about how many sessions, workspaces or agents exist. */
    fun decide(capabilityId: String, hasPro: Boolean, context: GateContext, gated: Set<String> = GATED): GateDecision = when {
        !locked(capabilityId, hasPro, gated) -> GateDecision.PROCEED
        context == GateContext.IDLE -> GateDecision.SHOW_GATE
        else -> GateDecision.DEFER
    }

    /** Whether a control for [capabilityId] carries its Pro label and does not run: it is gated and Pro is not held. */
    fun locked(capabilityId: String, hasPro: Boolean, gated: Set<String> = GATED): Boolean = capabilityId in gated && !hasPro

    /**
     * How long a journal row may stay Requested or Sent and still count as an operation in flight for the gate. Every call a row stands for ends by
     * itself inside a bound: the longest are a worktree create (60 s on the relay) and starting an agent (herdr's 30 s wait plus 20 s of slack on the
     * exec), each after at most one short read, and a failure turns the row Unknown in the same breath (`Operation.run`). A row still Requested or Sent
     * after two minutes is one whose call never came back (a coroutine that leaked, a write that never returned), and nothing changes it before the next
     * start, where `OperationJournal.recover` makes it Unknown. It must not hold the gate shut for the life of the process: every locked control would be a
     * dead tap with no way to buy Pro (review F6). Only the gate stops counting it; the journal is untouched, so the composer and Spaces still treat the row
     * as running until it is settled (the follow-up is in docs/billing.md, "What is not done").
     */
    const val OPERATION_STUCK_AFTER_MILLIS: Long = 2 * 60 * 1000L

    /**
     * Whether a row of [records] is an operation that can still come back: Requested or Sent, and requested no more than [stuckAfterMillis] before
     * [nowMillis]. Any terminal, session or saga counts, as before. A clock that moved back makes a row look young, so the gate waits, the safe side; one that
     * jumped forward can make a running row look stuck for a moment; the journal has only the phone's wall clock to go by.
     */
    fun operationInFlight(records: List<OperationRecord>, nowMillis: Long, stuckAfterMillis: Long = OPERATION_STUCK_AFTER_MILLIS): Boolean =
        records.any { it.inFlight && nowMillis - it.requestedAt <= stuckAfterMillis }

    /**
     * What the user is in the middle of, from the facts only the app holds: the screen says a pending answer is up, [manualInputOpen] is the Manual input
     * mode, and [records] with [nowMillis] say whether an operation can still come back ([operationInFlight]). In that order, so the sentence names what
     * the user can see first.
     */
    fun context(pendingAnswerOnScreen: Boolean, manualInputOpen: Boolean, records: List<OperationRecord>, nowMillis: Long): GateContext = when {
        pendingAnswerOnScreen -> GateContext.PENDING_ANSWER
        manualInputOpen -> GateContext.MANUAL_INPUT
        operationInFlight(records, nowMillis) -> GateContext.OPERATION_IN_FLIGHT
        else -> GateContext.IDLE
    }

    /**
     * The one sentence a [GateDecision.DEFER] shows in place of the sheet, so a tap that does nothing still says why and when Pro is offered again. Null for
     * [GateContext.IDLE], where nothing is deferred.
     */
    fun deferNotice(context: GateContext): String? = when (context) {
        GateContext.IDLE -> null
        GateContext.PENDING_ANSWER -> "Pro is offered once the request on screen is answered."
        GateContext.MANUAL_INPUT -> "Pro is offered once Manual input is closed."
        GateContext.OPERATION_IN_FLIGHT -> "Pro is offered once the operation in progress finishes."
    }
}
