package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.hostprofile.HostOs
import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineName
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.MachineRow
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.FilterChips
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.components.proSpoken
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import io.github.tuthan.paddock.wake.WakeRelay

/**
 * One saved machine as its card on the Machines screen draws it: the [row] (name, address, session, watched, free return) and its [state] line,
 * already in words ("Watching · live", "Not watched · wake ready · alerts set up"; `MachineCopy`), so the screen is a pure function of this.
 */
data class MachineCard(val row: MachineRow, val state: String)

/**
 * Everything the Machines screen draws. [switchLocked]: watching another machine is Pro and this phone does not hold it ([MachineRow.returnFree]
 * rows stay open). [busy]: a removal is running, so Watch waits for it (the graph also serializes the two).
 */
data class MachineListState(val cards: List<MachineCard>, val switchLocked: Boolean, val busy: Boolean = false)

/**
 * The machines saved on this phone (decision D3, 2026-10-06: a screen, not a dialog), reached from the watched chip on Home and from Settings.
 * One card per machine, the watched one first: its name, address and session, its state line, and a chevron; the whole card opens the machine's
 * page ([MachinePage]), where Watch, Wake-on-LAN, its alerts and Remove live. A card that is not watched also keeps one small Watch, so switching
 * from here is one tap; it carries the Pro lock when the switch is Pro for this phone and the row is not the free return to the chosen machine,
 * and without Pro a line under the intro says why that one is free. Remove is only on the page. Nothing here connects or sends by itself: the
 * callbacks hand the row to the caller.
 */
@Composable
fun MachinesScreen(
    state: MachineListState,
    onBack: () -> Unit,
    onOpen: (MachineRow) -> Unit,
    onWatch: (MachineRow) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader(MachineCopy.TITLE, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(MachineCopy.INTRO, style = PaddockTokens.type.secondary, color = c.dim)
            val rows = state.cards.map { it.row }
            val back = rows.firstOrNull { it.returnFree }
            val watched = rows.firstOrNull { it.watched }
            if (state.switchLocked && back != null && watched != null) Text(MachineCopy.displaced(back.name, watched.name), style = PaddockTokens.type.secondary, color = c.dim)
            for (card in state.cards.sortedBy { if (it.row.watched) 0 else 1 }) {
                MachineCardView(card, watchLocked = MachineRoster.watchLocked(card.row, state.switchLocked), busy = state.busy, onOpen = { onOpen(card.row) }, onWatch = { onWatch(card.row) })
            }
            PaddockButton("Add another machine", onAdd, kind = ButtonKind.Ghost, icon = PaddockIcons.Plus)
        }
    }
}

@Composable
private fun MachineCardView(card: MachineCard, watchLocked: Boolean, busy: Boolean, onOpen: () -> Unit, onWatch: () -> Unit) {
    val c = PaddockTokens.colors
    val row = card.row
    Column(
        Modifier.card(c, padded = false)
            .then(if (row.watched) Modifier.border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = MachineCopy.openLabel(row.name), onClick = onOpen)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The kind of machine, read from it or picked on its page; a machine with neither has no glyph rather than a wrong one.
            PaddockIcons.forOs(row.os)?.let { Icon(it, contentDescription = MachineCopy.osSpoken(row.os), tint = if (row.watched) c.accent else c.dim, modifier = Modifier.size(24.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.name, style = PaddockTokens.type.rowTitle, color = c.title)
                Text("${row.endpoint} · ${MachineCopy.session(row.session)}", style = PaddockTokens.type.secondary, color = c.dim)
                Text(card.state, style = PaddockTokens.type.secondary, color = if (row.watched) c.accent else c.dim)
            }
            Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
        }
        if (!row.watched) {
            PaddockButton(
                "Watch", onWatch, kind = ButtonKind.Secondary, small = true, fillWidth = false, enabled = !busy, pro = watchLocked,
                modifier = Modifier.semantics { contentDescription = MachineCopy.watchLabel(row.name).let { if (watchLocked) proSpoken(it) else it } },
            )
        }
    }
}

/**
 * Everything one machine's page draws. [state] is the card's state line; [wake] is the Wake-on-LAN section's words for this machine
 * (`WakeWordsMapper`), whether it is watched or not (decision D2: Wake is Free for every saved machine). [alerts] is its UnifiedPush registration
 * in words (`PushWords`). [next] is the machine Paddock watches after this one is removed, for the confirmation; [busy]: a removal is running.
 */
data class MachinePageState(
    val row: MachineRow,
    val state: String,
    val switchLocked: Boolean,
    val wake: WakeWords,
    val alerts: String,
    val next: String? = null,
    val busy: Boolean = false,
    /** The names of the other saved machines, so a rename is refused when it would make two cards look the same ([MachineName]). */
    val otherNames: List<String> = emptyList(),
    /** The user's icon pick and what the machine said ([io.github.tuthan.paddock.hostprofile.HostProfile.os], `detectedOs`); [MachineRow.os] is the one drawn. */
    val chosenOs: HostOs? = null,
    val detectedOs: HostOs? = null,
    /** Guarded answers are Pro and locked on this phone: the row says so, and a tap asks the gate before the setup opens. */
    val guardedLocked: Boolean = false,
)

/**
 * One saved machine (decision D3): what it is and whether it is watched, Watch (Pro for this phone unless it is the free return), Wake-on-LAN
 * (Free for every saved machine, watched or not), its locked-phone alerts, its guarded answers (Pro) and Remove (Free, asked first). The alert relay and
 * guarded answers screens read the machine through the live connection, so each is offered only for the watched machine; another one says to watch it first.
 */
@Composable
fun MachinePage(
    state: MachinePageState,
    onBack: () -> Unit,
    onWatch: () -> Unit,
    onRemove: () -> Unit,
    onAlertRelay: () -> Unit,
    onCopyWakeCommand: (String) -> Unit,
    onSaveWakeRelay: (WakeRelay?) -> Unit,
    onWake: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (String) -> Unit = {},
    onSetOs: (HostOs?) -> Unit = {},
    onGuardedAnswers: () -> Unit = {},
) {
    val c = PaddockTokens.colors
    val row = state.row
    var confirm by rememberSaveable(row.id) { mutableStateOf(false) }
    if (confirm) {
        ConfirmDialog(
            MachineCopy.removeTitle(row.name), MachineCopy.removeBody(row.name, watched = row.watched, next = state.next),
            confirm = MachineCopy.REMOVE, facts = MachineCopy.REMOVE_FACTS, danger = true,
            onCancel = { confirm = false }, onConfirm = { confirm = false; onRemove() },
        )
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader(row.name, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Kicker("This machine", Modifier.padding(top = 4.dp))
            Fact("Address", row.endpoint)
            Fact("Session", row.session ?: MachineCopy.DEFAULT_SESSION)
            if (row.watched) {
                // The watched machine's state line is its mark, drawn as the design's accent chip, so "Watching" is not said twice.
                val pill = RoundedCornerShape(50)
                Text(
                    state.state, style = PaddockTokens.type.chip, color = c.title,
                    modifier = Modifier.clip(pill).background(c.accent.copy(alpha = 0.14f)).border(1.dp, c.accent.copy(alpha = 0.5f), pill).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Text(state.state, style = PaddockTokens.type.secondary, color = c.dim)
                val locked = MachineRoster.watchLocked(row, state.switchLocked)
                PaddockButton(
                    "Watch", onWatch, kind = ButtonKind.Secondary, enabled = !state.busy, pro = locked,
                    modifier = Modifier.semantics { contentDescription = MachineCopy.watchLabel(row.name).let { if (locked) proSpoken(it) else it } },
                )
            }

            // Renaming and the icon are occasional: they come after the state line and Watch, which is what the page is mostly opened for.
            Kicker("Name and icon", Modifier.padding(top = 12.dp))
            NameEditor(row, state.otherNames, state.busy, onRename)
            OsPicker(state.chosenOs, state.detectedOs, onSetOs)

            WakeOnLanSection(state.wake, onCopyWakeCommand, onSaveWakeRelay, onWake)

            Kicker("Locked-phone alerts", Modifier.padding(top = 12.dp))
            Text(state.alerts, style = PaddockTokens.type.body, color = c.text)
            if (row.watched) {
                Row(
                    Modifier.card(c, padded = false)
                        .clickable(role = Role.Button, onClickLabel = "Set up or check the alert relay", onClick = onAlertRelay)
                        .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Set up or check…", style = PaddockTokens.type.rowTitle, color = c.title, modifier = Modifier.weight(1f))
                    Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
                }
            } else {
                Note(MachineCopy.ALERTS_WATCH_FIRST)
            }

            Kicker("Guarded answers", Modifier.padding(top = 12.dp))
            Text(GUARDED_ROW, style = PaddockTokens.type.body, color = c.text)
            if (row.watched) {
                val label = proLabel("Set up guarded answers…", state.guardedLocked)
                Row(
                    Modifier.card(c, padded = false)
                        .clickable(role = Role.Button, onClickLabel = "Set up or check guarded answers", onClick = onGuardedAnswers)
                        .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) { contentDescription = "Guarded answers, $label" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, style = PaddockTokens.type.rowTitle, color = c.title, modifier = Modifier.weight(1f))
                    Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
                }
            } else {
                Note(MachineCopy.GUARDED_WATCH_FIRST)
            }

            Kicker("Remove", Modifier.padding(top = 12.dp))
            PaddockButton(
                "Remove…", { confirm = true }, kind = ButtonKind.Danger, enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = MachineCopy.removeLabel(row.name) },
            )
        }
    }
}

/**
 * Renaming a machine: the field starts at its name, Save is on only for a name [MachineName] accepts that is not the current one, and the words under it say
 * the name is this phone's label (nothing on the machine changes). The error shows once the text differs from the saved name, so an untouched field is quiet.
 */
@Composable
private fun NameEditor(row: MachineRow, otherNames: List<String>, busy: Boolean, onRename: (String) -> Unit) {
    var draft by rememberSaveable(row.id, row.name) { mutableStateOf(row.name) }
    val result = MachineName.check(draft, otherNames)
    val changed = draft.trim() != row.name
    val save = { (result as? MachineName.Result.Ok)?.let { if (changed && !busy) onRename(it.name) } ; Unit }
    Field(
        MachineCopy.NAME_LABEL, draft, { draft = it }, error = if (changed) MachineName.message(result) else null,
        imeAction = ImeAction.Done, onDone = save,
    )
    PaddockButton("Save name", save, kind = ButtonKind.Secondary, enabled = changed && result is MachineName.Result.Ok && !busy)
    Note(MachineCopy.NAME_HINT)
}

/** Which glyph the machine's card draws: what the machine said (Automatic) or the user's own pick; a pick is kept when the machine later says something else. */
@Composable
private fun OsPicker(chosen: HostOs?, detected: HostOs?, onSet: (HostOs?) -> Unit) {
    val c = PaddockTokens.colors
    val options = listOf<HostOs?>(null) + HostOs.entries
    Kicker(MachineCopy.OS_LABEL, Modifier.padding(top = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PaddockIcons.forOs(chosen ?: detected)?.let { Icon(it, contentDescription = MachineCopy.osSpoken(chosen ?: detected), tint = c.accent, modifier = Modifier.size(24.dp)) }
        Text(MachineCopy.osFact(chosen, detected), style = PaddockTokens.type.body, color = c.text)
    }
    FilterChips(options.map { it?.label ?: MachineCopy.osAuto(detected) }, options.indexOf(chosen), { onSet(options[it]) })
    Note(MachineCopy.OS_HINT)
}
