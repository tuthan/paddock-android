package io.github.tuthan.paddock.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.ManualInputRules
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.PromptTextCheck
import io.github.tuthan.paddock.ops.RereadOutcome
import io.github.tuthan.paddock.ops.ResultLine
import io.github.tuthan.paddock.ops.SendOutcome
import io.github.tuthan.paddock.ops.TextCheck
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.screens.ManualInputActions
import io.github.tuthan.paddock.ui.screens.ManualInputUi

/** What the agent screen and the composer share about Manual input for one terminal: the session, its key and its gate. */
internal class ManualView(val session: io.github.tuthan.paddock.ops.ManualSession?, val key: TerminalKey?, val gate: OperationGate?)

@Composable
internal fun manualView(graph: AppGraph, host: MonitoredHost, terminalId: String): ManualView {
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
internal fun enterManual(graph: AppGraph, host: MonitoredHost, terminalId: String, epoch: Long?) {
    if (epoch == null) return
    graph.manualInput.enter(terminalId, System.currentTimeMillis(), epoch)
    host.refresh()
}

/** The agent screen's Manual input state and actions, or null when the host has no operations (the inert key strip stays). */
@Composable
internal fun rememberManualInput(
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
internal fun outcomeLine(presenter: OperationPresenter, outcome: SendOutcome?, currentSeq: Long?, nowMillis: Long): ResultLine? {
    outcome ?: return null
    val accepted = (outcome.result as? OperationResult.Acknowledged<*>)?.record
    return presenter.line(outcome.kind, outcome.result, noProgress = accepted != null && presenter.noProgress(accepted, currentSeq, nowMillis))
}
