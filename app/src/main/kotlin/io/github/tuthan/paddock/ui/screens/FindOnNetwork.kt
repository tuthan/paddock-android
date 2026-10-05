package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.discovery.FinderPhase
import io.github.tuthan.paddock.discovery.FinderState
import io.github.tuthan.paddock.discovery.FinderText
import io.github.tuthan.paddock.discovery.FoundHost
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val FINDER_LOCAL_ACCESS_OFF =
    "Local-network access is off, so Paddock cannot look for machines. Turn it on in the app's settings, or type the machine's address instead."

/**
 * Find on this network. It says what it will do before anything runs, and nothing runs until Start. A row is a machine that answered
 * (or announced itself) on this network; tapping one only fills in its address and port on Add machine, with the name it announced.
 * Nothing is connected to, trusted or stored from here.
 */
@Composable
fun FindOnNetwork(
    state: FinderState,
    port: String,
    onPort: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onAllow: () -> Unit,
    onPick: (FoundHost) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The local-network request was refused: say where to turn it on. */
    accessDenied: Boolean = false,
    onOpenSettings: () -> Unit = {},
) {
    val typed = FinderText.port(port)
    val scanning = state.phase == FinderPhase.Scanning
    Column(modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Find on this network", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.sentence, style = PaddockTokens.type.body, color = PaddockTokens.colors.dim)
            if (state.needsGrant && accessDenied) Banner(FINDER_LOCAL_ACCESS_OFF, actionLabel = "Open settings", onAction = onOpenSettings)
            if (state.canStart || state.phase != FinderPhase.Idle) {
                Field(
                    "Port to look at besides 22 (optional)", port, onPort, error = typed.error, keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done, placeholder = "22 only", mono = true,
                )
            }
            // Read aloud as it changes: the count moves while the user is looking at the list.
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FinderText.progress(state)?.let { Text(it, style = PaddockTokens.type.body, color = PaddockTokens.colors.title) }
                FinderText.result(state)?.let { Text(it, style = PaddockTokens.type.body, color = PaddockTokens.colors.title) }
            }
            if (state.rows.isNotEmpty()) {
                Kicker("Found")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.rows.forEach { row -> PaddockButton(row.labelWithPort, { onPick(row) }, kind = ButtonKind.Secondary, icon = PaddockIcons.Machine) }
                }
            }
            state.note?.takeIf { state.phase == FinderPhase.Done }?.let { Note(it, icon = PaddockIcons.Warning) }
        }
        Column(Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                scanning -> PaddockButton("Cancel", onCancel, kind = ButtonKind.Secondary)
                state.needsGrant -> PaddockButton("Allow local-network access", onAllow, icon = PaddockIcons.Key)
                else -> PaddockButton(
                    if (state.phase == FinderPhase.Idle) "Start" else "Search again", onStart,
                    enabled = state.canStart && typed.error == null, icon = PaddockIcons.Machine,
                )
            }
        }
    }
}
