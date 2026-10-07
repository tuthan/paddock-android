package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.herdr.AgentStatus

/**
 * Why a request cannot be answered from the phone. [code] is what the journal keeps when the answer is refused before anything is
 * sent, and [sentence] is the whole explanation the sheet shows next to the disabled buttons. Every one of them ends the same
 * way: the desktop dialog is still there, and so is the terminal.
 */
enum class NotAnswerable(val code: String, val sentence: String) {
    NothingPending("request_none", "No permission request from an agent's hook is waiting for this agent. Use the terminal."),
    Unreadable("request_unreadable", "The request could not be read in full, so it cannot be answered from here. Answer on the desktop."),
    TooLarge("request_too_large", "The input is too large to show in full, so it cannot be answered from here. Answer on the desktop."),
    Expired("request_expired", "This request has expired. Answer on the desktop."),
    HookGone("request_hook_gone", "The hook that asked has ended, so nothing would receive an answer. Answer on the desktop."),
    TooLate("request_too_late", "Too little time is left to answer this request safely. Answer on the desktop."),
    TwoSessions("request_ambiguous", "Two sessions of the agent share this pane, so Paddock cannot tell which is asking. Answer on the desktop."),
    Replaced("request_replaced", "A newer request replaced the one on this screen. Review the new one."),
    NotAPermission("request_not_permission", "This is a question for you, or a plan to approve, not a permission. A Yes or No cannot choose an answer to it. Answer on the desktop."),
}

sealed interface Answerability {
    /** [millisLeft] is the time the request has left by the host's clock once this call and the answer's own call are allowed for. */
    data class Yes(val request: PendingRequest, val millisLeft: Long) : Answerability
    data class No(val why: NotAnswerable, val request: PendingRequest?) : Answerability
}

/** How a request that was shown or answered stands now, read from the host's files and nothing else. */
sealed interface RequestOutcome {
    /** Still pending: nobody has answered. */
    data object Waiting : RequestOutcome
    /** The phone's decision is written; the hook has not taken it yet. */
    data class Sent(val behavior: Behavior) : RequestOutcome
    /**
     * The hook read the decision and printed it to the agent. That is the last thing the host can see: if the desktop answered a
     * moment earlier, the agent applied the desktop's answer and ignored this one, and no file says so (Codex has no desktop prompt
     * to answer meanwhile).
     */
    data class Consumed(val behavior: Behavior?) : RequestOutcome
    /** The window lapsed, the desktop answered first, or the hook ended without taking a decision. The desktop handles it. */
    data object Expired : RequestOutcome
    /** A newer request exists for the pane. */
    data object Replaced : RequestOutcome
    /** The request's files are gone (the host cleans them up after an hour, or the session ended). */
    data object Gone : RequestOutcome
}

/** The rules of Phase 08 that decide, from one listing, whether a Yes or No may go out and what happened to a request. All pure. */
object AnswerRules {
    /** Time kept in hand beyond the answer's own round trip. */
    const val MARGIN_MILLIS = 500L
    /** The hook waits this long for a claimed request's decision after its window lapses (see the hook's GRACE). */
    const val HOOK_GRACE_MILLIS = 2_000L
    /** A claimed request nobody took this long after its window plus the grace is treated as expired: the hook is gone. */
    const val CLAIM_STALE_MILLIS = 3_000L

    /**
     * Whether a screen watching an agent reads its request files at full pace ([AnswerController.POLL_MILLIS]) rather than the quiet one. A blocked agent,
     * and a Codex agent **whatever herdr says**: Codex shows no dialog of its own until its hooks return, herdr's own Codex integration owns the pane's
     * state, and the hook does not write to herdr, so while the hook waits for the phone the pane is never `blocked` and its status is not something to
     * rely on (a real machine showed Codex requests that were published and never offered).
     *
     * A screen reads the request files for **every** agent, at the quiet pace when this says no: herdr's status says an agent is waiting, not that the
     * wait is a permission (opencode and Claude Code are `blocked` for their questions too), and a request is worth showing the moment its file exists.
     * The files decide what is offered; the status only decides how often to look.
     */
    fun readsAtFullPace(status: AgentStatus?, kind: String?): Boolean = status == AgentStatus.Blocked || AgentNames.isCodex(kind)

    /**
     * Whether this phone can answer a permission prompt of the agent [kind] on this machine: guarded answers exist on the app side ([wired]: an operation journal
     * and the host scripts' client), Pro has not locked them ([locked]), and the agent is one of the three whose hook or plugin Paddock ships. When it can,
     * Review (a blocked row's button, a blocked agent's notification) opens the agent's Output tab, which carries the request entry, instead of its Terminal tab,
     * which only observes; the Terminal is one tap away. It does not know whether the scripts are installed on the host: without them the Output tab still
     * shows the agent's recent output, which includes the prompt.
     */
    fun canAnswerFromPhone(kind: String?, wired: Boolean, locked: Boolean): Boolean = wired && !locked && AgentNames.label(kind) != null

    /**
     * Tools Claude Code raises a PermissionRequest for that are not a Yes or No: a question with options, and the plan approval. A hook's allow
     * cannot pick an option, so a Yes would be consumed and change nothing (seen on a real machine). The shipped hook never publishes them; this keeps a
     * machine that still runs an older hook from offering a Yes that does nothing.
     */
    fun isQuestion(toolName: String?): Boolean = toolName == "AskUserQuestion" || toolName == "ExitPlanMode"

    /**
     * The host's clock now, as best the phone can tell: the host's reading when the script ran, plus the call it took to hear about it,
     * plus the time since. Only differences of the phone's own clock are used, so a phone whose clock is wrong judges deadlines the same.
     */
    fun hostNow(listing: RequestListing, nowMillis: Long): Long = listing.hostNowMillis + listing.roundTripMillis + (nowMillis - listing.receivedAtMillis).coerceAtLeast(0)

    /**
     * Whether the newest pending request may be answered: it was read completely, is not truncated, is not shared with another live
     * agent session, and has more time left than one more round trip plus [MARGIN_MILLIS]. Nothing from herdr takes part: the
     * request id the answer carries is the one in this very file.
     */
    fun answerability(listing: RequestListing, nowMillis: Long): Answerability {
        val candidate = listing.candidate ?: return Answerability.No(NotAnswerable.NothingPending, null)
        val request = candidate.request
            ?: return Answerability.No(if (candidate.complete) NotAnswerable.Unreadable else NotAnswerable.TooLarge, null)
        if (isQuestion(request.toolName)) return Answerability.No(NotAnswerable.NotAPermission, request)
        if (request.truncated) return Answerability.No(NotAnswerable.TooLarge, request)
        if (listing.entry(request.requestId)?.hookAlive == false) return Answerability.No(NotAnswerable.HookGone, request)
        val host = hostNow(listing, nowMillis)
        val others = listing.requests.filter { it.state == RequestState.Pending && it.requestId != request.requestId && (it.claudeSessionId ?: "") != request.claudeSessionId }
        if (others.any { (it.expiresAt ?: Long.MAX_VALUE) > host }) return Answerability.No(NotAnswerable.TwoSessions, request)
        val left = request.expiresAt - host
        return when {
            left <= 0 -> Answerability.No(NotAnswerable.Expired, request)
            left <= listing.roundTripMillis + MARGIN_MILLIS -> Answerability.No(NotAnswerable.TooLate, request)
            else -> Answerability.Yes(request, left)
        }
    }

    /**
     * How [requestId] stands in [listing]. A pending request that is not the candidate has been replaced; one the hook never settled
     * after its deadline is expired; a claimed one is Sent until the hook takes it, and expired when the hook plainly never will.
     */
    fun outcome(listing: RequestListing, requestId: String, nowMillis: Long): RequestOutcome {
        val e = listing.entry(requestId) ?: return RequestOutcome.Gone
        val host = hostNow(listing, nowMillis)
        return when (e.state) {
            RequestState.Consumed -> RequestOutcome.Consumed(e.decision)
            RequestState.Expired -> RequestOutcome.Expired
            RequestState.Claimed -> when {
                e.hookAlive == false -> RequestOutcome.Expired
                e.decision == null -> if (e.expiresAt != null && host > e.expiresAt + HOOK_GRACE_MILLIS + CLAIM_STALE_MILLIS) RequestOutcome.Expired else RequestOutcome.Waiting
                e.expiresAt != null && host > e.expiresAt + HOOK_GRACE_MILLIS + CLAIM_STALE_MILLIS -> RequestOutcome.Expired
                else -> RequestOutcome.Sent(e.decision)
            }
            RequestState.Pending -> when {
                e.hookAlive == false -> RequestOutcome.Expired
                e.expiresAt != null && host > e.expiresAt -> RequestOutcome.Expired
                listing.candidate != null && listing.candidate.requestId != requestId -> RequestOutcome.Replaced
                else -> RequestOutcome.Waiting
            }
        }
    }
}
