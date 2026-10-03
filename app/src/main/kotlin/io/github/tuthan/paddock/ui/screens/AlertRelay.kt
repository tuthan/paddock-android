package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AlertDelivery
import io.github.tuthan.paddock.alerts.AlertRelayStatus
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** What the screen knows about the relay on the host right now. */
sealed interface AlertRelayHostState {
    /** No live connection to the machine, so nothing can be read or installed from here. */
    data object NotConnected : AlertRelayHostState
    data object Reading : AlertRelayHostState
    data class Failed(val message: String) : AlertRelayHostState
    data class Known(val status: AlertRelayStatus) : AlertRelayHostState
}

/** A UnifiedPush distributor installed on the phone. */
data class PushDistributorUi(val packageName: String, val label: String)

/** This machine's registration with a distributor. The address itself is a capability and is never drawn; only its host is. */
data class PushRegisteredUi(val distributorLabel: String, val distributorInstalled: Boolean, val endpointHost: String?, val hasEndpoint: Boolean, val shared: Boolean, val failure: String?)

/** What the screen knows about connector mode (UnifiedPush) for this machine. */
data class PushUi(
    val distributors: List<PushDistributorUi> = emptyList(),
    val registered: PushRegisteredUi? = null,
    /** What the distributor last said needs attention. */
    val notice: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Whether the address file exists on the host; null when that cannot be asked now. */
    val onHost: Boolean? = null,
)

data class AlertRelayUi(
    val machine: String,
    val profileId: String,
    val scriptSha256: String,
    val host: AlertRelayHostState,
    val installing: Boolean = false,
    val installError: String? = null,
    /** The commands to paste on the host, shown in full before they can be copied; null while not connected. */
    val commands: String? = null,
    val copied: Boolean = false,
    val push: PushUi = PushUi(),
)

/**
 * Locked-phone alerts through a relay on the machine and the ntfy app (the stopgap of Phase 07). The screen says what the relay
 * does, shows the hash of the script before anything is installed or copied, installs only that script and only on the tap
 * after a confirmation, and hands the rest (the unit, the configuration, enabling it) to the user as commands they can read first.
 */
@Composable
fun AlertRelay(
    ui: AlertRelayUi, onBack: () -> Unit, onInstall: () -> Unit, onCheck: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier,
    onPushRegister: (packageName: String) -> Unit = {}, onPushShare: () -> Unit = {}, onPushRemove: () -> Unit = {},
) {
    val c = PaddockTokens.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val known = (ui.host as? AlertRelayHostState.Known)?.status
    if (confirm) {
        ConfirmDialog(
            if (known?.script is RelayState.Mismatch) "Replace the alert relay on ${ui.machine}?" else "Install the alert relay on ${ui.machine}?",
            "Paddock writes this one file, with owner-only permissions, and checks its hash before it is ever run. It does not write the unit or the configuration, and it does not start or enable anything.",
            confirm = if (known?.script is RelayState.Mismatch) "Replace" else "Install",
            onConfirm = { confirm = false; onInstall() }, onCancel = { confirm = false },
            facts = listOfNotNull(known?.destination?.let { "File on the host" to it }, "SHA-256 of the script" to ui.scriptSha256),
        )
    }
    if (confirmShare) {
        ConfirmDialog(
            "Send the address to ${ui.machine}?",
            "Paddock writes the address your distributor gave this phone to one file on ${ui.machine}, with owner-only permissions. The relay posts a message to it that holds a machine id and a one-time number, nothing from any agent. Whoever has the address can send this phone an alert hint, so anything on that machine that can read your files can too.",
            confirm = "Send the address",
            onConfirm = { confirmShare = false; onPushShare() }, onCancel = { confirmShare = false },
            facts = listOfNotNull(ui.push.registered?.endpointHost?.let { "Address host" to it }, "File on the host" to PUSH_FILE),
        )
    }
    Column(modifier.fillMaxSize()) {
        ScreenHeader("Alert relay", onBack = onBack, compact = true)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(AlertDelivery.MODE, style = PaddockTokens.type.screenTitle, color = c.title)
            Text(AlertDelivery.HOW, style = PaddockTokens.type.body, color = c.text)
            Banner(AlertDelivery.BEST_EFFORT, tint = c.accent, icon = PaddockIcons.Bell)
            Note(AlertDelivery.MEASURED)

            Kicker("This machine", Modifier.padding(top = 8.dp))
            Fact("Name on this phone", ui.machine)
            Fact("Machine id for the relay's configuration", ui.profileId)

            Kicker("The script", Modifier.padding(top = 8.dp))
            Fact("SHA-256 of paddock-alert-relay.py", ui.scriptSha256)
            when (val h = ui.host) {
                AlertRelayHostState.NotConnected -> Banner("Paddock is not connected to ${ui.machine}, so it cannot see or install the script. Connect from the herd, then come back.")
                AlertRelayHostState.Reading -> Note("Reading the machine…")
                is AlertRelayHostState.Failed -> Banner("Could not read the machine: ${h.message}", actionLabel = "Try again", onAction = onCheck)
                is AlertRelayHostState.Known -> {
                    val s = h.status
                    Fact("File on the host", s.destination)
                    Text(
                        when (s.script) {
                            RelayState.Current -> "Installed, and it is the pinned script."
                            RelayState.Missing -> "Not installed on ${ui.machine} yet."
                            is RelayState.Mismatch -> "A different file is there. It is never run; installing replaces it."
                        },
                        style = PaddockTokens.type.body, color = c.text,
                    )
                    s.check?.let { check ->
                        Fact("Check of its configuration", (listOf(if (check.ok) "ok" else "needs attention (exit ${check.exit})") + check.lines).joinToString("\n"))
                    }
                    Fact("systemd user unit", s.service.label)
                }
            }
            ui.installError?.let { Banner(it) }
            val installLabel = when {
                ui.installing -> "Installing…"
                known?.script is RelayState.Mismatch -> "Replace the script…"
                known?.script == RelayState.Current -> "Reinstall the script…"
                else -> "Install the script…"
            }
            PaddockButton(installLabel, { confirm = true }, enabled = known != null && !ui.installing, kind = if (known?.script == RelayState.Current) ButtonKind.Ghost else ButtonKind.Primary)
            PaddockButton("Check the setup", onCheck, enabled = ui.host !is AlertRelayHostState.NotConnected && ui.host !is AlertRelayHostState.Reading, kind = ButtonKind.Ghost)

            Kicker("Setup commands", Modifier.padding(top = 8.dp))
            Text(
                "Paste these on ${ui.machine}. They write the unit and (only if there is none) the configuration, then check it and enable the unit. Paddock never enables the unit itself. Then edit the ntfy url and topic in the configuration.",
                style = PaddockTokens.type.body, color = c.text,
            )
            if (ui.commands != null) {
                val shape = RoundedCornerShape(PaddockTokens.radii.row)
                Text(
                    ui.commands, style = PaddockTokens.type.monoFact, color = c.text,
                    modifier = Modifier.fillMaxWidth().clip(shape).background(c.slab).border(1.dp, c.line(), shape).horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                PaddockButton(if (ui.copied) "Copied" else "Copy the setup commands", onCopy, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)
            }
            Note("Install the ntfy app, subscribe to the topic you put in the configuration, and prefer your own ntfy server: the topic name is the only secret in this path, and the message holds nothing else.")

            PushSection(ui, onPushRegister, { confirmShare = true }, onPushRemove)
        }
    }
}

private const val PUSH_FILE = "~/.config/paddock/push-endpoint.json"

/** Connector mode: register with a UnifiedPush distributor, then (only on the user's say-so) hand its address to the relay. */
@Composable
private fun PushSection(ui: AlertRelayUi, onRegister: (String) -> Unit, onShare: () -> Unit, onRemove: () -> Unit) {
    val c = PaddockTokens.colors
    val push = ui.push
    val reg = push.registered
    Kicker("UnifiedPush", Modifier.padding(top = 16.dp))
    Text(
        "A UnifiedPush distributor on this phone (the ntfy app can be one) receives the relay's message and wakes Paddock. The message holds a machine id and a one-time number and nothing else; opening the notification makes Paddock read the herd over SSH first. Set delivery = \"unifiedpush\" in the relay's configuration to use it.",
        style = PaddockTokens.type.body, color = c.text,
    )
    push.notice?.let { Banner(it) }
    push.error?.let { Banner(it) }
    if (reg == null) {
        if (push.distributors.isEmpty()) Banner("No UnifiedPush distributor is installed on this phone, so this mode is unavailable. The ntfy-app mode above still works.")
        else for (d in push.distributors) PaddockButton("Register with ${d.label}", { onRegister(d.packageName) }, enabled = !push.busy, kind = ButtonKind.Secondary)
        return
    }
    Fact("Distributor", reg.distributorLabel + if (reg.distributorInstalled) "" else " (not installed)")
    reg.failure?.let { Banner(it) }
    Text(
        when {
            reg.hasEndpoint && reg.shared && push.onHost == false -> "The address was sent, but the file is not on ${ui.machine} now. Send it again."
            reg.hasEndpoint && reg.shared -> "The address is on ${ui.machine}" + (reg.endpointHost?.let { " (host $it)" } ?: "") + "."
            reg.hasEndpoint -> "The distributor gave this phone an address" + (reg.endpointHost?.let { " (host $it)" } ?: "") + ". It is not on ${ui.machine} yet."
            reg.failure != null -> "Registration failed. Register again."
            else -> "Waiting for the distributor's address…"
        },
        style = PaddockTokens.type.body, color = c.text,
    )
    val connected = ui.host !is AlertRelayHostState.NotConnected && ui.host !is AlertRelayHostState.Reading
    PaddockButton(if (reg.shared) "Send the address again…" else "Send the address to ${ui.machine}…", onShare, enabled = reg.hasEndpoint && connected && !push.busy, kind = if (reg.shared) ButtonKind.Ghost else ButtonKind.Primary)
    PaddockButton("Unregister", onRemove, enabled = !push.busy, kind = ButtonKind.Ghost)
    Note(AlertDelivery.BEST_EFFORT)
}
