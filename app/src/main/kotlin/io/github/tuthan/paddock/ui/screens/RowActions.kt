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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.ops.SagaRules
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * What the long-press on a herd row offers (Phase 09): rename the agent, or move the desktop's focus to its workspace or its tab. Each is
 * a journaled operation of its own and none changes what the agent is doing. Cancel is the focused choice.
 */
@Composable
fun RowActionsDialog(title: String, onRename: () -> Unit, onFocusWorkspace: () -> Unit, onFocusTab: () -> Unit, onDismiss: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = PaddockTokens.type.headerTitle, color = c.title)
            Text("Changes what the desktop shows, or what this agent is called. It does not touch what the agent is doing.", style = PaddockTokens.type.secondary, color = c.dim)
            PaddockButton("Rename agent…", onRename, kind = ButtonKind.Ghost)
            PaddockButton("Show its workspace on the desktop", onFocusWorkspace, kind = ButtonKind.Ghost)
            PaddockButton("Show its tab on the desktop", onFocusTab, kind = ButtonKind.Ghost)
            PaddockButton("Cancel", onDismiss, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/** A new name for an agent, or none. The name is the short token `agent start` takes; herdr refuses one another agent already holds. */
@Composable
fun RenameDialog(current: String?, onRename: (String?) -> Unit, onCancel: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    var name by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val problem = name.takeIf { it.isNotEmpty() }?.let { SagaRules.nameProblem(it) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rename agent", style = PaddockTokens.type.headerTitle, color = c.title)
            Field("Name", name, { name = it }, error = problem, placeholder = "worker-1", onDone = { if (name.isNotEmpty() && problem == null) onRename(name) })
            ButtonPair(
                { m -> PaddockButton("Cancel", onCancel, m, kind = ButtonKind.Secondary, autoFocus = true) },
                { m -> PaddockButton("Rename", { onRename(name) }, m, kind = ButtonKind.Primary, enabled = name.isNotEmpty() && problem == null) },
            )
            if (!current.isNullOrEmpty()) PaddockButton("Clear the name", { onRename(null) }, kind = ButtonKind.Ghost, small = true)
        }
    }
}
