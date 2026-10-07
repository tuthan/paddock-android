package io.github.tuthan.paddock.routes

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
import io.github.tuthan.paddock.output.OutputState
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
    val pro by proService.view.collectAsState()
    val answersLocked = proService.locked(ProCapabilities.GUARDED_ANSWERS, pro)
    val decision = rememberDecision(host, terminalId, now, watch = !answersLocked)?.takeIf { !answersLocked }
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
