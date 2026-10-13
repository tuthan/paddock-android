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
import io.github.tuthan.paddock.files.forItem
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
import io.github.tuthan.paddock.ui.screens.ShareReplace
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
    val sendState by sender.state.collectAsState()
    val view by hostUi.view.collectAsState()
    val settings by prefs.settings.collectAsState()
    val machines by machineBook.machines.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val pro by proService.view.collectAsState()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }

    val shared = item
    // Nothing waits (a restored screen after the process died, or the item was used or dismissed): back to where this was opened from.
    LaunchedEffect(shared == null) { if (shared == null) onBack() }
    if (shared == null) return

    val file = (shared as? SharedItem.File)?.snapshot
    // This item's send or result only: an earlier share's is never shown under it, and its Done never closes it.
    val sending = sendState.forItem(shared)
    // The question before the share replaces another agent's draft; it belongs to this item and goes when another one takes its place.
    var replacing by remember(shared) { mutableStateOf<ReplaceAsk?>(null) }

    val leave = {
        file?.let { sender.cancel(it.id) }
        // Only the item on the screen is dropped: a newer one that came a moment before stays, and the chooser shows it.
        if (shares.discard(shared)) onBack()
    }
    BackHandler(enabled = sending !is ShareSend.Sending) { leave() }

    val host = (view.phase as? HostPhase.Monitoring)?.host
    val installed by (host?.reconciler?.installed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val home by (host?.home ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val transfers = host?.transfers
    val live = host != null && view.freshness == Freshness.Live && installed != null

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

    /** [confirmed]: the person said Replace to the question about another agent's draft. */
    fun addTo(agent: ShareAgent, confirmed: Boolean) {
        val id = agent.terminalId
        when (val plan = shareToAgent(draft, id, shared, confirmed)) {
            is ShareToAgent.Ask -> replacing = ReplaceAsk(id, ShareReplace(agents.firstOrNull { it.terminalId == plan.other }?.title, plan.words, plan.file))
            ShareToAgent.TooLong -> notice = ShareDraft.TOO_LONG
            is ShareToAgent.Add -> {
                // Taken first, and only while it is still the item on screen: a share that arrived a moment before the tap replaced this one (and deleted its
                // file), and the chooser now shows that one instead.
                if (!shares.take(shared)) return
                val attachment = plan.attachment
                plan.dropped?.let { files.delete(it) }
                onDraft { d -> d.typed(id, plan.text).let { if (attachment != null) it.attached(id, attachment) else it } }
                val removed = when (shared) { is SharedItem.Text -> shared.removed; is SharedItem.File -> shared.removed }
                if (removed > 0) onNotice(ShareCopy.removed(removed))
                onComposeAgent(id)
            }
        }
    }

    val ui = ShareChooserUi(
        summary = summary, machines = machineRows, live = live,
        liveNote = if (host == null || view.freshness != Freshness.Live) ShareRouteCopy.NOT_CONNECTED else null,
        agents = agents, folders = folders,
        dropFolder = if (live && file != null && transfers != null) profile?.dropFolderValue else null,
        busy = sending is ShareSend.Sending, notice = notice,
        result = (sending as? ShareSend.Done)?.let { ShareResult(it.ok, it.message, it.path) },
        replace = replacing?.question,
    )
    ShareChooser(
        ui,
        ShareChooserActions(
            onBack = leave,
            onMachine = { m -> rows.firstOrNull { it.id == m.id }?.let { switchMachine(proService, machineBook, appScope, it, onNotice) } },
            onAgent = { a -> if (a.reason == null) addTo(a, confirmed = false) },
            onReplace = {
                val asked = replacing?.terminalId
                replacing = null
                // The agent is looked up again: it may have gone, or stopped taking a prompt, while the question was up.
                agents.firstOrNull { it.terminalId == asked && it.reason == null }?.let { addTo(it, confirmed = true) }
            },
            onKeep = { replacing = null },
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
                // The path becomes the waiting item in this item's place, so the chooser now lists agents; the file's own snapshot goes with the item it replaces.
                // A newer share that already took its place is left as it is.
                val cleaned = SafeText.cleanPrompt(path).text
                file?.let { sender.clear(it.id) }
                if (shares.discard(shared) && cleaned.isNotBlank()) shares.offer(SharedItem.Text(cleaned))
            },
            onCloseResult = {
                val ok = (sending as? ShareSend.Done)?.ok == true
                file?.let { sender.clear(it.id) }
                // A sent file's item goes with its result; never a newer share that took its place.
                if (ok && shares.discard(shared)) onBack()
            },
        ),
    )
    (sending as? ShareSend.Sending)?.let { s -> TransferSheet(TransferUi(s.name, s.sent, s.total, TransferWay.ToMachine), onCancel = { sender.cancel(s.item) }) }
}

/** The question up, for the agent [terminalId] the person chose. */
private data class ReplaceAsk(val terminalId: String, val question: ShareReplace)

/** What choosing an agent for a shared item does to the one prompt draft the app holds. */
internal sealed interface ShareToAgent {
    /** The draft holds unsent words or a file for [other], another agent: nothing changes until the person says Replace. */
    data class Ask(val other: String, val words: Boolean, val file: Boolean) : ShareToAgent

    /** The shared text and the words already there would pass the prompt limit together: nothing changes ([ShareDraft.TOO_LONG]). */
    data object TooLong : ShareToAgent

    /** The draft becomes [text] for the agent, waiting with [attachment] when a file was shared; [dropped] is the snapshot the draft lets go of, to delete. */
    data class Add(val text: String, val attachment: DraftAttachment?, val dropped: String?) : ShareToAgent
}

/**
 * [ShareDraft]'s rule (nothing the person wrote is lost or overwritten by a share) over the whole draft. The app holds one prompt draft, one agent's words and
 * file, so adding a share for [id] while it is another agent's would drop those words and delete that file: unless [confirmed], that is asked first. The agent's
 * own draft is merged into and never asked about.
 */
internal fun shareToAgent(draft: PromptDraft, id: String, shared: SharedItem, confirmed: Boolean): ShareToAgent {
    val other = draft.terminalId?.takeIf { it != id }
    val words = draft.text.isNotBlank()
    val file = draft.attachment != null
    if (other != null && (words || file) && !confirmed) return ShareToAgent.Ask(other, words, file)
    val sharedText = when (shared) { is SharedItem.Text -> shared.text; is SharedItem.File -> shared.text }
    val text = if (sharedText == null) draft.textFor(id) else ShareDraft.merge(draft.textFor(id), sharedText) ?: return ShareToAgent.TooLong
    val attachment = (shared as? SharedItem.File)?.snapshot?.let { DraftAttachment(it.id, it.name, it.size, it.mime) }
    // A draft holds one attachment: the one it had goes, and its copy is deleted, when the new draft does not keep it (another file took its place, or the
    // draft is now another agent's).
    val kept = attachment ?: draft.attachmentFor(id)
    return ShareToAgent.Add(text, attachment, dropped = draft.attachment?.snapshotId?.takeIf { it != kept?.snapshotId })
}

/** The path is cleaned as a prompt is (SEC-3) and never marked: a place on a machine is not a secret. */
private fun copyPath(context: Context, path: String): Boolean = copyText(context, CopyKind.MachinePath, SafeText.cleanPrompt(path).text)

private const val SUMMARY_CHARS = 120
