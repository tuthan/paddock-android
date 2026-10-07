package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.alerts.AlertDelivery
import io.github.tuthan.paddock.alerts.AlertRelayStatus
import io.github.tuthan.paddock.alerts.AlertSetupCopy
import io.github.tuthan.paddock.alerts.AlertSetupForm
import io.github.tuthan.paddock.alerts.AlertStatus
import io.github.tuthan.paddock.alerts.DeliveryMode
import io.github.tuthan.paddock.alerts.NtfyServer
import io.github.tuthan.paddock.alerts.SavedAlertSetup
import io.github.tuthan.paddock.alerts.ServiceState
import io.github.tuthan.paddock.alerts.SetupStep
import io.github.tuthan.paddock.alerts.StepState
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ChoiceCard
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.FilterChips
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.card
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

/** What the screen knows about the Paddock-shows-them mode (UnifiedPush) for this machine. */
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

/** The choices on the setup form. The route keeps them across a rotation; the screen only edits them. */
data class AlertForm(
    val mode: DeliveryMode = DeliveryMode.NtfyApp,
    val ownServer: Boolean = false,
    val ownUrl: String = "",
    val token: String = "",
    /** The package of the distributor to register with (the Paddock-shows-them mode); null picks the first installed. */
    val distributor: String? = null,
)

/** The setup as it runs: each step and where it is, and how it ended. */
data class SetupRunUi(
    val steps: List<Pair<SetupStep, StepState>> = emptyList(),
    val running: Boolean = false,
    /** Set when a step stopped it, in words. */
    val message: String? = null,
    /** Set when it finished but something is worth knowing (the machine will not keep the relay after a logout). */
    val note: String? = null,
    val finished: Boolean = false,
)

data class AlertRelayUi(
    val machine: String,
    val profileId: String,
    val scriptSha256: String,
    val host: AlertRelayHostState,
    /** What this phone last set up for the machine, or null. */
    val saved: SavedAlertSetup? = null,
    val push: PushUi = PushUi(),
    val run: SetupRunUi = SetupRunUi(),
    /** The script the setup runs for the form as it stands, shown on request and copyable; null while the form is incomplete or the machine is not connected. */
    val script: String? = null,
    val copied: Boolean = false,
    val installing: Boolean = false,
    val installError: String? = null,
    /** A test alert or a turn-off is in flight. */
    val busy: Boolean = false,
    /** What the last test or turn-off said, and whether that is a problem. */
    val result: String? = null,
    val resultIsProblem: Boolean = false,
    /** How the machine keeps the relay running: a systemd unit on Linux, a LaunchAgent on a Mac. It changes what the confirmation and the details say. */
    val platform: io.github.tuthan.paddock.alerts.ServicePlatform = io.github.tuthan.paddock.alerts.ServicePlatform.Systemd,
)

private const val OWN_SERVER_HINT = "Recommended for a machine you work on: your server, your logs. Give its address and, if it needs one, an access token."

/**
 * Locked-phone alerts, set up in one step. The screen asks two things (how the alert reaches you, and which server), shows exactly what will be written
 * on the machine, and does the lot over SSH after one confirmation: it installs the pinned relay, writes its unit and configuration (owner-only), checks
 * the configuration with the relay's own check, then enables and starts it. A test alert proves the path; turning alerts off is one tap too. What used
 * to be steps to paste by hand is under "Details and manual setup", as the exact text that runs.
 */
@Composable
fun AlertRelay(
    ui: AlertRelayUi, form: AlertForm, onForm: (AlertForm) -> Unit, onBack: () -> Unit,
    onTurnOn: () -> Unit, onTurnOff: () -> Unit, onTest: () -> Unit, onCheck: () -> Unit, onCopy: () -> Unit, onInstall: () -> Unit,
    onOpenNtfy: (String) -> Unit, onCopyLink: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    var confirmOn by rememberSaveable { mutableStateOf(false) }
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    var confirmInstall by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val known = (ui.host as? AlertRelayHostState.Known)?.status
    val connected = ui.host is AlertRelayHostState.Known || ui.host is AlertRelayHostState.Failed
    val running = known != null && known.script == RelayState.Current && known.check?.ok == true && known.service == ServiceState.Active
    val status = AlertStatus.of(running, ui.saved, ui.push.onHost)
    val check = AlertSetupForm.check(form.mode, form.ownServer, form.ownUrl, form.token, ui.push.distributors.size)
    val canStart = connected && check.canStart && !ui.run.running && !ui.busy
    val on = status == AlertStatus.On || status == AlertStatus.NeedsAttention || status == AlertStatus.OnByHand
    // The buttons that report back (Send a test alert, Open in the ntfy app, Copy the subscribe link) are in the lower part of the screen, which is there once alerts are on or a
    // ntfy topic is saved; the result is drawn under them and scrolled into view. Otherwise (after Turn off, say) the lower part is gone and the result stays at the top.
    val resultBelow = on || (ui.saved?.let { it.mode == DeliveryMode.NtfyApp && it.topic.isNotEmpty() } == true)
    val resultRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(ui.result) { if (ui.result != null) resultRequester.bringIntoView() }
    @Composable fun ResultLine() {
        val text = ui.result ?: return
        Box(Modifier.fillMaxWidth().bringIntoViewRequester(resultRequester).semantics { liveRegion = LiveRegionMode.Polite }) {
            if (ui.resultIsProblem) Banner(text) else Note(text)
        }
    }

    if (confirmOn) {
        ConfirmDialog(
            "Turn on alerts on ${ui.machine}?",
            "Paddock does this on ${ui.machine} over SSH, then checks it and starts the relay. Nothing is started before the relay's own check of the configuration passes. " +
                "The exact commands are under \"Details and manual setup\".",
            confirm = if (on) "Update" else "Turn on", onConfirm = { confirmOn = false; onTurnOn() }, onCancel = { confirmOn = false },
            facts = buildList {
                if (known?.script != RelayState.Current) add("Relay script" to "${known?.destination ?: "~/.local/share/paddock/paddock-alert-relay.py"} (SHA-256 ${ui.scriptSha256.take(16)}…)")
                add("Configuration" to "~/.config/paddock/alert-relay.toml, owner-only; an earlier one is kept as .bak")
                add("Service" to AlertSetupCopy.serviceFact(ui.platform))
                add((if (ui.platform == io.github.tuthan.paddock.alerts.ServicePlatform.Launchd) "After a logout or restart" else "After a logout") to AlertSetupCopy.afterLogoutFact(ui.platform))
                if (form.mode == DeliveryMode.Push) add("Address file" to "~/.config/paddock/push-endpoint.json, owner-only: whoever has the address can send this phone an alert")
            },
        )
    }
    if (confirmOff) {
        ConfirmDialog(
            "Turn off alerts on ${ui.machine}?",
            "Paddock stops and disables the relay on ${ui.machine} and removes the address file. The configuration stays, so turning alerts on again keeps your topic. Nothing else on the machine changes.",
            confirm = "Turn off", onConfirm = { confirmOff = false; onTurnOff() }, onCancel = { confirmOff = false }, danger = true,
        )
    }
    if (confirmInstall) {
        ConfirmDialog(
            if (known?.script is RelayState.Mismatch) "Replace the alert relay on ${ui.machine}?" else "Install the alert relay on ${ui.machine}?",
            "Paddock writes this one file, with owner-only permissions, and checks its hash before it is ever run. It does not write the unit or the configuration, and it does not start or enable anything.",
            confirm = if (known?.script is RelayState.Mismatch) "Replace" else "Install",
            onConfirm = { confirmInstall = false; onInstall() }, onCancel = { confirmInstall = false },
            facts = listOfNotNull(known?.destination?.let { "File on the host" to it }, "SHA-256 of the script" to ui.scriptSha256),
        )
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Locked-phone alerts", onBack = onBack, compact = true)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PaddockTokens.spacing.gutter, end = PaddockTokens.spacing.gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(AlertDelivery.HOW, style = PaddockTokens.type.body, color = c.text)
            Banner(AlertDelivery.BEST_EFFORT, tint = c.accent, icon = PaddockIcons.Bell)

            // ---- where things stand ----
            when (val h = ui.host) {
                AlertRelayHostState.NotConnected -> Banner("Paddock is not connected to ${ui.machine}, so it cannot see or set up the relay. Connect from the herd, then come back.")
                AlertRelayHostState.Reading -> Note("Reading the machine…")
                is AlertRelayHostState.Failed -> Banner("Could not read the machine: ${h.message}", actionLabel = "Try again", onAction = onCheck)
                is AlertRelayHostState.Known -> Unit
            }
            if (known != null) StatusCard(status, ui, known, running)
            ui.push.notice?.let { Banner(it) }
            if (!resultBelow) ResultLine()

            // ---- the setup ----
            Kicker(if (on) "Change how alerts arrive" else "How should alerts reach you?", Modifier.padding(top = 8.dp))
            ChoiceCard(
                "Paddock shows the alert", pushDetail(ui),
                selected = form.mode == DeliveryMode.Push, onSelect = { onForm(form.copy(mode = DeliveryMode.Push)) },
            )
            ChoiceCard(
                "The ntfy app shows the alert", "Works with the ntfy app on Android and on an iPhone. On Android, tapping it opens Paddock.",
                selected = form.mode == DeliveryMode.NtfyApp, onSelect = { onForm(form.copy(mode = DeliveryMode.NtfyApp)) },
            )
            check.modeError?.let { Banner(it) }

            if (form.mode == DeliveryMode.NtfyApp) {
                Kicker("Which server?", Modifier.padding(top = 4.dp))
                ChoiceCard(
                    "Public server (ntfy.sh)", "Nothing to set up. The topic is a long random name made for you; anyone who learns it can read and send to it.",
                    selected = !form.ownServer, onSelect = { onForm(form.copy(ownServer = false)) },
                )
                ChoiceCard("My own server", OWN_SERVER_HINT, selected = form.ownServer, onSelect = { onForm(form.copy(ownServer = true)) })
                if (form.ownServer) {
                    Field("Server address", form.ownUrl, { onForm(form.copy(ownUrl = it)) }, error = check.urlError, placeholder = "https://ntfy.example.org", mono = true)
                    Field("Access token (optional)", form.token, { onForm(form.copy(token = it)) }, error = check.tokenError, secret = true, mono = true)
                }
            } else if (ui.push.distributors.size > 1) {
                Kicker("Which app?", Modifier.padding(top = 4.dp))
                val labels = ui.push.distributors.map { it.label }
                val picked = ui.push.distributors.indexOfFirst { it.packageName == form.distributor }.coerceAtLeast(0)
                FilterChips(labels, picked, { onForm(form.copy(distributor = ui.push.distributors[it].packageName)) })
            }
            if (form.mode == DeliveryMode.Push) {
                val host = ui.push.registered?.endpointHost
                Note(
                    if (host != null) "Alerts go through ${AlertSetupForm.serverWords("https://$host")}. To change the server, change it in the ntfy app (Settings), then turn alerts on again."
                    else "The server is the one set in the ntfy app (its Settings): the public ntfy.sh unless you changed it. Paddock does not ask for one.",
                )
            }

            PaddockButton(
                if (ui.run.running) "Setting up…" else if (on) "Update alerts…" else "Turn on alerts…", { confirmOn = true },
                enabled = canStart, icon = PaddockIcons.Bell,
            )
            if (!connected) Note("Connect to ${ui.machine} to set up alerts.")

            // ---- progress and the end of it ----
            if (ui.run.steps.isNotEmpty()) {
                Kicker("Setting up", Modifier.padding(top = 4.dp))
                Column(Modifier.card(c), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((step, state) in ui.run.steps) {
                        Row(Modifier.semantics(mergeDescendants = true) { stateDescription = state.word() }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(step.label, style = PaddockTokens.type.body, color = c.text, modifier = Modifier.weight(1f))
                            Text(state.word(), style = PaddockTokens.type.secondary, color = if (state == StepState.Failed) c.needsYou else c.dim)
                        }
                    }
                }
            }
            ui.run.message?.let { Banner(it) }
            if (ui.run.finished && ui.run.message == null) Banner("Alerts are on for ${ui.machine}.", tint = c.accent, icon = PaddockIcons.Bell)
            ui.run.note?.let { Banner(it) }

            // ---- after: subscribe (ntfy app), test, turn off ----
            val saved = ui.saved
            if (saved != null && saved.mode == DeliveryMode.NtfyApp && saved.topic.isNotEmpty()) SubscribeCard(saved, onOpenNtfy, onCopyLink)
            if (on) {
                Kicker("Check it works", Modifier.padding(top = 8.dp))
                Text("Sends one test alert from ${ui.machine}, the way the relay would. You should see it on this phone within a few seconds.", style = PaddockTokens.type.body, color = c.text)
                PaddockButton(if (ui.busy) "Working…" else "Send a test alert", onTest, kind = ButtonKind.Secondary, enabled = connected && !ui.busy && !ui.run.running)
                PaddockButton("Turn off alerts…", { confirmOff = true }, kind = ButtonKind.Danger, enabled = connected && !ui.busy && !ui.run.running)
            }
            // What a test, a copy or an open said, right under the buttons that asked: it used to sit under the status card at the top, off screen from the button.
            if (resultBelow) ResultLine()

            // ---- details and the manual way ----
            Row(
                Modifier.padding(top = 8.dp).card(c, padded = false)
                    .clickable(role = Role.Button, onClickLabel = if (details) "Hide the details" else "Show the details") { details = !details }
                    .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { stateDescription = if (details) "Expanded" else "Collapsed" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Details and manual setup", style = PaddockTokens.type.rowTitle, color = c.title)
                    Text("The relay script's hash, its state on the machine, and the exact commands the setup runs", style = PaddockTokens.type.secondary, color = c.dim)
                }
                Icon(PaddockIcons.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp).rotate(if (details) 90f else 0f))
            }
            if (details) Details(ui, known, onCheck, { confirmInstall = true }, onCopy)
            Note(AlertDelivery.MEASURED)
        }
    }
}

private fun StepState.word() = when (this) { StepState.Pending -> "waiting"; StepState.Running -> "working…"; StepState.Done -> "done"; StepState.Failed -> "failed" }

private fun pushDetail(ui: AlertRelayUi): String {
    val label = ui.push.registered?.distributorLabel ?: ui.push.distributors.firstOrNull()?.label
    return if (label == null) "Needs the ntfy app, or another UnifiedPush app, on this phone. Android only."
    else "Through $label. Paddock raises the notification itself, with its own channels and lock-screen privacy, and a tap opens the herd. Android only."
}

/** One line of where things stand, then what that means. The word comes first so the state is not carried by colour. */
@Composable
private fun StatusCard(status: AlertStatus, ui: AlertRelayUi, known: AlertRelayStatus, running: Boolean) {
    val c = PaddockTokens.colors
    val saved = ui.saved
    val how = when {
        saved == null -> null
        saved.mode == DeliveryMode.Push -> "Paddock shows them"
        else -> "the ntfy app shows them, through ${AlertSetupForm.serverWords(saved.ntfyUrl)}"
    }
    val (title, detail) = when (status) {
        AlertStatus.Off -> "Alerts are off" to "Nothing is set up on ${ui.machine} for this phone."
        AlertStatus.On -> "Alerts are on" to "The relay is running on ${ui.machine}; $how."
        AlertStatus.OnByHand -> "A relay is running" to "It was set up outside Paddock (by hand, or from another phone), so Paddock cannot say how it delivers. Turning alerts on here replaces its configuration; the old one is kept."
        AlertStatus.NeedsAttention -> "Alerts need attention" to when {
            known.script != RelayState.Current -> "The relay script is not installed on ${ui.machine}."
            known.check?.ok == false -> "The relay's own check fails: ${known.check?.lines?.joinToString("; ").orEmpty().ifBlank { "see the details" }}."
            known.service != ServiceState.Active -> "The relay is ${known.service.label} on ${ui.machine}."
            else -> "The address file is missing on ${ui.machine}."
        } + " Turn alerts on again to repair it."
    }
    Column(
        Modifier.card(c).semantics(mergeDescendants = true) { stateDescription = title },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = PaddockTokens.type.rowTitle, color = if (status == AlertStatus.NeedsAttention) c.attention else c.title)
        Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
    }
}

/** Subscribing the ntfy app to the topic: one tap on Android, the two facts to type on an iPhone. */
@Composable
private fun SubscribeCard(saved: SavedAlertSetup, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    val c = PaddockTokens.colors
    val link = NtfyServer.subscribeLink(saved.ntfyUrl, saved.topic)
    Kicker("Subscribe the ntfy app", Modifier.padding(top = 8.dp))
    Text("The ntfy app only shows alerts once it is subscribed to the topic on ${AlertSetupForm.serverWords(saved.ntfyUrl)}.", style = PaddockTokens.type.body, color = c.text)
    PaddockButton("Open in the ntfy app", { onOpen(link) }, icon = PaddockIcons.Bell)
    PaddockButton("Copy the subscribe link", { onCopy(link) }, kind = ButtonKind.Secondary, icon = PaddockIcons.Copy)
    Fact("Server", saved.ntfyUrl)
    Fact("Topic", saved.topic)
    Note("On an iPhone: in the ntfy app, add a subscription, enter the topic and, for your own server, its address. An iPhone only gets instant alerts from your own server if that server forwards a poll request to ntfy.sh (upstream-base-url); only a message id and a hash of the topic are forwarded, never the alert.")
    if (!NtfyServer.isPublic(saved.ntfyUrl)) Note("If your server needs a login, the ntfy app needs the same one.")
}

@Composable
private fun Details(ui: AlertRelayUi, known: AlertRelayStatus?, onCheck: () -> Unit, onInstall: () -> Unit, onCopy: () -> Unit) {
    val c = PaddockTokens.colors
    Fact("SHA-256 of paddock-alert-relay.py", ui.scriptSha256)
    when (val h = ui.host) {
        AlertRelayHostState.NotConnected, AlertRelayHostState.Reading -> Unit
        is AlertRelayHostState.Failed -> Unit
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
            Fact(AlertSetupCopy.serviceLabel(ui.platform), s.service.label)
        }
    }
    ui.installError?.let { Banner(it) }
    val installLabel = when {
        ui.installing -> "Installing…"
        known?.script is RelayState.Mismatch -> "Replace the script…"
        known?.script == RelayState.Current -> "Reinstall the script…"
        else -> "Install the script…"
    }
    PaddockButton(installLabel, onInstall, enabled = known != null && !ui.installing, kind = ButtonKind.Ghost)
    PaddockButton("Check the setup", onCheck, enabled = ui.host !is AlertRelayHostState.NotConnected && ui.host !is AlertRelayHostState.Reading, kind = ButtonKind.Ghost)

    Kicker("The commands", Modifier.padding(top = 8.dp))
    Text(
        "This is exactly what \"Turn on alerts\" runs on ${ui.machine}, for the choices above. Read it, or paste it into a shell there yourself. " +
            "It checks the configuration before it enables anything.",
        style = PaddockTokens.type.body, color = c.text,
    )
    if (ui.script != null) {
        val shape = RoundedCornerShape(PaddockTokens.radii.row)
        Text(
            ui.script, style = PaddockTokens.type.monoFact, color = c.text,
            modifier = Modifier.fillMaxWidth().clip(shape).background(c.slab).border(1.dp, c.line(), shape).horizontalScroll(rememberScrollState()).padding(12.dp),
        )
        PaddockButton(if (ui.copied) "Copied" else "Copy the commands", onCopy, icon = PaddockIcons.Copy, kind = ButtonKind.Secondary)
        if (ui.script.contains("unifiedpush")) Note("In the Paddock-shows-them mode Paddock writes the address file itself (it holds the address the ntfy app gave this phone); these commands cover the rest.")
    } else {
        Note("The commands appear once Paddock is connected and the choices above are complete.")
    }
}
