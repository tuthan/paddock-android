package io.github.tuthan.paddock.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ops.ComposerRules
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationGate
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationPresenter
import io.github.tuthan.paddock.ops.OperationResult
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.ops.RereadOutcome
import io.github.tuthan.paddock.ops.SendBlock
import io.github.tuthan.paddock.ops.SendGate
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.ops.TextCheck
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.AgentHeader
import io.github.tuthan.paddock.ui.screens.Composer
import io.github.tuthan.paddock.ui.screens.ComposerUi
import io.github.tuthan.paddock.ui.screens.ESC_ENTERS_MANUAL_NOTE
import io.github.tuthan.paddock.ui.screens.SnippetEditor
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/** The draft survives a rotation and a process restore with the rest of the root's saved state; a terminal id is never empty. */
internal val PromptDraftSaver = listSaver<PromptDraft, Any>(
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
internal fun ComposeRoute(
    hostUi: HostUiModel, prefs: SettingsController, machineBook: MachineBook, journal: OperationJournal, manualInput: ManualInputMode,
    terminalId: String?, draft: PromptDraft, onDraft: ((PromptDraft) -> PromptDraft) -> Unit,
    onBack: () -> Unit, onOpenTerminal: () -> Unit, onEditSnippets: () -> Unit,
) {
    val view by hostUi.view.collectAsState()
    val settings by prefs.settings.collectAsState()
    val profile by machineBook.profile.collectAsState()
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
    val snippets by prefs.snippets.collectAsState()
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
    val manual = manualView(manualInput, host, terminalId)
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
                block == SendBlock.JournalUnreadable -> journal.retryLoad()
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
            if (keyGate == null) enterManual(manualInput, host, terminalId, liveEpoch)
            else manual.key?.takeIf { keyGate is OperationGate.Open }?.let { sends.sendKey(it, OperationKind.Esc) }
        },
    )
}

@Composable
internal fun SnippetsRoute(prefs: SettingsController, onBack: () -> Unit) {
    val snippets by prefs.snippets.collectAsState()
    SnippetEditor(snippets, onChange = { prefs.setSnippets(it) }, onBack = onBack)
}
