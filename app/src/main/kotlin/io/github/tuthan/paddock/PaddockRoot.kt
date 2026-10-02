package io.github.tuthan.paddock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.ImportedKeyInfo
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.ComposerRules
import io.github.tuthan.paddock.ops.FocusRules
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.ManualInputRules
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.ops.PromptTextCheck
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.RereadOutcome
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ops.SendGate
import io.github.tuthan.paddock.ops.SendOutcome
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.ops.TextCheck
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.screens.Composer
import io.github.tuthan.paddock.ui.screens.ComposerUi
import io.github.tuthan.paddock.ui.screens.SnippetEditor
import io.github.tuthan.paddock.ui.screens.ImportKey
import io.github.tuthan.paddock.ui.screens.PickedKeyFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.host.HomeUiMapper
import io.github.tuthan.paddock.host.HostScreen
import io.github.tuthan.paddock.host.Recovery
import io.github.tuthan.paddock.hostkey.HostKeyPrompts
import io.github.tuthan.paddock.hostkey.HostKeyState
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityPresenter
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.FingerprintDialog
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.ActivityLog
import io.github.tuthan.paddock.ui.screens.ADD_MACHINE_INTRO
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.SET_UP_KEY_INTRO
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.ESC_OFF_NOTE
import io.github.tuthan.paddock.ui.screens.FocusGuard
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.ManualInputActions
import io.github.tuthan.paddock.ui.screens.ManualInputUi
import io.github.tuthan.paddock.ui.screens.RelayInstall
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.screens.TerminalActions
import io.github.tuthan.paddock.ui.screens.TerminalTab
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.NavItem
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.PaddockNavBar
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.screens.MachineSummary
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { Home, Output, Compose, Snippets, Activity, Settings, AddMachine }

private val NAV = listOf(NavItem("Herd", PaddockIcons.Herd), NavItem("Activity", PaddockIcons.Activity))

/**
 * The app's one navigation host. Screens are stateless; this connects them to the graph. The route, where Add machine
 * was opened from, and the open terminal survive rotation (`rememberSaveable`); everything else is read from the graph.
 * Home and Activity share the bottom bar; Settings is the gear on Home, and Add machine lives in Settings.
 */
@Composable
fun PaddockRoot(graph: AppGraph, modifier: Modifier = Modifier) {
    val boot by graph.boot.collectAsState()
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var addFrom by rememberSaveable { mutableStateOf(Route.Home) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var outputTab by rememberSaveable { mutableStateOf(AgentTab.Output) }
    var snippetsFrom by rememberSaveable { mutableStateOf(Route.Settings) }
    var relayDismissed by rememberSaveable { mutableStateOf(false) }
    var reviewKey by rememberSaveable { mutableStateOf(false) }
    // The composer's text lives here, above the route: leaving the composer (Edit snippets, Back) or a reconnect that swaps it for "not available" must not lose it.
    var draft by rememberSaveable(stateSaver = PromptDraftSaver) { mutableStateOf(PromptDraft()) }
    // Add machine opened to set up the watched machine's key (its key could not be read), not to add another.
    var editing by rememberSaveable { mutableStateOf(false) }

    val effective = if (boot == Boot.NoMachines) Route.AddMachine else route
    // Manual input belongs to the agent screen and the composer opened from it; leaving them, or opening another agent, ends it.
    val onAgent = (effective == Route.Output && outputTab == AgentTab.Output) || effective == Route.Compose || (effective == Route.Snippets && snippetsFrom == Route.Compose)
    LaunchedEffect(onAgent, terminalId) { if (onAgent) graph.manualInput.leaveUnless(terminalId) else graph.manualInput.leave() }
    val backTo = when (effective) { Route.AddMachine -> addFrom; Route.Compose -> Route.Output; Route.Snippets -> snippetsFrom; else -> Route.Home }
    // Registered before the screens', so a screen's own back handling (the import screen's) is asked first.
    BackHandler(enabled = effective != Route.Home && boot == Boot.Ready) { route = backTo }
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (boot) {
            Boot.Loading -> Text("Paddock", style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title, modifier = Modifier.padding(PaddockTokens.spacing.gutter))
            else -> when (effective) {
                Route.Home, Route.Activity -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (effective == Route.Home) HomeRoute(
                            graph, relayDismissed, { relayDismissed = it }, { reviewKey = true },
                            onOpen = { terminalId = it; outputTab = AgentTab.Output; route = Route.Output }, onSettings = { route = Route.Settings },
                            onSetUpKey = { editing = true; addFrom = Route.Home; route = Route.AddMachine },
                        ) else ActivityRoute(graph, onOpenAgent = { terminalId = it; outputTab = AgentTab.Output; route = Route.Output })
                    }
                    PaddockNavBar(NAV, if (effective == Route.Home) 0 else 1, { route = if (it == 0) Route.Home else Route.Activity })
                }
                Route.Output -> OutputRoute(graph, terminalId, outputTab, { outputTab = it }, onBack = { route = Route.Home }, onCompose = { route = Route.Compose })
                Route.Compose -> ComposeRoute(
                    graph, terminalId, draft, { change -> draft = change(draft) }, onBack = { route = Route.Output },
                    onOpenTerminal = { outputTab = AgentTab.Terminal; route = Route.Output },
                    onEditSnippets = { snippetsFrom = Route.Compose; route = Route.Snippets },
                )
                Route.Snippets -> SnippetsRoute(graph, onBack = { route = snippetsFrom })
                Route.Settings -> SettingsRoute(
                    graph, onBack = { route = Route.Home }, onAddMachine = { editing = false; addFrom = Route.Settings; route = Route.AddMachine },
                    onEditSnippets = { snippetsFrom = Route.Settings; route = Route.Snippets },
                )
                Route.AddMachine -> AddMachineRoute(
                    graph, canGoBack = boot == Boot.Ready, editing = editing && boot == Boot.Ready,
                    onBack = { editing = false; route = backTo }, onAdded = { editing = false; route = Route.Home; relayDismissed = false },
                )
            }
        }
        HostKeyDialogs(graph, reviewKey) { reviewKey = false }
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    return now
}

/** Counts the activity's resumes, so a value read from the system (a permission grant) is read again on return. */
@Composable
private fun rememberResumes(): Int {
    val owner = LocalContext.current as? LifecycleOwner
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return resumes
}

@Composable
private fun HomeRoute(
    graph: AppGraph, relayDismissed: Boolean, setRelayDismissed: (Boolean) -> Unit, onReviewKey: () -> Unit,
    onOpen: (terminalId: String) -> Unit, onSettings: () -> Unit, onSetUpKey: () -> Unit,
) {
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val now = rememberNow()
    val name = profile?.name ?: "this machine"
    val screen = HomeUiMapper.map(name, view, now)
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
    HerdHome(
        screen.state, now, preview = view.blockedPreview, onSettings = onSettings,
        onRefresh = if (host != null) ({ refreshing = true; host.refresh() }) else null, refreshing = refreshing,
        onReview = { row ->
            // Review prompt: read the prompt again, then open the output for that agent (the terminal replaces this later).
            host?.refreshPreview()
            onOpen(row.key.target.terminalId)
        },
        onOpenAgent = { row ->
            // A Done tap is the user acknowledging it: local only, then the agent's output opens.
            if (row.state == StateWord.Done) host?.markSeen(row)
            onOpen(row.key.target.terminalId)
        },
        onRecovery = {
            when (screen.recovery) {
                Recovery.OpenSettings -> ctx.startActivity(graph.gate.settingsIntent())
                Recovery.ReviewKey -> onReviewKey()
                Recovery.InstallRelay -> setRelayDismissed(false)
                Recovery.Retry -> graph.retry()
                Recovery.SetUpKey -> onSetUpKey()
                null -> Unit
            }
        },
    )
}

@Composable
private fun OutputRoute(graph: AppGraph, terminalId: String?, tab: AgentTab, onTab: (AgentTab) -> Unit, onBack: () -> Unit, onCompose: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val settings by graph.settings.collectAsState()
    val profile by graph.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    if (terminalId == null || host == null) {
        // The connection is not up (or the app was restored without one): say so and offer the way back.
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Agent", onBack = onBack, compact = true)
            Column(Modifier.padding(PaddockTokens.spacing.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This agent is not available right now. Its machine is not connected.", style = PaddockTokens.type.body, color = PaddockTokens.colors.title)
                PaddockButton("Back to the herd", onBack, kind = ButtonKind.Ghost, icon = PaddockIcons.Back)
            }
        }
        return
    }
    SecureWindow(settings.protectSensitiveScreens)
    val feed = remember(host, terminalId) { host.outputFeed(terminalId) }
    val owner = LocalContext.current as? LifecycleOwner
    DisposableEffect(feed, owner) {
        feed.start()
        val observer = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> feed.setVisible(true); Lifecycle.Event.ON_PAUSE -> feed.setVisible(false); else -> Unit }
        }
        owner?.lifecycle?.addObserver(observer)
        if (owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) feed.setVisible(true)
        onDispose { owner?.lifecycle?.removeObserver(observer); feed.setVisible(false); feed.stop() }
    }
    val output by feed.state.collectAsState()
    val following by feed.following.collectAsState()
    val home by host.home.collectAsState()
    val now = rememberNow()
    val row: AgentRowModel? = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    // "claude · api › tab 2 · main · laptop": what it is, where, which session, which machine.
    val context = listOfNotNull(row?.agentKind, row?.context?.ifEmpty { null }, host.sessionName, profile?.name).joinToString(" · ")
    val header = AgentHeader(row?.title ?: "Agent", context, row?.state ?: StateWord.Unknown, row?.observedAtMillis, agentKind = row?.agentKind)
    val focus = focusView(host, terminalId)
    // The tap checks the gate again, as for the keys; the first time ever it asks what focus does.
    FocusGuard(settings.desktopFocusConfirmed, onConfirmed = graph::setDesktopFocusConfirmed, focus = { focus.key?.takeIf { focus.gate is OperationGate.Open }?.let { host.sends?.focus(it) } }) { requestFocus ->
        val manual = rememberManualInput(
            graph, host, terminalId, focus.gate, now, (output as? OutputState.Showing)?.lines?.map { it.text },
            onOpenTerminal = { onTab(AgentTab.Terminal) }, onFocus = requestFocus,
        )
        AgentOutput(
            header, output, following, now, tab, onTab, onBack, onUserScrolledUp = { feed.userScrolledUp() }, onResumeFollowing = { feed.resumeFollowing() },
            terminal = { TerminalRoute(graph, host, terminalId) },
            onCompose = if (host.sends != null) onCompose else null,
            manualInput = manual?.first, manualActions = manual?.second,
        )
    }
}

/** The desktop-focus button's key (in the epoch now installed: it has no screen-opened epoch to go stale against) and its gate. */
private class FocusView(val key: TerminalKey?, val gate: OperationGate?)

@Composable
private fun focusView(host: MonitoredHost, terminalId: String): FocusView {
    val installed by host.reconciler.installed.collectAsState()
    val freshness by host.freshness.collectAsState()
    val records by host.operationRecords.collectAsState()
    val target = TargetRef(host.profile.hostId, host.sessionName, terminalId)
    val epoch = installed?.epoch ?: return FocusView(null, FocusRules.gate(null, null, freshness == Freshness.Live, records, TerminalKey(target, 0L)))
    val key = TerminalKey(target, epoch)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    return FocusView(key, FocusRules.gate(agent, installed?.readAtMillis, freshness == Freshness.Live, records, key, currentEpoch = epoch))
}

/** What the agent screen and the composer share about Manual input for one terminal: the session, its key and its gate. */
private class ManualView(val session: io.github.tuthan.paddock.ops.ManualSession?, val key: TerminalKey?, val gate: OperationGate?)

@Composable
private fun manualView(graph: AppGraph, host: MonitoredHost, terminalId: String): ManualView {
    val session by graph.manualInput.current.collectAsState()
    val installed by host.reconciler.installed.collectAsState()
    val freshness by host.freshness.collectAsState()
    val records by host.operationRecords.collectAsState()
    val mine = session?.takeIf { it.terminalId == terminalId } ?: return ManualView(null, null, null)
    val key = TerminalKey(TargetRef(host.profile.hostId, host.sessionName, terminalId), mine.epoch)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    val gate = ManualInputRules.gate(agent, installed?.readAtMillis, mine.enteredAtMillis, freshness == Freshness.Live, records, key, currentEpoch = installed?.epoch)
    return ManualView(mine, key, gate)
}

/** Entering Manual input is the user's choice and starts with a read made after it: the keys stay off until that read is installed. */
private fun enterManual(graph: AppGraph, host: MonitoredHost, terminalId: String, epoch: Long?) {
    if (epoch == null) return
    graph.manualInput.enter(terminalId, System.currentTimeMillis(), epoch)
    host.refresh()
}

/** The agent screen's Manual input state and actions, or null when the host has no operations (the inert key strip stays). */
@Composable
private fun rememberManualInput(
    graph: AppGraph, host: MonitoredHost, terminalId: String, focusGate: OperationGate?, nowMillis: Long, outputLines: List<String>?,
    onOpenTerminal: () -> Unit, onFocus: () -> Unit,
): Pair<ManualInputUi, ManualInputActions>? {
    val sends = host.sends ?: return null
    val view = manualView(graph, host, terminalId)
    val installed by host.reconciler.installed.collectAsState()
    val outcomes by sends.outcomes.collectAsState()
    val running by sends.running.collectAsState()
    val rereads by sends.rereads.collectAsState()
    val records by host.operationRecords.collectAsState()
    val presenter = remember { OperationPresenter() }
    val target = TargetRef(host.profile.hostId, host.sessionName, terminalId)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    // The journal's own row, which survives a restart, is the unknown card; the send's transient card would only say it twice.
    val waiting = records.lastOrNull { it.sameTerminal(TerminalKey(target, 0L)) && it.awaitsReread }
    val outcome = outcomes[terminalId]?.takeUnless { it.result is OperationResult.Unknown || it.result is OperationResult.NeedsReread }
    val reread = rereads[terminalId]
    val rereadLines = (reread as? RereadOutcome.Done)?.let { done ->
        presenter.rereadLines(done.report, done.report.resolved.lastOrNull()?.let { PromptTextCheck.check(it, outputLines) } ?: TextCheck.NotKept)
    }.orEmpty()
    val ui = ManualInputUi(
        view.gate, terminalId in running, installed?.readAtMillis, outcomeLine(presenter, outcome, agent?.stateChangeSeq, nowMillis), focus = focusGate,
        unknown = waiting?.let { presenter.unknownText(it) }, rereadLines = rereadLines, rereadFailure = (reread as? RereadOutcome.Failed)?.let { presenter.rereadFailure(it) },
    )
    val actions = ManualInputActions(
        onEnter = { enterManual(graph, host, terminalId, installed?.epoch) },
        onLeave = { graph.manualInput.leave() },
        // The buttons are off unless the gate is open; the tap checks it again, so a key never goes out outside the mode or before its read.
        onKey = { kind -> view.key?.takeIf { view.gate is OperationGate.Open }?.let { sends.sendKey(it, kind) } },
        onDismissOutcome = { sends.dismiss(terminalId) },
        onOpenTerminal = onOpenTerminal, onFocus = onFocus,
        onReread = { installed?.epoch?.let { sends.reread(TerminalKey(target, it)) } },
        onDismissReread = { sends.dismissReread(terminalId) },
    )
    return ui to actions
}

/** A send's outcome as a line, with the "no progress observed" label once an accepted prompt has seen no state change for five seconds. */
private fun outcomeLine(presenter: OperationPresenter, outcome: SendOutcome?, currentSeq: Long?, nowMillis: Long): ResultLine? {
    outcome ?: return null
    val accepted = (outcome.result as? OperationResult.Acknowledged<*>)?.record
    return presenter.line(outcome.kind, outcome.result, noProgress = accepted != null && presenter.noProgress(accepted, currentSeq, nowMillis))
}

/**
 * The Terminal tab of one agent. The session lives while the tab is on screen: leaving the tab or the screen closes it
 * (which releases control), going to the background suspends it (also a release), and a rotation keeps it, because turning the
 * phone must not hand the terminal back.
 */
@Composable
private fun TerminalRoute(graph: AppGraph, host: io.github.tuthan.paddock.live.MonitoredHost, terminalId: String) {
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val session = remember(host, terminalId, attempt) { graph.terminals.acquire(Triple(host, terminalId, attempt)) { host.terminalSession(terminalId) } }
    val view by session.view.collectAsState()
    val ctx = LocalContext.current
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity }
    DisposableEffect(session) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                // A rotation stops and restarts the activity too; only a real trip to the background releases the terminal.
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations != true) session.suspend()
                Lifecycle.Event.ON_START -> session.resume()
                else -> Unit
            }
        }
        val owner = ctx as? LifecycleOwner
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            graph.terminals.release(session, recreating = activity?.isChangingConfigurations == true)
        }
    }
    val actions = remember(session) {
        TerminalActions(
            onViewport = { cols, rows -> if (graph.terminals.firstUse(session)) session.open(cols, rows) else session.setViewport(cols, rows) },
            onRequestControl = session::requestControl, onTakeOver = session::takeOver, onInstallHelper = session::installHelper,
            onDismissNotice = session::dismissNotice, onRelease = session::release,
            onResizeToFit = { cols, rows -> session.resizeToFit(cols, rows) }, onKey = { session.send(it) },
            onRetry = { attempt++ },
        )
    }
    TerminalTab(view, actions)
}

/** The draft survives a rotation and a process restore with the rest of the root's saved state; a terminal id is never empty. */
private val PromptDraftSaver = listSaver<PromptDraft, Any>(
    save = { d -> listOf<Any>(d.terminalId.orEmpty(), d.text) + d.acceptedThrough.flatMap { (id, row) -> listOf(id, row) } },
    restore = { saved ->
        PromptDraft(
            terminalId = (saved[0] as String).ifEmpty { null }, text = saved[1] as String,
            acceptedThrough = saved.drop(2).chunked(2).associate { (id, row) -> id as String to row as Long },
        )
    },
)

/**
 * The prompt composer for one agent. Everything it shows is derived from the host's installed read, the journal and the
 * send controller; the text and the epoch the screen opened in survive a rotation. A send runs in the host's scope, so
 * turning the phone cannot cancel it, and a refused one keeps the text.
 */
@Composable
private fun ComposeRoute(
    graph: AppGraph, terminalId: String?, draft: PromptDraft, onDraft: ((PromptDraft) -> PromptDraft) -> Unit,
    onBack: () -> Unit, onOpenTerminal: () -> Unit, onEditSnippets: () -> Unit,
) {
    val view by graph.hostUi.view.collectAsState()
    val settings by graph.settings.collectAsState()
    val profile by graph.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val sends = host?.sends
    if (terminalId == null || host == null || sends == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Prompt", onBack = onBack, compact = true)
            Column(Modifier.padding(PaddockTokens.spacing.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This agent is not available right now. Its machine is not connected.", style = PaddockTokens.type.body, color = PaddockTokens.colors.title)
                PaddockButton("Back", onBack, kind = ButtonKind.Ghost, icon = PaddockIcons.Back)
            }
        }
        return
    }
    SecureWindow(settings.protectSensitiveScreens)
    val installed by host.reconciler.installed.collectAsState()
    val freshness by host.freshness.collectAsState()
    val records by host.operationRecords.collectAsState()
    val outcomes by sends.outcomes.collectAsState()
    val running by sends.running.collectAsState()
    val rereads by sends.rereads.collectAsState()
    val home by host.home.collectAsState()
    val snippets by graph.snippets.collectAsState()
    val now = rememberNow()
    val presenter = remember { OperationPresenter() }
    val text = draft.textFor(terminalId)
    val setText = { value: String -> onDraft { it.typed(terminalId, value) } }
    var openedAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var openedEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    val liveEpoch = installed?.epoch
    LaunchedEffect(liveEpoch) { if (openedEpoch == null && liveEpoch != null) openedEpoch = liveEpoch }
    // A read made after the composer opened is what first enables Send.
    LaunchedEffect(Unit) { host.refresh() }

    val row = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    val key = TerminalKey(TargetRef(host.profile.hostId, host.sessionName, terminalId), openedEpoch ?: liveEpoch ?: 0L)
    val gate = ComposerRules.gate(agent, installed?.readAtMillis, openedAt, freshness == Freshness.Live, records, key, text, currentEpoch = liveEpoch)
    val outcome = outcomes[terminalId]
    // The text goes with the prompt: cleared only when herdr accepted it, kept for a refusal, a failure or an unknown outcome.
    // The draft remembers which accepted row it has already gone with, so an old accepted outcome shown again clears nothing.
    LaunchedEffect(outcome) {
        val result = outcome?.result
        if (outcome != null && outcome.kind == OperationKind.Prompt && result is OperationResult.Acknowledged<*>) onDraft { it.accepted(terminalId, result.record.id) }
    }

    val context = listOfNotNull(row?.agentKind, row?.context?.ifEmpty { null }, host.sessionName, profile?.name).joinToString(" · ")
    val header = AgentHeader(row?.title ?: "Agent", context, row?.state ?: StateWord.Unknown, row?.observedAtMillis, agentKind = row?.agentKind)
    val block = (gate as? SendGate.Closed)?.block
    val stale = block == SendBlock.Stale
    val manual = manualView(graph, host, terminalId)
    val keyGate = manual.gate
    // A re-read looks at the agent as it is now, so it takes the epoch now installed, not the one this screen opened in.
    val reread = { liveEpoch?.let { sends.reread(TerminalKey(key.target, it)) }; Unit }
    // The composer shows only the first line of a re-read (when and what herdr reports); whether the text appears is on the agent screen.
    val rereadLines = when (val r = rereads[terminalId]) {
        is RereadOutcome.Done -> presenter.rereadLines(r.report, TextCheck.NoOutput).take(1)
        is RereadOutcome.Failed -> listOf(presenter.rereadFailure(r))
        null -> emptyList()
    }
    Composer(
        ComposerUi(header, gate, sending = terminalId in running, outcome = outcomeLine(presenter, outcome, agent?.stateChangeSeq, now), snippets = snippets, rereadLines = rereadLines),
        now, text, setText, onSnippet = { setText(Snippets.insert(text, it)) },
        onSend = { sends.prompt(key, text, settings.keepPromptText) },
        onBack = onBack, onEditSnippets = onEditSnippets, onOpenTerminal = onOpenTerminal, onDismissOutcome = { sends.dismiss(terminalId) },
        onReread = reread, onDismissReread = { sends.dismissReread(terminalId) },
        gateActionLabel = if (stale || block == SendBlock.NeedsReread) "Re-read" else null,
        onGateAction = { if (stale) { openedEpoch = liveEpoch; openedAt = System.currentTimeMillis(); host.refresh() } else reread() },
        // Esc is live only inside Manual input, and under the keys' own gate: it says why when the mode is on but the keys are not open.
        escEnabled = keyGate is OperationGate.Open && terminalId !in running,
        escNote = if (keyGate is OperationGate.Closed) "Esc is off. ${keyGate.sentence}" else ESC_OFF_NOTE,
        onEsc = { manual.key?.takeIf { keyGate is OperationGate.Open }?.let { sends.sendKey(it, OperationKind.Esc) } },
    )
}

@Composable
private fun SnippetsRoute(graph: AppGraph, onBack: () -> Unit) {
    val snippets by graph.snippets.collectAsState()
    SnippetEditor(snippets, onChange = { graph.setSnippets(it) }, onBack = onBack)
}

@Composable
private fun ActivityRoute(graph: AppGraph, onOpenAgent: (terminalId: String) -> Unit) {
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val now = rememberNow()
    // Re-read each second: the ledger is small and in memory. An unknown row offers Re-read only for an agent the connected host lists now.
    val presenter = remember(profile, host) {
        ActivityPresenter(
            java.time.ZoneId.systemDefault(), java.util.Locale.getDefault(), hostName = { profile?.name ?: it },
            titleOf = { _, _, tid -> graph.hostUi.view.value.lastHome?.rows?.firstOrNull { it.key.target.terminalId == tid }?.title },
            canReread = { h, s, tid -> host?.sends != null && h == host.profile.hostId.value && s == host.sessionName && host.home.value?.rows?.any { it.key.target.terminalId == tid } == true },
        )
    }
    val sections = remember(filter, now / 1_000) { presenter.present(Activity.build(graph.ledger.observations(), graph.ledger.actions(), filter, graph.journal.records.value), now) }
    // Re-read, then open the agent: its screen is where the result is shown. Never a resend from here.
    val onReread: (Long) -> Unit = { id ->
        val record = graph.journal.get(id)
        val epoch = host?.reconciler?.installed?.value?.epoch
        if (record != null && host != null && epoch != null) {
            host.sends?.reread(TerminalKey(TargetRef(host.profile.hostId, record.session, record.terminalId), epoch))
            onOpenAgent(record.terminalId)
        }
    }
    ActivityLog(sections, filter, { filter = it }, onReread = if (host?.sends != null) onReread else null)
}

@Composable
private fun SettingsRoute(graph: AppGraph, onBack: () -> Unit, onAddMachine: () -> Unit, onEditSnippets: () -> Unit) {
    val snippets by graph.snippets.collectAsState()
    val settings by graph.settings.collectAsState()
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // The grant can change in system settings while Paddock is in the background: read it again on every return.
    val resumes = rememberResumes()
    val access = remember(resumes) {
        when {
            !graph.gate.lanAccessApplies() -> LocalAccess.NotRequired
            graph.gate.lanAccessMissing() -> LocalAccess.Denied
            else -> LocalAccess.Granted
        }
    }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown" }
    val machine = profile?.let { p -> MachineSummary(p.name, "${p.user}@${p.host}:${p.port}", p.session) }
    Settings(
        SettingsState(settings.protectSensitiveScreens, access, version, machine = machine, herdrVersion = view.herdrVersion, keepPromptText = settings.keepPromptText, snippetCount = snippets.size),
        onProtectSensitive = { scope.launch { graph.setProtectSensitive(it) } },
        onOpenSystemSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = onBack,
        onAddMachine = onAddMachine,
        onKeepPromptText = { graph.setKeepPromptText(it) },
        onEditSnippets = onEditSnippets,
    )
}

@Composable
private fun AddMachineRoute(graph: AppGraph, canGoBack: Boolean, editing: Boolean, onBack: () -> Unit, onAdded: () -> Unit) {
    val ctx = LocalContext.current
    val watched by graph.profile.collectAsState()
    // Setting up the key keeps the machine (its id, so its pinned host key and history) while it is the same host and port.
    val fixing = watched.takeIf { editing }
    val scope = rememberCoroutineScope()
    var keyTick by remember { mutableIntStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AddMachineInput?>(null) }

    fun finish(input: AddMachineInput) {
        scope.launch {
            val existing = graph.profiles.list().map { it.id }.toSet()
            val made = AddMachineForm.profile(input, existing - setOfNotNull(fixing?.id)) ?: return@launch
            val same = fixing != null && made.host == fixing.host && made.port == fixing.port
            val profile = if (same) made.copy(id = fixing.id, name = fixing.name) else made
            graph.addMachine(profile)
            // The same profile is resumed, not rebuilt: ask for the reconnect that picks up the new key.
            if (same) graph.retry()
            onAdded()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        val input = pending
        pending = null
        // A refusal is not the end: the screen keeps the recovery row, and a VPN address still works without the grant.
        if (granted && input != null) finish(input)
    }
    // Keystore reads and key generation can take a while on some phones: off the main thread. An unreadable key (lost
    // Keystore entry, corrupt store) reads as "no key" here; connecting reports it with its own recovery.
    val key by produceState<Pair<String, io.github.tuthan.paddock.ssh.KeyBacking>?>(null, keyTick) {
        value = withContext(Dispatchers.Default) {
            runCatching { if (graph.phoneKey.exists()) graph.phoneKey.publicLine("paddock@phone") to graph.phoneKey.info().backing else null }.getOrNull()
        }
    }
    var importedTick by remember { mutableIntStateOf(0) }
    val imported by produceState<ImportedKeyInfo?>(null, importedTick) { value = runCatching { graph.importedKey() }.getOrNull() }
    var importing by rememberSaveable { mutableStateOf(false) }
    // The form is kept by the holder while the import screen is up, so typed values are still there on return.
    val holder = rememberSaveableStateHolder()
    if (importing) {
        // Back from the import screen returns to the form, not past it.
        BackHandler { importing = false }
        ImportKeyRoute(graph, onDone = { importedTick++; importing = false }, onBack = { importing = false })
        return
    }
    val state = AddMachineState(
        route = { host -> AddMachineForm.route(host, graph.gate.decide(AddMachineForm.normalizeHost(host))) },
        resolveRoute = { host ->
            val h = AddMachineForm.normalizeHost(host)
            val (endpoint, grant) = graph.gate.resolvedRoute(h)
            AddMachineForm.route(host, grant, endpoint)
        },
        publicKeyLine = key?.first,
        keyBacking = key?.second,
        importedKeyId = imported?.id,
        importedKeySummary = imported?.let { "${it.keyType} · ${it.fingerprint}" },
        permissionDenied = denied,
    )
    // Its own saved form: setting up a key starts from the watched machine's values, not from a half-typed new one.
    holder.SaveableStateProvider(fixing?.let { "key-${it.id}" } ?: "add-machine") {
        AddMachine(
            state,
            onConnect = { input ->
                // Decided from where the name resolves (a LAN hostname needs the grant too); the lookup is bounded and off the main thread.
                scope.launch { if (graph.gate.needsRequest(AddMachineForm.normalizeHost(input.host))) { pending = input; permission.launch(LocalNetworkPolicy.PERMISSION) } else finish(input) }
            },
            onGenerateKey = { scope.launch { graph.createPhoneKey(); keyTick++ } },
            onCopyPublicKey = { line -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock public key", line)) },
            onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
            onBack = { if (canGoBack) onBack() },
            onImportKey = { importing = true },
            initial = fixing?.let { AddMachineInput(it.host, it.port.toString(), it.user, it.key, it.importedKeyId, it.session ?: "") } ?: AddMachineInput(),
            title = if (fixing != null) "Set up the key" else "Add a machine",
            intro = if (fixing != null) SET_UP_KEY_INTRO else ADD_MACHINE_INTRO,
        )
    }
}

private const val MAX_KEY_FILE_BYTES = 64 * 1024

@Composable
private fun ImportKeyRoute(graph: AppGraph, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.collectAsState()
    SecureWindow(settings.protectSensitiveScreens)
    // Held in plain remember state on purpose: key text must not reach saved instance state.
    var picked by remember { mutableStateOf<PickedKeyFile?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportCheck?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            pickError = null
            val file = withContext(Dispatchers.IO) { readKeyFile(ctx, uri) }
            if (file == null) pickError = "That file could not be read, or it is too large to be a private key." else picked = file
            result = null
        }
    }
    ImportKey(
        picked, pickError, busy, result,
        onChooseFile = { chooser.launch(arrayOf("*/*")) },
        onClearFile = { picked = null; result = null },
        onImport = { pem, pass ->
            busy = true
            val pasted = picked == null
            scope.launch {
                val check = graph.importKey(pem, pass)
                busy = false
                result = check
                if (check is ImportCheck.Ready) {
                    // A pasted key is still on the clipboard, where any app could read it: take it off.
                    if (pasted) clearClipboardIfKey(ctx)
                    onDone()
                }
            }
        },
        onBack = onBack,
    )
}

/** Reads at most [MAX_KEY_FILE_BYTES]; a larger file is refused rather than truncated. */
private fun readKeyFile(ctx: Context, uri: android.net.Uri): PickedKeyFile? = runCatching {
    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "key file"
    // InputStream.readNBytes is API 33 and minSdk is 26: a bounded loop, reading one byte past the limit to detect a larger file.
    val buf = ByteArray(MAX_KEY_FILE_BYTES + 1)
    try {
        val n = ctx.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            total
        } ?: return null
        if (n > MAX_KEY_FILE_BYTES) null else PickedKeyFile(name, String(buf, 0, n, Charsets.UTF_8))
    } finally {
        buf.fill(0)
    }
}.getOrNull()

/** Clears the clipboard when it holds a private key (the one just pasted). Anything else on it is left alone. */
private fun clearClipboardIfKey(ctx: Context) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull() ?: return
    if ("PRIVATE KEY-----" !in text) return
    if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
}

@Composable
private fun HostKeyDialogs(graph: AppGraph, reviewKey: Boolean, dismissReview: () -> Unit) {
    val request by graph.broker.firstTrust.collectAsState()
    request?.let { r ->
        // Answers name the request they answer: a tap on a dialog whose connect was cancelled answers nothing else.
        FingerprintDialog(HostKeyPrompts.firstTrust(r.endpoint, r.presented), onTrust = { graph.broker.answerFirstTrust(r.id, true) }, onCancel = { graph.broker.answerFirstTrust(r.id, false) })
    }
    val profile by graph.profile.collectAsState()
    val changed by graph.broker.changed.collectAsState()
    val c = profile?.let { changed[it.id] }
    val scope = rememberCoroutineScope()
    if (reviewKey && c != null && profile != null) {
        val p = profile!!
        FingerprintDialog(
            HostKeyPrompts.changed(c.endpoint, HostKeyState.Changed(c.pin, c.presented)),
            onTrust = { scope.launch { graph.replaceKey(p); dismissReview() } },
            onCancel = dismissReview,
        )
    }
}
