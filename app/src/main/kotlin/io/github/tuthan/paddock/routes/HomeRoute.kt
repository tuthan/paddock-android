package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.host.HomeUiMapper
import io.github.tuthan.paddock.host.Recovery
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.probe.ProbeCopy
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.RelayInstall
import io.github.tuthan.paddock.ui.screens.RenameDialog
import io.github.tuthan.paddock.ui.screens.RowActionsDialog
import io.github.tuthan.paddock.ui.screens.clockLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun HomeRoute(
    graph: AppGraph, relayDismissed: Boolean, setRelayDismissed: (Boolean) -> Unit, onReviewKey: () -> Unit,
    onOpen: (terminalId: String) -> Unit, onReviewPrompt: (terminalId: String) -> Unit, onSettings: () -> Unit, onSetUpKey: () -> Unit,
    onShowCommand: () -> Unit, onNotice: (String) -> Unit = {}, onMachines: () -> Unit = {},
) {
    val profile by graph.machineBook.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val now = rememberNow()
    val name = profile?.name ?: "this machine"
    val wakeFacts by graph.wakes.watchedFacts.collectAsState()
    // Read again when the connection changes and on every return: the phone may have joined or left a network meanwhile.
    val homeResumes = rememberResumes()
    val wakeReady = remember(profile, view.phase, homeResumes) { profile?.let { graph.wakes.ready(it) } == true }
    val screen = HomeUiMapper.map(name, view, now, wakeAvailable = wakeReady && wakeFacts?.canWakeAgain(now) != false)
    val ctx = LocalContext.current
    // The captured prompt is agent output, so while it is on Home the window is protected like Output is.
    val settings by graph.settings.collectAsState()
    val promptShown = (screen.state as? HomeUiState.Live)?.model?.rows?.any { it.key.target.terminalId == view.blockedPreview?.terminalId } == true && view.blockedPreview?.state is PreviewState.Showing
    SecureWindow(settings.protectSensitiveScreens && promptShown)

    val prompt = screen.relayPrompt
    if (prompt != null && !relayDismissed) {
        RelayInstall(name, prompt, installing = false, onInstall = { graph.installRelay() }, onNotNow = { setRelayDismissed(true) })
        return
    }
    val host = (view.phase as? HostPhase.Monitoring)?.host
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(view.lastReadAtMillis) { refreshing = false }
    LaunchedEffect(refreshing) { if (refreshing) { delay(5_000); refreshing = false } }
    // Long-press on a live row (Phase 09): rename the agent, or show its workspace or tab on the desktop. Each is a journaled operation.
    var menuRow by remember { mutableStateOf<AgentRowModel?>(null) }
    // The row and the name it has now, read when Rename was tapped (a StateFlow is not read in composition).
    var renameRow by remember { mutableStateOf<Pair<AgentRowModel, String?>?>(null) }
    val rowOps = host?.operations
    val presenter = remember { io.github.tuthan.paddock.ops.OperationPresenter() }
    menuRow?.let { row ->
        RowActionsDialog(
            row.title, onDismiss = { menuRow = null },
            onRename = {
                renameRow = row to host?.reconciler?.installed?.value?.snapshot?.agents?.firstOrNull { it.terminalId == row.key.target.terminalId }?.name
                menuRow = null
            },
            onFocusWorkspace = {
                menuRow = null
                val agent = host?.reconciler?.installed?.value?.snapshot?.agents?.firstOrNull { it.terminalId == row.key.target.terminalId }
                if (agent == null) onNotice("This agent is no longer listed. Nothing was sent.")
                else graph.scope.launch { host?.spaceOps?.focusWorkspace(agent.workspaceId)?.let { onNotice(presenter.line(io.github.tuthan.paddock.ops.OperationKind.FocusWorkspace, it).text) } }
            },
            onFocusTab = {
                menuRow = null
                val agent = host?.reconciler?.installed?.value?.snapshot?.agents?.firstOrNull { it.terminalId == row.key.target.terminalId }
                if (agent == null) onNotice("This agent is no longer listed. Nothing was sent.")
                else graph.scope.launch { host?.spaceOps?.focusTab(agent.tabId)?.let { onNotice(presenter.line(io.github.tuthan.paddock.ops.OperationKind.FocusTab, it).text) } }
            },
        )
    }
    renameRow?.let { (row, current) ->
        RenameDialog(current, onCancel = { renameRow = null }, onRename = { name ->
            renameRow = null
            graph.scope.launch { rowOps?.rename(row.key, name)?.let { onNotice(presenter.line(io.github.tuthan.paddock.ops.OperationKind.Rename, it).text) } }
        })
    }
    // The machine chips (decision D4, 2026-10-06): with two or more saved machines, one chip each; another machine's chip asks to watch it.
    val machines by graph.machineBook.machines.collectAsState()
    val pro by graph.proService.view.collectAsState()
    val switchLocked = graph.proService.locked(ProCapabilities.SWITCH_MACHINE, pro)
    val rows = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId)
    // What the probe found on the machines not watched, worded for now (Pro: without it nothing is looked at, and a count read while Pro was held is not shown).
    val probed by graph.probe.states.collectAsState()
    val glances = if (graph.proService.locked(ProCapabilities.HOSTS_MERGED, pro)) emptyMap()
    else probed.mapNotNull { (id, state) -> ProbeCopy.glance(state, now)?.let { id to it } }.toMap()
    val chips = MachineRoster.chips(rows, switchLocked, glances)
    fun recover(r: Recovery?) {
        when (r) {
            Recovery.OpenSettings -> ctx.startActivity(graph.gate.settingsIntent())
            Recovery.ReviewKey -> onReviewKey()
            Recovery.InstallRelay -> setRelayDismissed(false)
            Recovery.Retry -> graph.retry()
            Recovery.SetUpKey -> onSetUpKey()
            Recovery.Wake -> graph.wakes.wake()
            Recovery.ShowCommand -> onShowCommand()
            null -> Unit
        }
    }
    HerdHome(
        screen.state, now, preview = view.blockedPreview, onSettings = onSettings, onMachines = onMachines,
        machineChips = chips, onWatchMachine = { chip -> rows.firstOrNull { it.id == chip.id }?.let { switchMachine(graph, it, onNotice) } },
        onRowMenu = if (host?.operations != null && host.spaceOps != null) { row -> menuRow = row } else null,
        onRefresh = if (host != null) ({ refreshing = true; host.refresh() }) else null, refreshing = refreshing,
        // Review prompt opens the live terminal for that agent, observing only; the captured prompt on Home is not read again, since the terminal shows it as it is.
        onReview = { row -> onReviewPrompt(row.key.target.terminalId) },
        onOpenAgent = { row ->
            // A Done tap is the user acknowledging it: local only, then the agent's output opens.
            if (row.state == StateWord.Done) host?.markSeen(row)
            onOpen(row.key.target.terminalId)
        },
        onRecovery = { recover(screen.recovery) },
        onSecondaryRecovery = { recover(screen.secondary) },
        wakeLines = wakeFacts?.lines(::clockLabel).orEmpty(),
    )
}
