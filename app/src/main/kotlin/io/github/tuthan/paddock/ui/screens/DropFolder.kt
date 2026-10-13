package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** One file in the drop folder, named for display already (`SafeText`). */
data class DropRowUi(val name: String, val detail: String)

/** What the page shows under the folder: nothing read yet, the read, what it found, or why it could not. */
sealed interface DropFilesUi {
    data object Idle : DropFilesUi
    data object Reading : DropFilesUi

    /** [where] is the folder's real path on the machine; [more] is how many files the page does not list; [exists] false means the first file creates the folder. */
    data class Listed(val rows: List<DropRowUi>, val more: Int, val where: String, val exists: Boolean, val readAtTime: String) : DropFilesUi
    data class Failed(val sentence: String) : DropFilesUi
}

/** What the machine said about [forValue] (the text as it was when asked): [sentence] is its answer or its refusal in words, [ok] whether the value can be saved. */
data class DropTold(val forValue: String, val sentence: String, val ok: Boolean)

/**
 * The "Files from this phone go to" section of a machine's page (Phase 15, slice 11, X10). [askable] is true while this phone watches the machine and it is
 * connected: the folder is resolved and judged on the machine itself, so changing it, and the list of what is in it, need that. [editing] is the sheet.
 */
data class DropFolderUi(
    val value: String,
    val isDefault: Boolean,
    val askable: Boolean,
    val files: DropFilesUi = DropFilesUi.Idle,
    val editing: Boolean = false,
    val asking: Boolean = false,
    val told: DropTold? = null,
)

class DropFolderActions(
    val onReRead: () -> Unit,
    val onEdit: () -> Unit,
    val onCloseEdit: () -> Unit,
    val onCheck: (String) -> Unit,
    /** The trimmed text, or null for the default folder. */
    val onSave: (String?) -> Unit,
)

object DropFolderCopy {
    const val KICKER = "Files from this phone go to"
    const val NOTE = "What you share to Paddock with no agent chosen goes here, on this machine, in a folder only you can read. An agent reads nothing in it unless you point it at a file."
    const val WATCH_FIRST = "Watch this machine to change this folder or to see what is in it: the machine itself judges the folder."
    const val DEFAULT_TAG = "the default"
    const val EMPTY = "Nothing in the folder yet."
    const val EDIT_TITLE = "Folder for files from this phone"
    const val EDIT_BODY = "A folder under this machine's home. Paddock asks the machine which folder this is before it is saved, and the machine refuses home itself, a hidden folder or a link."
    const val FIELD = "Folder on this machine"
    const val NOT_CONNECTED = "Not connected to this machine, so it cannot be asked."
    const val CHECK = "Check this folder"
    const val CHECKING = "Asking the machine…"
    const val SAVE = "Save folder"
    const val USE_DEFAULT = "Use the default"

    fun listRead(time: String) = "List read at $time"
    const val MORE = "and more in that folder"
    fun saved(name: String, value: String) = "Files from this phone now go to $value on $name."
    fun notThereYet(path: String) = "$path is not there yet. The first file creates it."
}

/** The section on the page: the folder, why it is the machine's to judge, the newest files, and Change…. */
@Composable
fun DropFolderSection(ui: DropFolderUi, actions: DropFolderActions) {
    val c = PaddockTokens.colors
    Kicker(DropFolderCopy.KICKER, Modifier.padding(top = 12.dp))
    Text(
        ui.value + if (ui.isDefault) " (${DropFolderCopy.DEFAULT_TAG})" else "", style = PaddockTokens.type.monoFact, color = c.text,
        modifier = Modifier.testTag("drop-folder-value"),
    )
    Note(DropFolderCopy.NOTE)
    if (!ui.askable) {
        Note(DropFolderCopy.WATCH_FIRST)
    } else {
        PaddockButton("Change…", actions.onEdit, kind = ButtonKind.Ghost, small = true, fillWidth = false, modifier = Modifier.semantics { contentDescription = "Change the folder for files from this phone" })
        when (val f = ui.files) {
            DropFilesUi.Idle -> Unit
            DropFilesUi.Reading -> Text("Reading the folder…", style = PaddockTokens.type.secondary, color = c.dim)
            is DropFilesUi.Failed -> Text(f.sentence, style = PaddockTokens.type.body, color = c.text)
            is DropFilesUi.Listed -> Column(Modifier.testTag("drop-files"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DropFolderCopy.listRead(f.readAtTime), style = PaddockTokens.type.secondary, color = c.dim)
                if (!f.exists) Text(DropFolderCopy.notThereYet(f.where), style = PaddockTokens.type.body, color = c.text)
                else if (f.rows.isEmpty()) Text(DropFolderCopy.EMPTY, style = PaddockTokens.type.body, color = c.text)
                f.rows.forEach { r ->
                    Column(Modifier.semantics(mergeDescendants = true) {}.testTag("drop-file-row")) {
                        Text(r.name, style = PaddockTokens.type.body, color = c.title, maxLines = 2)
                        Text(r.detail, style = PaddockTokens.type.secondary, color = c.dim)
                    }
                }
                if (f.more > 0) Text(DropFolderCopy.MORE, style = PaddockTokens.type.secondary, color = c.dim)
            }
        }
        if (ui.files !is DropFilesUi.Reading) PaddockButton("Re-read", actions.onReRead, kind = ButtonKind.Ghost, small = true, fillWidth = false)
    }
    if (ui.editing) DropFolderDialog(ui, actions)
}

/**
 * The edit sheet: the field starts at what is saved. **Check this folder** asks the machine (resolve and judge) and says what the value is there and whether it will be
 * created; **Save folder** is on only for a value that was checked as it stands now, so an edit after the check turns it off until it is checked again. Cancel has the focus.
 */
@Composable
private fun DropFolderDialog(ui: DropFolderUi, actions: DropFolderActions) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    var text by rememberSaveable { mutableStateOf(ui.value) }
    val value = text.trim()
    val told = ui.told?.takeIf { it.forValue == value }
    val valid = value.isEmpty() || HostProfile.validDropFolder(value)
    // The sheet stays open while the connection is away (the app lets it go a few seconds after it leaves the front): what was typed is kept, and Check waits.
    val canCheck = valid && value.isNotEmpty() && !ui.asking && ui.askable
    val canSave = (value.isEmpty() || told?.ok == true) && !ui.asking
    Dialog(onDismissRequest = actions.onCloseEdit, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState())
                .padding(18.dp).testTag("drop-folder-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(DropFolderCopy.EDIT_TITLE, style = PaddockTokens.type.headerTitle, color = c.title, modifier = Modifier.semantics { heading() })
            Text(DropFolderCopy.EDIT_BODY, style = PaddockTokens.type.body, color = c.text)
            Field(
                DropFolderCopy.FIELD, text, { text = it }, error = if (!valid) "Start with ~/ or /, with no control characters, up to ${HostProfile.MAX_DROP_FOLDER} characters." else told?.takeIf { !it.ok }?.sentence,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, onDone = { if (canCheck) actions.onCheck(value) }, placeholder = HostProfile.DEFAULT_DROP_FOLDER, mono = true,
            )
            when {
                !ui.askable -> Text(DropFolderCopy.NOT_CONNECTED, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.testTag("drop-not-connected"))
                ui.asking -> Text(DropFolderCopy.CHECKING, style = PaddockTokens.type.secondary, color = c.dim)
                told?.ok == true -> Text(told.sentence, style = PaddockTokens.type.body, color = c.title, modifier = Modifier.testTag("drop-told"))
            }
            PaddockButton(DropFolderCopy.CHECK, { actions.onCheck(value) }, kind = ButtonKind.Secondary, enabled = canCheck)
            if (!ui.isDefault || value != HostProfile.DEFAULT_DROP_FOLDER) {
                PaddockButton(DropFolderCopy.USE_DEFAULT, { text = HostProfile.DEFAULT_DROP_FOLDER }, kind = ButtonKind.Ghost, small = true, fillWidth = false, enabled = !ui.asking)
            }
            ButtonPair(
                { m -> PaddockButton("Cancel", actions.onCloseEdit, m, kind = ButtonKind.Secondary, autoFocus = true) },
                { m -> PaddockButton(DropFolderCopy.SAVE, { actions.onSave(value.ifEmpty { null }) }, m, kind = ButtonKind.Primary, enabled = canSave) },
            )
        }
    }
}
