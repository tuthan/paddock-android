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
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

const val WELCOME_INTRO =
    "Paddock watches the AI coding agents that run in herdr on your own computer, and lets you answer them from here. It reaches the machine over SSH. Nothing goes through a Paddock server."

const val WELCOME_NEEDS_SSH = "An SSH server on the machine that this phone can reach: a LAN address on the same Wi-Fi, a VPN address such as Tailscale, or a tunnel."

/**
 * The first screen of a phone with no machine. It says what is needed on the machine, each with the command that checks it, and then
 * the ways in: type the address, find it on this network, paste a pairing link, scan one. Every way ends on Add machine, where the
 * traditional steps (this phone's key and its authorize command, or an imported key) are always available.
 */
@Composable
fun Welcome(
    onEnterAddress: () -> Unit,
    modifier: Modifier = Modifier,
    onFind: (() -> Unit)? = null,
    onPasteLink: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    notice: String? = null,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Paddock", style = PaddockTokens.type.screenTitle, color = PaddockTokens.colors.title, modifier = Modifier.semantics { heading() })
        Text(WELCOME_INTRO, style = PaddockTokens.type.body, color = PaddockTokens.colors.dim)
        Kicker("Before you start")
        Fact("herdr is running on the machine. Check with", "herdr session list")
        Fact("SSH reaches it from this phone", WELCOME_NEEDS_SSH)
        Fact("Python 3.11 or newer is on the machine. Check with", "python3 --version")
        if (notice != null) Banner(notice)
        PaddockButton("Enter the address", onEnterAddress, icon = PaddockIcons.Key)
        if (onFind != null) PaddockButton("Find on this network", onFind, kind = ButtonKind.Ghost, icon = PaddockIcons.Machine)
        if (onPasteLink != null) PaddockButton("Paste a pairing link", onPasteLink, kind = ButtonKind.Ghost, icon = PaddockIcons.Copy)
        if (onScan != null) PaddockButton("Scan the code on the desktop", onScan, kind = ButtonKind.Ghost, icon = PaddockIcons.Qr)
    }
}
