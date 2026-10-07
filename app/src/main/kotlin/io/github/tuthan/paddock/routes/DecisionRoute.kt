package io.github.tuthan.paddock.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.answers.AnswerController
import io.github.tuthan.paddock.answers.AnswerGate
import io.github.tuthan.paddock.answers.Behavior
import io.github.tuthan.paddock.answers.DecisionEntryModel
import io.github.tuthan.paddock.answers.DecisionModel
import io.github.tuthan.paddock.answers.DecisionPresenter
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.DecisionActions
import io.github.tuthan.paddock.ui.screens.DecisionSheet
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.launch

/** One terminal's permission request as the Output tab's entry and the decision sheet draw it. */
internal class DecisionState(val key: TerminalKey?, val model: DecisionModel, val entry: DecisionEntryModel?, val awaitsSettle: Boolean, val answers: AnswerController)

/**
 * Watches the host's request files for [terminalId] while the screen is up (a read only while the agent is blocked) and turns them into
 * words. Null when this machine has no answers (no operation journal, so no operations at all). A reconnect changes the key, which
 * restarts the watch under the new epoch. [watch] false reads nothing from the host (the Output tab without Pro, where nothing would show the
 * request); the decision sheet always watches, because a request already on screen stays answerable whatever Pro says.
 */
@Composable
internal fun rememberDecision(host: MonitoredHost, terminalId: String, nowMillis: Long, watch: Boolean = true): DecisionState? {
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
    return DecisionState(key, model, DecisionPresenter.entry(views[terminalId], nowMillis, agent?.agent), awaitsSettle, answers)
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

/** Why the decision sheet has no Set up when guarded answers are locked: it is Pro, it is asked for on the machine's page, and the terminal answers meanwhile. */
internal const val GUARDED_SETUP_IS_PRO = "Guarded answers are Pro. They are set up from the machine's page (Settings, Machines), never over a request. Until then, answer in the terminal."

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
internal fun DecisionRoute(graph: AppGraph, terminalId: String?, onBack: () -> Unit, onOpenTerminal: () -> Unit, onSetUp: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val settings by graph.settings.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val profile by graph.machineBook.profile.collectAsState()
    // Pro gates setting guarded answers up, never the request already on screen: Yes and No work as they did.
    val pro by graph.proService.view.collectAsState()
    val setUpLocked = graph.proService.locked(ProCapabilities.GUARDED_ANSWERS, pro)
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
