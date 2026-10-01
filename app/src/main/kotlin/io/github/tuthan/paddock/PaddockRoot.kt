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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.ImportedKeyInfo
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
import io.github.tuthan.paddock.live.PreviewState
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

private enum class Route { Home, Output, Activity, Settings, AddMachine }

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
    var relayDismissed by rememberSaveable { mutableStateOf(false) }
    var reviewKey by rememberSaveable { mutableStateOf(false) }

    val effective = if (boot == Boot.NoMachines) Route.AddMachine else route
    val backTo = if (effective == Route.AddMachine) addFrom else Route.Home
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
                            onOpen = { terminalId = it; route = Route.Output }, onSettings = { route = Route.Settings },
                        ) else ActivityRoute(graph)
                    }
                    PaddockNavBar(NAV, if (effective == Route.Home) 0 else 1, { route = if (it == 0) Route.Home else Route.Activity })
                }
                Route.Output -> OutputRoute(graph, terminalId, onBack = { route = Route.Home })
                Route.Settings -> SettingsRoute(graph, onBack = { route = Route.Home }, onAddMachine = { addFrom = Route.Settings; route = Route.AddMachine })
                Route.AddMachine -> AddMachineRoute(graph, canGoBack = boot == Boot.Ready, onBack = { route = backTo }, onAdded = { route = Route.Home; relayDismissed = false })
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
    onOpen: (terminalId: String) -> Unit, onSettings: () -> Unit,
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
                null -> Unit
            }
        },
    )
}

@Composable
private fun OutputRoute(graph: AppGraph, terminalId: String?, onBack: () -> Unit) {
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
    var tab by rememberSaveable { mutableStateOf(AgentTab.Output) }
    val now = rememberNow()
    val row: AgentRowModel? = home?.rows?.firstOrNull { it.key.target.terminalId == terminalId }
    // "claude · api › tab 2 · main · laptop": what it is, where, which session, which machine.
    val context = listOfNotNull(row?.agentKind, row?.context?.ifEmpty { null }, host.sessionName, profile?.name).joinToString(" · ")
    val header = AgentHeader(row?.title ?: "Agent", context, row?.state ?: StateWord.Unknown, row?.observedAtMillis)
    AgentOutput(header, output, following, now, tab, { tab = it }, onBack, onUserScrolledUp = { feed.userScrolledUp() }, onResumeFollowing = { feed.resumeFollowing() })
}

@Composable
private fun ActivityRoute(graph: AppGraph) {
    val profile by graph.profile.collectAsState()
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    val now = rememberNow()
    // Re-read each second: the ledger is small and in memory.
    val presenter = remember(profile) {
        ActivityPresenter(java.time.ZoneId.systemDefault(), java.util.Locale.getDefault(), hostName = { profile?.name ?: it }, titleOf = { _, _, tid -> graph.hostUi.view.value.lastHome?.rows?.firstOrNull { it.key.target.terminalId == tid }?.title })
    }
    val sections = remember(filter, now / 1_000) { presenter.present(Activity.build(graph.ledger.observations(), graph.ledger.actions(), filter), now) }
    ActivityLog(sections, filter, { filter = it })
}

@Composable
private fun SettingsRoute(graph: AppGraph, onBack: () -> Unit, onAddMachine: () -> Unit) {
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
        SettingsState(settings.protectSensitiveScreens, access, version, machine = machine, herdrVersion = view.herdrVersion),
        onProtectSensitive = { scope.launch { graph.setProtectSensitive(it) } },
        onOpenSystemSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = onBack,
        onAddMachine = onAddMachine,
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
    // Keystore reads and key generation can take a while on some phones: off the main thread. An unreadable key (lost
    // Keystore entry, corrupt store) reads as "no key" here; connecting reports it with its own recovery.
    val key by produceState<Pair<String, io.github.tuthan.paddock.ssh.KeyBacking>?>(null, keyTick) {
        value = withContext(Dispatchers.Default) {
            runCatching { if (graph.phoneKey.exists()) graph.phoneKey.publicLine("paddock@phone") to graph.phoneKey.info().backing else null }.getOrNull()
        }
    }
    var importedTick by remember { mutableStateOf(0) }
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
        publicKeyLine = key?.first,
        keyBacking = key?.second,
        importedKeyId = imported?.id,
        importedKeySummary = imported?.let { "${it.keyType} · ${it.fingerprint}" },
        permissionDenied = denied,
    )
    holder.SaveableStateProvider("add-machine") {
        AddMachine(
            state,
            onConnect = { input ->
                if (graph.gate.needsRequest(AddMachineForm.normalizeHost(input.host))) { pending = input; permission.launch(LocalNetworkPolicy.PERMISSION) } else finish(input)
            },
            onGenerateKey = { scope.launch { withContext(Dispatchers.Default) { runCatching { graph.phoneKey.getOrCreate() } }; keyTick++ } },
            onCopyPublicKey = { line -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock public key", line)) },
            onOpenSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
            onBack = { if (canGoBack) onBack() },
            onImportKey = { importing = true },
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
