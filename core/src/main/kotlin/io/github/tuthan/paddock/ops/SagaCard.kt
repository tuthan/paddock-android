package io.github.tuthan.paddock.ops

/** What a recovery card can offer. The screen draws one control per action; none of them runs without the user's tap. */
sealed interface CardAction {
    /** Look at the new pane (its terminal tab), when the pane is still in the herd. */
    data class OpenPane(val paneId: String, val terminalId: String?) : CardAction
    /** `agent_name_taken`: a new name, then one new `agent start` in the pane that is still there. */
    data object ChooseAnotherName : CardAction
    /** herdr wants the repository trusted before it creates a worktree; asks first, naming the repository. */
    data class TrustRepository(val repository: String?) : CardAction
    /** Nothing was created and the executable was not found by the SSH PATH; starting anyway is the user's call. */
    data object StartAnyway : CardAction
    /** Close the workspace, tab or pane the saga created: a confirmed operation of its own. [label] is the button's text. */
    data class CloseCreated(val label: String, val what: CloseTarget) : CardAction
    /** Leave what exists as it is, or dismiss a card for a saga that created nothing. */
    data class Leave(val label: String) : CardAction
}

data class SagaCardModel(
    val sagaId: String,
    val title: String,
    /** The sentences: what stopped it, herdr's own text, what exists, what stays on the host. */
    val lines: List<String>,
    /** Every id the saga created, as label and value, so none has to be read out of a sentence. */
    val ids: List<Pair<String, String>>,
    val actions: List<CardAction>,
)

object SagaCopy {
    fun blocked(r: OperationResult<*>): String = when (r) {
        is OperationResult.Busy -> "Another operation on this saga is still running."
        is OperationResult.NeedsReread -> "An earlier step of this saga has an unknown outcome."
        is OperationResult.Stale -> "This screen is out of date. Nothing was sent."
        is OperationResult.JournalFailed -> "Nothing was sent: the operation record could not be written on this phone."
        is OperationResult.JournalUnreadable -> "Nothing was sent: the record of earlier operations on this phone cannot be read. Retry or reset it in Settings."
        else -> "Nothing was sent."
    }

    fun trustTitle(repository: String?) = "Trust ${repository ?: "this repository"}?"
    fun trustBody(repository: String?) =
        "herdr asked for the repository ${repository?.let { "\"$it\" " }.orEmpty()}to be trusted before it creates the worktree. Trusting it lets Git run that repository's hooks and configuration on the host for this one request. Only trust a repository you know."
    const val TRUST_CONFIRM = "Trust and create"

    fun closeTitle(label: String) = label
    fun closeBody(what: CloseTarget, hostName: String) = when (what) {
        is CloseTarget.Workspace -> "On $hostName. herdr closes the new workspace and the pane in it. The worktree folder and its branch stay on the host."
        is CloseTarget.Tab -> "On $hostName. herdr closes the new tab and the pane in it."
        is CloseTarget.Pane -> "On $hostName. herdr closes the new pane."
    }
    const val CLOSE_CONFIRM = "Close it"

    const val STARTED = "Started."
    fun started(name: String, kind: String) = "$name ($kind) is running."
}

/** The recovery card for a failed saga, from its record alone: pure, so each failure's card can be pinned by a test. */
object SagaCard {
    private val TRUST_WORDS = listOf("trust", "dubious", "safe.directory", "ownership")

    fun of(rec: SagaRecord, hostName: String = "the host"): SagaCardModel {
        val ids = buildList {
            rec.createdWorkspaceId?.let { add("Workspace" to it) }
            rec.createdTabId?.let { add("Tab" to it) }
            rec.createdPaneId?.let { add("Pane" to it) }
            rec.worktreePath?.let { add("Worktree" to it) }
        }
        val lines = buildList {
            add(rec.message)
            if (rec.createdSomething) add("Created on $hostName: ${ids.joinToString(" · ") { "${it.first.lowercase()} ${it.second}" }}.")
            else add("Nothing was created on $hostName.")
            if (rec.worktreePath != null) add("The worktree folder and its branch stay on the host even if the workspace is closed.")
            if (rec.failure == SagaFailure.NAME_TAKEN) add("Nothing in the new pane was changed; the agent is simply not started.")
        }
        val actions = buildList {
            when (rec.failure) {
                SagaFailure.EXECUTABLE_MISSING -> add(CardAction.StartAnyway)
                SagaFailure.PLACE_REFUSED -> if (TRUST_WORDS.any { it in (rec.message.lowercase()) } && rec.branch != null) add(CardAction.TrustRepository(rec.repository))
                SagaFailure.NAME_TAKEN -> add(CardAction.ChooseAnotherName)
            }
            rec.createdPaneId?.let { add(CardAction.OpenPane(it, rec.createdTerminalId)) }
            val close = rec.createdWorkspaceId?.takeIf { rec.branch != null }?.let { CloseTarget.Workspace(it) } ?: rec.createdTabId?.let { CloseTarget.Tab(it) } ?: rec.createdPaneId?.let { CloseTarget.Pane(it) }
            close?.let { add(CardAction.CloseCreated(when (it) { is CloseTarget.Workspace -> "Close the new workspace"; is CloseTarget.Tab -> "Close the new tab"; is CloseTarget.Pane -> "Close the new pane" }, it)) }
            add(CardAction.Leave(if (rec.createdSomething) "Leave it" else "Dismiss"))
        }
        return SagaCardModel(rec.id, "Starting ${rec.agentName} (${rec.kind}) stopped: ${rec.step.label}", lines, ids, actions)
    }
}
