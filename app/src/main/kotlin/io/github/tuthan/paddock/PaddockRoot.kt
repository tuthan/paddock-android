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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.FingerprintDialog
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.ActivityLog
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.HerdHome
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.RelayInstall
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Route { Home, Output, Activity, Settings, AddMachine }

/**
 * The app's one navigation host. Screens are stateless; this connects them to the graph. The route and the open terminal
 * survive rotation (`rememberSaveable`); everything else is read from the graph.
 */
@Composable
fun PaddockRoot(graph: AppGraph, modifier: Modifier = Modifier) {
    val boot by graph.boot.collectAsState()
    var route by rememberSaveable { mutableStateOf(Route.Home) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var relayDismissed by rememberSaveable { mutableStateOf(false) }
    var reviewKey by rememberSaveable { mutableStateOf(false) }

    val effective = if (boot == Boot.NoMachines) Route.AddMachine else route
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (boot) {
            Boot.Loading -> Text("Paddock", style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title, modifier = Modifier.padding(PaddockTokens.spacing.gutter))
            else -> when (effective) {
                Route.Home -> HomeRoute(
                    graph, relayDismissed, { relayDismissed = it }, { reviewKey = true },
                    onOpen = { terminalId = it; route = Route.Output }, onNav = { route = it },
                )
                Route.Output -> OutputRoute(graph, terminalId, onBack = { route = Route.Home })
                Route.Activity -> ActivityRoute(graph, onBack = { route = Route.Home })
                Route.Settings -> SettingsRoute(graph, onBack = { route = Route.Home })
                Route.AddMachine -> AddMachineRoute(graph, canGoBack = boot == Boot.Ready, onBack = { route = Route.Home }, onAdded = { route = Route.Home; relayDismissed = false })
            }
        }
        HostKeyDialogs(graph, reviewKey) { reviewKey = false }
    }
    BackHandler(enabled = effective != Route.Home && boot == Boot.Ready) { route = Route.Home }
}

private fun Route.label() = name

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    return now
}

@Composable
private fun HomeRoute(
    graph: AppGraph, relayDismissed: Boolean, setRelayDismissed: (Boolean) -> Unit, onReviewKey: () -> Unit,
    onOpen: (terminalId: String) -> Unit, onNav: (Route) -> Unit,
) {
    val profile by graph.profile.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val now = rememberNow()
    val name = profile?.name ?: "this machine"
    val screen = HomeUiMapper.map(name, view, now)
    val ctx = LocalContext.current

    val prompt = screen.relayPrompt
    if (prompt != null && !relayDismissed) {
        RelayInstall(name, prompt, installing = false, onInstall = { graph.installRelay() }, onNotNow = { setRelayDismissed(true) })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PaddockTokens.spacing.gutter), horizontalArrangement = Arrangement.End) {
            TopLink("Activity") { onNav(Route.Activity) }
            TopLink("Add machine") { onNav(Route.AddMachine) }
            TopLink("Settings") { onNav(Route.Settings) }
        }
        Box(Modifier.weight(1f)) {
            HerdHome(
                screen.state, now,
                onOpenAgent = { paneId ->
                    val rows = (screen.state as? HomeUiState.Live)?.model?.rows ?: (screen.state as? HomeUiState.Degraded)?.model?.rows.orEmpty()
                    val row = rows.firstOrNull { it.paneId == paneId } ?: return@HerdHome
                    // A Done tap is the user acknowledging it: local only, then the agent's output opens.
                    (view.phase as? HostPhase.Monitoring)?.host?.let { h -> if (row.state == StateWord.Done) h.markSeen(row) }
                    onOpen(row.key.target.terminalId)
                },
                onRecovery = {
                    when (screen.recovery) {
                        Recovery.OpenSettings -> ctx.startActivity(graph.gate.settingsIntent())
                        Recovery.ReviewKey -> onReviewKey()
                        Recovery.InstallRelay -> setRelayDismissed(false)
                        Recovery.Retry -> graph.retry()
                        null -> Unit
                    }
                },
            )
        }
    }
}

@Composable
private fun TopLink(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = PaddockTokens.spacing.touchTarget).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = PaddockTokens.type.secondary, color = PaddockTokens.colors.accent) }
}

@Composable
private fun OutputRoute(graph: AppGraph, terminalId: String?, onBack: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val settings by graph.settings.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    if (terminalId == null || host == null) {
        // The connection is not up (or the app was restored without one): say so and offer the way back.
        Column(Modifier.padding(PaddockTokens.spacing.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This agent is not available right now.", style = PaddockTokens.type.body, color = PaddockTokens.colors.title)
            TopLink("Back to the list", onBack)
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
    var tab by rememberSaveable { mutableStateOf(AgentTab.Output) }
    val now = rememberNow()
    val row: AgentRowModel? = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    val header = AgentHeader(row?.title ?: "Agent", row?.context ?: "", row?.state ?: StateWord.Unknown, row?.observedAtMillis)
    AgentOutput(header, output, following, now, tab, { tab = it }, onBack, onUserScrolledUp = { feed.userScrolledUp() }, onResumeFollowing = { feed.resumeFollowing() })
}

@Composable
private fun ActivityRoute(graph: AppGraph, onBack: () -> Unit) {
    val profile by graph.profile.collectAsState()
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val now = rememberNow()
    // Re-read each second: the ledger is small and in memory.
    val presenter = remember(profile) {
        ActivityPresenter(java.time.ZoneId.systemDefault(), java.util.Locale.getDefault(), hostName = { profile?.name ?: it }, titleOf = { _, _, tid -> graph.hostUi.view.value.lastHome?.rows?.firstOrNull { it.key.target.terminalId == tid }?.title })
    }
    val sections = remember(filter, now / 1_000) { presenter.present(Activity.build(graph.ledger.observations(), graph.ledger.actions(), filter), now) }
    Column(Modifier.fillMaxSize()) {
        TopLink("← Back", onBack)
        Box(Modifier.weight(1f)) { ActivityLog(sections, filter, { filter = it }) }
    }
}

@Composable
private fun SettingsRoute(graph: AppGraph, onBack: () -> Unit) {
    val settings by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val access = when {
        !graph.gate.lanAccessApplies() -> LocalAccess.NotRequired
        graph.gate.lanAccessMissing() -> LocalAccess.Denied
        else -> LocalAccess.Granted
    }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown" }
    Settings(
        SettingsState(settings.protectSensitiveScreens, access, version),
        onProtectSensitive = { scope.launch { graph.setProtectSensitive(it) } },
        onOpenSystemSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = onBack,
    )
}

@Composable
private fun AddMachineRoute(graph: AppGraph, canGoBack: Boolean, onBack: () -> Unit, onAdded: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyTick by remember { mutableStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AddMachineInput?>(null) }

    fun finish(input: AddMachineInput) {
        scope.launch {
            val existing = graph.profiles.list().map { it.id }.toSet()
            val profile = AddMachineForm.profile(input, existing) ?: return@launch
            graph.addMachine(profile)
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
    val key = remember(keyTick) { if (graph.phoneKey.exists()) graph.phoneKey.info() else null }
    val state = AddMachineState(
        route = { host -> AddMachineForm.route(host, graph.gate.decide(AddMachineForm.normalizeHost(host))) },
        publicKeyLine = key?.let { graph.phoneKey.publicLine("paddock@phone") },
        keyBacking = key?.backing,
        permissionDenied = denied,
    )
    AddMachine(
        state,
        onConnect = { input ->
            if (graph.gate.needsRequest(AddMachineForm.normalizeHost(input.host))) { pending = input; permission.launch(LocalNetworkPolicy.PERMISSION) } else finish(input)
        },
        onGenerateKey = { graph.phoneKey.getOrCreate(); keyTick++ },
        onCopyPublicKey = { line -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock public key", line)) },
        onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = { if (canGoBack) onBack() },
    )
}

@Composable
private fun HostKeyDialogs(graph: AppGraph, reviewKey: Boolean, dismissReview: () -> Unit) {
    val request by graph.broker.firstTrust.collectAsState()
    request?.let { r ->
        FingerprintDialog(HostKeyPrompts.firstTrust(r.endpoint, r.presented), onTrust = { graph.broker.answerFirstTrust(true) }, onCancel = { graph.broker.answerFirstTrust(false) })
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
