package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * The machines saved on this phone (the machine chip on Home): watch another one (Pro, so the control carries its label when this phone does not
 * hold Pro and a tap asks the gate), remove one (Free), or add another (Free). Paddock watches one machine at a time; the one it watches is marked.
 * Close is the focused choice. Nothing here connects, trusts or sends anything by itself: [onSwitch] and [onRemove] hand the row to the caller.
 */
@Composable
fun MachinesDialog(
    rows: List<MachineRow>,
    switchLocked: Boolean,
    onSwitch: (MachineRow) -> Unit,
    onRemove: (MachineRow) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    /** A removal is running: Watch and Remove wait for it (the graph also serializes them), so a second tap cannot race the first. */
    busy: Boolean = false,
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(MachineCopy.TITLE, style = PaddockTokens.type.headerTitle, color = c.title, modifier = Modifier.semantics { heading() })
            Text(MachineCopy.INTRO, style = PaddockTokens.type.secondary, color = c.dim)
            for (row in rows) MachineCard(row, switchLocked, busy, onSwitch = { onSwitch(row) }, onRemove = { onRemove(row) })
            PaddockButton("Add another machine", onAdd, kind = ButtonKind.Ghost, icon = PaddockIcons.Plus)
            PaddockButton("Close", onClose, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

@Composable
private fun MachineCard(row: MachineRow, switchLocked: Boolean, busy: Boolean, onSwitch: () -> Unit, onRemove: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.field).border(1.dp, if (row.watched) c.accent.copy(alpha = 0.6f) else c.fieldLine(), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = PaddockTokens.type.headerTitle, color = c.title, maxLines = 1)
            Text(
                listOfNotNull(row.endpoint, row.session?.let { "session $it" } ?: "default session").joinToString(" · "),
                style = PaddockTokens.type.secondary, color = c.dim,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.watched) {
                Text(MachineCopy.WATCHING, style = PaddockTokens.type.chip, color = c.accent, modifier = Modifier.padding(end = 4.dp))
            } else {
                PaddockButton(
                    proLabel("Watch", switchLocked), onSwitch, kind = ButtonKind.Secondary, small = true, fillWidth = false, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = proLabel("Watch ${row.name}", switchLocked) },
                )
            }
            PaddockButton("Remove…", onRemove, kind = ButtonKind.Danger, small = true, fillWidth = false, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Remove ${row.name}…" })
        }
    }
}
