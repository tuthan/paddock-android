package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * Asks before anything is written to the host: which file, what hash, what it is for. Installing is the only thing here
 * that changes the host, and it happens only on the explicit tap.
 */
@Composable
fun RelayInstall(hostName: String, ask: HostPhase.NeedsRelayInstall, installing: Boolean, onInstall: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxSize().padding(PaddockTokens.spacing.gutter)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Install the relay on $hostName?", style = PaddockTokens.type.screenTitle, color = c.title, modifier = Modifier.semantics { heading() })
            Text(
                "Paddock watches herdr through one small Python script that runs on $hostName. It reads herdr's state and sends nothing else. " +
                    "It is written to the file below with owner-only permissions, and checked against the hash below before every use.",
                style = PaddockTokens.type.body, color = c.text,
            )
            if (ask.replacing) Banner("A different file is already there. Installing replaces it.")
            Fact("File on the host", ask.destination)
            Fact("SHA-256 of the script", ask.expectedSha256)
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaddockButton(if (installing) "Installing…" else "Install the relay", onInstall, enabled = !installing)
            PaddockButton("Not now", onNotNow, kind = ButtonKind.Secondary, enabled = !installing)
        }
    }
}
