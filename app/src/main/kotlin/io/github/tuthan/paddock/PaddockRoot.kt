package io.github.tuthan.paddock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AlertCopy
import io.github.tuthan.paddock.alerts.AlertEvent
import io.github.tuthan.paddock.alerts.AlertOutcome
import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.answers.AnswerRules
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.host.ConnectOutcome
import io.github.tuthan.paddock.host.ConnectOutcomes
import io.github.tuthan.paddock.host.PairingLinkSaver
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.PairingEvent
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.DownReasonText
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.routes.ActivityRoute
import io.github.tuthan.paddock.routes.AddMachineRoute
import io.github.tuthan.paddock.routes.AlertRelayRoute
import io.github.tuthan.paddock.routes.ComposeRoute
import io.github.tuthan.paddock.routes.DecisionRoute
import io.github.tuthan.paddock.routes.FindRoute
import io.github.tuthan.paddock.routes.GuardedAnswersRoute
import io.github.tuthan.paddock.routes.HomeRoute
import io.github.tuthan.paddock.routes.HostKeyDialogs
import io.github.tuthan.paddock.routes.MachineRoute
import io.github.tuthan.paddock.routes.MachinesRoute
import io.github.tuthan.paddock.routes.OutputRoute
import io.github.tuthan.paddock.routes.ProGateHost
import io.github.tuthan.paddock.routes.PromptDraftSaver
import io.github.tuthan.paddock.routes.ScanRoute
import io.github.tuthan.paddock.routes.SettingsRoute
import io.github.tuthan.paddock.routes.SnippetsRoute
import io.github.tuthan.paddock.routes.SpacesRoute
import io.github.tuthan.paddock.routes.WelcomeRoute
import io.github.tuthan.paddock.routes.decisionSetUp
import io.github.tuthan.paddock.ui.components.LocalAgentGlyphs
import io.github.tuthan.paddock.ui.components.NavItem
import io.github.tuthan.paddock.ui.components.NoticeBar
import io.github.tuthan.paddock.ui.components.PaddockNavBar
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.AlertRelay
import io.github.tuthan.paddock.ui.screens.GateNoticeBar
import io.github.tuthan.paddock.ui.screens.GuardedAnswers
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Route { Welcome, Home, Output, Compose, Snippets, Activity, Spaces, Settings, AddMachine, AlertRelay, Decision, GuardedAnswers, Machines, Machine }

private val NAV = listOf(NavItem("Herd", PaddockIcons.Herd), NavItem("Spaces", PaddockIcons.Spaces), NavItem("Activity", PaddockIcons.Activity))

/**
 * The app's one navigation host. Screens are stateless; this connects them to the graph. The route, where Add machine
 * was opened from, and the open terminal survive rotation (`rememberSaveable`); everything else is read from the graph.
 * Home, Spaces and Activity share the bottom bar; Settings is the gear on Home. Machines (the saved machines) opens from the watched chip on Home and
 * from Settings, and each machine's page from there; Add machine opens from Machines (and from Welcome with no machine).
 */
@Composable
fun PaddockRoot(graph: AppGraph, modifier: Modifier = Modifier) {
    // One provider for every agent tile in the app, so Settings > Agent icons redraws the herd at once without threading it through each screen.
    val agentGlyphs by remember(graph) { graph.settings.map { it.agentGlyphs }.distinctUntilChanged() }.collectAsState(true)
    CompositionLocalProvider(LocalAgentGlyphs provides agentGlyphs) { AppLockGate(graph) { PaddockRootContent(graph, modifier) } }
}

/**
 * Where Review lands for [terminalId]: the Output tab, which carries the Yes/No entry, when this machine can answer that agent's prompts from the phone
 * ([AnswerRules.canAnswerFromPhone]); otherwise the Terminal tab, observing. Read when the tap happens, from what the app knows then.
 */
private fun reviewTab(graph: AppGraph, terminalId: String): AgentTab {
    val host = (graph.hostUi.view.value.phase as? HostPhase.Monitoring)?.host ?: return AgentTab.Terminal
    val kind = host.reconciler.installed.value?.snapshot?.agents?.firstOrNull { it.terminalId == terminalId }?.agent
    return if (AnswerRules.canAnswerFromPhone(kind, wired = host.answers != null, locked = graph.proService.locked(ProCapabilities.GUARDED_ANSWERS))) AgentTab.Output else AgentTab.Terminal
}

@Composable
private fun PaddockRootContent(graph: AppGraph, modifier: Modifier) {
    val boot by graph.machineBook.boot.collectAsState()
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var addFrom by rememberSaveable { mutableStateOf(Route.Home) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var outputTab by rememberSaveable { mutableStateOf(AgentTab.Output) }
    var snippetsFrom by rememberSaveable { mutableStateOf(Route.Settings) }
    var guardedFrom by rememberSaveable { mutableStateOf(Route.Machine) }
    // Machines opens from Home's chip or from Settings and returns there; a machine's page returns to Machines; the alert relay returns to whichever opened it.
    var machinesFrom by rememberSaveable { mutableStateOf(Route.Home) }
    var machineId by rememberSaveable { mutableStateOf<String?>(null) }
    var alertRelayFrom by rememberSaveable { mutableStateOf(Route.Settings) }
    // A removal confirmed on a machine's page and still running: Watch and Remove wait for it on the list and the page (the graph also serializes them).
    // The graph's, not this composition's: the removal runs in the graph's scope and outlives a rotation.
    val removingId by graph.machineBook.removingMachine.collectAsState()
    var relayDismissed by rememberSaveable { mutableStateOf(false) }
    // "Not now" on the relay prompt is about the machine it was shown for: another machine watched means its own prompt, if it has one.
    val watchedId = graph.machineBook.profile.collectAsState().value?.id
    var dismissedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(watchedId) { if (dismissedFor != null && dismissedFor != watchedId) relayDismissed = false; dismissedFor = watchedId }
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
                val r = graph.alertService.resolveMachine(e)
                val machineOutcome = r.outcome
                alertNotice = when {
                    r.unknownMachine -> AlertCopy.UNKNOWN_MACHINE
                    machineOutcome == null -> AlertCopy.unreachable(r.machine)
                    else -> AlertCopy.machineNotice(machineOutcome, r.machine)
                }
                graph.alerts.consume(e)
            }
            is AlertEvent.Arrived -> {
                // The herd shows while the connection and the read come up; the alert is resolved against that read, never a cached one.
                alertNotice = AlertCopy.OPENING
                route = Route.Home
                val resolved = graph.alertService.resolveAlert(e)
                val outcome = resolved.outcome
                if (outcome == null) alertNotice = AlertCopy.unreachable(resolved.machine)
                else {
                    alertNotice = AlertCopy.notice(outcome, e.hint, System.currentTimeMillis(), resolved.machine)
                    when (outcome) {
                        is AlertOutcome.Current -> {
                            terminalId = outcome.terminalId
                            // A blocked agent opens where Review prompt does: its Output tab when the phone can answer it, else its Terminal tab, observing.
                            outputTab = if (outcome.state == AlertState.Blocked) reviewTab(graph, outcome.terminalId) else AgentTab.Output
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

    // What a Remove… confirmed on a machine's page came to. Read from the graph, not from a callback the page captured: the removal runs in the graph's
    // scope and can end after a rotation or while the app is away, and whichever composition is here then says it once.
    val removal by graph.machineBook.removalOutcome.collectAsState()
    LaunchedEffect(removal) {
        val r = removal ?: return@LaunchedEffect
        when (val result = r.result) {
            MachineRemoval.Result.Removed -> {
                alertNotice = MachineCopy.removed(r.name)
                // Back to the list once the machine is gone, unless the user has already gone somewhere else meanwhile.
                if (route == Route.Machine && machineId == r.id) route = Route.Machines
            }
            is MachineRemoval.Result.Incomplete -> alertNotice = MachineCopy.removeFailed(result.failed)
        }
        graph.machineBook.consumeRemoval(r)
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
    val backTo = when (effective) {
        Route.AddMachine -> addFrom; Route.Compose -> Route.Output; Route.Snippets -> snippetsFrom; Route.AlertRelay -> alertRelayFrom; Route.Decision -> Route.Output
        Route.GuardedAnswers -> guardedFrom; Route.Machines -> machinesFrom; Route.Machine -> Route.Machines; else -> Route.Home
    }
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
    val gateNotice by graph.proService.gateNotice.collectAsState()
    gateNotice?.let { GateNoticeBar(it, onDismiss = graph.proService::dismissGateNotice, modifier = Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp)) }
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
                            // Review prompt: the Output tab (the request entry) when the phone can answer this agent, else its Terminal tab, observing.
                            onReviewPrompt = { terminalId = it; outputTab = reviewTab(graph, it); route = Route.Output },
                            onSettings = { route = Route.Settings },
                            onSetUpKey = { editing = true; authorizeIntro = false; pairing = null; addFrom = Route.Home; route = Route.AddMachine },
                            onShowCommand = { editing = true; authorizeIntro = true; pairing = null; addFrom = Route.Home; route = Route.AddMachine },
                            onNotice = { alertNotice = it },
                            onMachines = { machinesFrom = Route.Home; route = Route.Machines },
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
                    onSetUp = { if (graph.proService.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true)) { guardedFrom = Route.Decision; route = Route.GuardedAnswers } },
                )
                Route.Compose -> ComposeRoute(
                    graph, terminalId, draft, { change -> draft = change(draft) }, onBack = { route = Route.Output },
                    onOpenTerminal = { outputTab = AgentTab.Terminal; route = Route.Output },
                    onEditSnippets = { snippetsFrom = Route.Compose; route = Route.Snippets },
                )
                Route.Snippets -> SnippetsRoute(graph, onBack = { route = snippetsFrom })
                Route.Settings -> SettingsRoute(
                    graph, onBack = { route = Route.Home }, onMachines = { machinesFrom = Route.Settings; route = Route.Machines },
                    onEditSnippets = { snippetsFrom = Route.Settings; route = Route.Snippets },
                    onAlertRelay = { alertRelayFrom = Route.Settings; route = Route.AlertRelay },
                )
                Route.AlertRelay -> AlertRelayRoute(graph, onBack = { route = alertRelayFrom })
                Route.Machines -> MachinesRoute(
                    graph, busy = removingId != null, onBack = { route = machinesFrom },
                    onOpen = { machineId = it.id; route = Route.Machine },
                    onAdd = { editing = false; pairing = null; addFrom = Route.Machines; route = Route.AddMachine },
                    onNotice = { alertNotice = it },
                )
                Route.Machine -> MachineRoute(
                    graph, machineId, removingId, onBack = { route = Route.Machines },
                    onAlertRelay = { alertRelayFrom = Route.Machine; route = Route.AlertRelay },
                    onGuardedAnswers = { if (graph.proService.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = false)) { guardedFrom = Route.Machine; route = Route.GuardedAnswers } },
                    onNotice = { alertNotice = it },
                )
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

/** How long Add machine waits for the connection to decide before saying it is still waiting. Not counted while a host key question is on screen (the connector allows that its own, longer time). */
private const val CONNECT_WAIT_MILLIS = 45_000L
