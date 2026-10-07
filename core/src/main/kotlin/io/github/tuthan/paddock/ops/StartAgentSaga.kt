package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ports.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a create call returned: every id herdr gave, so each is persisted before the next step starts. */
data class Created(
    val workspaceId: String?,
    val tabId: String,
    val paneId: String,
    val terminalId: String,
    /** The root pane's working directory at creation: the worktree path for a worktree, the workspace's folder for a tab. */
    val cwd: String?,
    val worktreePath: String? = null,
    val repository: String? = null,
)

/** What a read-only look at the new pane shows. [atShellPrompt] means the foreground is the shell itself and nothing else. */
data class PaneFacts(
    val exists: Boolean,
    val terminalId: String? = null,
    /** Where the pane is now. A pane keeps its id when it is moved to another tab, so the tab and workspace are what show a move. */
    val tabId: String? = null,
    val workspaceId: String? = null,
    val revision: Long = 0,
    val cwd: String? = null,
    val hasAgent: Boolean = false,
    val atShellPrompt: Boolean = false,
)

data class StartedAgent(val terminalId: String, val paneId: String)

/**
 * Everything the saga needs from herdr and the host, as a port: the app wires it to the relay and SSH, the tests script it and
 * inject a failure at any step. Each mutating member must call [before] once, immediately before the request's first byte, the
 * way a relay call's `beforeWrite` does; that is what lets the journal tell "never sent" from "may have been".
 */
interface SagaHost {
    /** True when any of [candidates] is an executable the host can find. A throw means the host could not be asked. */
    suspend fun executableAvailable(candidates: List<String>): Boolean
    suspend fun createWorktree(parentWorkspaceId: String, branch: String, trust: Boolean, before: suspend () -> Unit): Created
    /** A new tab in [workspaceId]; with [cwd] (a folder [resolveFolder] returned) its shell starts there, without it in herdr's own default folder. */
    suspend fun createTab(workspaceId: String, cwd: String?, before: suspend () -> Unit): Created
    /**
     * The real, absolute path of [path] on the host when it is a folder there (`~` and symlinks resolved), null when it is not one. Read-only. herdr
     * itself never refuses a folder: a missing one, a file and a relative path are all quietly replaced by its own default, so this is asked first.
     */
    suspend fun resolveFolder(path: String): String?
    /** Read-only. Never journaled. */
    suspend fun inspectPane(paneId: String): PaneFacts
    suspend fun startAgent(name: String, kind: String, paneId: String, timeoutMs: Int, before: suspend () -> Unit): StartedAgent
    suspend fun closeWorkspace(workspaceId: String, before: suspend () -> Unit)
    suspend fun closeTab(tabId: String, before: suspend () -> Unit)
    suspend fun closePane(paneId: String, before: suspend () -> Unit)
}

/** The Phase 06 prompt path, once the new agent shows in the installed read. [Held] says why nothing was sent, in a sentence. */
fun interface SagaPrompter {
    suspend fun promptWhenReady(terminalId: String, text: String): PromptStep
}

sealed interface PromptStep {
    data object Sent : PromptStep
    data class Held(val sentence: String) : PromptStep
}

/** Why a saga stopped. The code is stored; [SagaCard] turns it into the card's words. */
object SagaFailure {
    const val SAGA_UNWRITABLE = "saga_unwritable"
    const val HOST_UNREACHABLE = "host_unreachable"
    const val EXECUTABLE_MISSING = "executable_missing"
    const val PLACE_REFUSED = "place_refused"
    const val PLACE_NOT_SENT = "place_not_sent"
    const val PLACE_UNKNOWN = "place_unknown"
    const val PLACE_BLOCKED = "place_blocked"
    const val IDS_NOT_SAVED = "ids_not_saved"
    const val PANE_MOVED = "pane_moved"
    const val PANE_NOT_READY = "pane_not_ready"
    const val PANE_BUSY = "pane_busy"
    const val WRONG_CWD = "wrong_cwd"
    const val FOLDER_MISSING = "folder_missing"
    const val NAME_TAKEN = "name_taken"
    const val START_REFUSED = "start_refused"
    const val START_NOT_SENT = "start_not_sent"
    const val START_UNKNOWN = "start_unknown"
    const val START_MISDELIVERED = "start_misdelivered"
    const val APP_ENDED = "app_ended"
}

/** What the user asked for. [firstPrompt] lives in memory only. */
data class SagaRequest(
    val host: HostProfileId,
    val session: String,
    val agentName: String,
    val kind: String,
    val workspaceId: String,
    val branch: String? = null,
    val trustRepository: Boolean = false,
    val firstPrompt: String? = null,
    /** The user chose to start although the executable was not found (a kind whose executable is spelled differently, a shell-only PATH). */
    val skipAvailabilityCheck: Boolean = false,
    /** For the trust dialog and the card: the repository's name, when the caller knows it. */
    val repository: String? = null,
    /** The folder the agent's shell starts in, as the user typed it (`~/api`, `/srv/app`); null is the workspace's own. Never with a [branch]: a worktree has a folder of its own. */
    val folder: String? = null,
)

/** The rules a request has to meet before anything is created; the form shows the first problem, [StartAgentSaga] refuses a request that has one. */
object SagaRules {
    /** The 24 kinds `herdr agent start --kind` accepts on 0.9.1, in herdr's order (`fixtures/herdr-0.9.1/agent-start-kinds.txt` pins the same list). */
    val KINDS = listOf(
        "pi", "claude", "codex", "gemini", "cursor", "devin", "agy", "cline", "omp", "mastracode", "opencode", "copilot",
        "kimi", "kiro", "droid", "amp", "grok", "hermes", "kilo", "qodercli", "qwen", "letta", "maki", "muse",
    )
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")
    /** A branch name that is safe in a JSON field and in git: no space, `..`, `@{`, control or glob characters, no leading `-`, `/` or `.`. */
    val BRANCH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}")
    const val TIMEOUT_MS = 30_000

    fun nameProblem(name: String): String? = if (NAME.matches(name)) null else "A name is 1 to 40 letters, digits, dots, dashes or underscores, starting with a letter or digit."
    fun branchProblem(branch: String): String? = when {
        !BRANCH.matches(branch) -> "A branch name is letters, digits, dots, dashes, underscores and slashes, starting with a letter or digit."
        ".." in branch || "//" in branch || branch.endsWith("/") || branch.endsWith(".") || branch.endsWith(".lock") || "/." in branch -> "That is not a branch name git accepts."
        else -> null
    }

    const val FOLDER_MAX = 1024
    /** A path the host can be asked about: absolute or `~`-relative, no control character. Whether it exists is the host's answer, not a rule. */
    fun folderProblem(path: String): String? = when {
        path.isBlank() -> "Type the folder's full path."
        path.length > FOLDER_MAX || path.any { it.isISOControl() } -> "That is not a folder path."
        !(path == "~" || path.startsWith("/") || path.startsWith("~/")) -> "Type the full path, starting with / or ~/ (the home folder)."
        else -> null
    }

    fun problem(r: SagaRequest): String? =
        nameProblem(r.agentName)
            ?: (if (r.kind !in KINDS) "herdr cannot start \"${r.kind.take(40)}\"." else null)
            ?: r.branch?.let { branchProblem(it) }
            ?: r.folder?.let { folderProblem(it) }
            ?: (if (r.folder != null && r.branch != null) "A worktree has a folder of its own: choose a folder or a worktree, not both." else null)
            ?: (if (r.workspaceId.isBlank()) "Choose the workspace to start in." else null)

    /** The executables to look for: the kind's own name, plus the spellings its vendor's CLI is known by. */
    fun executables(kind: String): List<String> = when (kind) {
        "cursor" -> listOf("cursor", "cursor-agent")
        "kiro" -> listOf("kiro", "kiro-cli")
        else -> listOf(kind)
    }
}

/**
 * Starting an agent as a saga, not a transaction: availability, then a place (a worktree workspace, or a tab in the chosen
 * workspace), then a check of the new pane, then `agent start` in the pane that came back, then an optional first prompt. Every
 * herdr call that changes something is a journaled [Operation] with its returned ids written to [store] before the next step begins.
 *
 * Nothing is ever replayed. A failure leaves the ids it created in the record and a recovery card offering to open the pane, to
 * close what was created (each a confirmed operation of its own) or to leave it. The only second attempt is the user's, with a new
 * name, after herdr said the name was taken: the place is still there and was verified again first.
 */
class StartAgentSaga(
    private val host: SagaHost,
    private val prompter: SagaPrompter?,
    private val journal: OperationJournal,
    private val store: SagaStore,
    private val clock: Clock,
    private val epoch: () -> Long = { 0L },
    private val verifyWindowMillis: Long = 10_000,
    private val pollMillis: Long = 500,
    private val startTimeoutMs: Int = SagaRules.TIMEOUT_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var counter = 0
    private val prompts = HashMap<String, String>()
    private val requests = HashMap<String, SagaRequest>()
    private val _sagas = MutableStateFlow(recovered(store.load().records))

    /** Every saga on this phone, oldest first. A [SagaRecord.needsRecovery] one has a card. */
    val sagas: StateFlow<List<SagaRecord>> = _sagas.asStateFlow()

    fun get(id: String): SagaRecord? = _sagas.value.firstOrNull { it.id == id }

    /**
     * What was asked for [id], to start again from the card (with trust, or past the availability check): from memory while the app has run
     * it, rebuilt from the record after a restart, when the first prompt's text is gone with the process.
     */
    fun requestOf(id: String): SagaRequest? = synchronized(lock) { requests[id] } ?: get(id)?.let {
        SagaRequest(HostProfileId(it.host), it.session, it.agentName, it.kind, it.workspaceId, it.branch, repository = it.repository, folder = it.folder)
    }

    private fun recovered(rows: List<SagaRecord>): List<SagaRecord> = rows.map {
        if (it.state != SagaState.Running) it
        else it.copy(state = SagaState.Failed, failure = SagaFailure.APP_ENDED, message = "The app ended during \"${it.step.label}\". What was created is listed here; nothing is repeated.")
    }.also { if (it != rows) runCatching { store.save(SagaData(it)) } }

    // ---- the saga ------------------------------------------------------------------------------------------------------

    suspend fun run(req: SagaRequest): SagaRecord {
        SagaRules.problem(req)?.let { throw IllegalArgumentException(it) }
        val now = clock.nowMillis()
        val id = synchronized(lock) { "s$now-${++counter}" }
        var rec = SagaRecord(id, req.host.value, req.session, req.agentName, req.kind, req.workspaceId, req.branch, req.repository,
            folder = req.folder, promptSha256 = req.firstPrompt?.let(OperationJournal::sha256Hex), startedAt = now, updatedAt = now)
        synchronized(lock) { requests[id] = req; req.firstPrompt?.let { prompts[id] = it } }
        // Nothing exists yet: if even the first record cannot be written, nothing is created.
        rec = try { write(rec, add = true) } catch (e: java.io.IOException) {
            return rec.copy(state = SagaState.Failed, failure = SagaFailure.SAGA_UNWRITABLE, message = "The saga record could not be written on this phone, so nothing was created.").also { publish(it, add = true) }
        }

        if (!req.skipAvailabilityCheck) {
            val found = try { host.executableAvailable(SagaRules.executables(req.kind)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return fail(rec, SagaFailure.HOST_UNREACHABLE, "The host could not be asked whether ${req.kind} is installed (${reason(e)}). Nothing was created.").rec }
            if (!found) return fail(rec, SagaFailure.EXECUTABLE_MISSING, "${SagaRules.executables(req.kind).joinToString(" or ")} was not found on the host. Nothing was created.").rec
        }

        // A folder is asked about before anything is created, because herdr would not refuse a bad one: it opens the tab in its own default folder instead.
        var folder: String? = null
        if (req.folder != null) {
            rec = write(rec.copy(step = SagaStep.Folder))
            folder = try { host.resolveFolder(req.folder) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return fail(rec, SagaFailure.HOST_UNREACHABLE, "The host could not be asked about the folder (${reason(e)}). Nothing was created.").rec }
            if (folder == null) return fail(rec, SagaFailure.FOLDER_MISSING, "${req.folder.take(200)} is not a folder on the host. Nothing was created.").rec
        }

        rec = place(rec, req, folder).next { return it }
        return afterPlace(rec, req)
    }

    /**
     * The user chose a new name after herdr said the old one was taken. Allowed only when the saga failed at Start with that
     * answer (a certain refusal) and its place still exists; the pane is verified again first, then one new `agent start`.
     */
    suspend fun retryWithName(sagaId: String, newName: String): SagaRecord {
        val rec = get(sagaId) ?: throw NoSuchElementException("no saga $sagaId")
        require(rec.state == SagaState.Failed && rec.failure == SagaFailure.NAME_TAKEN && !rec.recovered) { "only a refused name can be retried" }
        SagaRules.nameProblem(newName)?.let { throw IllegalArgumentException(it) }
        val req = synchronized(lock) { requests[sagaId] } ?: SagaRequest(HostProfileId(rec.host), rec.session, newName, rec.kind, rec.workspaceId, rec.branch)
        val renamed = write(rec.copy(agentName = newName, state = SagaState.Running, step = SagaStep.Verify, failure = null, message = ""))
        return afterPlace(renamed, req.copy(agentName = newName))
    }

    private suspend fun place(start: SagaRecord, req: SagaRequest, folder: String?): Step {
        var rec = write(start.copy(step = SagaStep.Place))
        val worktree = req.branch != null
        val kind = if (worktree) OperationKind.WorktreeCreate else OperationKind.TabCreate
        val result = Operation.run(journal, key(rec.id), kind,
            resolveTarget = { req.workspaceId },
            send = { _, before -> if (worktree) host.createWorktree(req.workspaceId, req.branch!!, req.trustRepository, before) else host.createTab(req.workspaceId, folder, before) })
        return when (result) {
            is OperationResult.Acknowledged -> {
                val c = result.value
                rec = try {
                    // The ids come first: they are all that names what now exists.
                    write(rec.copy(createdWorkspaceId = c.workspaceId, createdTabId = c.tabId, createdPaneId = c.paneId, createdTerminalId = c.terminalId,
                        worktreePath = c.worktreePath, expectedCwd = folder ?: c.cwd, repository = c.repository ?: rec.repository))
                } catch (e: java.io.IOException) {
                    val shown = listOfNotNull(c.workspaceId, c.tabId, c.paneId).joinToString(", ")
                    return Step.Stop(publish(rec.copy(createdWorkspaceId = c.workspaceId, createdTabId = c.tabId, createdPaneId = c.paneId, createdTerminalId = c.terminalId, worktreePath = c.worktreePath, expectedCwd = c.cwd,
                        state = SagaState.Failed, failure = SagaFailure.IDS_NOT_SAVED, message = "herdr created $shown but this phone could not save the ids. They are shown here only until the app closes. Nothing was started.")))
                }
                // The folder was checked a moment ago, so a tab that opened anywhere else was opened by herdr's own fallback: stop before an agent starts in the wrong place.
                if (folder != null && c.cwd != null && c.cwd != folder)
                    return fail(rec, SagaFailure.WRONG_CWD, "herdr opened the new tab in ${c.cwd}, not in $folder. Nothing was started.")
                Step.Next(rec)
            }
            is OperationResult.Rejected -> fail(rec, SagaFailure.PLACE_REFUSED, "herdr refused to create the ${if (worktree) "worktree" else "tab"} (${result.code}): ${result.message}".take(300), code = result.code)
            is OperationResult.NotSent -> fail(rec, SagaFailure.PLACE_NOT_SENT, "Nothing was sent (${result.reason}). ${result.message}".trim())
            is OperationResult.Unknown -> fail(rec, SagaFailure.PLACE_UNKNOWN, "The link went away after the ${if (worktree) "worktree" else "tab"} request left. A ${if (worktree) "workspace" else "tab"} may exist on the host; its ids are unknown, so look at the Spaces list. Nothing is repeated.")
            is OperationResult.Busy, is OperationResult.NeedsReread, is OperationResult.Stale, is OperationResult.JournalFailed, is OperationResult.JournalUnreadable ->
                fail(rec, SagaFailure.PLACE_BLOCKED, SagaCopy.blocked(result))
        }
    }

    private suspend fun afterPlace(start: SagaRecord, req: SagaRequest): SagaRecord {
        var rec = verify(write(start.copy(step = SagaStep.Verify, state = SagaState.Running, failure = null, message = ""))).next { return it }
        rec = startAgent(write(rec.copy(step = SagaStep.Start)), req).next { return it }
        return firstPrompt(rec, req)
    }

    private suspend fun verify(rec: SagaRecord): Step {
        val pane = rec.createdPaneId ?: return fail(rec, SagaFailure.PANE_MOVED, "No pane id was recorded for this saga.")
        val deadline = clock.nowMillis() + verifyWindowMillis
        var last: PaneFacts? = null
        var unreadable: String? = null
        while (true) {
            val facts = try { host.inspectPane(pane).also { unreadable = null } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { unreadable = reason(e); null }
            if (facts != null) {
                last = facts
                // A pane that is gone, or now holds another terminal, never gets an agent: pane ids are not identities.
                if (!facts.exists || (facts.terminalId != null && facts.terminalId != rec.createdTerminalId))
                    return fail(rec, SagaFailure.PANE_MOVED, "The pane ${pane} is gone or now holds another terminal. Nothing was started. Re-read the herd before touching it.")
                // A pane keeps its id when someone moves it to another tab or workspace on the desktop, and the tab or workspace this saga
                // created may be gone with it. The ids the saga holds are then wrong, so it records only the pane and stops.
                if ((facts.tabId != null && facts.tabId != rec.createdTabId) || (rec.createdWorkspaceId != null && facts.workspaceId != null && facts.workspaceId != rec.createdWorkspaceId)) {
                    val moved = rec.copy(createdTabId = null, createdWorkspaceId = null)
                    return fail(moved, SagaFailure.PANE_MOVED, "The pane $pane was moved to tab ${facts.tabId} since it was created, so the tab and workspace recorded for it are no longer its own. Nothing was started. Only the pane is offered for closing.")
                }
                if (facts.hasAgent) return fail(rec, SagaFailure.PANE_BUSY, "An agent is already running in the new pane. Nothing was started.")
                if (facts.atShellPrompt && facts.revision >= 1 && cwdMatches(rec, facts)) return Step.Next(rec)
            }
            if (clock.nowMillis() >= deadline) break
            pause(pollMillis)
        }
        val f = last
        return when {
            f == null -> fail(rec, SagaFailure.PANE_NOT_READY, "The new pane could not be read for ${verifyWindowMillis / 1000} seconds (${unreadable ?: "no answer"}). Nothing was started.")
            f.atShellPrompt && !cwdMatches(rec, f) -> fail(rec, SagaFailure.WRONG_CWD,
                "The new pane is at a shell prompt in ${f.cwd ?: "an unknown folder"}, not in ${rec.worktreePath ?: rec.expectedCwd ?: "the folder herdr created it in"}. Nothing was started.")
            !f.atShellPrompt && f.revision >= 1 -> fail(rec, SagaFailure.PANE_BUSY, "Something other than the shell is running in the new pane. Nothing was started.")
            else -> fail(rec, SagaFailure.PANE_NOT_READY, "The new pane did not reach a shell prompt within ${verifyWindowMillis / 1000} seconds. Nothing was started.")
        }
    }

    /** With a worktree the pane must be in the worktree's own path; without one, in the folder herdr reported at creation. */
    private fun cwdMatches(rec: SagaRecord, f: PaneFacts): Boolean {
        val want = rec.worktreePath ?: rec.expectedCwd ?: return true
        return f.cwd == want
    }

    private suspend fun startAgent(rec: SagaRecord, req: SagaRequest): Step {
        val pane = rec.createdPaneId!!
        val result = Operation.run(journal, key(rec.id), OperationKind.AgentStart,
            resolveTarget = { pane },
            send = { p, before ->
                host.startAgent(rec.agentName, rec.kind, p, startTimeoutMs, before).also {
                    // The answer is the only place that shows the pane changed hands between the check and the start.
                    if (it.terminalId != rec.createdTerminalId) throw Misdelivered(rec.createdTerminalId.orEmpty(), it.terminalId)
                }
            })
        return when (result) {
            is OperationResult.Acknowledged -> Step.Next(write(rec.copy(terminalId = result.value.terminalId)))
            is OperationResult.Rejected -> when (result.code) {
                "agent_name_taken" -> fail(rec, SagaFailure.NAME_TAKEN, "herdr says the name ${rec.agentName} is already used in this session. The new pane is still there; choose another name.", code = result.code)
                "agent_pane_busy" -> fail(rec, SagaFailure.PANE_BUSY, "herdr says the new pane is not an available shell (${result.code}).", code = result.code)
                else -> fail(rec, SagaFailure.START_REFUSED, "herdr did not start ${rec.kind} (${result.code}): ${result.message}".take(300), code = result.code)
            }
            is OperationResult.NotSent -> fail(rec, SagaFailure.START_NOT_SENT, "Nothing was sent (${result.reason}). The new pane exists without an agent. ${result.message}".trim())
            is OperationResult.Unknown ->
                if (result.record.note.startsWith(Misdelivered.PREFIX)) fail(rec, SagaFailure.START_MISDELIVERED, "herdr started the agent in a different terminal than the one this saga created. Look at the herd before touching either.")
                else fail(rec, SagaFailure.START_UNKNOWN, "The link went away after the start request left. The agent may be starting in the new pane; nothing is repeated. Open the pane to see.")
            is OperationResult.Busy, is OperationResult.NeedsReread, is OperationResult.Stale, is OperationResult.JournalFailed, is OperationResult.JournalUnreadable ->
                fail(rec, SagaFailure.START_NOT_SENT, SagaCopy.blocked(result))
        }
    }

    private suspend fun firstPrompt(rec: SagaRecord, req: SagaRequest): SagaRecord {
        val text = synchronized(lock) { prompts.remove(rec.id) }
        val terminal = rec.terminalId
        if (text == null || terminal == null || prompter == null) return write(rec.copy(state = SagaState.Succeeded, step = SagaStep.FirstPrompt))
        val at = write(rec.copy(step = SagaStep.FirstPrompt))
        val note = try {
            when (val p = prompter.promptWhenReady(terminal, text)) { PromptStep.Sent -> null; is PromptStep.Held -> p.sentence }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { "The first prompt was not sent (${reason(e)})." }
        return write(at.copy(state = SagaState.Succeeded, promptNote = note))
    }

    // ---- recovery --------------------------------------------------------------------------------------------------------

    /** What "close what was created" would close for [rec], by its own words and its own ids; null when nothing is left to close. */
    fun closeTarget(rec: SagaRecord): CloseTarget? = when {
        rec.recovered -> null
        rec.createdWorkspaceId != null && rec.branch != null -> CloseTarget.Workspace(rec.createdWorkspaceId)
        rec.createdTabId != null -> CloseTarget.Tab(rec.createdTabId)
        rec.createdPaneId != null -> CloseTarget.Pane(rec.createdPaneId)
        else -> null
    }

    /**
     * One confirmed close of what the saga created, as a journaled operation on that workspace, tab or pane. It never removes the
     * worktree checkout or the branch: those stay on the host and the card says so. A close herdr says no longer has a target
     * (`*_not_found`) counts as done, since the aim was that it be gone.
     */
    suspend fun closeCreated(sagaId: String): OperationResult<Unit> {
        val rec = get(sagaId) ?: throw NoSuchElementException("no saga $sagaId")
        val target = closeTarget(rec) ?: throw IllegalStateException("nothing left to close")
        val (kind, subject) = when (target) {
            is CloseTarget.Workspace -> OperationKind.CloseWorkspace to Subject.workspace(target.id)
            is CloseTarget.Tab -> OperationKind.CloseTab to Subject.tab(target.id)
            is CloseTarget.Pane -> OperationKind.ClosePane to Subject.pane(target.id)
        }
        val result = Operation.run(journal, TerminalKey(TargetRef(HostProfileId(rec.host), rec.session, subject), epoch()), kind,
            resolveTarget = { target.id },
            send = { id, before -> when (target) { is CloseTarget.Workspace -> host.closeWorkspace(id, before); is CloseTarget.Tab -> host.closeTab(id, before); is CloseTarget.Pane -> host.closePane(id, before) } })
        val gone = result is OperationResult.Acknowledged || (result is OperationResult.Rejected && result.code.endsWith("_not_found"))
        if (gone) write(rec.copy(recovered = true, message = if (result is OperationResult.Acknowledged) "Closed." else "Already gone."))
        return result
    }

    /** The user chose to leave what a failed saga created. The card goes away; the ids stay in this record and in Activity. */
    fun leave(sagaId: String) { get(sagaId)?.let { write(it.copy(recovered = true, message = "Left as it is.")) } }

    // ---- bookkeeping -----------------------------------------------------------------------------------------------------

    private fun key(sagaId: String) = TerminalKey(TargetRef(HostProfileId(get(sagaId)!!.host), get(sagaId)!!.session, Subject.saga(sagaId)), epoch())

    /** Records [rec] and makes it durable; throws an [java.io.IOException] when it cannot be. */
    private fun write(rec: SagaRecord, add: Boolean = false): SagaRecord {
        val next = rec.copy(updatedAt = clock.nowMillis())
        synchronized(lock) {
            val rows = _sagas.value
            val updated = if (add || rows.none { it.id == next.id }) rows + next else rows.map { if (it.id == next.id) next else it }
            store.save(SagaData(updated))
            _sagas.value = updated
        }
        return next
    }

    /** Shows [rec] without needing the disk: for a failure that happened because the disk failed. */
    private fun publish(rec: SagaRecord, add: Boolean = false): SagaRecord {
        synchronized(lock) { _sagas.value = if (add || _sagas.value.none { it.id == rec.id }) _sagas.value + rec else _sagas.value.map { if (it.id == rec.id) rec else it } }
        return rec
    }

    private fun fail(rec: SagaRecord, failure: String, message: String, code: String? = null): Step.Stop {
        val next = rec.copy(state = SagaState.Failed, failure = failure, message = if (code != null && code !in message) "$message [$code]" else message)
        val shown = try { write(next) } catch (_: java.io.IOException) { publish(next.copy(updatedAt = clock.nowMillis())) }
        return Step.Stop(shown)
    }

    /** One step's end: carry on with the record, or stop the saga with the failed record. */
    private sealed interface Step {
        data class Next(val rec: SagaRecord) : Step
        data class Stop(val rec: SagaRecord) : Step
    }

    private inline fun Step.next(stop: (SagaRecord) -> Nothing): SagaRecord = when (this) { is Step.Next -> rec; is Step.Stop -> stop(rec) }

    private fun reason(e: Throwable) = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(120)
}

sealed interface CloseTarget {
    val id: String
    data class Workspace(override val id: String) : CloseTarget
    data class Tab(override val id: String) : CloseTarget
    data class Pane(override val id: String) : CloseTarget
}
