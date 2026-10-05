package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import io.github.tuthan.paddock.wake.WakeRelay

/** One command Settings shows for the user to run on the machine; Paddock never runs it. */
data class WakeCommandRow(val label: String, val text: String)

/**
 * What Settings says about waking the watched machine: [status] in words, the [commands] that would change what is not ready, the saved
 * [relay] (an IPv4 address with an optional port), [suggestions] to put in the relay field (never saved by themselves), whether a Wake
 * tap is possible now, and the [lines] the last tap came to.
 */
data class WakeWords(
    val status: String,
    val commands: List<WakeCommandRow> = emptyList(),
    val relay: String? = null,
    val suggestions: List<String> = emptyList(),
    val canWake: Boolean = false,
    val lines: List<String> = emptyList(),
)

internal const val WAKE_RELAY_ERROR = "Enter an IPv4 address such as 192.168.1.1, with an optional port (192.168.1.1:9). A name is not accepted."

/** The relay field's answer: a relay to save, null to clear, or an error sentence. Pure so it can be tested without a screen. */
sealed interface RelayEntry {
    data class Save(val relay: WakeRelay) : RelayEntry
    data object Clear : RelayEntry
    data class Invalid(val message: String) : RelayEntry
}

fun relayEntry(text: String): RelayEntry = when {
    text.isBlank() -> RelayEntry.Clear
    else -> WakeRelay.parse(text)?.let { RelayEntry.Save(it) } ?: RelayEntry.Invalid(WAKE_RELAY_ERROR)
}

@Composable
fun WakeOnLanSection(words: WakeWords, onCopy: (String) -> Unit, onSaveRelay: (WakeRelay?) -> Unit, onWake: () -> Unit) {
    var relayText by rememberSaveable(words.relay) { mutableStateOf(words.relay.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker("Wake-on-LAN")
        Text(words.status, style = PaddockTokens.type.body, color = PaddockTokens.colors.text)
        for (c in words.commands) {
            Fact(c.label, c.text)
            var copied by rememberSaveable(c.text) { mutableStateOf(false) }
            PaddockButton(if (copied) "Copied" else "Copy", { onCopy(c.text); copied = true }, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Copy, fillWidth = false)
        }
        if (words.commands.isNotEmpty()) Note("Paddock shows these and never runs them. Waking works from sleep, not from a full shutdown.")
        Field(
            "Relay for waking from away", relayText, { relayText = it; error = null }, error = error, mono = true,
            placeholder = "192.168.1.1", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
        )
        Note("Away from home the phone cannot broadcast to the machine's network. A relay on that network re-broadcasts the packet: a router with a wake forwarder, a VPN server, an always-on box. Paddock sends the packet to it and cannot tell whether it re-broadcast.")
        PaddockButton("Save relay", {
            when (val e = relayEntry(relayText)) {
                is RelayEntry.Save -> { error = null; onSaveRelay(e.relay) }
                RelayEntry.Clear -> { error = null; onSaveRelay(null) }
                is RelayEntry.Invalid -> error = e.message
            }
        }, kind = ButtonKind.Secondary, small = true, fillWidth = false)
        for (s in words.suggestions) {
            PaddockButton("Use $s", { relayText = s; error = null }, kind = ButtonKind.Ghost, small = true, fillWidth = false)
        }
        if (words.canWake) PaddockButton("Wake the machine", onWake, kind = ButtonKind.Ghost)
        for (line in words.lines) Text(line, style = PaddockTokens.type.secondary, color = PaddockTokens.colors.dim)
    }
}
