package io.github.tuthan.paddock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.tuthan.paddock.alerts.AlertCopy
import io.github.tuthan.paddock.alerts.AlertEvent
import io.github.tuthan.paddock.alerts.AlertOutcome
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.ImportedKeyInfo
import io.github.tuthan.paddock.answers.AnswerController
import io.github.tuthan.paddock.answers.AnswerGate
import io.github.tuthan.paddock.answers.AnswerSetupText
import io.github.tuthan.paddock.answers.Behavior
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.answers.DecisionModel
import io.github.tuthan.paddock.answers.DecisionPresenter
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_INTRO_IMPORTED
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.ui.screens.DecisionActions
import io.github.tuthan.paddock.ui.screens.DecisionSheet
import io.github.tuthan.paddock.ui.screens.GateNoticeBar
import io.github.tuthan.paddock.ui.screens.GuardedAnswers
import io.github.tuthan.paddock.ui.screens.GuardedAnswersUi
import io.github.tuthan.paddock.ui.screens.GuardedCopied
import io.github.tuthan.paddock.ui.screens.GuardedHostState
import io.github.tuthan.paddock.ui.screens.ProCardState
import io.github.tuthan.paddock.ui.screens.ProGateSheet
import io.github.tuthan.paddock.ui.screens.TipOption
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProView
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
import io.github.tuthan.paddock.discovery.FinderPhase
import io.github.tuthan.paddock.discovery.FinderText
import io.github.tuthan.paddock.ui.screens.FindOnNetwork
import io.github.tuthan.paddock.ui.screens.ScanLink
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.PairingEvent
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.hostprofile.PairingLinks
import io.github.tuthan.paddock.hostprofile.PairingResult
import io.github.tuthan.paddock.ledger.ActivityFilter
import io.github.tuthan.paddock.ledger.ActivityPresenter
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.live.PreviewState
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.commandShareIntent
import io.github.tuthan.paddock.ui.components.FingerprintDialog
import io.github.tuthan.paddock.ui.components.LocalAgentGlyphs
import io.github.tuthan.paddock.ui.components.SecureWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import io.github.tuthan.paddock.host.PairingLinkSaver
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.host.ConnectOutcome
import io.github.tuthan.paddock.host.ConnectOutcomes
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.DownReasonText
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_INTRO
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.screens.PairWithDesktop
import io.github.tuthan.paddock.ui.screens.PairingTarget
import io.github.tuthan.paddock.pairing.PairingState
import io.github.tuthan.paddock.pairing.PairingText
import io.github.tuthan.paddock.pairing.pendingOrNull
import io.github.tuthan.paddock.live.SpacesState
import io.github.tuthan.paddock.ops.CardAction
import io.github.tuthan.paddock.ops.SagaCard
import io.github.tuthan.paddock.ops.SagaRecord
import io.github.tuthan.paddock.ops.SagaRequest
import io.github.tuthan.paddock.ops.SagaState
import io.github.tuthan.paddock.ui.components.HostHealth
import io.github.tuthan.paddock.ui.screens.RenameDialog
import io.github.tuthan.paddock.ui.screens.RowActionsDialog
import io.github.tuthan.paddock.ui.screens.SagaProgress
import io.github.tuthan.paddock.ui.screens.SpacesActions
import io.github.tuthan.paddock.ui.screens.SpacesScreen
import io.github.tuthan.paddock.ui.screens.SpacesScreenState
import io.github.tuthan.paddock.ui.screens.StartAvailability
import io.github.tuthan.paddock.ui.screens.WorkspaceChoice
import io.github.tuthan.paddock.ui.screens.ActivityLog
import io.github.tuthan.paddock.ui.screens.ADD_MACHINE_INTRO
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.SET_UP_KEY_INTRO
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.outputFeedVisible
import io.github.tuthan.paddock.ui.screens.ESC_ENTERS_MANUAL_NOTE
import io.github.tuthan.paddock.ui.screens.FocusGuard
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.ManualInputActions
import io.github.tuthan.paddock.ui.screens.ManualInputUi
import io.github.tuthan.paddock.ui.screens.RelayInstall
import io.github.tuthan.paddock.ui.screens.AlertRelay
import io.github.tuthan.paddock.ui.screens.AlertRelayHostState
import io.github.tuthan.paddock.ui.screens.AlertRelayUi
import io.github.tuthan.paddock.ui.screens.PushDistributorUi
import io.github.tuthan.paddock.ui.screens.PushRegisteredUi
import io.github.tuthan.paddock.ui.screens.PushUi
import io.github.tuthan.paddock.ui.screens.AlertsState
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.screens.TerminalActions
import io.github.tuthan.paddock.ui.screens.TerminalTab
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.NavItem
import io.github.tuthan.paddock.ui.components.NoticeBar
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.PaddockNavBar
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.screens.MachineSummary
import io.github.tuthan.paddock.ui.screens.clockLabel
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { Welcome, Home, Output, Compose, Snippets, Activity, Spaces, Settings, AddMachine, AlertRelay, Decision, GuardedAnswers }

private val NAV = listOf(NavItem("Herd", PaddockIcons.Herd), NavItem("Spaces", PaddockIcons.Spaces), NavItem("Activity", PaddockIcons.Activity))

/**
 * The app's one navigation host. Screens are stateless; this connects them to the graph. The route, where Add machine
 * was opened from, and the open terminal survive rotation (`rememberSaveable`); everything else is read from the graph.
 * Home, Spaces and Activity share the bottom bar; Settings is the gear on Home, and Add machine lives in Settings.
 */
@Composable
fun PaddockRoot(graph: AppGraph, modifier: Modifier = Modifier) {
    // One provider for every agent tile in the app, so Settings > Agent icons redraws the herd at once without threading it through each screen.
    val agentGlyphs by remember(graph) { graph.settings.map { it.agentGlyphs }.distinctUntilChanged() }.collectAsState(true)
    CompositionLocalProvider(LocalAgentGlyphs provides agentGlyphs) { PaddockRootContent(graph, modifier) }
}

@Composable
private fun PaddockRootContent(graph: AppGraph, modifier: Modifier) {
    val boot by graph.boot.collectAsState()
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var addFrom by rememberSaveable { mutableStateOf(Route.Home) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var outputTab by rememberSaveable { mutableStateOf(AgentTab.Output) }
    var snippetsFrom by rememberSaveable { mutableStateOf(Route.Settings) }
    var guardedFrom by rememberSaveable { mutableStateOf(Route.Settings) }
    var relayDismissed by rememberSaveable { mutableStateOf(false) }
    var reviewKey by rememberSaveable { mutableStateOf(false) }
    // The composer's text lives here, above the route: leaving the composer (Edit snippets, Back) or a reconnect that swaps it for "not available" must not lose it.
    var draft by rememberSaveable(stateSaver = PromptDraftSaver) { mutableStateOf(PromptDraft()) }
    // Add machine opened to set up the watched machine's key (its key could not be read), not to add another.
    var editing by rememberSaveable { mutableStateOf(false) }
    // Opened from Home's "Show the command": the intro leads with how to authorize this phone.
    var authorizeIntro by rememberSaveable { mutableStateOf(false) }
    // The first screen of a phone with no machine is Welcome; any way in sets this and moves on to Add machine.
    var welcomeChoice by rememberSaveable { mutableStateOf(false) }
    // Find on this network opened from Welcome (from Add machine it is opened inside the form's own route, which keeps what was typed).
    var welcomeFinder by rememberSaveable { mutableStateOf(false) }
    // The scanner opened from Welcome (from Add machine it is opened inside the form's route, like the finder).
    var welcomeScan by rememberSaveable { mutableStateOf(false) }
    // A machine the finder found, waiting for the form to take it. Taken once, then cleared.
    var found by remember { mutableStateOf<AddMachineInput?>(null) }
    // A Connect that has not finished. Above the screens: saving the first machine ends "no machines" and must not take the form away mid-attempt.
    var attempt by rememberSaveable(stateSaver = ConnectAttempt.Saver) { mutableStateOf<ConnectAttempt?>(null) }

    // What the last alert tap found ("State changed since the alert", "No longer observed"). It stays until the user dismisses it.
    var alertNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val alertEvent by graph.alerts.event.collectAsState()
    LaunchedEffect(alertEvent, boot) {
        val e = alertEvent ?: return@LaunchedEffect
        if (boot == Boot.Loading) return@LaunchedEffect
        when (e) {
            is AlertEvent.Invalid -> { alertNotice = AlertCopy.INVALID_LINK; graph.alerts.consume(e) }
            is AlertEvent.MachineWoke -> {
                // A push that named no terminal: the herd of that machine, read fresh, and what that read says in counts.
                alertNotice = AlertCopy.OPENING
                route = Route.Home
                val r = graph.resolveMachine(e)
                alertNotice = when {
                    r.unknownMachine -> AlertCopy.UNKNOWN_MACHINE
                    r.outcome == null -> AlertCopy.unreachable(r.machine)
                    else -> AlertCopy.machineNotice(r.outcome, r.machine)
                }
                graph.alerts.consume(e)
            }
            is AlertEvent.Arrived -> {
                // The herd shows while the connection and the read come up; the alert is resolved against that read, never a cached one.
                alertNotice = AlertCopy.OPENING
                route = Route.Home
                val resolved = graph.resolveAlert(e)
                val outcome = resolved.outcome
                if (outcome == null) alertNotice = AlertCopy.unreachable(resolved.machine)
                else {
                    alertNotice = AlertCopy.notice(outcome, e.hint, System.currentTimeMillis(), resolved.machine)
                    when (outcome) {
                        is AlertOutcome.Current -> {
                            terminalId = outcome.terminalId
                            // A blocked agent opens its Terminal tab, observing (the Review prompt route); answering stays a deliberate Request control.
                            outputTab = if (outcome.state == AlertState.Blocked) AgentTab.Terminal else AgentTab.Output
                            // Opening a Done from its alert is the user acknowledging it, as tapping its row is.
                            if (outcome.state == AlertState.Done) resolved.row?.takeIf { it.state == StateWord.Done }?.let { resolved.host?.markSeen(it) }
                            route = Route.Output
                        }
                        is AlertOutcome.Changed -> { terminalId = outcome.terminalId; outputTab = AgentTab.Output; route = Route.Output }
                        is AlertOutcome.NoLongerObserved -> route = Route.Home
                    }
                }
                graph.alerts.consume(e)
            }
        }
    }

    // A pairing link (a tap, or Paste a pairing link): Add machine opens pre-filled. Nothing connects and nothing is trusted from here.
    var pairing by rememberSaveable(stateSaver = PairingLinkSaver) { mutableStateOf<PairingLink?>(null) }
    val pairingEvent by graph.pairing.event.collectAsState()
    LaunchedEffect(pairingEvent, boot) {
        val e = pairingEvent ?: return@LaunchedEffect
        if (boot == Boot.Loading) return@LaunchedEffect
        when (e) {
            is PairingEvent.Invalid -> alertNotice = PairingCopy.invalid(e.reason)
            is PairingEvent.Link -> {
                alertNotice = null
                pairing = e.link; editing = false
                if (route != Route.AddMachine) { addFrom = if (boot == Boot.Ready && route != Route.Output) route else Route.Home; route = Route.AddMachine }
            }
        }
        graph.pairing.consume(e)
    }

    val effective = when {
        attempt != null -> Route.AddMachine
        boot == Boot.NoMachines && pairing == null && !welcomeChoice -> Route.Welcome
        boot == Boot.NoMachines -> Route.AddMachine
        else -> route
    }
    // Follows the connection from Connect until it is live: a live or set-up-needing host ends the attempt, a failure stays on the form with its fix.
    // It keeps following after a failure (a retry that succeeds, or a host key trusted late, must still end the attempt) and after the wait ran out.
    val attemptKey = attempt?.let { it.profileId to it.connecting }
    LaunchedEffect(attemptKey) {
        val (id, connecting) = attemptKey ?: return@LaunchedEffect
        coroutineScope {
            // The time is counted only while no question is on screen: comparing a fingerprint with the desktop's takes as long as the user needs.
            if (connecting) launch {
                graph.broker.firstTrust.map { it != null }.distinctUntilChanged().collectLatest { asking ->
                    if (!asking) {
                        delay(CONNECT_WAIT_MILLIS)
                        if (attempt?.profileId == id && attempt?.connecting == true) attempt = ConnectAttempt(id, ConnectOutcomes.STILL_WAITING, ConnectFix.Retry)
                    }
                }
            }
            graph.hostUi.view.map { it.phase }.distinctUntilChanged().collect { phase ->
                when (val outcome = ConnectOutcomes.outcome(phase, id, graph.hostUi.phaseBeforeAttempt)) {
                    ConnectOutcome.Connected -> {
                        attempt = null; editing = false; authorizeIntro = false; pairing = null; route = Route.Home; relayDismissed = false
                        this@coroutineScope.cancel()
                    }
                    // Only a form that is still waiting (or only said it was) takes a failure: a sentence already shown stays put while the owner retries.
                    is ConnectOutcome.Failed -> attempt?.takeIf { it.profileId == id && (it.connecting || it.error == ConnectOutcomes.STILL_WAITING) }
                        ?.let { attempt = ConnectAttempt(id, DownReasonText.sentence(outcome.reason), DownReasonText.fix(outcome.reason)) }
                    null -> {}
                }
            }
        }
    }
    // Manual input belongs to the agent screen and the composer opened from it; leaving them, or opening another agent, ends it.
    val onAgent = (effective == Route.Output && outputTab == AgentTab.Output) || effective == Route.Compose || (effective == Route.Snippets && snippetsFrom == Route.Compose)
    LaunchedEffect(onAgent, terminalId) { if (onAgent) graph.manualInput.leaveUnless(terminalId) else graph.manualInput.leave() }
    val backTo = when (effective) { Route.AddMachine -> addFrom; Route.Compose -> Route.Output; Route.Snippets -> snippetsFrom; Route.AlertRelay -> Route.Settings; Route.Decision -> Route.Output; Route.GuardedAnswers -> guardedFrom; else -> Route.Home }
    // Registered before the screens', so a screen's own back handling (the import screen's) is asked first.
    BackHandler(enabled = effective != Route.Home && boot == Boot.Ready) { attempt = null; authorizeIntro = false; route = backTo }
    // With no machine yet, Back from the form returns to Welcome instead of leaving the app.
    BackHandler(enabled = boot == Boot.NoMachines && attempt == null && (welcomeChoice || pairing != null)) { welcomeChoice = false; pairing = null }
    BackHandler(enabled = welcomeFinder && effective == Route.Welcome) { welcomeFinder = false }
    BackHandler(enabled = welcomeScan && effective == Route.Welcome) { welcomeScan = false }
    ProGateHost(graph, pendingAnswerOnScreen = effective == Route.Decision || effective == Route.GuardedAnswers)
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
    alertNotice?.let { NoticeBar(it, onDismiss = { alertNotice = null }, modifier = Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp)) }
    // Why a tap on a Pro control did nothing (the gate never opens over a busy screen); the graph clears it after a few seconds.
    val gateNotice by graph.gateNotice.collectAsState()
    gateNotice?.let { GateNoticeBar(it, onDismiss = graph::dismissGateNotice, modifier = Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp)) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (boot) {
            Boot.Loading -> Text("Paddock", style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title, modifier = Modifier.padding(PaddockTokens.spacing.gutter))
            else -> when (effective) {
                Route.Welcome -> if (welcomeFinder) FindRoute(
                    graph, onPick = { found = it; welcomeFinder = false; welcomeChoice = true }, onBack = { welcomeFinder = false },
                ) else if (welcomeScan) ScanRoute(
                    graph, onLink = { pairing = it; welcomeScan = false; welcomeChoice = true }, onBack = { welcomeScan = false },
                ) else WelcomeRoute(
                    onEnterAddress = { welcomeChoice = true }, onFind = { welcomeFinder = true }, onScan = { welcomeScan = true },
                    onPairing = { pairing = it; welcomeChoice = true },
                )
                Route.Home, Route.Spaces, Route.Activity -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (effective == Route.Spaces) SpacesRoute(
                            graph, onNotice = { alertNotice = it },
                            onOpenAgent = { terminalId = it; outputTab = AgentTab.Output; route = Route.Output },
                        ) else if (effective == Route.Home) HomeRoute(
                            graph, relayDismissed, { relayDismissed = it }, { reviewKey = true },
                            onOpen = { terminalId = it; outputTab = AgentTab.Output; route = Route.Output },
                            // Review prompt goes to the agent's Terminal tab, observing: answering is a deliberate Request control from there.
                            onReviewPrompt = { terminalId = it; outputTab = AgentTab.Terminal; route = Route.Output },
                            onSettings = { route = Route.Settings },
                            onSetUpKey = { editing = true; authorizeIntro = false; pairing = null; addFrom = Route.Home; route = Route.AddMachine },
                            onShowCommand = { editing = true; authorizeIntro = true; pairing = null; addFrom = Route.Home; route = Route.AddMachine },
                            onNotice = { alertNotice = it },
                        ) else ActivityRoute(graph, onOpenAgent = { terminalId = it; outputTab = AgentTab.Output; route = Route.Output })
                    }
                    PaddockNavBar(NAV, when (effective) { Route.Home -> 0; Route.Spaces -> 1; else -> 2 }, { route = when (it) { 0 -> Route.Home; 1 -> Route.Spaces; else -> Route.Activity } })
                }
                Route.Output -> OutputRoute(graph, terminalId, outputTab, { outputTab = it }, onBack = { route = Route.Home }, onCompose = { route = Route.Compose }, onDecision = { route = Route.Decision })
                Route.Decision -> DecisionRoute(
                    graph, terminalId, onBack = { route = Route.Output },
                    onOpenTerminal = { outputTab = AgentTab.Terminal; route = Route.Output },
                    // Locked, the sheet does not offer Set up at all (decisionSetUp): the gate may not open over a request, so a button here would be a dead one.
                    // The check stays for the moment Pro goes away between the frame and the tap; the next frame already shows the locked sheet.
                    onSetUp = { if (graph.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true)) { guardedFrom = Route.Decision; route = Route.GuardedAnswers } },
                )
                Route.Compose -> ComposeRoute(
                    graph, terminalId, draft, { change -> draft = change(draft) }, onBack = { route = Route.Output },
                    onOpenTerminal = { outputTab = AgentTab.Terminal; route = Route.Output },
                    onEditSnippets = { snippetsFrom = Route.Compose; route = Route.Snippets },
                )
                Route.Snippets -> SnippetsRoute(graph, onBack = { route = snippetsFrom })
                Route.Settings -> SettingsRoute(
                    graph, onBack = { route = Route.Home }, onAddMachine = { editing = false; pairing = null; addFrom = Route.Settings; route = Route.AddMachine },
                    onEditSnippets = { snippetsFrom = Route.Settings; route = Route.Snippets },
                    onAlertRelay = { route = Route.AlertRelay },
                    onGuardedAnswers = { if (graph.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = false)) { guardedFrom = Route.Settings; route = Route.GuardedAnswers } },
                )
                Route.AlertRelay -> AlertRelayRoute(graph, onBack = { route = Route.Settings })
                Route.GuardedAnswers -> GuardedAnswersRoute(graph, onBack = { route = guardedFrom })
                Route.AddMachine -> AddMachineRoute(
                    graph, editing = editing && boot == Boot.Ready, pairing = pairing,
                    // With no machine the header arrow is the system Back: it returns to Welcome.
                    onBack = { attempt = null; editing = false; authorizeIntro = false; pairing = null; if (boot == Boot.Ready) route = backTo else welcomeChoice = false },
                    attempt = attempt, onAttempt = { attempt = it }, authorizeIntro = authorizeIntro,
                    // A link or a pick replaces the form's machine: an error that belonged to the one before is not this one's.
                    onPairing = { attempt = null; pairing = it }, onNotice = { alertNotice = it },
                    picked = found, onPicked = { found = it },
                )
            }
        }
        HostKeyDialogs(graph, reviewKey) { reviewKey = false }
    }
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
    onOpen: (terminalId: String) -> Unit, onReviewPrompt: (terminalId: String) -> Unit, onSettings: () -> Unit, onSetUpKey: () -> Unit,
    onShowCommand: () -> Unit, onNotice: (String) -> Unit = {},
) {
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val now = rememberNow()
    val name = profile?.name ?: "this machine"
    val wakeFacts by graph.wakeFacts.collectAsState()
    // Read again when the connection changes and on every return: the phone may have joined or left a network meanwhile.
    val homeResumes = rememberResumes()
    val wakeReady = remember(profile, view.phase, homeResumes) { profile?.let { graph.wakeReady(it) } == true }
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
    fun recover(r: Recovery?) {
        when (r) {
            Recovery.OpenSettings -> ctx.startActivity(graph.gate.settingsIntent())
            Recovery.ReviewKey -> onReviewKey()
            Recovery.InstallRelay -> setRelayDismissed(false)
            Recovery.Retry -> graph.retry()
            Recovery.SetUpKey -> onSetUpKey()
            Recovery.Wake -> graph.wake()
            Recovery.ShowCommand -> onShowCommand()
            null -> Unit
        }
    }
    HerdHome(
        screen.state, now, preview = view.blockedPreview, onSettings = onSettings,
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

@Composable
private fun OutputRoute(graph: AppGraph, terminalId: String?, tab: AgentTab, onTab: (AgentTab) -> Unit, onBack: () -> Unit, onCompose: () -> Unit, onDecision: () -> Unit) {
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
                PaddockButton("Back to the herd", onBack, kind = ButtonKind.Secondary, icon = PaddockIcons.Back)
            }
        }
        return
    }
    SecureWindow(settings.protectSensitiveScreens)
    val feed = remember(host, terminalId) { host.outputFeed(terminalId) }
    val owner = LocalContext.current as? LifecycleOwner
    var resumed by remember(feed) { mutableStateOf(false) }
    DisposableEffect(feed, owner) {
        feed.start()
        val observer = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> resumed = true; Lifecycle.Event.ON_PAUSE -> resumed = false; else -> Unit }
        }
        owner?.lifecycle?.addObserver(observer)
        if (owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) resumed = true
        onDispose { owner?.lifecycle?.removeObserver(observer); feed.setVisible(false); feed.stop() }
    }
    // Polling needs the screen in the foreground and the Output tab shown: the Terminal tab streams on its own.
    val outputShown = outputFeedVisible(resumed, tab)
    LaunchedEffect(feed, outputShown) { feed.setVisible(outputShown) }
    val output by feed.state.collectAsState()
    val following by feed.following.collectAsState()
    val home by host.home.collectAsState()
    val now = rememberNow()
    val row: AgentRowModel? = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    // "claude · api › tab 2 · main · laptop": what it is, where, which session, which machine.
    val context = listOfNotNull(row?.agentKind, row?.context?.ifEmpty { null }, host.sessionName, profile?.name).joinToString(" · ")
    val header = AgentHeader(row?.title ?: "Agent", context, row?.state ?: StateWord.Unknown, row?.observedAtMillis, agentKind = row?.agentKind)
    val focus = focusView(host, terminalId)
    // A permission request the hook published for this agent: the entry above the output opens the sheet. Nothing here can answer it.
    // The entry is the only reader of the request, and it is not offered without Pro (below), so without Pro the host's request files are not polled over SSH
    // and there is no decision here at all. `locked` is read from state: when Pro appears the watch starts without the screen restarting.
    val pro by graph.pro.collectAsState()
    val answersLocked = graph.locked(ProCapabilities.GUARDED_ANSWERS, pro)
    val decision = rememberDecision(host, terminalId, now, watch = !answersLocked)?.takeIf { !answersLocked }
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
            // Tapping the entry is the user choosing to look at the newest request: it becomes the one on the sheet before the sheet is up.
            // Without Pro the entry is not offered at all (`decision` is null): a pending request is never answered by a purchase screen, and typing into the terminal stays free.
            decision = decision?.entry, onOpenDecision = { decision?.answers?.review(terminalId); onDecision() },
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
    val journalUnreadable by host.journalUnreadable.collectAsState()
    val target = TargetRef(host.profile.hostId, host.sessionName, terminalId)
    val epoch = installed?.epoch ?: return FocusView(null, FocusRules.gate(null, null, freshness == Freshness.Live, records, TerminalKey(target, 0L), journalUnreadable = journalUnreadable != null))
    val key = TerminalKey(target, epoch)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    return FocusView(key, FocusRules.gate(agent, installed?.readAtMillis, freshness == Freshness.Live, records, key, currentEpoch = epoch, journalUnreadable = journalUnreadable != null))
}

/** What the agent screen and the composer share about Manual input for one terminal: the session, its key and its gate. */
private class ManualView(val session: io.github.tuthan.paddock.ops.ManualSession?, val key: TerminalKey?, val gate: OperationGate?)

@Composable
private fun manualView(graph: AppGraph, host: MonitoredHost, terminalId: String): ManualView {
    val session by graph.manualInput.current.collectAsState()
    val installed by host.reconciler.installed.collectAsState()
    val freshness by host.freshness.collectAsState()
    val records by host.operationRecords.collectAsState()
    val journalUnreadable by host.journalUnreadable.collectAsState()
    val mine = session?.takeIf { it.terminalId == terminalId } ?: return ManualView(null, null, null)
    val key = TerminalKey(TargetRef(host.profile.hostId, host.sessionName, terminalId), mine.epoch)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    val gate = ManualInputRules.gate(agent, installed?.readAtMillis, mine.enteredAtMillis, freshness == Freshness.Live, records, key, currentEpoch = installed?.epoch, journalUnreadable = journalUnreadable != null)
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
                PaddockButton("Back", onBack, kind = ButtonKind.Secondary, icon = PaddockIcons.Back)
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
    val journalUnreadable by host.journalUnreadable.collectAsState()
    val gate = ComposerRules.gate(agent, installed?.readAtMillis, openedAt, freshness == Freshness.Live, records, key, text, currentEpoch = liveEpoch, journalUnreadable = journalUnreadable != null)
    val outcome = outcomes[terminalId]
    // The text goes with the prompt: cleared only when herdr accepted it, kept for a refusal, a failure or an unknown outcome.
    // The draft remembers which accepted row it has already gone with, so an old accepted outcome shown again clears nothing,
    // and it goes only while it is still the text that was sent: the field stays editable during a send.
    LaunchedEffect(outcome) {
        val result = outcome?.result
        if (outcome != null && outcome.kind == OperationKind.Prompt && result is OperationResult.Acknowledged<*>) onDraft { it.accepted(terminalId, result.record.id, result.record.payloadSha256) }
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
        gateActionLabel = when { stale || block == SendBlock.NeedsReread -> "Re-read"; block == SendBlock.JournalUnreadable -> "Retry"; else -> null },
        onGateAction = {
            when {
                stale -> { openedEpoch = liveEpoch; openedAt = System.currentTimeMillis(); host.refresh() }
                block == SendBlock.JournalUnreadable -> graph.journal.retryLoad()
                else -> reread()
            }
        },
        // A key goes only inside Manual input and under the keys' own gate. With the mode off, Esc is the way in: the tap turns the
        // mode on (which starts with a read) and sends nothing; the keys open once that read is installed, and a second tap sends.
        escEnabled = terminalId !in running && (if (keyGate == null) liveEpoch != null else keyGate is OperationGate.Open),
        escNote = when (keyGate) {
            null -> ESC_ENTERS_MANUAL_NOTE
            is OperationGate.Closed -> "Esc is off. ${keyGate.sentence}"
            OperationGate.Open -> null
        },
        onEsc = {
            if (keyGate == null) enterManual(graph, host, terminalId, liveEpoch)
            else manual.key?.takeIf { keyGate is OperationGate.Open }?.let { sends.sendKey(it, OperationKind.Esc) }
        },
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
    val sections = remember(filter, now / 1_000) { presenter.present(Activity.build(graph.ledger.observations(), graph.ledger.actions(), filter, graph.journal.records.value, host?.saga?.sagas?.value ?: graph.savedSagas()), now) }
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

/** The Spaces tab (Phase 09): the watched machine's sessions, the start-agent flow and its recovery cards. */
@Composable
private fun SpacesRoute(graph: AppGraph, onNotice: (String) -> Unit, onOpenAgent: (terminalId: String) -> Unit) {
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val pro by graph.pro.collectAsState()
    val spaces = view.spaces
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val saga = host?.saga
    val scope = graph.scope
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

    val mine = sagas.filter { it.host == profile?.hostId?.value }
    // updatedAt, not startedAt: a retry under a new name runs in the saga that began earlier.
    val progress = progressSince?.let { since -> mine.filter { it.updatedAt >= since && it.state != SagaState.Failed }.maxByOrNull { it.updatedAt } }?.let {
        SagaProgress(it.id, it.agentName, it.kind, it.step.label, done = it.state == SagaState.Succeeded, terminalId = it.terminalId, note = it.promptNote)
    }
    val cards = mine.filter { it.needsRecovery }.map { SagaCard.of(it, name) }
    val start = when {
        saga == null || host == null -> StartAvailability.Unavailable("Starting an agent needs this phone to be watching a live session on $name.")
        else -> StartAvailability.Available(installed?.snapshot?.workspaces.orEmpty().map { WorkspaceChoice(it.workspaceId, it.label.ifBlank { "workspace ${it.number}" }) })
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
            locked = listOf(ProCapabilities.START_AGENT, ProCapabilities.MANAGE_SESSIONS).filter { graph.locked(it, pro) }.map { it.id }.toSet(),
        ),
        SpacesActions(
            onChoose = { id -> graph.requestCapability(id, pendingAnswerOnScreen = false) },
            onRefresh = { refreshing = true; scope.launch { try { spaces?.refresh() } finally { refreshing = false } } },
            onStop = { e -> scope.launch { spaces?.stop(e) } },
            onDelete = { e -> scope.launch { spaces?.delete(e) } },
            onReread = { n -> scope.launch { spaces?.reread(n) } },
            onDismissNotice = { spaces?.dismissNotice() },
            onStart = { f -> begin(SagaRequest(profile!!.hostId, host!!.sessionName, f.name, f.kind, f.workspaceId, f.branch, firstPrompt = f.prompt, repository = null)) },
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
private object SagaCopyLine {
    fun closed(presenter: io.github.tuthan.paddock.ops.OperationPresenter, what: io.github.tuthan.paddock.ops.CloseTarget, r: io.github.tuthan.paddock.ops.OperationResult<Unit>): String {
        val kind = when (what) {
            is io.github.tuthan.paddock.ops.CloseTarget.Workspace -> io.github.tuthan.paddock.ops.OperationKind.CloseWorkspace
            is io.github.tuthan.paddock.ops.CloseTarget.Tab -> io.github.tuthan.paddock.ops.OperationKind.CloseTab
            is io.github.tuthan.paddock.ops.CloseTarget.Pane -> io.github.tuthan.paddock.ops.OperationKind.ClosePane
        }
        return presenter.line(kind, r).text
    }
}

/** Settings' Pro card from the graph's view of Pro. The foss build says everything is unlocked; the play build shows the store's last answer and its age. */
private fun proCard(pro: ProView, nowMillis: Long): ProCardState {
    val summary = io.github.tuthan.paddock.billing.EntitlementPresenter.summary(pro.state, nowMillis, pro.unlocked, pro.reachable, pro.sellsPro)
    return ProCardState(
        headline = summary.headline, detail = summary.detail, stale = summary.stale, sellsPro = pro.sellsPro, busy = pro.busy, message = pro.message,
        coverage = if (pro.unlocked) "" else io.github.tuthan.paddock.billing.ProCopy.coverage(io.github.tuthan.paddock.billing.ProGate.GATED),
        refunds = if (pro.sellsPro) io.github.tuthan.paddock.billing.ProCopy.REFUNDS else null,
        tips = if (pro.sellsPro) io.github.tuthan.paddock.billing.Products.TIPS.mapNotNull { id -> pro.prices[id]?.let { TipOption(id, it) } } else emptyList(),
    )
}

/**
 * Shows the Pro gate when, and only when, the user chose a Pro capability without Pro and is idle. If the user has since become busy
 * (a pending answer on screen, Manual input, an operation in flight) or bought Pro, the request is dropped without a word.
 */
@Composable
private fun ProGateHost(graph: AppGraph, pendingAnswerOnScreen: Boolean) {
    val request by graph.gateRequest.collectAsState()
    val pro by graph.pro.collectAsState()
    // Read so the sheet reacts the moment either changes; the decision itself reads them again through gateContext.
    val manual by graph.manualInput.current.collectAsState()
    val records by graph.journal.records.collectAsState()
    val capability = request ?: return
    val context = graph.gateContext(pendingAnswerOnScreen)
    val decision = io.github.tuthan.paddock.billing.ProGate.decide(capability, graph.entitlements.hasPro(pro.state), context)
    if (decision != io.github.tuthan.paddock.billing.GateDecision.SHOW_GATE) {
        androidx.compose.runtime.LaunchedEffect(capability, context, pro.state, manual, records) { graph.dismissGate() }
        return
    }
    ProGateSheet(
        title = io.github.tuthan.paddock.billing.ProCopy.gateTitle(capability),
        coverage = io.github.tuthan.paddock.billing.ProCopy.coverage(io.github.tuthan.paddock.billing.ProGate.GATED),
        price = pro.prices[io.github.tuthan.paddock.billing.Products.PRO], busy = pro.busy, message = pro.gateMessage, canBuy = pro.sellsPro,
        onBuy = { graph.buyPro() }, onNotNow = { graph.dismissGate() },
    )
}

@Composable
private fun SettingsRoute(graph: AppGraph, onBack: () -> Unit, onAddMachine: () -> Unit, onEditSnippets: () -> Unit, onAlertRelay: () -> Unit, onGuardedAnswers: () -> Unit) {
    val snippets by graph.snippets.collectAsState()
    val settings by graph.settings.collectAsState()
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val pro by graph.pro.collectAsState()
    // The store's prices (the tip buttons) are asked for when the Pro card is first shown, not at start; a no-op after they are known.
    androidx.compose.runtime.LaunchedEffect(Unit) { graph.loadPrices() }
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
    val wakeFacts by graph.wakeFacts.collectAsState()
    val settingsNow = rememberNow()
    val machine = profile?.let { p ->
        val wake = io.github.tuthan.paddock.host.WakeWordsMapper.words(
            p, wakeFacts, settingsNow, wakeReady = remember(p, resumes) { graph.wakeReady(p) },
            phoneSuggestion = remember(resumes) { io.github.tuthan.paddock.wake.WakeRelay.suggestionFrom(graph.lanPaths.paths()) },
            clockLabel = { m -> if (java.time.LocalDate.ofEpochDay(m / 86_400_000L) == java.time.LocalDate.ofEpochDay(settingsNow / 86_400_000L)) clockLabel(m) else java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(m)) },
        )
        MachineSummary(p.name, "${p.user}@${p.host}:${p.port}", p.session, wake)
    }
    val journalUnreadable by graph.journal.unreadable.collectAsState()
    // Read again on every return from system settings and after the dialog answers: the user can change any of it out of our sight.
    var accessTick by remember { mutableIntStateOf(0) }
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity }
    val notificationAccess = remember(resumes, accessTick, settings.notificationPermissionAsked) { graph.notificationAccess.read(activity, settings.notificationPermissionAsked) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accessTick++ }
    val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(notificationAccess)
    fun askPermission() {
        graph.notePermissionAsked()
        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    Settings(
        SettingsState(
            settings.protectSensitiveScreens, access, version, machine = machine, herdrVersion = view.herdrVersion, keepPromptText = settings.keepPromptText, snippetCount = snippets.size, journalUnreadable = journalUnreadable,
            alerts = AlertsState(settings.localAlerts, settings.hidePromptOnLockScreen, recovery), agentGlyphs = settings.agentGlyphs,
            pro = proCard(pro, graph.clock.nowMillis()),
            guardedAnswersLocked = graph.locked(ProCapabilities.GUARDED_ANSWERS, pro),
        ),
        onRestorePurchase = { graph.restorePurchases() },
        onBuyTip = { graph.buyTip(it) },
        onCopyWakeCommand = { cmd -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock wake command", cmd)) },
        onSaveWakeRelay = { relay -> profile?.let { p -> scope.launch { graph.setWakeRelay(p.id, relay) } } },
        onWake = { graph.wake() },
        onProtectSensitive = { scope.launch { graph.setProtectSensitive(it) } },
        onAgentGlyphs = { graph.setAgentGlyphs(it) },
        onOpenSystemSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = onBack,
        onAddMachine = onAddMachine,
        onKeepPromptText = { graph.setKeepPromptText(it) },
        onEditSnippets = onEditSnippets,
        onRetryJournal = { graph.journal.retryLoad() },
        // Off the main thread: setting the file aside is disk work. A failure leaves the journal unreadable, which the card keeps saying.
        onResetJournal = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { graph.journal.resetUnreadable() } } },
        // Turning alerts on is the moment the permission is asked for (Android 13 and later); a refusal leaves the recovery row.
        onLocalAlerts = { on ->
            graph.setLocalAlerts(on)
            if (on && notificationAccess is io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission && notificationAccess.canAsk) askPermission()
        },
        onHideOnLockScreen = { graph.setHidePromptOnLockScreen(it) },
        onAlertRelay = onAlertRelay,
        onGuardedAnswers = onGuardedAnswers,
        onAlertRecovery = { action ->
            if (action == io.github.tuthan.paddock.alerts.AccessRecovery.Action.AskPermission) askPermission()
            else recovery?.let { r -> graph.notificationAccess.settingsIntent(r, notificationAccess)?.let { ctx.startActivity(it) } }
        },
    )
}

/** One terminal's permission request as the Output tab's entry and the decision sheet draw it. */
private class DecisionState(val key: TerminalKey?, val model: DecisionModel, val entry: DecisionEntryModel?, val awaitsSettle: Boolean, val answers: AnswerController)

/**
 * Watches the host's request files for [terminalId] while the screen is up (a read only while the agent is blocked) and turns them into
 * words. Null when this machine has no answers (no operation journal, so no operations at all). A reconnect changes the key, which
 * restarts the watch under the new epoch. [watch] false reads nothing from the host (the Output tab without Pro, where nothing would show the
 * request); the decision sheet always watches, because a request already on screen stays answerable whatever Pro says.
 */
@Composable
private fun rememberDecision(host: MonitoredHost, terminalId: String, nowMillis: Long, watch: Boolean = true): DecisionState? {
    val answers = host.answers ?: return null
    val installed by host.reconciler.installed.collectAsState()
    val freshness by host.freshness.collectAsState()
    val records by host.operationRecords.collectAsState()
    val journalUnreadable by host.journalUnreadable.collectAsState()
    val views by answers.views.collectAsState()
    val running by answers.running.collectAsState()
    val presenter = remember { OperationPresenter() }
    val epoch = installed?.epoch
    val key = epoch?.let { TerminalKey(TargetRef(host.profile.hostId, host.sessionName, terminalId), it) }
    RequestWatch(answers, key, watch)
    val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }
    val gate = if (key == null) OperationGate.Closed(SendBlock.Reading, "Reading the agent's state…")
    else AnswerGate.gate(agent, installed?.readAtMillis, freshness == Freshness.Live, records, key, currentEpoch = epoch, journalUnreadable = journalUnreadable != null)
    val model = DecisionPresenter.model(views[terminalId], nowMillis, gate, terminalId in running, presenter)
    val awaitsSettle = key != null && records.any { it.sameTerminal(key) && it.awaitsReread && (it.kind == OperationKind.Allow || it.kind == OperationKind.Deny) }
    return DecisionState(key, model, DecisionPresenter.entry(views[terminalId], nowMillis), awaitsSettle, answers)
}

/**
 * Keeps [answers] watching [key]'s request files while this is composed and [enabled]. A watch is a poll of the host over SSH, so [enabled] is how a
 * screen that has nothing to show for the answer (guarded answers locked) stays off the wire. [enabled] is read from state: when it turns true (Pro
 * appears) the watch starts without the screen restarting, and when it turns false it ends. A reconnect changes [key], which restarts it under the new epoch.
 */
@Composable
internal fun RequestWatch(answers: AnswerController, key: TerminalKey?, enabled: Boolean) {
    DisposableEffect(answers, key, enabled) {
        val watched = key?.takeIf { enabled }
        if (watched != null) answers.watch(watched)
        onDispose { if (watched != null) answers.unwatch(watched.target.terminalId) }
    }
}

/** Why the decision sheet has no Set up when guarded answers are locked: it is Pro, it is asked for in Settings, and the terminal answers meanwhile. */
internal const val GUARDED_SETUP_IS_PRO = "Guarded answers are Pro. They are set up from Settings, never over a request. Until then, answer in the terminal."

/**
 * The decision sheet's "Set up" (the read of the request failed, usually because the hook is not installed): on offer only while guarded answers are
 * not locked. Locked, it is not offered, because the gate sheet may not open over a request (AC-13.5) and a Set up that did nothing would be a dead
 * button; the sheet's own fallback, "Try again", re-reads, and [decisionNotice] says why there is no Set up.
 */
internal fun decisionSetUp(readError: String?, locked: Boolean, setUp: () -> Unit): (() -> Unit)? = if (readError != null && !locked) setUp else null

/** The note above the decision sheet: what the last re-read of the host's files found, then [GUARDED_SETUP_IS_PRO] when Set up was withheld for the lock. Null when there is neither. */
internal fun decisionNotice(settled: String?, readError: String?, locked: Boolean): String? =
    listOfNotNull(settled, GUARDED_SETUP_IS_PRO.takeIf { readError != null && locked }).joinToString("\n").ifEmpty { null }

/**
 * The decision sheet: the request the hook published for this agent, shown whole, with Yes and No for exactly that request. Every
 * button acts on the request on screen; a newer one is only offered. Leaving it for the terminal is always one tap.
 */
@Composable
private fun DecisionRoute(graph: AppGraph, terminalId: String?, onBack: () -> Unit, onOpenTerminal: () -> Unit, onSetUp: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val settings by graph.settings.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val profile by graph.profile.collectAsState()
    // Pro gates setting guarded answers up, never the request already on screen: Yes and No work as they did.
    val pro by graph.pro.collectAsState()
    val setUpLocked = graph.locked(ProCapabilities.GUARDED_ANSWERS, pro)
    val unavailable: @Composable (String) -> Unit = { what ->
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Permission request", onBack = onBack, compact = true)
            Column(Modifier.padding(PaddockTokens.spacing.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(what, style = PaddockTokens.type.body, color = PaddockTokens.colors.title)
                PaddockButton("Back", onBack, kind = ButtonKind.Secondary, icon = PaddockIcons.Back)
            }
        }
    }
    if (terminalId == null || host == null) { unavailable("This agent is not available right now. Its machine is not connected."); return }
    SecureWindow(settings.protectSensitiveScreens)
    val now = rememberNow()
    val state = rememberDecision(host, terminalId, now)
    if (state == null) { unavailable("Answers from the phone are not available on this machine: Paddock cannot keep the record of an answer here."); return }
    val home by host.home.collectAsState()
    val row = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    val header = AgentHeader(row?.title ?: "Agent", listOfNotNull(row?.agentKind, host.sessionName, profile?.name).joinToString(" · "), row?.state ?: StateWord.Unknown, row?.observedAtMillis, agentKind = row?.agentKind)
    val scope = rememberCoroutineScope()
    var notice by remember(terminalId) { mutableStateOf<String?>(null) }
    val key = state.key
    val answers = state.answers
    DecisionSheet(
        header, state.model, state.awaitsSettle,
        DecisionActions(
            onYes = { id -> key?.let { answers.answer(it, Behavior.Allow, id) } },
            onNo = { id -> key?.let { answers.answer(it, Behavior.Deny, id) } },
            onReviewNewer = { answers.review(terminalId) },
            onOpenTerminal = onOpenTerminal,
            onRefresh = { key?.let { answers.refresh(it) } },
            onDismissResult = { answers.dismiss(terminalId) },
            onSettle = {
                key?.let { k ->
                    scope.launch {
                        notice = try { answers.settle(k).lastOrNull()?.let { DecisionPresenter.settled(it.second) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Throwable) { "Could not read the host's files: " + (e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "unknown error") + ". Nothing was sent." }
                    }
                }
            },
            onSetUp = decisionSetUp(state.model.readError, setUpLocked, onSetUp),
        ),
        onBack, notice = decisionNotice(notice, state.model.readError, setUpLocked),
    )
}

/**
 * Guarded answers on the watched machine: the two pinned scripts' state on the host (read through the live connection), the consented
 * install of exactly those files, and the two texts to paste. Nothing here registers the hook or edits Claude Code's settings.
 */
@Composable
private fun GuardedAnswersRoute(graph: AppGraph, onBack: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val profile by graph.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val answerHost = host?.answerHost
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hostState by remember(answerHost) { mutableStateOf<GuardedHostState>(if (answerHost == null) GuardedHostState.NotConnected else GuardedHostState.Reading) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(GuardedCopied.None) }
    var window by rememberSaveable { mutableIntStateOf(AnswerSetupText.DEFAULT_WINDOW_SECONDS) }
    LaunchedEffect(answerHost, tick) {
        if (answerHost == null) { hostState = GuardedHostState.NotConnected; return@LaunchedEffect }
        hostState = GuardedHostState.Reading
        hostState = try { GuardedHostState.Known(answerHost.inspect()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { GuardedHostState.Failed(e.message?.lineSequence()?.firstOrNull()?.take(160) ?: "the read failed") }
    }
    val known = (hostState as? GuardedHostState.Known)?.setup
    val config = runCatching { AnswerSetupText.configCommand(window) }.getOrNull()
    val snippet = known?.let { runCatching { AnswerSetupText.settingsSnippet(it.hookDestination, window) }.getOrNull() }
    val copy = { label: String, text: String?, which: GuardedCopied ->
        text?.let {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, it))
            copied = which
        }
    }
    GuardedAnswers(
        GuardedAnswersUi(
            profile?.name ?: "this machine", answerHost?.decideSha256 ?: graph.decideScriptSha256, answerHost?.hookSha256 ?: graph.hookScriptSha256,
            hostState, installing, installError, window, config, snippet, copied,
        ),
        onBack = onBack,
        onInstall = {
            val h = answerHost ?: return@GuardedAnswers
            installing = true; installError = null
            scope.launch {
                try { h.install() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { installError = "The install did not finish: " + (e.message?.lineSequence()?.firstOrNull()?.take(160) ?: "unknown error") }
                finally { installing = false; tick++ }
            }
        },
        onCheck = { tick++ },
        onWindow = { window = it; copied = GuardedCopied.None },
        onCopyConfig = { copy("Paddock guarded answers configuration", config, GuardedCopied.Config) },
        onCopySettings = { copy("Paddock guarded answers registration", snippet, GuardedCopied.Settings) },
    )
}

/**
 * The alert relay on the watched machine: what the pinned script's state on the host is (read through the live connection), the
 * consented install of that one file, and the setup commands to read and copy. Nothing here enables the user's unit.
 */
@Composable
private fun AlertRelayRoute(graph: AppGraph, onBack: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val profile by graph.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val relay = host?.alertRelay
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hostState by remember(relay) { mutableStateOf<AlertRelayHostState>(if (relay == null) AlertRelayHostState.NotConnected else AlertRelayHostState.Reading) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    // Connector mode: the registrations and the distributors on the phone, read again on every return (an app may have been installed meanwhile).
    val registrations by graph.push.registrations.collectAsState()
    val pushNotice by graph.push.notice.collectAsState()
    val resumes = rememberResumes()
    LaunchedEffect(Unit) { graph.push.refresh() }
    val distributors = remember(resumes, registrations) { graph.push.distributors() }
    var pushBusy by remember { mutableStateOf(false) }
    var pushError by remember { mutableStateOf<String?>(null) }
    var onHost by remember(relay, tick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(relay, tick) { onHost = if (relay == null) null else try { relay.hasPushEndpoint() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null } }
    val reg = registrations.firstOrNull { it.profile == profile?.id }
    LaunchedEffect(relay, tick) {
        if (relay == null) { hostState = AlertRelayHostState.NotConnected; return@LaunchedEffect }
        hostState = AlertRelayHostState.Reading
        hostState = try { AlertRelayHostState.Known(relay.inspect()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { AlertRelayHostState.Failed(e.message?.take(160) ?: "the read failed") }
    }
    val commands = host?.let { h -> profile?.let { p -> runCatching { io.github.tuthan.paddock.alerts.AlertRelaySetup.commands(graph.alertUnit, graph.alertConfigExample, p.id, h.socketPath, graph.alertScriptSha256) }.getOrNull() } }
    AlertRelay(
        AlertRelayUi(
            profile?.name ?: "this machine", profile?.id ?: "", graph.alertScriptSha256, hostState, installing, installError, commands, copied,
            push = PushUi(
                distributors = distributors.map { PushDistributorUi(it.packageName, it.label) },
                registered = reg?.let { r ->
                    PushRegisteredUi(
                        distributorLabel = distributors.firstOrNull { it.packageName == r.distributor }?.label ?: r.distributor,
                        distributorInstalled = distributors.any { it.packageName == r.distributor },
                        endpointHost = r.endpoint?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() },
                        hasEndpoint = r.endpoint != null, shared = r.sharedAtMillis != null, failure = r.failure?.label,
                    )
                },
                notice = pushNotice, busy = pushBusy, error = pushError, onHost = onHost,
            ),
        ),
        onPushRegister = { pkg ->
            val p = profile ?: return@AlertRelay
            pushError = null
            scope.launch { graph.push.register(p.id, p.name, pkg) }
        },
        onPushShare = {
            val r = relay ?: return@AlertRelay
            val p = profile ?: return@AlertRelay
            val endpoint = reg?.endpoint ?: return@AlertRelay
            pushBusy = true; pushError = null
            scope.launch {
                try { r.writePushEndpoint(endpoint); graph.push.markShared(p.id) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { pushError = "The address was not written: " + (e.message?.take(160) ?: "unknown error") }
                finally { pushBusy = false; tick++ }
            }
        },
        onPushRemove = {
            val p = profile ?: return@AlertRelay
            pushBusy = true; pushError = null
            scope.launch {
                try {
                    graph.push.unregister(p.id)
                    // The file on the host goes too when the phone can reach it; otherwise it stays, and the screen says it could not be removed.
                    if (relay != null) relay.removePushEndpoint()
                    else if (reg?.sharedAtMillis != null) pushError = "Unregistered. The address file on the host could not be removed because Paddock is not connected."
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { pushError = "Unregistered. The address file on the host was not removed: " + (e.message?.take(160) ?: "unknown error") }
                finally { pushBusy = false; tick++ }
            }
        },
        onBack = onBack,
        onInstall = {
            val r = relay ?: return@AlertRelay
            installing = true; installError = null
            scope.launch {
                try { r.install() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { installError = "The install did not finish: " + (e.message?.take(160) ?: "unknown error") }
                finally { installing = false; tick++ }
            }
        },
        onCheck = { tick++ },
        onCopy = {
            commands?.let {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock alert relay setup", it))
                copied = true
            }
        },
    )
}

@Composable
private fun AddMachineRoute(
    graph: AppGraph, editing: Boolean, pairing: PairingLink?, onBack: () -> Unit,
    attempt: ConnectAttempt?, onAttempt: (ConnectAttempt?) -> Unit, authorizeIntro: Boolean,
    onPairing: (PairingLink) -> Unit, onNotice: (String) -> Unit,
    picked: AddMachineInput?, onPicked: (AddMachineInput?) -> Unit,
) {
    val ctx = LocalContext.current
    val watched by graph.profile.collectAsState()
    // Setting up the key keeps the machine (its id, so its pinned host key and history) while it is the same host and port.
    val fixing = watched.takeIf { editing }
    val scope = rememberCoroutineScope()
    var keyTick by remember { mutableIntStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // The refusal is the dialog's answer, not the live state: the grant can be turned on in the app's system settings, so it is
    // read again on every return, and a banner saying "access is off" never outlives the grant.
    val resumes = rememberResumes()
    LaunchedEffect(resumes) { if (denied && !graph.gate.lanAccessMissing()) denied = false }
    var pending by remember { mutableStateOf<AddMachineInput?>(null) }
    // Send the key waits for the local-network grant the way Connect does; kept here so the answer to the dialog can pick it up.
    // It keeps the offer it was asked for with the form: the dialog can stay up while another link arrives, and the key goes to the desktop that was tapped, never to whichever link is current when the dialog is answered.
    var pendingSend by remember { mutableStateOf<Pair<AddMachineInput, PairingLink>?>(null) }
    // The form as typed when the key was sent: what Connect uses once the desktop approves (or when the window ended unheard).
    var sentInput by remember { mutableStateOf<AddMachineInput?>(null) }

    fun finish(input: AddMachineInput) {
        scope.launch {
            // The same machine (host, port and user, or the one being fixed) stays one profile: its id, name and wake facts carry over.
            val known = graph.profiles.list()
            val profile = AddMachineForm.resolve(input, known, fixing) ?: return@launch
            val already = known.any { it.id == profile.id }
            // Set or cleared on every Connect: a link's fingerprints are compared with the key this machine presents, and never outlive the form that carried them.
            graph.broker.expectPairing(profile.id, input.pairedFingerprints)
            // What is on screen now is the last attempt's: only a phase that is not that one can be this attempt's answer.
            graph.hostUi.beginAttempt()
            graph.addMachine(profile)
            // The same profile is resumed, not rebuilt: ask for the reconnect that picks up the new key.
            if (already) graph.retry()
            // Stay on the form: the root follows the connection and either leaves for Home or shows what went wrong here.
            onAttempt(ConnectAttempt(profile.id))
        }
    }
    fun beginSend(input: AddMachineInput, offer: PairingLink) {
        val port = offer.pairPort ?: return
        val sid = offer.sid ?: return
        sentInput = input
        scope.launch { if (!graph.sendPairingKey(offer.host, port, sid)) onNotice("Create this phone's key first, then send it.") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        val input = pending
        pending = null
        // A refusal is not the end: the screen keeps the recovery row, and a VPN address still works without the grant.
        if (granted && input != null) finish(input)
        val send = pendingSend
        pendingSend = null
        if (granted && send != null) beginSend(send.first, send.second)
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
    // The same for the scanner: opening it leaves the form's typed values under the holder, and a code read replaces them with the link's.
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        BackHandler { scanning = false }
        ScanRoute(graph, onLink = { onPairing(it); scanning = false }, onBack = { scanning = false })
        return
    }
    // The same for Find on this network: the form (and what was typed in it) waits under the holder while the finder is up.
    var finding by rememberSaveable { mutableStateOf(false) }
    if (finding) {
        BackHandler { finding = false }
        FindRoute(graph, onPick = { onAttempt(null); onPicked(it); finding = false }, onBack = { finding = false })
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
        pairOfferHost = pairing?.takeIf { it.pairPort != null && it.sid != null }?.host,
        connecting = attempt?.connecting == true,
        connectError = attempt?.error,
        connectFix = attempt?.fix,
    )
    val pairState by graph.pairingCoordinator.state.collectAsState()
    val pairNow = rememberNow()
    val pairView = PairingText.view(pairState, pairNow)
    // Approved: the key is authorized on the machine, so connect with the form as it was sent. The request is done with either way.
    LaunchedEffect(pairState) {
        val approved = pairState as? PairingState.Approved ?: return@LaunchedEffect
        val input = sentInput
        sentInput = null
        graph.pairingCoordinator.clear()
        if (input != null) finish(input) else onNotice("The desktop approved this phone's key. Press Connect to sign in.")
    }
    if (pairView != null && !pairView.approved) {
        val p = pairState.pendingOrNull
        // The system Back gesture is the header arrow's twin: a request that is still going is cancelled (so a late approval cannot connect a
        // phone that has left this page), a finished one is dismissed.
        BackHandler { scope.launch { if (pairView.active) graph.pairingCoordinator.cancel() else graph.pairingCoordinator.clear() } }
        PairWithDesktop(
            pairView, PairingTarget(p?.let { "${it.host}:${it.port}" }.orEmpty(), p?.fingerprint.orEmpty()),
            onCancel = { scope.launch { graph.pairingCoordinator.cancel() } },
            onBack = { scope.launch { graph.pairingCoordinator.clear() } },
            onConnect = { val input = sentInput; sentInput = null; scope.launch { graph.pairingCoordinator.clear() }; if (input != null) finish(input) },
        )
        return
    }
    // Its own saved form: setting up a key starts from the watched machine's values, not from a half-typed new one.
    holder.SaveableStateProvider(fixing?.let { "key-${it.id}" } ?: pairing?.let { "pair-${it.hashCode()}" } ?: "add-machine") {
        AddMachine(
            state,
            onConnect = { input ->
                // Decided from where the name resolves (a LAN hostname needs the grant too); the lookup is bounded and off the main thread.
                scope.launch { if (graph.gate.needsRequest(AddMachineForm.normalizeHost(input.host))) { pending = input; permission.launch(LocalNetworkPolicy.PERMISSION) } else finish(input) }
            },
            onSendKey = onSendKey@{ input ->
                // The offer is read now, at the tap: what is sent and to whom is what was on screen when it was pressed.
                val offer = pairing?.takeIf { it.pairPort != null && it.sid != null } ?: return@onSendKey
                // The TCP connect to the desktop is a LAN connection like Connect's: ask for the local-network grant first, then send.
                scope.launch { if (graph.gate.needsRequest(AddMachineForm.normalizeHost(offer.host))) { pendingSend = input to offer; permission.launch(LocalNetworkPolicy.PERMISSION) } else beginSend(input, offer) }
            },
            onGenerateKey = { scope.launch { graph.createPhoneKey(); keyTick++ } },
            onCopyPublicKey = { line -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock public key", line)) },
            onCopyCommand = { command -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock authorize command", command)) },
            onShareCommand = { command -> ctx.startActivity(Intent.createChooser(commandShareIntent(command), "Share the command")) },
            onPastePairingLink = {
                when (val r = readPairingLink(ctx)) {
                    is PairingResult.Valid -> onPairing(r.link)
                    is PairingResult.Rejected -> onNotice(PairingCopy.invalid(r.reason))
                }
            },
            onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
            onBack = onBack,
            onImportKey = { importing = true },
            // Not while a key is being set up for the watched machine, or under a pairing link: the host there is already decided.
            onFind = if (fixing == null && pairing == null) ({ finding = true }) else null,
            onScan = if (fixing == null) ({ scanning = true }) else null,
            picked = picked, onPickedApplied = { onPicked(null) },
            initial = fixing?.let { AddMachineInput(it.host, it.port.toString(), it.user, it.key, it.importedKeyId, it.session ?: "") } ?: pairing?.toInput() ?: AddMachineInput(),
            title = when { fixing != null && authorizeIntro -> if (fixing.key == KeyKind.Imported) "Authorize the key" else "Authorize this phone"; fixing != null -> "Set up the key"; else -> "Add a machine" },
            intro = when { fixing != null && authorizeIntro -> if (fixing.key == KeyKind.Imported) AUTHORIZE_INTRO_IMPORTED else AUTHORIZE_INTRO; fixing != null -> SET_UP_KEY_INTRO; else -> ADD_MACHINE_INTRO },
        )
    }
}

private const val MAX_KEY_FILE_BYTES = 64 * 1024

/** How long Add machine waits for the connection to decide before saying it is still waiting. Not counted while a host key question is on screen (the connector allows that its own, longer time). */
private const val CONNECT_WAIT_MILLIS = 45_000L

/** The user asked for this read. Only a token that starts with the pairing scheme is looked at, and the text is never echoed. */
private fun readPairingLink(ctx: Context): PairingResult {
    val text = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
    return PairingLinks.fromText(text)
}

@Composable
private fun WelcomeRoute(onEnterAddress: () -> Unit, onFind: () -> Unit, onScan: () -> Unit, onPairing: (PairingLink) -> Unit) {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    Welcome(
        onEnterAddress = onEnterAddress,
        onFind = onFind,
        onScan = onScan,
        onPasteLink = {
            when (val r = readPairingLink(ctx)) {
                is PairingResult.Valid -> { notice = null; onPairing(r.link) }
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        notice = notice,
    )
}

/**
 * The scanner page. The camera is asked for here, when the scanner opens, and never before; a refusal leaves Allow the camera, Open
 * settings and Paste a pairing link. A code that is not a pairing link says so and the scan goes on (the camera is not restarted, and the same
 * code left in view is not announced again); a good one goes to [onLink]. "Paste a pairing link" reads the clipboard here, the same as the
 * paste buttons elsewhere, so the camera is never the only way.
 */
@Composable
private fun ScanRoute(graph: AppGraph, onLink: (PairingLink) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val resumes = rememberResumes()
    val granted = remember(tick, resumes) { ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; permission.launch(android.Manifest.permission.CAMERA) } }
    ScanLink(
        cameraGranted = granted,
        onRequestCamera = { permission.launch(android.Manifest.permission.CAMERA) },
        onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onPayload = { text ->
            when (val r = PairingLinks.fromText(text)) {
                is PairingResult.Valid -> onLink(r.link)
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        onPaste = {
            when (val r = readPairingLink(ctx)) {
                is PairingResult.Valid -> onLink(r.link)
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        onBack = onBack, notice = notice,
    )
}

/** The finder page over [AppGraph.finder]. Nothing runs until Start; leaving the page stops whatever runs and releases the network. */
@Composable
private fun FindRoute(graph: AppGraph, onPick: (AddMachineInput) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by graph.finder.state.collectAsState()
    var port by rememberSaveable { mutableStateOf("") }
    var denied by rememberSaveable { mutableStateOf(false) }
    val typed = FinderText.port(port).port
    val resumes = rememberResumes()
    // Said again when the port changes (the sentence names the ports), and when the grant is turned on in the system settings and the user returns.
    LaunchedEffect(port) { graph.finder.refresh(typed) }
    LaunchedEffect(resumes) { if (graph.finder.state.value.phase == FinderPhase.Idle) { if (denied && !graph.gate.lanAccessMissing()) denied = false; graph.finder.refresh(typed) } }
    DisposableEffect(Unit) { onDispose { graph.finder.close() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        graph.finder.refresh(typed)
    }
    FindOnNetwork(
        state, port, { port = it.take(5) },
        onStart = { graph.finder.start(typed) },
        onCancel = { scope.launch { graph.finder.cancel() } },
        onAllow = { permission.launch(LocalNetworkPolicy.PERMISSION) },
        onPick = { row -> onPick(AddMachineInput(host = row.address, port = row.port.toString(), name = row.name)) },
        onBack = onBack,
        accessDenied = denied,
        onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
    )
}

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
                    // On the main thread whatever resumed us: before API 28 the clipboard service needs a Looper to be created.
                    if (pasted) withContext(Dispatchers.Main) { clearClipboardIfKey(ctx) }
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
        FingerprintDialog(HostKeyPrompts.firstTrust(r.endpoint, r.presented, r.linkFingerprints), onTrust = { graph.broker.answerFirstTrust(r.id, true) }, onCancel = { graph.broker.answerFirstTrust(r.id, false) })
    }
    // A key the pairing link does not name was refused before any question: this only says so, with both fingerprints, and offers no way to trust it.
    val refusal by graph.broker.pairingRefused.collectAsState()
    refusal?.let { r -> FingerprintDialog(HostKeyPrompts.pairingMismatch(r), onTrust = {}, onCancel = { graph.broker.clearPairingRefusal() }) }
    val profile by graph.profile.collectAsState()
    val changed by graph.broker.changed.collectAsState()
    val c = profile?.let { changed[it.id] }
    val scope = rememberCoroutineScope()
    if (reviewKey && c != null) {
        FingerprintDialog(
            HostKeyPrompts.changed(c.endpoint, HostKeyState.Changed(c.pin, c.presented)),
            // The tap approves the key drawn here: `c` names its attempt, and replaceKey refuses it once a later attempt replaced it.
            onTrust = { scope.launch { graph.replaceKey(c); dismissReview() } },
            onCancel = dismissReview,
        )
    }
}
