package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.ResultTone

/** What the decision sheet draws, as words. Pure: the screen renders it and decides nothing. */
data class DecisionModel(
    val kind: Kind,
    /** The whole id of the request on the sheet: what a tap on Yes or No is bound to. */
    val requestId: String? = null,
    val toolName: String? = null,
    /** The tool input in full ([ToolInputText]); when the hook had to cut it, the cut text, with [inputNote] saying so. */
    val inputText: String? = null,
    val inputNote: String? = null,
    val permissionMode: String? = null,
    /** The first characters of the request id, so the user can tell two requests apart; the whole id travels in the answer. */
    val requestShort: String? = null,
    val timeLeft: String? = null,
    val canAnswer: Boolean = false,
    /** The one reason the buttons are off, in a sentence. Null when they are on or when an answer has been given. */
    val whyNot: String? = null,
    /** How the phone's own answer ended, as it is. */
    val result: String? = null,
    val resultTone: ResultTone? = null,
    /** What became of the request on the host, from its files. */
    val status: String? = null,
    val statusTone: ResultTone? = null,
    val newerToolName: String? = null,
    val readError: String? = null,
    /** An answer is on its way: the buttons show it and ignore taps. */
    val sending: Boolean = false,
) {
    enum class Kind { Loading, NoRequest, Request }
    val offersNewer: Boolean get() = newerToolName != null
}

/**
 * What the Output tab's entry says: the newest pending request exists, for which tool, and how long it has. It describes the request
 * the hook is waiting on now, never the one on the sheet (which may be one already answered).
 */
data class DecisionEntryModel(val toolName: String?, val timeLeft: String?)

object DecisionPresenter {
    /** A request that has just appeared on the sheet cannot be answered for this long: a finger already on its way to something else must not land on it. */
    const val APPEAR_GUARD_MILLIS = 1_500L
    const val JUST_APPEARED = "This request just appeared. Yes and No are off for a moment, so a tap meant for something else cannot land on it."
    const val CAVEAT = "If the desktop answered a moment earlier, Claude Code kept the desktop's answer."

    fun model(view: AnswerView?, nowMillis: Long, gate: OperationGate, sending: Boolean, presenter: OperationPresenter = OperationPresenter()): DecisionModel {
        if (view == null || view.listing == null && view.error == null) return DecisionModel(DecisionModel.Kind.Loading)
        val shown = view.shown
        if (shown == null) {
            val why = (view.answerability as? Answerability.No)?.why?.sentence ?: NotAnswerable.NothingPending.sentence
            return DecisionModel(DecisionModel.Kind.NoRequest, whyNot = why, readError = view.error)
        }
        val listing = view.listing
        val outcome = view.outcome
        val left = listing?.let { AnswerRules.hostNow(it, nowMillis) }?.let { shown.expiresAt - it }
        val waiting = outcome == null || outcome is RequestOutcome.Waiting
        val answerable = view.answerability as? Answerability.Yes
        val resultLine = view.result?.takeIf { it.requestId == shown.requestId }?.let {
            presenter.line(if (it.behavior == Behavior.Allow) OperationKind.Allow else OperationKind.Deny, it.result)
        }
        val gateClosed = (gate as? OperationGate.Closed)?.sentence
        val justAppeared = view.shownSince?.let { nowMillis - it < APPEAR_GUARD_MILLIS } == true
        val why = when {
            !waiting -> null
            sending -> null
            gateClosed != null -> gateClosed
            justAppeared && view.result == null && answerable != null -> JUST_APPEARED
            resultLine != null && view.result?.result !is OperationResult.Acknowledged<*> -> null
            answerable == null -> (view.answerability as? Answerability.No)?.why?.sentence
            answerable.request.requestId != shown.requestId -> NotAnswerable.Replaced.sentence
            else -> null
        }
        val can = waiting && answerable != null && answerable.request.requestId == shown.requestId && gateClosed == null && !sending && view.result == null && !justAppeared
        return DecisionModel(
            kind = DecisionModel.Kind.Request,
            requestId = shown.requestId,
            toolName = shown.toolName,
            inputText = shown.toolInputText,
            inputNote = if (shown.truncated) "The input was cut at ${TRUNCATED_AT_KIB} KiB by the hook, so this is not all of it." else null,
            permissionMode = shown.permissionMode.ifEmpty { null },
            requestShort = shown.requestId.take(8),
            timeLeft = when {
                !waiting -> null
                left == null -> null
                left <= 0 -> "Expired"
                else -> "Answer within ${(left + 999) / 1000} s"
            },
            canAnswer = can,
            whyNot = why,
            result = resultLine?.text,
            resultTone = resultLine?.tone,
            // A replaced request is said once: by the offer of the newer one when there is one, by this sentence when there is not.
            status = status(outcome, view.workingObserved)?.takeUnless { outcome is RequestOutcome.Replaced && view.newer != null },
            statusTone = tone(outcome),
            newerToolName = view.newer?.toolName,
            readError = view.error,
            sending = sending,
        )
    }

    /**
     * The entry above the output of a blocked agent: non-null only while a pending request exists that has time left and whose hook is
     * alive. A request that is too large to show still counts (the sheet says why it cannot be answered); an expired one, one whose hook
     * ended and an unreadable one do not.
     */
    fun entry(view: AnswerView?, nowMillis: Long): DecisionEntryModel? {
        val listing = view?.listing ?: return null
        val a = AnswerRules.answerability(listing, nowMillis)
        val why = (a as? Answerability.No)?.why
        if (why == NotAnswerable.NothingPending || why == NotAnswerable.Expired || why == NotAnswerable.HookGone || why == NotAnswerable.Unreadable) return null
        val request = when (a) { is Answerability.Yes -> a.request; is Answerability.No -> a.request }
        val left = request?.let { it.expiresAt - AnswerRules.hostNow(listing, nowMillis) }
        return DecisionEntryModel(request?.toolName, left?.let { "Answer within ${(it + 999) / 1000} s" })
    }

    /** What reading the host's files after an unknown answer found, for the one request that answer was for. */
    fun settled(o: RequestOutcome): String = when (o) {
        RequestOutcome.Waiting -> "Read from the host's files: the request is still waiting, so the earlier answer never reached it. Nothing was applied."
        else -> "Read from the host's files: " + status(o, false).orEmpty()
    }

    private fun status(o: RequestOutcome?, working: Boolean): String? = when (o) {
        null, RequestOutcome.Waiting -> null
        is RequestOutcome.Sent -> "${label(o.behavior)} written. Waiting for the hook to take it."
        is RequestOutcome.Consumed -> "Handed to Claude Code" + (o.behavior?.let { " as ${label(it)}" } ?: "") + ". " + (if (working) "The agent moved on." else "Waiting for the agent to move on.") + " " + CAVEAT
        RequestOutcome.Expired -> "Expired. Answer on the desktop."
        RequestOutcome.Replaced -> NotAnswerable.Replaced.sentence
        RequestOutcome.Gone -> "This request is gone from the host. Its files are removed after an hour."
    }

    private fun tone(o: RequestOutcome?): ResultTone? = when (o) {
        null, RequestOutcome.Waiting -> null
        is RequestOutcome.Sent, is RequestOutcome.Consumed -> ResultTone.Ok
        RequestOutcome.Expired, RequestOutcome.Replaced, RequestOutcome.Gone -> ResultTone.Refused
    }

    private fun label(b: Behavior) = if (b == Behavior.Allow) "Yes" else "No"

    /** The hook's cap on the input it keeps, for the sentence above. */
    const val TRUNCATED_AT_KIB = 256
}
