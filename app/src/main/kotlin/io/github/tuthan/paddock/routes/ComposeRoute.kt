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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.files.AttachLaunchers
import io.github.tuthan.paddock.files.UploadProgress
import io.github.tuthan.paddock.files.displayNameOf
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.SendController
import io.github.tuthan.paddock.ui.screens.AttachSource
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.tuthan.paddock.files.CountingSource
import io.github.tuthan.paddock.files.FileTransfers
import io.github.tuthan.paddock.files.IncomingFiles
import io.github.tuthan.paddock.files.InboxCheck
import io.github.tuthan.paddock.files.Intake
import io.github.tuthan.paddock.files.Snapshot
import io.github.tuthan.paddock.files.Thumbnails
import io.github.tuthan.paddock.files.TransferReply
import io.github.tuthan.paddock.files.folder
import io.github.tuthan.paddock.files.rememberAttachSources
import io.github.tuthan.paddock.lifecycle.TransferHold
import io.github.tuthan.paddock.ops.DraftAttachment
import io.github.tuthan.paddock.ui.screens.ATTACH_NOTE_OFF_NO_FOLDER
import io.github.tuthan.paddock.ui.screens.AttachMenuDialog
import io.github.tuthan.paddock.ui.screens.AttachUi
import io.github.tuthan.paddock.ui.screens.AttachmentUi
import io.github.tuthan.paddock.ui.screens.Sizes
import io.github.tuthan.paddock.ui.screens.TransferUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** The draft survives a rotation and a process restore with the rest of the root's saved state; a terminal id is never empty. The attachment is its snapshot's id, never its bytes. */
internal val PromptDraftSaver = listSaver<PromptDraft, Any>(save = { it.saved() }, restore = { PromptDraft.restore(it) })

/**
 * The prompt composer for one agent. Everything it shows is derived from the host's installed read, the journal and the
 * send controller; the text and the epoch the screen opened in survive a rotation. A send runs in the host's scope, so
 * turning the phone cannot cancel it, and a refused one keeps the text.
 *
 * The app lets the connection go a few seconds after it leaves the front, and this screen says "not available" until it is back. What the user had here
 * outlives that: the text is the root's ([PromptDraft]), the connected part keeps its saved state in [keep], and the ways to attach a file (the pickers and
 * the camera, whose results can arrive while the connection is away) sit above the connection check, with the intake in [appScope] so nothing cancels it.
 */
@Composable
internal fun ComposeRoute(
    hostUi: HostUiModel, prefs: SettingsController, machineBook: MachineBook, journal: OperationJournal, manualInput: ManualInputMode,
    terminalId: String?, draft: PromptDraft, onDraft: ((PromptDraft) -> PromptDraft) -> Unit,
    files: IncomingFiles, hold: TransferHold, uploads: UploadProgress, appScope: CoroutineScope,
    onBack: () -> Unit, onOpenTerminal: () -> Unit, onEditSnippets: () -> Unit,
) {
    val view by hostUi.view.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val sends = host?.sends
    val keep = rememberSaveableStateHolder()

    // ---- Phase 15: the ways to attach a file ----
    val app = LocalContext.current.applicationContext
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    val forTerminal by rememberUpdatedState(terminalId)
    val draftNow by rememberUpdatedState(draft)
    // A result goes to the agent whose composer started it, through the draft as it is when the snapshot is made. The intake does its reading on IO; the
    // draft and the notice change on the main thread, as typing does, so neither write races the other.
    val take = { intake: suspend () -> Intake ->
        forTerminal?.let { id ->
            appScope.launch(Dispatchers.Main.immediate) {
                when (val r = intake()) {
                    is Intake.Taken -> attach(files, id, draftNow.attachmentFor(id), r.snapshot, onDraft)
                    is Intake.Refused -> notice = r.reason.sentence
                }
            }
        }
        Unit
    }
    val sources = rememberAttachSources(
        hold,
        onPicked = { uri -> take { files.take(uri, withContext(Dispatchers.IO) { displayNameOf(app, uri) }) } },
        onCaptured = { id -> take { files.takeCapture(id) } },
        onCameraDenied = { notice = "Camera access is off, so Paddock cannot take a photo. Choose Photo to pick one from this phone, or allow the camera in the phone's settings." },
        onUnavailable = { source -> notice = if (source == AttachSource.Photo) "This phone has no app to pick a photo with." else "This phone has no app to pick a file with." },
    )

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
    keep.SaveableStateProvider("compose:$terminalId") {
        ComposeConnected(
            host, sends, prefs, machineBook, journal, manualInput, terminalId, draft, onDraft, files, uploads, sources, notice, { notice = it },
            onBack, onOpenTerminal, onEditSnippets,
        )
    }
}

/** The composer while the machine is connected: the gate, the send, the attachment chip and its upload, Esc and the re-read. */
@Composable
private fun ComposeConnected(
    host: MonitoredHost, sends: SendController, prefs: SettingsController, machineBook: MachineBook, journal: OperationJournal, manualInput: ManualInputMode,
    terminalId: String, draft: PromptDraft, onDraft: ((PromptDraft) -> PromptDraft) -> Unit, files: IncomingFiles, uploads: UploadProgress,
    sources: AttachLaunchers, notice: String?, onNotice: (String?) -> Unit,
    onBack: () -> Unit, onOpenTerminal: () -> Unit, onEditSnippets: () -> Unit,
) {
    val settings by prefs.settings.collectAsState()
    val profile by machineBook.profile.collectAsState()
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
    val attachment = draft.attachmentFor(terminalId)
    // A cut upload holds only the prompt that would name its file (X9): a draft with no file sends as before.
    val gate = ComposerRules.gate(
        agent, installed?.readAtMillis, openedAt, freshness == Freshness.Live, records, key, text, currentEpoch = liveEpoch, journalUnreadable = journalUnreadable != null,
        hasAttachment = attachment != null, attachmentUnknown = attachment != null && ComposerRules.attachmentUnknown(records, key),
    )
    val outcome = outcomes[terminalId]
    // The text goes with the prompt: cleared only when herdr accepted it, kept for a refusal, a failure or an unknown outcome.
    // The draft remembers which accepted row it has already gone with, so an old accepted outcome shown again clears nothing,
    // and it goes only while it is still the text that was sent: the field stays editable during a send.
    LaunchedEffect(outcome) {
        val result = outcome?.result
        if (outcome != null && outcome.kind == OperationKind.Prompt && result is OperationResult.Acknowledged<*>) {
            // The snapshot goes with the acknowledged prompt (SEC-2); the sweep at start is the backstop for any that stay.
            val before = draft.attachmentFor(terminalId)
            if (before != null && draft.accepted(terminalId, result.record).attachment == null) files.delete(before.snapshotId)
            onDraft { it.accepted(terminalId, result.record) }
        }
    }

    // ---- Phase 15: the attachment ----
    val scope = rememberCoroutineScope()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val uploading by uploads.sent.collectAsState()
    val folder = agent?.folder()
    // The thumbnail and whether the snapshot is still there are read off the main thread; a swept file shows as gone and Send is off until it is removed.
    val look by produceState<AttachmentLook?>(null, attachment?.snapshotId) {
        value = attachment?.let { a -> withContext(Dispatchers.IO) { val f = files.file(a.snapshotId); AttachmentLook(f?.let { Thumbnails.load(it)?.asImageBitmap() }, gone = f == null) } }
    }
    LaunchedEffect(running) { if (terminalId !in running) uploads.ended(terminalId) }
    val attachUi = when {
        io.github.tuthan.paddock.demo.Demo.isDemo(host.profile) -> AttachUi.Off(io.github.tuthan.paddock.demo.DemoCopy.FILES)
        host.transfers == null -> AttachUi.Hidden
        folder == null -> AttachUi.Off(ATTACH_NOTE_OFF_NO_FOLDER)
        else -> AttachUi.On
    }
    val attachmentUi = attachment?.let { a ->
        AttachmentUi(a.name, Sizes.text(a.size), kindText(a.mime), look?.thumbnail, gone = look?.gone == true)
    }
    val transferUi = uploading[terminalId]?.takeIf { terminalId in running && attachment != null }?.let { TransferUi(attachment!!.name, it, attachment.size) }
    var inboxNote by remember { mutableStateOf<String?>(null) }

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
    val unknownFile = records.firstOrNull { it.sameTerminal(key) && it.awaitsInboxCheck }
    val readInbox = {
        unknownFile?.path?.let { path ->
            val name = path.substringAfterLast('/')
            val where = path.removeSuffix("/.paddock/inbox/$name")
            scope.launch {
                inboxNote = when (val r = host.transfers?.check(key, where, name)) {
                    is TransferReply.Ok -> when (val c = r.value) {
                        is InboxCheck.Present -> "The file is on the machine (${Sizes.text(c.size)}). Its prompt was not sent."
                        InboxCheck.Absent -> "The file is not on the machine. Nothing was sent."
                    }
                    // The agent's folder is gone (a removed worktree): no file can be there, and the check has settled the cut upload as it does for Absent.
                    is TransferReply.Refused -> if (r.refusal in FileTransfers.PROVES_ABSENT) "The file is not on the machine: the agent's folder is not there. Nothing was sent." else r.refusal.sentence
                    is TransferReply.Failed -> "Paddock could not read the inbox: ${r.message}"
                    null -> null
                }
            }
        }
        Unit
    }
    Composer(
        ComposerUi(
            header, gate, sending = terminalId in running, outcome = outcomeLine(presenter, outcome, agent?.stateChangeSeq, now), snippets = snippets, rereadLines = rereadLines,
            attach = attachUi, attachment = attachmentUi, transfer = transferUi, attachNotice = notice ?: inboxNote,
        ),
        now, text, setText, onSnippet = { setText(Snippets.insert(text, it)) },
        onSend = {
            if (attachment == null) sends.prompt(key, text, settings.keepPromptText)
            else scope.launch {
                val snap = files.snapshot(attachment.snapshotId, attachment.name, attachment.mime)
                val source = files.source(attachment.snapshotId)
                if (snap == null || source == null) { onNotice("The file is no longer on this phone. Remove it and attach it again."); return@launch }
                uploads.started(terminalId)
                sends.promptWithAttachment(key, text, snap, CountingSource(source) { uploads.sent(terminalId, it) }, settings.keepPromptText, folder)
            }
        },
        onAttach = { menuOpen = true },
        onRemoveAttachment = { attachment?.let { files.delete(it.snapshotId) }; onDraft { it.detached(terminalId) }; inboxNote = null },
        onCancelTransfer = { sends.cancel(terminalId) },
        onDismissAttachNotice = { onNotice(null); inboxNote = null },
        onBack = onBack, onEditSnippets = onEditSnippets, onOpenTerminal = onOpenTerminal, onDismissOutcome = { sends.dismiss(terminalId) },
        onReread = reread, onDismissReread = { sends.dismissReread(terminalId) },
        gateActionLabel = when { stale || block == SendBlock.NeedsReread -> "Re-read"; block == SendBlock.JournalUnreadable -> "Retry"; block == SendBlock.InboxUnread -> "Read the inbox"; else -> null },
        onGateAction = {
            when {
                stale -> { openedEpoch = liveEpoch; openedAt = System.currentTimeMillis(); host.refresh() }
                block == SendBlock.JournalUnreadable -> journal.retryLoad()
                block == SendBlock.InboxUnread -> readInbox()
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
    if (menuOpen) AttachMenuDialog(onChoose = { menuOpen = false; sources.launch(it) }, onDismiss = { menuOpen = false })
}

/** What the chip shows that is read off the main thread: the thumbnail, and whether the snapshot is still on the phone. */
private data class AttachmentLook(val thumbnail: ImageBitmap?, val gone: Boolean)

internal fun kindText(mime: String): String = when {
    mime.startsWith("image/") -> "Image"
    mime == "application/pdf" -> "PDF"
    mime.startsWith("text/") -> "Text"
    else -> "File"
}

/** The intake's snapshot becomes the draft's attachment; one already there is replaced and its file deleted (a draft holds one). */
private fun attach(files: IncomingFiles, terminalId: String, previous: DraftAttachment?, snap: Snapshot, onDraft: ((PromptDraft) -> PromptDraft) -> Unit) {
    if (previous != null && previous.snapshotId != snap.id) files.delete(previous.snapshotId)
    onDraft { it.attached(terminalId, DraftAttachment(snap.id, snap.name, snap.size, snap.mime)) }
}

@Composable
internal fun SnippetsRoute(prefs: SettingsController, onBack: () -> Unit) {
    val snippets by prefs.snippets.collectAsState()
    SnippetEditor(snippets, onChange = { prefs.setSnippets(it) }, onBack = onBack)
}
