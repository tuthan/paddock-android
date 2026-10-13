package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ops.CardAction
import io.github.tuthan.paddock.ops.SagaCard
import io.github.tuthan.paddock.ops.SagaRecord
import io.github.tuthan.paddock.ops.SagaRequest
import io.github.tuthan.paddock.ops.SagaState
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.screens.SagaProgress
import io.github.tuthan.paddock.ui.screens.SpacesActions
import io.github.tuthan.paddock.ui.screens.SpacesScreen
import io.github.tuthan.paddock.ui.screens.SpacesScreenState
import io.github.tuthan.paddock.ui.screens.StartAvailability
import io.github.tuthan.paddock.ui.screens.WorkspaceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The Spaces tab (Phase 09): the watched machine's sessions, the start-agent flow and its recovery cards. */
@Composable
internal fun SpacesRoute(machineBook: MachineBook, hostUi: HostUiModel, proService: ProService, appScope: CoroutineScope, onNotice: (String) -> Unit, onOpenAgent: (terminalId: String) -> Unit) {
    val profile by machineBook.profile.collectAsState()
    val view by hostUi.view.collectAsState()
    val pro by proService.view.collectAsState()
    val spaces = view.spaces
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val saga = host?.saga
    val scope = appScope
    val name = profile?.name ?: "this machine"
    val listState by (spaces?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow<SpacesState>(SpacesState.Loading) }).collectAsState()
    val notice by (spaces?.notice ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val sagas by (saga?.sagas ?: remember { kotlinx.coroutines.flow.MutableStateFlow<List<SagaRecord>>(emptyList()) }).collectAsState()
    val installed by (host?.reconciler?.installed ?: remember { kotlinx.coroutines.flow.MutableStateFlow<io.github.tuthan.paddock.reconcile.Installed?>(null) }).collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var progressSince by rememberSaveable { mutableStateOf<Long?>(null) }
    val now = rememberNow()

    // The list is read when the tab opens and whenever the connection to the machine changes; every operation reads it again after itself.
    LaunchedEffect(spaces) { spaces?.refresh() }

    // Host and session both: the saga file holds every machine's and every session's attempts, and its ids mean something only in the session they came from.
    val mine = sagas.filter { saga?.owns(it) == true }
    // updatedAt, not startedAt: a retry under a new name runs in the saga that began earlier.
    val progress = progressSince?.let { since -> mine.filter { it.updatedAt >= since && it.state != SagaState.Failed }.maxByOrNull { it.updatedAt } }?.let {
        SagaProgress(it.id, it.agentName, it.kind, it.step.label, done = it.state == SagaState.Succeeded, terminalId = it.terminalId, note = it.promptNote)
    }
    val cards = mine.filter { it.showsCard(now) }.map { SagaCard.of(it, name) }
    val start = when {
        host != null && io.github.tuthan.paddock.demo.Demo.isDemo(host.profile) -> StartAvailability.Unavailable(io.github.tuthan.paddock.demo.DemoCopy.START_AGENT)
        saga == null || host == null -> StartAvailability.Unavailable("Starting an agent needs this phone to be watching a live session on $name.")
        else -> StartAvailability.Available(
            installed?.snapshot?.workspaces.orEmpty().map { WorkspaceChoice(it.workspaceId, it.label.ifBlank { "workspace ${it.number}" }) },
            installed?.snapshot?.panes.orEmpty().mapNotNull { it.cwd?.takeIf { c -> c.startsWith("/") } }.distinct(),
        )
    }
    val health = when { host != null && view.freshness == io.github.tuthan.paddock.reconcile.Freshness.Live -> HostHealth.Live; view.phase == null || view.phase == HostPhase.Connecting -> HostHealth.Connecting; else -> HostHealth.Degraded }
    val presenter = remember { io.github.tuthan.paddock.ops.OperationPresenter() }

    fun begin(req: SagaRequest) {
        progressSince = now
        scope.launch {
            try { saga?.run(req) } catch (e: IllegalArgumentException) { onNotice(e.message ?: "That request cannot be sent.") }
        }
    }

    SpacesScreen(
        SpacesScreenState(
            hostName = name, hostStatus = when (health) { HostHealth.Live -> "live"; HostHealth.Connecting -> "connecting"; else -> "not live" }, health = health,
            list = if (spaces == null) SpacesState.Failed("No connection to $name yet, so its sessions cannot be read.") else listState,
            notice = notice, watchedSession = host?.sessionName, cards = cards, progress = progress, start = start, refreshing = refreshing,
            locked = listOf(ProCapabilities.START_AGENT, ProCapabilities.MANAGE_SESSIONS).filter { proService.locked(it, pro) }.map { it.id }.toSet(),
        ),
        SpacesActions(
            onChoose = { id -> proService.requestCapability(id, pendingAnswerOnScreen = false) },
            onRefresh = { refreshing = true; scope.launch { try { spaces?.refresh() } finally { refreshing = false } } },
            onStop = { e -> scope.launch { spaces?.stop(e) } },
            onDelete = { e -> scope.launch { spaces?.delete(e) } },
            onReread = { n -> scope.launch { spaces?.reread(n) } },
            onDismissNotice = { spaces?.dismissNotice() },
            onStart = { f -> begin(SagaRequest(profile!!.hostId, host!!.sessionName, f.name, f.kind, f.workspaceId, f.branch, firstPrompt = f.prompt, repository = null, folder = f.folder)) },
            onDismissProgress = { progressSince = null },
            onOpenAgent = onOpenAgent,
            onRetryName = { id, newName -> progressSince = now; scope.launch { try { saga?.retryWithName(id, newName) } catch (e: IllegalArgumentException) { onNotice(e.message ?: "That name cannot be used.") } } },
            onCard = { id, action ->
                when (action) {
                    is CardAction.OpenPane -> action.terminalId?.let(onOpenAgent)
                    is CardAction.Leave -> saga?.leave(id)
                    is CardAction.CloseCreated -> scope.launch { saga?.closeCreated(id)?.let { onNotice(SagaCopyLine.closed(presenter, action.what, it)) } }
                    is CardAction.StartAnyway -> saga?.requestOf(id)?.let { r -> saga.leave(id); begin(r.copy(skipAvailabilityCheck = true)) }
                    is CardAction.TrustRepository -> saga?.requestOf(id)?.let { r -> saga.leave(id); begin(r.copy(trustRepository = true)) }
                    CardAction.ChooseAnotherName -> Unit
                }
            },
        ),
    )
}

/** The sentence for the end of a "close what the saga created" operation. */
internal object SagaCopyLine {
    fun closed(presenter: io.github.tuthan.paddock.ops.OperationPresenter, what: io.github.tuthan.paddock.ops.CloseTarget, r: io.github.tuthan.paddock.ops.OperationResult<Unit>): String {
        val kind = when (what) {
            is io.github.tuthan.paddock.ops.CloseTarget.Workspace -> io.github.tuthan.paddock.ops.OperationKind.CloseWorkspace
            is io.github.tuthan.paddock.ops.CloseTarget.Tab -> io.github.tuthan.paddock.ops.OperationKind.CloseTab
            is io.github.tuthan.paddock.ops.CloseTarget.Pane -> io.github.tuthan.paddock.ops.OperationKind.ClosePane
        }
        return presenter.line(kind, r).text
    }
}
