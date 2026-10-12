package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.attention.AgeText
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * What the Get sheet shows (Phase 15, slice 12). Every name and path in it has been through `SafeText` already; the sheet draws what it is given.
 */
sealed interface GetUi {
    /** The stat is out. */
    data class Looking(val shown: String) : GetUi

    /** The machine answered: [facts] are the size, the age and the folder; [nameNote] says the name was changed for display and what it is saved as. */
    data class Offer(val name: String, val facts: List<String>, val nameNote: String?) : GetUi

    /** The read: [read] of [total] bytes. */
    data class Reading(val name: String, val read: Long, val total: Long) : GetUi

    /**
     * The file is in the app's cache. [canOpen] is true only for a type Open may go to (images, PDF, text); [apk] holds what an APK says about itself
     * (Share and Save only, never Open); [note] is what Save last said.
     */
    data class Ready(val name: String, val facts: List<String>, val canOpen: Boolean, val apk: List<String>, val note: String?) : GetUi

    data class Failed(val sentence: String) : GetUi
}

class GetActions(
    val onGet: () -> Unit, val onCancel: () -> Unit, val onOpen: () -> Unit, val onShare: () -> Unit, val onSave: () -> Unit, val onDone: () -> Unit,
)

/** The words of the Get sheet and the outbox. */
object GetCopy {
    const val TITLE_OFFER = "Get this file?"
    const val OFFER_NOTE = "Paddock reads it over SSH and keeps it in this app's cache for a day, unless you save it."
    const val READY_NOTE = "It is in this app's cache for a day. Save keeps it."
    const val OUTBOX_TITLE = "Files from this agent"
    const val OUTBOX_EMPTY = "Nothing in this agent's .paddock/outbox folder."
    const val OUTBOX_NOTE = "What the agent puts in .paddock/outbox. Paddock only reads it; nothing here is deleted."

    fun size(bytes: Long) = Sizes.text(bytes)

    /** "1.2 MB · changed 3 min ago", the file's age against [nowMillis]. */
    fun sizeAndAge(bytes: Long, mtimeSeconds: Long, nowMillis: Long): String = "${Sizes.text(bytes)} · changed ${AgeText.span(nowMillis - mtimeSeconds * 1000)}"

    fun inFolder(folder: String) = "In $folder"

    fun savedAs(cachedName: String) = "Shown without characters Paddock does not display; kept as $cachedName."

    fun listRead(time: String) = "List read at $time"
}

@Composable
private fun SheetDialog(onDismiss: (() -> Unit)?, tag: String, content: @Composable () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(
        onDismissRequest = onDismiss ?: {},
        properties = DialogProperties(dismissOnBackPress = onDismiss != null, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape)
                .verticalScroll(rememberScrollState()).padding(18.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = { content() },
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = PaddockTokens.type.headerTitle, color = PaddockTokens.colors.title, maxLines = 3, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Fact(text: String) {
    Text(text, style = PaddockTokens.type.secondary, color = PaddockTokens.colors.dim)
}

/**
 * The Get sheet: what the stat found and **Get**; the read, as the transfer sheet; then **Open** for the types Open may go to, **Share** and **Save**. An
 * APK never gets Open (X5): its package, version and signer sit above Share and Save, with the way to install it from Files.
 */
@Composable
fun GetSheet(ui: GetUi, actions: GetActions) {
    val c = PaddockTokens.colors
    when (ui) {
        is GetUi.Looking -> SheetDialog(actions.onCancel, "get-sheet") {
            SheetTitle("Looking at ${ui.shown}")
            Fact("Asking the machine what is there.")
            PaddockButton("Cancel", actions.onCancel, kind = ButtonKind.Secondary, autoFocus = true)
        }
        is GetUi.Offer -> SheetDialog(actions.onCancel, "get-sheet") {
            SheetTitle(GetCopy.TITLE_OFFER)
            Text(ui.name, style = PaddockTokens.type.body, color = c.title, maxLines = 3)
            ui.facts.forEach { Fact(it) }
            ui.nameNote?.let { Note(it) }
            Note(GetCopy.OFFER_NOTE)
            PaddockButton("Get", actions.onGet, kind = ButtonKind.Primary, autoFocus = true)
            PaddockButton("Cancel", actions.onCancel, kind = ButtonKind.Secondary)
        }
        is GetUi.Reading -> TransferSheet(TransferUi(ui.name, ui.read, ui.total, TransferWay.ToPhone), actions.onCancel)
        is GetUi.Ready -> SheetDialog(actions.onDone, "get-sheet") {
            SheetTitle(ui.name)
            ui.facts.forEach { Fact(it) }
            ui.apk.forEach { Fact(it) }
            if (ui.apk.isEmpty()) Note(GetCopy.READY_NOTE)
            ui.note?.let { Text(it, style = PaddockTokens.type.secondary, color = c.title, modifier = Modifier.testTag("get-note")) }
            if (ui.canOpen) PaddockButton("Open", actions.onOpen, kind = ButtonKind.Primary, autoFocus = true)
            PaddockButton("Share", actions.onShare, kind = ButtonKind.Secondary)
            PaddockButton("Save", actions.onSave, kind = ButtonKind.Secondary)
            PaddockButton("Done", actions.onDone, kind = ButtonKind.Secondary, autoFocus = !ui.canOpen)
        }
        is GetUi.Failed -> SheetDialog(actions.onDone, "get-sheet") {
            SheetTitle("Could not get the file")
            Text(ui.sentence, style = PaddockTokens.type.body, color = c.title)
            PaddockButton("Done", actions.onDone, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/** One row of the outbox: a name already cleaned for display, its size and age. */
data class OutboxRowUi(val name: String, val detail: String, val key: String)

/** What the outbox list is showing. */
sealed interface OutboxUi {
    data object Reading : OutboxUi
    data class Listed(val rows: List<OutboxRowUi>, val readAtTime: String) : OutboxUi
    data class Failed(val sentence: String) : OutboxUi
}

/**
 * The agent's `.paddock/outbox/`, newest first (X2): a list read now, "List read at 14:02", and a row that opens the Get sheet for that file. There is no
 * delete. A row is a clickable row, not a button, as on the Machines screen.
 */
@Composable
fun OutboxSheet(ui: OutboxUi, onGet: (OutboxRowUi) -> Unit, onReRead: () -> Unit, onClose: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 560.dp).clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape)
                .padding(18.dp).testTag("outbox-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetTitle(GetCopy.OUTBOX_TITLE)
            when (ui) {
                OutboxUi.Reading -> Fact("Reading the folder…")
                is OutboxUi.Failed -> Text(ui.sentence, style = PaddockTokens.type.body, color = c.title)
                is OutboxUi.Listed -> {
                    Fact(GetCopy.listRead(ui.readAtTime))
                    if (ui.rows.isEmpty()) Text(GetCopy.OUTBOX_EMPTY, style = PaddockTokens.type.body, color = c.title)
                    else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(ui.rows, key = { it.key }) { row ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = PaddockTokens.spacing.touchTarget).clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "Get ${row.name}") { onGet(row) }
                                    .semantics(mergeDescendants = true) { contentDescription = "${row.name}, ${row.detail}. Get" }
                                    .padding(vertical = 6.dp, horizontal = 4.dp).testTag("outbox-row"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = PaddockTokens.type.body, color = c.title, maxLines = 2)
                                    Text(row.detail, style = PaddockTokens.type.secondary, color = c.dim)
                                }
                                Text("Get", style = PaddockTokens.type.chip, color = c.accent)
                            }
                        }
                    }
                }
            }
            Note(GetCopy.OUTBOX_NOTE)
            PaddockButton("Re-read", onReRead, kind = ButtonKind.Ghost)
            PaddockButton("Close", onClose, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}
