package io.github.tuthan.paddock.routes

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.tuthan.paddock.files.ApkCheck
import io.github.tuthan.paddock.files.ApkNotes
import io.github.tuthan.paddock.files.FileTransfers
import io.github.tuthan.paddock.files.GetController
import io.github.tuthan.paddock.files.ListWhich
import io.github.tuthan.paddock.files.TransferCache
import io.github.tuthan.paddock.files.TransferRefusal
import io.github.tuthan.paddock.files.TransferReply
import io.github.tuthan.paddock.files.folder
import io.github.tuthan.paddock.lifecycle.TransferHold
import io.github.tuthan.paddock.output.SafeText
import io.github.tuthan.paddock.ui.screens.GetCopy
import io.github.tuthan.paddock.ui.screens.OutboxRowUi
import io.github.tuthan.paddock.ui.screens.OutboxSheet
import io.github.tuthan.paddock.ui.screens.OutboxUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.tuthan.paddock.ui.CopyKind
import io.github.tuthan.paddock.ui.CopyWords
import io.github.tuthan.paddock.ui.commandShareIntent
import io.github.tuthan.paddock.ui.copyNotice
import io.github.tuthan.paddock.ui.copyText
import io.github.tuthan.paddock.ui.screens.SelectHolder
import io.github.tuthan.paddock.ui.screens.SelectText
import io.github.tuthan.paddock.ui.screens.Since
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.host.Retained
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.FocusRules
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.output.OutputCopy
import io.github.tuthan.paddock.output.OutputState
import io.github.tuthan.paddock.ui.components.LineAction
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.terminal.TerminalSession
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.AgentOutput
import io.github.tuthan.paddock.ui.screens.AgentTab
import io.github.tuthan.paddock.ui.screens.FocusGuard
import io.github.tuthan.paddock.ui.screens.outputFeedVisible
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Composable
internal fun OutputRoute(
    hostUi: HostUiModel, prefs: SettingsController, machineBook: MachineBook, proService: ProService, manualInput: ManualInputMode, terminals: Retained<TerminalSession>,
    selections: Retained<SelectHolder>, gets: Retained<GetController>, transferCache: TransferCache, transferHold: TransferHold,
    terminalId: String?, tab: AgentTab, onTab: (AgentTab) -> Unit, onBack: () -> Unit, onCompose: () -> Unit, onDecision: () -> Unit) {
    val view by hostUi.view.collectAsState()
    val settings by prefs.settings.collectAsState()
    val profile by machineBook.profile.collectAsState()
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
    // The Select text view shows a frozen copy, so the feed reads nothing while it is open. `selecting` is saved (the view comes back after a rotation, held by
    // `selections`); after a process death it is restored with no copy behind it, so the Output tab comes back instead.
    var selecting by rememberSaveable(terminalId) { mutableStateOf(false) }
    val outputShown = outputFeedVisible(resumed, tab, selecting)
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Phase 15, Get: paths the agent printed are links and the agent has a Files list, wherever the machine can transfer and herdr reports the agent's folder.
    val transfers = host.transfers
    val getKey = remember(host, terminalId) { host to terminalId }
    val getter = if (transfers != null) remember(getKey) {
        gets.acquire(getKey) {
            GetController(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), transfers, transferCache, host.profile.id,
                apk = { f -> ApkNotes.lines(ApkCheck.read(ctx, f), ctx.packageName, ApkCheck.ownSigners(ctx)) },
            )
        }
    } else null
    val getFolder = focus.folder
    val getAgentKey = focus.key
    val canGet = getter != null && getFolder != null && getAgentKey != null
    val selectKey = remember(host, terminalId) { host to terminalId }
    val holder = if (selecting) remember(selectKey, selecting) { selections.current(selectKey) } else null
    LaunchedEffect(selecting, holder) { if (selecting && holder == null) selecting = false }
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity }
    DisposableEffect(holder) {
        onDispose { holder?.let { selections.release(it, recreating = activity?.isChangingConfigurations == true) } }
    }
    DisposableEffect(getter) {
        onDispose { getter?.let { gets.release(it, recreating = activity?.isChangingConfigurations == true) } }
    }
    var outboxOpen by rememberSaveable(terminalId) { mutableStateOf(false) }
    var outboxVersion by remember { mutableStateOf(0) }
    var outbox by remember { mutableStateOf<OutboxUi>(OutboxUi.Reading) }
    LaunchedEffect(outboxOpen, outboxVersion, getFolder) {
        if (outboxOpen && transfers != null && getFolder != null) {
            outbox = OutboxUi.Reading
            outbox = readOutbox(transfers, getFolder, now = { System.currentTimeMillis() })
        }
    }
    var reReading by remember { mutableStateOf(false) }
    val openSelect = { shown: OutputState.Showing, startLine: Int? -> selections.acquire(selectKey) { SelectHolder(shown, startLine) }; selecting = true }
    // A permission request the hook published for this agent: the entry above the output opens the sheet. Nothing here can answer it.
    // The entry is the only reader of the request, and it is not offered without Pro (below), so without Pro the host's request files are not polled over SSH
    // and there is no decision here at all. `locked` is read from state: when Pro appears the watch starts without the screen restarting.
    val pro by proService.view.collectAsState()
    val answersLocked = proService.locked(ProCapabilities.GUARDED_ANSWERS, pro)
    val decision = rememberDecision(host, terminalId, now, watch = !answersLocked)?.takeIf { !answersLocked }
    if (holder != null) {
        val snap by holder.snapshot.collectAsState()
        val since = when (output) { OutputState.AgentGone -> Since.AgentExited; OutputState.PaneGone -> Since.TerminalClosed; else -> null }
        val readAtTime = remember(snap.readAtMillis) { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(snap.readAtMillis)) }
        BackHandler { selecting = false }
        SelectText(
            header.title, snap, readAtTime, since, settings.protectSensitiveScreens, holder.startLine, reReading,
            onCopyAll = { fit ->
                val said = if (copyText(ctx, CopyKind.Output, fit.text, settings.protectSensitiveScreens)) copyNotice(Build.VERSION.SDK_INT, fit.lines.size, fit.cut, fit.leftOut) else CopyWords.COULD_NOT
                said?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            },
            onShare = { fit -> ctx.startActivity(Intent.createChooser(commandShareIntent(fit.text), "Share the output")) },
            onReRead = {
                if (!reReading) scope.launch {
                    reReading = true
                    try {
                        if (feed.readOnce()) (feed.state.value as? OutputState.Showing)?.let { holder.snapshot.value = it }
                        else holder.snapshot.value = holder.snapshot.value.copy(stale = true)
                    } finally { reReading = false }
                }
            },
            onClose = { selecting = false },
        )
        return
    }
    // The tap checks the gate again, as for the keys; the first time ever it asks what focus does.
    FocusGuard(settings.desktopFocusConfirmed, onConfirmed = prefs::setDesktopFocusConfirmed, focus = { focus.key?.takeIf { focus.gate is OperationGate.Open }?.let { host.sends?.focus(it) } }) { requestFocus ->
        val manual = rememberManualInput(
            manualInput, host, terminalId, focus.gate, now, (output as? OutputState.Showing)?.lines?.map { it.text },
            onOpenTerminal = { onTab(AgentTab.Terminal) }, onFocus = requestFocus,
        )
        AgentOutput(
            header, output, following, now, tab, onTab, onBack, onUserScrolledUp = { feed.userScrolledUp() }, onResumeFollowing = { feed.resumeFollowing() },
            terminal = { TerminalRoute(terminals, host, terminalId) },
            onCompose = if (host.sends != null) onCompose else null,
            manualInput = manual?.first, manualActions = manual?.second,
            // Tapping the entry is the user choosing to look at the newest request: it becomes the one on the sheet before the sheet is up.
            // Without Pro the entry is not offered at all (`decision` is null): a pending request is never answered by a purchase screen, and typing into the terminal stays free.
            decision = decision?.entry, onOpenDecision = { decision?.answers?.review(terminalId); onDecision() },
            onSelectText = (output as? OutputState.Showing)?.let { shown -> { openSelect(shown, null) } },
            onPath = if (canGet) { token -> getter!!.look(getFolder!!, getAgentKey!!, token) } else null,
            onFiles = if (canGet) { { outboxOpen = true } } else null,
            onLineAction = { captured, index, action ->
                val shown = output as? OutputState.Showing
                when (action) {
                    LineAction.SelectFromHere -> if (shown != null) openSelect(shown.copy(lines = captured), index)
                    LineAction.CopyLine, LineAction.CopyToNewest -> {
                        val fit = OutputCopy.fit(if (action == LineAction.CopyLine) listOf(captured[index]) else captured.subList(index, captured.size))
                        val said = if (copyText(ctx, CopyKind.Output, fit.text, settings.protectSensitiveScreens)) copyNotice(Build.VERSION.SDK_INT, fit.lines.size, fit.cut, fit.leftOut) else CopyWords.COULD_NOT
                        said?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    }
                }
            },
        )
        // The outbox list, then the Get sheet over it (a tap on a row, or on a path in the output).
        if (canGet && outboxOpen) OutboxSheet(
            outbox,
            onGet = { row -> getter!!.lookAtPath(getFolder!!, getAgentKey!!, "${getFolder.trimEnd('/')}/.paddock/outbox/${row.key}") },
            onReRead = { outboxVersion++ },
            onClose = { outboxOpen = false },
        )
        if (getter != null) GetSheetHost(getter, transferHold, now)
    }
}

/**
 * The newest 50 files of the agent's outbox as rows (X2): a name the machine gave, shown through `SafeText`, its size and age. A folder that is not there is an empty
 * list, not an error. [now] is read once, so every row's age is against the same moment.
 */
internal suspend fun readOutbox(transfers: FileTransfers, folder: String, now: () -> Long): OutboxUi = when (val r = transfers.list(folder, ListWhich.Outbox)) {
    is TransferReply.Ok -> {
        val at = now()
        OutboxUi.Listed(
            r.value.map { f -> OutboxRowUi(SafeText.clean(f.name), GetCopy.sizeAndAge(f.size, f.mtimeSeconds, at), key = f.name) },
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at)),
        )
    }
    is TransferReply.Refused ->
        if (r.refusal == TransferRefusal.NotFound) OutboxUi.Listed(emptyList(), java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now())))
        else OutboxUi.Failed(r.refusal.sentence)
    is TransferReply.Failed -> OutboxUi.Failed("Could not read the folder: ${r.message}.")
}

/** The desktop-focus button's key (in the epoch now installed: it has no screen-opened epoch to go stale against) and its gate. */
private class FocusView(val key: TerminalKey?, val gate: OperationGate?, val folder: String? = null)

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
    return FocusView(key, FocusRules.gate(agent, installed?.readAtMillis, freshness == Freshness.Live, records, key, currentEpoch = epoch, journalUnreadable = journalUnreadable != null), agent?.folder())
}
