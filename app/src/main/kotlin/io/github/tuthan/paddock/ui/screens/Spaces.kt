package io.github.tuthan.paddock.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.live.SessionRow
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ops.CardAction
import io.github.tuthan.paddock.ops.CloseTarget
import io.github.tuthan.paddock.ops.SagaCardModel
import io.github.tuthan.paddock.ops.SagaCopy
import io.github.tuthan.paddock.ops.SagaRules
import io.github.tuthan.paddock.ops.SavedLayoutParser
import io.github.tuthan.paddock.ops.SavedLayoutResult
import io.github.tuthan.paddock.ops.SessionCopy
import io.github.tuthan.paddock.ops.SessionRules
import io.github.tuthan.paddock.ui.components.AgentMarkTile
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Dot
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.HostChip
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.Toggle
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** A workspace the start-agent form can start in: herdr's id and the label the herd shows for it. */
data class WorkspaceChoice(val id: String, val label: String)

/** Whether an agent can be started from here right now, and where. */
sealed interface StartAvailability {
    data class Available(val workspaces: List<WorkspaceChoice>) : StartAvailability
    data class Unavailable(val reason: String) : StartAvailability
}

/** The start-agent saga as the screen shows it while it runs and just after it succeeds; a failed one is a recovery card instead. */
data class SagaProgress(val sagaId: String, val agentName: String, val kind: String, val step: String, val done: Boolean, val terminalId: String?, val note: String?)

/** What the user filled in on the start-agent form. [branch] is null for "no worktree". */
data class StartForm(val workspaceId: String, val kind: String, val name: String, val branch: String?, val prompt: String?)

data class SpacesScreenState(
    val hostName: String,
    val hostStatus: String,
    val health: HostHealth,
    val list: SpacesState,
    val notice: String?,
    /** The session this phone is watching, marked in its row. */
    val watchedSession: String?,
    val cards: List<SagaCardModel>,
    val progress: SagaProgress?,
    val start: StartAvailability,
    val refreshing: Boolean = false,
)

class SpacesActions(
    val onRefresh: () -> Unit = {},
    val onStop: (SessionEntry) -> Unit = {},
    val onDelete: (SessionEntry) -> Unit = {},
    val onReread: (String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onStart: (StartForm) -> Unit = {},
    val onCard: (sagaId: String, CardAction) -> Unit = { _, _ -> },
    val onRetryName: (sagaId: String, name: String) -> Unit = { _, _ -> },
    val onDismissProgress: () -> Unit = {},
    val onOpenAgent: (terminalId: String) -> Unit = {},
)

private sealed interface SpacesAsk {
    data class Stop(val entry: SessionEntry) : SpacesAsk
    data class Delete(val entry: SessionEntry) : SpacesAsk
    data class Close(val sagaId: String, val action: CardAction.CloseCreated) : SpacesAsk
    data class Trust(val sagaId: String, val action: CardAction.TrustRepository) : SpacesAsk
}

/**
 * Spaces: the machine, its herdr sessions, and what can be done to them (Phase 09). A running session can be stopped and a stopped one
 * deleted, each after a dialog that names the exact session and the machine and opens with Cancel focused; `default` can never be deleted
 * and its Delete is not offered. A stopped session shows what herdr saved, as folder names with the file's date, or "saved contents
 * unavailable". Restart is disabled with its reason. Starting an agent is a form and a saga: what it created is shown if it stops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesScreen(state: SpacesScreenState, actions: SpacesActions, modifier: Modifier = Modifier, clock: (Long) -> String = ::clockLabel) {
    var form by rememberSaveable { mutableStateOf(false) }
    var ask by remember { mutableStateOf<SpacesAsk?>(null) }
    BackHandler(enabled = form) { form = false }

    ask?.let { a ->
        when (a) {
            is SpacesAsk.Stop -> ConfirmDialog(
                SessionCopy.stopTitle(a.entry.name), SessionCopy.stopBody(a.entry.name, state.hostName, a.entry.default),
                confirm = SessionCopy.STOP_CONFIRM, onConfirm = { ask = null; actions.onStop(a.entry) }, onCancel = { ask = null }, danger = true,
                facts = listOf("Session" to a.entry.name, "Machine" to state.hostName),
            )
            is SpacesAsk.Delete -> ConfirmDialog(
                SessionCopy.deleteTitle(a.entry.name), SessionCopy.deleteBody(a.entry.name, state.hostName),
                confirm = SessionCopy.DELETE_CONFIRM, onConfirm = { ask = null; actions.onDelete(a.entry) }, onCancel = { ask = null }, danger = true,
                facts = listOf("Session" to a.entry.name, "Machine" to state.hostName),
            )
            is SpacesAsk.Close -> ConfirmDialog(
                SagaCopy.closeTitle(a.action.label) + "?", SagaCopy.closeBody(a.action.what, state.hostName),
                confirm = SagaCopy.CLOSE_CONFIRM, onConfirm = { ask = null; actions.onCard(a.sagaId, a.action) }, onCancel = { ask = null }, danger = true,
                facts = listOf(when (a.action.what) { is CloseTarget.Workspace -> "Workspace"; is CloseTarget.Tab -> "Tab"; is CloseTarget.Pane -> "Pane" } to a.action.what.id),
            )
            is SpacesAsk.Trust -> ConfirmDialog(
                SagaCopy.trustTitle(a.action.repository), SagaCopy.trustBody(a.action.repository),
                confirm = SagaCopy.TRUST_CONFIRM, onConfirm = { ask = null; actions.onCard(a.sagaId, a.action) }, onCancel = { ask = null },
                facts = listOfNotNull(a.action.repository?.let { "Repository" to it }),
            )
        }
    }

    val available = state.start as? StartAvailability.Available
    if (form && available != null) {
        StartAgentForm(available.workspaces, onCancel = { form = false }, onStart = { form = false; actions.onStart(it) }, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Spaces")
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HostChip(state.hostName, state.hostStatus, health = state.health)
                state.notice?.let { Banner(it, actionLabel = "Dismiss", onAction = actions.onDismissNotice) }
                state.cards.forEach { RecoveryCard(it, state.hostName, onAction = { action ->
                    when (action) {
                        is CardAction.CloseCreated -> ask = SpacesAsk.Close(it.sagaId, action)
                        is CardAction.TrustRepository -> ask = SpacesAsk.Trust(it.sagaId, action)
                        else -> actions.onCard(it.sagaId, action)
                    }
                }, onRetryName = { name -> actions.onRetryName(it.sagaId, name) }) }
                state.progress?.let { Progress(it, onOpen = { id -> actions.onOpenAgent(id) }, onDismiss = actions.onDismissProgress) }

                when (val s = state.start) {
                    is StartAvailability.Available -> PaddockButton("Start an agent…", { form = true }, icon = io.github.tuthan.paddock.ui.theme.PaddockIcons.Plus)
                    is StartAvailability.Unavailable -> Note(s.reason)
                }

                Kicker("Sessions")
                when (val l = state.list) {
                    SpacesState.Loading -> Note("Reading the session list…")
                    is SpacesState.Failed -> Banner(l.message, actionLabel = "Try again", onAction = actions.onRefresh)
                    is SpacesState.Ready -> {
                        if (l.rows.isEmpty()) Note("herdr lists no sessions on this machine.")
                        l.rows.forEach { row -> SessionCard(row, watched = row.entry.name == state.watchedSession, clock = clock, ask = { ask = it }, onReread = actions.onReread) }
                        Note("List read at ${clock(l.readAtMillis)}. It is that moment's view, not a live one: pull down to read it again.")
                    }
                }
                Note(SessionCopy.RESTART_DISABLED)
            }
        }
    }
}

@Composable
private fun SessionCard(row: SessionRow, watched: Boolean, clock: (Long) -> String, ask: (SpacesAsk) -> Unit, onReread: (String) -> Unit) {
    val c = PaddockTokens.colors
    val e = row.entry
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.control(), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (e.running) c.accent else c.faint)
            Text(e.name, style = PaddockTokens.type.rowTitle, color = c.title, modifier = Modifier.weight(1f))
            Text(if (e.running) "running" else "stopped", style = PaddockTokens.type.secondary, color = c.dim)
        }
        val tags = listOfNotNull(if (e.default) "default session" else null, if (watched) "watched by this phone" else null)
        if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = PaddockTokens.type.secondary, color = c.dim)
        if (!e.running) {
            Text(
                when (val l = row.layout) {
                    null -> SavedLayoutParser.UNAVAILABLE
                    is SavedLayoutResult.Unavailable -> SavedLayoutParser.UNAVAILABLE
                    is SavedLayoutResult.Layout -> "Saved layout: ${l.layout.summary} · file dated ${clock(l.layout.savedAtMillis)}"
                },
                style = PaddockTokens.type.secondary, color = c.text,
            )
        }
        if (row.awaitsReread) {
            Text("The last operation on this session has an unknown outcome. Read the list again before doing anything else with it.", style = PaddockTokens.type.secondary, color = c.needsYou)
            PaddockButton("Re-read", { onReread(e.name) }, kind = ButtonKind.Ghost, small = true)
        } else if (e.running) {
            PaddockButton("Stop…", { ask(SpacesAsk.Stop(e)) }, kind = ButtonKind.Danger, small = true, enabled = !row.busy)
        } else {
            val refusal = SessionRules.deleteRefusal(e)
            ButtonPair(
                { m -> PaddockButton("Restart", {}, m, kind = ButtonKind.Secondary, small = true, enabled = false) },
                { m -> PaddockButton("Delete…", { ask(SpacesAsk.Delete(e)) }, m, kind = ButtonKind.Danger, small = true, enabled = refusal == null && !row.busy) },
            )
            if (refusal != null) Text(refusal, style = PaddockTokens.type.secondary, color = c.dim)
        }
    }
}

@Composable
private fun Progress(p: SagaProgress, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.control(), shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (p.done) SagaCopy.started(p.agentName, p.kind) else "Starting ${p.agentName} (${p.kind})…", style = PaddockTokens.type.rowTitle, color = c.title)
        Text(if (p.done) (p.note ?: "The agent is running in a pane of its own.") else "Step: ${p.step}. Each step is recorded before the next begins.", style = PaddockTokens.type.secondary, color = c.dim)
        if (p.done) ButtonPair(
            { m -> PaddockButton("Dismiss", onDismiss, m, kind = ButtonKind.Secondary, small = true) },
            { m -> PaddockButton("Open it", { p.terminalId?.let(onOpen) }, m, kind = ButtonKind.Ghost, small = true, enabled = p.terminalId != null) },
        )
    }
}

/** The recovery card of a saga that stopped: what stopped it, every id it created, and what the user may do about it. Nothing runs without a tap. */
@Composable
fun RecoveryCard(card: SagaCardModel, hostName: String, onAction: (CardAction) -> Unit, onRetryName: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    var naming by rememberSaveable(card.sagaId) { mutableStateOf(false) }
    var name by rememberSaveable(card.sagaId) { mutableStateOf("") }
    val problem = name.takeIf { it.isNotEmpty() }?.let { SagaRules.nameProblem(it) }
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.needsYou.copy(alpha = 0.5f), shape).padding(14.dp)
            .semantics(mergeDescendants = false) { contentDescription = "Recovery card. ${card.title}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(card.title, style = PaddockTokens.type.rowTitle, color = c.title)
        card.lines.forEach { Text(it, style = PaddockTokens.type.secondary, color = c.text) }
        card.ids.forEach { (k, v) -> Fact(k, v) }
        if (naming) {
            Field("New name", name, { name = it }, error = problem, placeholder = "worker-2", onDone = { if (problem == null && name.isNotEmpty()) onRetryName(name) })
            ButtonPair(
                { m -> PaddockButton("Cancel", { naming = false }, m, kind = ButtonKind.Secondary, small = true) },
                { m -> PaddockButton("Start with this name", { onRetryName(name) }, m, kind = ButtonKind.Primary, small = true, enabled = name.isNotEmpty() && problem == null) },
            )
        }
        card.actions.forEach { a ->
            when (a) {
                is CardAction.OpenPane -> PaddockButton("Open the pane", { onAction(a) }, kind = ButtonKind.Ghost, small = true, enabled = a.terminalId != null)
                CardAction.ChooseAnotherName -> if (!naming) PaddockButton("Choose another name", { naming = true }, kind = ButtonKind.Ghost, small = true)
                is CardAction.TrustRepository -> PaddockButton("Trust this repository…", { onAction(a) }, kind = ButtonKind.Ghost, small = true)
                CardAction.StartAnyway -> PaddockButton("Start anyway", { onAction(a) }, kind = ButtonKind.Ghost, small = true)
                is CardAction.CloseCreated -> PaddockButton("${a.label}…", { onAction(a) }, kind = ButtonKind.Danger, small = true)
                is CardAction.Leave -> PaddockButton(a.label, { onAction(a) }, kind = ButtonKind.Secondary, small = true)
            }
        }
    }
}

/** The start-agent form: a workspace, a kind, a name, an optional worktree branch and an optional first prompt. Back and Cancel change nothing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartAgentForm(workspaces: List<WorkspaceChoice>, onCancel: () -> Unit, onStart: (StartForm) -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    var workspace by rememberSaveable { mutableStateOf(workspaces.firstOrNull()?.id) }
    var kind by rememberSaveable { mutableStateOf("claude") }
    var name by rememberSaveable { mutableStateOf("") }
    var worktree by rememberSaveable { mutableStateOf(false) }
    var branch by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    val nameProblem = name.takeIf { it.isNotEmpty() }?.let { SagaRules.nameProblem(it) }
    val branchProblem = if (worktree) branch.takeIf { it.isNotEmpty() }?.let { SagaRules.branchProblem(it) } else null
    val ready = workspace != null && name.isNotEmpty() && nameProblem == null && (!worktree || (branch.isNotEmpty() && branchProblem == null))

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Start an agent", onBack = onCancel)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Kicker("Workspace")
            if (workspaces.isEmpty()) Note("This session has no workspace to start in.")
            workspaces.forEach { w ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = PaddockTokens.spacing.touchTarget).clip(RoundedCornerShape(PaddockTokens.radii.row))
                        .border(1.dp, if (workspace == w.id) c.accent.copy(alpha = 0.6f) else c.control(), RoundedCornerShape(PaddockTokens.radii.row))
                        .selectable(selected = workspace == w.id, role = Role.RadioButton, onClick = { workspace = w.id }).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text(w.label, style = PaddockTokens.type.rowTitle, color = c.title) }
            }
            Kicker("Agent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SagaRules.KINDS.forEach { k ->
                    val on = k == kind
                    Row(
                        Modifier.heightIn(min = PaddockTokens.spacing.touchTarget).clip(RoundedCornerShape(PaddockTokens.radii.row))
                            .background(if (on) c.accent.copy(alpha = 0.10f) else c.surface)
                            .border(1.dp, if (on) c.accent.copy(alpha = 0.6f) else c.control(), RoundedCornerShape(PaddockTokens.radii.row))
                            .selectable(selected = on, role = Role.RadioButton, onClick = { kind = k }).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AgentMarkTile(k)
                        Text(k, style = PaddockTokens.type.rowTitle, color = c.title)
                    }
                }
            }
            Field("Name", name, { name = it }, error = nameProblem, placeholder = "worker-1")
            Toggle("Start in a new worktree", worktree, { worktree = it }, detail = "herdr checks out a branch into a folder of its own and opens it as a new workspace.")
            if (worktree) Field("Branch", branch, { branch = it }, error = branchProblem, placeholder = "feature-x", mono = true)
            Field("First prompt (optional)", prompt, { prompt = it }, singleLine = false, imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                placeholder = "Sent once, only if the agent is ready")
            Note("Paddock checks that ${SagaRules.executables(kind).joinToString(" or ")} is installed on the machine, creates the place, checks the new pane, then starts the agent in it. If a step fails, what was created is listed and nothing is repeated.")
            ButtonPair(
                { m -> PaddockButton("Cancel", onCancel, m, kind = ButtonKind.Secondary) },
                { m -> PaddockButton("Start agent", { onStart(StartForm(workspace!!, kind, name, branch.takeIf { worktree }, prompt.takeIf { it.isNotBlank() })) }, m, kind = ButtonKind.Primary, enabled = ready) },
            )
        }
    }
}
