package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.components.proSpoken
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import androidx.compose.ui.semantics.heading

/** What was shared, as the chooser's header shows it. [thumbnail] is decoded at a bounded size and only for an image. */
data class ShareSummary(val title: String, val detail: String, val note: String? = null, val thumbnail: ImageBitmap? = null, val isFile: Boolean = false)

/** A saved machine in the chooser. [locked] is the Pro switch (X7): choosing it asks the gate, as Watch does. */
data class ShareMachine(val id: String, val name: String, val endpoint: String, val watched: Boolean, val locked: Boolean)

/** An agent of the watched machine. A [reason] means it cannot take a prompt right now, and the row is disabled with that sentence. */
data class ShareAgent(val terminalId: String, val title: String, val context: String, val reason: String? = null)

data class ShareFolder(val path: String, val label: String)

/** What a send to a folder or the drop folder said when it ended: [path] is where the file is, on the machine. */
data class ShareResult(val ok: Boolean, val message: String, val path: String? = null)

data class ShareChooserUi(
    val summary: ShareSummary,
    val machines: List<ShareMachine>,
    /** The watched machine is connected and has read its herd; otherwise [agents] and the folders wait and the screen says why. */
    val live: Boolean,
    val liveNote: String? = null,
    val agents: List<ShareAgent> = emptyList(),
    /** Folders herdr knows (file shares only); an empty list draws no section. */
    val folders: List<ShareFolder> = emptyList(),
    /** The machine's drop folder as the setting names it (`~/Paddock`), for a file share; null for text, which has no drop. */
    val dropFolder: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val result: ShareResult? = null,
)

class ShareChooserActions(
    val onBack: () -> Unit,
    val onMachine: (ShareMachine) -> Unit,
    val onAgent: (ShareAgent) -> Unit,
    val onFolder: (ShareFolder) -> Unit,
    val onDrop: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onCopyPath: (String) -> Unit,
    val onUsePath: (String) -> Unit,
    val onCloseResult: () -> Unit,
)

const val SHARE_TITLE = "Share to Paddock"
const val SHARE_WHERE = "Where?"

/**
 * The chooser for something another app shared (Phase 15, slice 11, X7, X13): the item, then the machine, then where on it. Nothing here connects or
 * sends by itself: a tap hands the choice to the caller, and the caller's send is the user's act. The machine step is the existing Pro switch, so a
 * locked machine shows the lock glyph and the tag, and the tap asks the gate.
 */
@Composable
fun ShareChooser(ui: ShareChooserUi, actions: ShareChooserActions, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader(SHARE_TITLE, onBack = actions.onBack, backDescription = "Back, and drop what was shared")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryCard(ui.summary)
            ui.notice?.let { n ->
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Banner(n)
                    PaddockButton("Dismiss", actions.onDismissNotice, kind = ButtonKind.Secondary, small = true, fillWidth = false)
                }
            }
            Kicker("Machine", Modifier.padding(top = 4.dp).semantics { heading() })
            for (m in ui.machines) MachineRowView(m, ui.busy) { actions.onMachine(m) }

            Kicker(SHARE_WHERE, Modifier.padding(top = 8.dp).semantics { heading() })
            if (!ui.live) {
                Note(ui.liveNote ?: "Not connected to this machine yet. Nothing is sent until you choose where.")
            } else {
                if (ui.agents.isEmpty()) Note("This machine has no agent to take a prompt.")
                for (a in ui.agents) AgentRowView(a, ui.busy) { actions.onAgent(a) }
                if (ui.folders.isNotEmpty()) {
                    Kicker("A folder on this machine, with no prompt", Modifier.padding(top = 6.dp))
                    for (f in ui.folders) PlaceRow(f.label, "In ${f.path}/.paddock/inbox/", !ui.busy) { actions.onFolder(f) }
                }
                ui.dropFolder?.let { PlaceRow("The machine's drop folder", "$it, with no prompt", !ui.busy) { actions.onDrop() } }
            }
            Note("Nothing leaves this phone until you choose where. The file is sent over SSH, and an image loses its location and camera details first.")
        }
    }
    ui.result?.let { ShareResultSheet(it, actions) }
}

@Composable
private fun SummaryCard(s: ShareSummary) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().card(c).testTag("share-summary"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape), contentAlignment = Alignment.Center) {
            if (s.thumbnail != null) Image(s.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (s.isFile) PaddockIcons.File else PaddockIcons.Copy, contentDescription = null, tint = c.dim, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = PaddockTokens.type.rowTitle, color = c.title, maxLines = 3)
            Text(s.detail, style = PaddockTokens.type.secondary, color = c.dim)
            s.note?.let { Text(it, style = PaddockTokens.type.secondary, color = c.dim) }
        }
    }
}

@Composable
private fun MachineRowView(m: ShareMachine, busy: Boolean, onChoose: () -> Unit) {
    val c = PaddockTokens.colors
    Column(
        Modifier.card(c, padded = false)
            .then(if (m.watched) Modifier.border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(m.name, style = PaddockTokens.type.rowTitle, color = c.title)
            Text(m.endpoint, style = PaddockTokens.type.secondary, color = c.dim)
            if (m.watched) Text("Watching", style = PaddockTokens.type.secondary, color = c.accent)
        }
        if (!m.watched) {
            PaddockButton(
                "Switch to this machine", onChoose, kind = ButtonKind.Secondary, small = true, fillWidth = false, enabled = !busy, pro = m.locked,
                modifier = Modifier.semantics { contentDescription = "Switch to ${m.name}".let { if (m.locked) proSpoken(it) else it } },
            )
        }
    }
}

@Composable
private fun AgentRowView(a: ShareAgent, busy: Boolean, onChoose: () -> Unit) {
    val enabled = a.reason == null && !busy
    PlaceRow(a.title, listOfNotNull(a.context.ifBlank { null }, a.reason).joinToString(" · "), enabled, a.reason, onChoose)
}

/** One destination: the whole row is the target (48 dp at least); a disabled one says why in its own words. */
@Composable
private fun PlaceRow(title: String, detail: String, enabled: Boolean, reason: String? = null, onChoose: () -> Unit) {
    val c = PaddockTokens.colors
    Row(
        Modifier.card(c, padded = false)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = "Send here", onClick = onChoose) else Modifier)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $detail" + if (!enabled && reason != null) ". Not available." else ""
                if (!enabled) disabled()
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = PaddockTokens.type.rowTitle, color = if (enabled) c.title else c.dim)
            if (detail.isNotEmpty()) Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
        }
        if (enabled) Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
    }
}

/** After a send to a folder or the drop folder: where the file is, and the two ways to use that path, each the user's own tap. */
@Composable
private fun ShareResultSheet(r: ShareResult, actions: ShareChooserActions) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = actions.onCloseResult, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState()).padding(18.dp)
                .testTag("share-result"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (r.ok) "Sent" else "Not sent", style = PaddockTokens.type.headerTitle, color = c.title, modifier = Modifier.semantics { heading() })
            Text(r.message, style = PaddockTokens.type.body, color = c.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            r.path?.let { p ->
                Text(p, style = PaddockTokens.type.monoFact, color = c.dim)
                PaddockButton("Copy path", { actions.onCopyPath(p) }, kind = ButtonKind.Secondary)
                PaddockButton("Use in a prompt", { actions.onUsePath(p) }, kind = ButtonKind.Ghost)
            }
            PaddockButton("Done", actions.onCloseResult, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}
