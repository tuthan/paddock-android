package io.github.tuthan.paddock.routes

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.files.IncomingFiles
import io.github.tuthan.paddock.files.ShareCopy
import io.github.tuthan.paddock.files.ShareDraft
import io.github.tuthan.paddock.files.ShareInbox
import io.github.tuthan.paddock.files.ShareSend
import io.github.tuthan.paddock.files.ShareSender
import io.github.tuthan.paddock.files.SharedItem
import io.github.tuthan.paddock.files.Thumbnails
import io.github.tuthan.paddock.files.folder
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ops.DraftAttachment
import io.github.tuthan.paddock.ops.PromptDraft
import io.github.tuthan.paddock.ops.Readiness
import io.github.tuthan.paddock.ops.isReady
import io.github.tuthan.paddock.output.SafeText
import io.github.tuthan.paddock.output.cleanPrompt
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ui.CopyKind
import io.github.tuthan.paddock.ui.copyText
import io.github.tuthan.paddock.ui.screens.ATTACH_NOTE_OFF_NO_FOLDER
import io.github.tuthan.paddock.ui.screens.ShareAgent
import io.github.tuthan.paddock.ui.screens.ShareChooser
import io.github.tuthan.paddock.ui.screens.ShareChooserActions
import io.github.tuthan.paddock.ui.screens.ShareChooserUi
import io.github.tuthan.paddock.ui.screens.ShareFolder
import io.github.tuthan.paddock.ui.screens.ShareMachine
import io.github.tuthan.paddock.ui.screens.ShareResult
import io.github.tuthan.paddock.ui.screens.ShareSummary
import io.github.tuthan.paddock.ui.screens.Sizes
import io.github.tuthan.paddock.ui.screens.TransferSheet
import io.github.tuthan.paddock.ui.screens.TransferUi
import io.github.tuthan.paddock.ui.screens.TransferWay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the chooser says that is not a result or a machine's own word. */
internal object ShareRouteCopy {
    const val NOT_CONNECTED = "Not connected to this machine yet. Nothing is sent until you choose where."
    const val NO_TRANSFERS = "This machine's connection cannot carry a file right now. Choose Switch to this machine, or try again when it is connected."
    const val PATH_COPIED = "Path copied."
    const val NO_PATH = "Paddock could not copy the path."
    fun reading(name: String) = "Reading $name's agents…"
}

/**
 * The chooser for something another app shared (Phase 15, slice 11, X7, X13): what is waiting in [shares], the saved machines, then where on the watched one. A tap
 * is the person's act and the only thing that connects or sends: an agent takes the item as a draft and the composer opens, a folder or the drop folder takes it
 * through [sender] with no prompt. The item stays in the inbox until it is used, dismissed or replaced; its snapshot goes with it (SEC-8).
 */
@Composable
internal fun ShareRoute(
    shares: ShareInbox, sender: ShareSender, files: IncomingFiles, machineBook: MachineBook, prefs: SettingsController, hostUi: HostUiModel, proService: ProService,
    appScope: CoroutineScope, draft: PromptDraft, onDraft: ((PromptDraft) -> PromptDraft) -> Unit,
    onBack: () -> Unit, onComposeAgent: (String) -> Unit, onNotice: (String) -> Unit,
) {
    val item by shares.pending.collectAsState()
    val sending by sender.state.collectAsState()
    val view by hostUi.view.collectAsState()
    val settings by prefs.settings.collectAsState()
    val machines by machineBook.machines.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val pro by proService.view.collectAsState()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    val shared = item
    // Nothing waits (a restored screen after the process died, or the item was used): back to where this was opened from.
    LaunchedEffect(shared == null && sending is ShareSend.Idle) { if (shared == null && sending is ShareSend.Idle) onBack() }
    if (shared == null) return

    val leave = {
        if (sender.busy) sender.cancel() else sender.clear()
        shares.discard()
        onBack()
    }
    BackHandler(enabled = sending !is ShareSend.Sending) { leave() }

    val host = (view.phase as? HostPhase.Monitoring)?.host
    val installed by (host?.reconciler?.installed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val home by (host?.home ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val transfers = host?.transfers
    val live = host != null && view.freshness == Freshness.Live && installed != null
    val file = (shared as? SharedItem.File)?.snapshot

    val switchLocked = proService.locked(ProCapabilities.SWITCH_MACHINE, pro)
    val rows = MachineRoster.rows(machines, profile?.id, settings.chosenProfileId)
    val machineRows = rows.map { ShareMachine(it.id, it.name, it.endpoint, it.watched, locked = MachineRoster.watchLocked(it, switchLocked)) }

    val agents = if (!live) emptyList() else home?.rows.orEmpty().map { row ->
        val agent = installed?.snapshot?.agents?.firstOrNull { it.terminalId == row.key.target.terminalId }
        val why = when {
            agent == null -> "No longer there."
            file != null && transfers == null -> ShareRouteCopy.NO_TRANSFERS
            file != null && agent.folder() == null -> ATTACH_NOTE_OFF_NO_FOLDER
            else -> (isReady(agent, readAtMillis = installed!!.readAtMillis, nowMillis = installed!!.readAtMillis) as? Readiness.NotReady)?.primary?.sentence
        }
        ShareAgent(row.key.target.terminalId, row.title, listOfNotNull(row.agentKind, row.context.ifEmpty { null }).joinToString(" · "), why)
    }
    // The folders herdr knows, as Spaces offers them: distinct absolute pane folders. Only a file goes to one, and only over a connection that can carry it.
    val folders = if (!live || file == null || transfers == null) emptyList() else installed?.snapshot?.panes.orEmpty()
        .mapNotNull { it.cwd?.takeIf { c -> c.startsWith("/") && c.none(Char::isISOControl) } }.distinct()
        .map { ShareFolder(it, SafeText.clean(it.trimEnd('/').substringAfterLast('/').ifEmpty { it })) }

    val thumbnail by produceState<ImageBitmap?>(null, file?.id) {
        value = file?.let { f -> withContext(Dispatchers.IO) { files.file(f.id)?.let { Thumbnails.load(it)?.asImageBitmap() } } }
    }
    val summary = when (shared) {
        is SharedItem.Text -> ShareSummary(
            SafeText.clean(shared.text.lineSequence().first { it.isNotBlank() }.trim()).take(SUMMARY_CHARS).ifEmpty { "Text" },
            "Text · ${shared.text.length} characters", note = if (shared.removed > 0) ShareCopy.removed(shared.removed) else null,
        )
        is SharedItem.File -> ShareSummary(
            shared.snapshot.name, "${Sizes.text(shared.snapshot.size)} · ${kindText(shared.snapshot.mime)}",
            note = if (shared.text != null) "The words that came with it join a prompt to an agent. A send with no agent leaves them out." else null,
            thumbnail = thumbnail, isFile = true,
        )
    }

    fun addTo(agent: ShareAgent) {
        val id = agent.terminalId
        val words = when (shared) { is SharedItem.Text -> shared.text; is SharedItem.File -> shared.text }
        val merged = if (words == null) draft.textFor(id) else ShareDraft.merge(draft.textFor(id), words)
        if (merged == null) { notice = ShareDraft.TOO_LONG; return }
        val removed = when (shared) { is SharedItem.Text -> shared.removed; is SharedItem.File -> shared.removed }
        if (shared is SharedItem.File) {
            val s = shared.snapshot
            // A draft holds one attachment: the one it had, if it is another file, is replaced and its copy deleted.
            draft.attachment?.takeIf { it.snapshotId != s.id }?.let { files.delete(it.snapshotId) }
            onDraft { it.typed(id, merged).attached(id, DraftAttachment(s.id, s.name, s.size, s.mime)) }
        } else onDraft { it.typed(id, merged) }
        shares.take()
        if (removed > 0) onNotice(ShareCopy.removed(removed))
        onComposeAgent(id)
    }

    val ui = ShareChooserUi(
        summary = summary, machines = machineRows, live = live,
        liveNote = if (host == null || view.freshness != Freshness.Live) ShareRouteCopy.NOT_CONNECTED else null,
        agents = agents, folders = folders,
        dropFolder = if (live && file != null && transfers != null) profile?.dropFolderValue else null,
        busy = sending is ShareSend.Sending, notice = notice,
        result = (sending as? ShareSend.Done)?.let { ShareResult(it.ok, it.message, it.path) },
    )
    ShareChooser(
        ui,
        ShareChooserActions(
            onBack = leave,
            onMachine = { m -> rows.firstOrNull { it.id == m.id }?.let { switchMachine(proService, machineBook, appScope, it, onNotice) } },
            onAgent = { a -> if (a.reason == null) addTo(a) },
            onFolder = { f ->
                val p = profile
                if (file != null && transfers != null && host != null && p != null) sender.toFolder(transfers, p.hostId, host.sessionName, f.path, file)
            },
            onDrop = {
                val p = profile
                if (shared is SharedItem.File && transfers != null && host != null && p != null) sender.toDrop(transfers, p.hostId, host.sessionName, p.dropFolderValue, shared.senderName, shared.snapshot)
            },
            onDismissNotice = { notice = null },
            onCopyPath = { path -> onNotice(if (copyPath(context, path)) ShareRouteCopy.PATH_COPIED else ShareRouteCopy.NO_PATH) },
            onUsePath = { path ->
                // The path becomes the waiting item, so the chooser now lists agents; the file's own snapshot goes with the item it replaces.
                val cleaned = SafeText.cleanPrompt(path).text
                sender.clear()
                if (cleaned.isNotBlank()) shares.offer(SharedItem.Text(cleaned)) else shares.discard()
            },
            onCloseResult = {
                val ok = (sending as? ShareSend.Done)?.ok == true
                sender.clear()
                if (ok) { shares.discard(); onBack() }
            },
        ),
    )
    (sending as? ShareSend.Sending)?.let { TransferSheet(TransferUi(it.name, it.sent, it.total, TransferWay.ToMachine), onCancel = { sender.cancel() }) }
}

/** The path is cleaned as a prompt is (SEC-3) and never marked: a place on a machine is not a secret. */
private fun copyPath(context: Context, path: String): Boolean = copyText(context, CopyKind.MachinePath, SafeText.cleanPrompt(path).text)

private const val SUMMARY_CHARS = 120
