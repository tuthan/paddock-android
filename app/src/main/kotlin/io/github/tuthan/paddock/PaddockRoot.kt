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
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.hostprofile.Boot
import io.github.tuthan.paddock.ledger.Activity
import io.github.tuthan.paddock.nav.NavState
import io.github.tuthan.paddock.nav.Route
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.routes.ActivityRoute
import io.github.tuthan.paddock.routes.AddMachineDeps
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
import io.github.tuthan.paddock.routes.ShareRoute
import io.github.tuthan.paddock.routes.SnippetsRoute
import io.github.tuthan.paddock.routes.SpacesRoute
import io.github.tuthan.paddock.routes.WelcomeRoute
import io.github.tuthan.paddock.routes.decisionSetUp
import io.github.tuthan.paddock.ui.components.LocalAgentGlyphs
import io.github.tuthan.paddock.ui.components.NavItem
import io.github.tuthan.paddock.ui.components.NoticeBar
import io.github.tuthan.paddock.ui.components.PaddockNavBar
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.GateNoticeBar
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.Welcome
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val NAV = listOf(NavItem("Herd", PaddockIcons.Herd), NavItem("Spaces", PaddockIcons.Spaces), NavItem("Activity", PaddockIcons.Activity))

/**
 * The app's one navigation host. Screens are stateless; this connects them to the graph. The route, where Add machine
 * was opened from, and the open terminal survive rotation ([NavState]); everything else is read from the graph.
 * Home, Spaces and Activity share the bottom bar; Settings is the gear on Home. Machines (the saved machines) opens from the watched chip on Home and
 * from Settings, and each machine's page from there; Add machine opens from Machines (and from Welcome with no machine).
 */
@Composable
fun PaddockRoot(graph: AppGraph, modifier: Modifier = Modifier) {
    // One provider for every agent tile in the app, so Settings > Agent icons redraws the herd at once without threading it through each screen.
    val agentGlyphs by remember(graph) { graph.prefs.settings.map { it.agentGlyphs }.distinctUntilChanged() }.collectAsState(true)
    val results = graph.activityResultsOverride
    val locals = listOfNotNull(
        LocalAgentGlyphs provides agentGlyphs,
        results?.let { androidx.activity.compose.LocalActivityResultRegistryOwner provides it },
    )
    CompositionLocalProvider(*locals.toTypedArray()) { AppLockGate(graph.appLock, graph.prefs, graph.triggers, graph.scope) { PaddockRootContent(graph, modifier) } }
}

@Composable
private fun PaddockRootContent(graph: AppGraph, modifier: Modifier) {
    val boot by graph.machineBook.boot.collectAsState()
    val nav = rememberSaveable(saver = NavState.Saver) { NavState() }
    val add = nav.add
    // A removal confirmed on a machine's page and still running: Watch and Remove wait for it on the list and the page (the graph also serializes them).
    // The graph's, not this composition's: the removal runs in the graph's scope and outlives a rotation.
    val removingId by graph.machineBook.removingMachine.collectAsState()
    val watchedId = graph.machineBook.profile.collectAsState().value?.id
    LaunchedEffect(watchedId) { nav.watchedChanged(watchedId) }
    // The composer's text lives here, above the route: leaving the composer (Edit snippets, Back) or a reconnect that swaps it for "not available" must not lose it.
    var draft by rememberSaveable(stateSaver = PromptDraftSaver) { mutableStateOf(PromptDraft()) }
    // Once per composition of the root, after the saved state is back: what the file features left in the cache goes unless the draft still names it (Phase 15, SEC-2).
    LaunchedEffect(Unit) { graph.sweepFiles(setOfNotNull(draft.attachment?.snapshotId)) }

    AlertEvents(graph.alerts, graph.alertService, graph.hostUi, graph.proService, nav, boot)
    PairingEvents(graph.pairing, nav, boot)
    RemovalOutcomes(graph.machineBook, nav)
    ShareEvents(graph.shares, graph.shareNotice, graph.appLock, nav, boot)
    Relocations(graph.relocator, nav)
    FollowConnectAttempt(graph.broker, graph.hostUi, nav)

    val effective = nav.effective(boot)
    LaunchedEffect(nav.onAgent(effective), nav.terminalId) { if (nav.onAgent(effective)) graph.manualInput.leaveUnless(nav.terminalId) else graph.manualInput.leave() }
    val backTo = nav.backTo(effective)
    // Registered before the screens', so a screen's own back handling (the import screen's) is asked first.
    BackHandler(enabled = effective != Route.Home && boot == Boot.Ready) { nav.back(backTo) }
    // With no machine yet, Back from the form returns to Welcome instead of leaving the app.
    BackHandler(enabled = boot == Boot.NoMachines && add.attempt == null && (add.welcomeChoice || add.pairing != null)) { nav.backToWelcome() }
    BackHandler(enabled = add.welcomeFinder && effective == Route.Welcome) { add.welcomeFinder = false }
    BackHandler(enabled = add.welcomeScan && effective == Route.Welcome) { add.welcomeScan = false }
    ProGateHost(graph.proService, graph.manualInput, graph.journal, graph.entitlements, pendingAnswerOnScreen = effective == Route.Decision || effective == Route.GuardedAnswers)
    Column(modifier.fillMaxSize().safeDrawingPadding()) {
    nav.alertNotice?.let { NoticeBar(it, onDismiss = { nav.alertNotice = null }, modifier = Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp)) }
    // Why a tap on a Pro control did nothing (the gate never opens over a busy screen); the graph clears it after a few seconds.
    val gateNotice by graph.proService.gateNotice.collectAsState()
    gateNotice?.let { GateNoticeBar(it, onDismiss = graph.proService::dismissGateNotice, modifier = Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp)) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (boot) {
            Boot.Loading -> Text("Paddock", style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title, modifier = Modifier.padding(PaddockTokens.spacing.gutter))
            else -> when (effective) {
                Route.Welcome -> if (add.welcomeFinder) FindRoute(
                    graph.finder, graph.gate, onPick = { add.found = it; add.welcomeFinder = false; add.welcomeChoice = true }, onBack = { add.welcomeFinder = false },
                ) else if (add.welcomeScan) ScanRoute(
                    graph.gate, onLink = { add.pairing = it; add.welcomeScan = false; add.welcomeChoice = true }, onBack = { add.welcomeScan = false },
                ) else WelcomeRoute(
                    onEnterAddress = { add.welcomeChoice = true }, onFind = { add.welcomeFinder = true }, onScan = { add.welcomeScan = true },
                    onPairing = { add.pairing = it; add.welcomeChoice = true },
                )
                Route.Home, Route.Spaces, Route.Activity -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (effective == Route.Spaces) SpacesRoute(
                            graph.machineBook, graph.hostUi, graph.proService, graph.scope, onNotice = { nav.alertNotice = it },
                            onOpenAgent = { nav.openAgent(it) },
                        ) else if (effective == Route.Home) HomeRoute(
                            graph.machineBook, graph.hostUi, graph.wakes, graph.prefs, graph.proService, graph.probe, graph.relocator, graph.gate, graph.scope,
                            retry = graph::retry, installRelay = graph::installRelay,
                            relayDismissed = nav.relayDismissed, setRelayDismissed = { nav.relayDismissed = it }, onReviewKey = { nav.reviewKey = true },
                            onOpen = { nav.openAgent(it) },
                            // Review prompt: the Output tab (the request entry) when the phone can answer this agent, else its Terminal tab, observing.
                            onReviewPrompt = { nav.openAgent(it, reviewTab(graph.hostUi, graph.proService, it)) },
                            onSettings = { nav.route = Route.Settings },
                            onSetUpKey = { nav.openAddMachineForKey(showCommand = false) },
                            onShowCommand = { nav.openAddMachineForKey(showCommand = true) },
                            onNotice = { nav.alertNotice = it },
                            onMachines = { nav.openMachines(Route.Home) },
                        ) else ActivityRoute(graph.ledger, graph.journal, graph.hostUi, graph.machineBook, graph::savedSagas, onOpenAgent = { nav.openAgent(it) })
                    }
                    PaddockNavBar(NAV, when (effective) { Route.Home -> 0; Route.Spaces -> 1; else -> 2 }, { nav.route = when (it) { 0 -> Route.Home; 1 -> Route.Spaces; else -> Route.Activity } })
                }
                Route.Output -> OutputRoute(graph.hostUi, graph.prefs, graph.machineBook, graph.proService, graph.manualInput, graph.terminals, graph.selections, graph.gets, graph.transferCache, graph.transferHold, nav.terminalId, nav.outputTab, { nav.outputTab = it }, onBack = { nav.route = Route.Home }, onCompose = { nav.route = Route.Compose }, onDecision = { nav.route = Route.Decision })
                Route.Decision -> DecisionRoute(
                    graph.hostUi, graph.prefs, graph.machineBook, graph.proService, nav.terminalId, onBack = { nav.route = Route.Output },
                    onOpenTerminal = { nav.outputTab = AgentTab.Terminal; nav.route = Route.Output },
                    // Locked, the sheet does not offer Set up at all (decisionSetUp): the gate may not open over a request, so a button here would be a dead one.
                    // The check stays for the moment Pro goes away between the frame and the tap; the next frame already shows the locked sheet.
                    onSetUp = { if (graph.proService.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true)) nav.openGuardedAnswers(Route.Decision) },
                )
                Route.Compose -> ComposeRoute(
                    graph.hostUi, graph.prefs, graph.machineBook, graph.journal, graph.manualInput, nav.terminalId, draft, { change -> draft = change(draft) },
                    graph.incoming, graph.transferHold, onBack = { nav.route = Route.Output },
                    onOpenTerminal = { nav.outputTab = AgentTab.Terminal; nav.route = Route.Output },
                    onEditSnippets = { nav.openSnippets(Route.Compose) },
                )
                Route.Share -> ShareRoute(
                    graph.shares, graph.shareSender, graph.incoming, graph.machineBook, graph.prefs, graph.hostUi, graph.proService, graph.scope, draft, { change -> draft = change(draft) },
                    onBack = { nav.route = Route.Home }, onComposeAgent = { nav.openAgent(it); nav.route = Route.Compose }, onNotice = { nav.alertNotice = it },
                )
                Route.Snippets -> SnippetsRoute(graph.prefs, onBack = { nav.route = nav.snippetsFrom })
                Route.Settings -> SettingsRoute(
                    graph.prefs, graph.machineBook, graph.hostUi, graph.proService, graph.gate, graph.journal, graph.notificationAccess, graph.appLock, graph.clock,
                    onBack = { nav.route = Route.Home }, onMachines = { nav.openMachines(Route.Settings) },
                    onEditSnippets = { nav.openSnippets(Route.Settings) },
                )
                Route.AlertRelay -> AlertRelayRoute(graph.hostUi, graph.machineBook, graph.push, graph.alertSetups, graph.hostScripts, graph.clock, graph.clipGuard, onBack = { nav.route = nav.alertRelayFrom })
                Route.Machines -> MachinesRoute(
                    graph.machineBook, graph.prefs, graph.hostUi, graph.proService, graph.push, graph.wakes, graph.scope, graph.clock, busy = removingId != null, onBack = { nav.route = nav.machinesFrom },
                    onOpen = { nav.openMachine(it.id) },
                    onAdd = { nav.openAddMachineFromMachines() },
                    onNotice = { nav.alertNotice = it },
                )
                Route.Machine -> MachineRoute(
                    graph.machineBook, graph.prefs, graph.hostUi, graph.proService, graph.push, graph.wakes, graph.scope, graph.clock, graph.lanPaths, graph.relocator, nav.machineId, removingId, onBack = { nav.route = Route.Machines },
                    onAlertRelay = { nav.openAlertRelay(Route.Machine) },
                    onGuardedAnswers = { if (graph.proService.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = false)) nav.openGuardedAnswers(Route.Machine) },
                    onNotice = { nav.alertNotice = it },
                )
                Route.GuardedAnswers -> GuardedAnswersRoute(graph.hostUi, graph.machineBook, graph.hostScripts, onBack = { nav.route = nav.guardedFrom })
                Route.AddMachine -> AddMachineRoute(
                    remember(graph) { AddMachineDeps.of(graph) }, editing = add.editing && boot == Boot.Ready, pairing = add.pairing,
                    // With no machine the header arrow is the system Back: it returns to Welcome.
                    onBack = { nav.leaveAddMachine(boot, backTo) },
                    attempt = add.attempt, onAttempt = { add.attempt = it }, authorizeIntro = add.authorizeIntro,
                    // A link or a pick replaces the form's machine: an error that belonged to the one before is not this one's.
                    onPairing = { add.attempt = null; add.pairing = it }, onNotice = { nav.alertNotice = it },
                    picked = add.found, onPicked = { add.found = it },
                )
            }
        }
        HostKeyDialogs(graph.broker, graph.machineBook, graph::replaceKey, nav.reviewKey) { nav.reviewKey = false }
    }
    }
}
