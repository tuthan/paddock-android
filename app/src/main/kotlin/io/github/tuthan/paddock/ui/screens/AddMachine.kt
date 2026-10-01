package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.RouteNote
import io.github.tuthan.paddock.qr.QrCode
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ChoiceCard
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.QrView
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * What the screen needs from outside: the phone's public key once it exists, where it is held, whether an imported
 * key is available, whether the local-network grant was refused, and whether a connect is in flight.
 */
data class AddMachineState(
    val route: (String) -> RouteNote,
    val publicKeyLine: String? = null,
    val keyBacking: KeyBacking? = null,
    val importedKeyId: String? = null,
    val permissionDenied: Boolean = false,
    val connecting: Boolean = false,
    val connectError: String? = null,
    /** `ssh-ed25519 · SHA256:…` of the stored imported key, shown so the user can tell which key is in use. */
    val importedKeySummary: String? = null,
)

/** Where the phone's key is held, in words. Shown after generation, from what the platform reported, never assumed. */
fun backingText(backing: KeyBacking): String = when (backing) {
    KeyBacking.StrongBox -> "Held in this phone's StrongBox chip. It can sign but cannot be copied out."
    KeyBacking.Tee -> "Held in this phone's secure hardware (TEE). It can sign but cannot be copied out."
    KeyBacking.Software -> "Held in software only: this phone reports no secure hardware for it."
    KeyBacking.Unknown -> "This phone did not say where the key is held."
}

/**
 * Add a machine. Fields survive rotation and process death (`rememberSaveable`). Connect is pinned below a scrolling
 * body, so the keyboard never covers it and it stays reachable at 200% font. The first-connection fingerprint dialog,
 * the permission request and the connect itself are the caller's: this screen only reports a validated input.
 */
@Composable
fun AddMachine(
    state: AddMachineState,
    onConnect: (AddMachineInput) -> Unit,
    onGenerateKey: () -> Unit,
    onCopyPublicKey: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onImportKey: () -> Unit = {},
    modifier: Modifier = Modifier,
    initial: AddMachineInput = AddMachineInput(),
) {
    val c = PaddockTokens.colors
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var session by rememberSaveable { mutableStateOf(initial.session) }
    var key by rememberSaveable { mutableStateOf(initial.key) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val input = AddMachineInput(host, port, user, key, if (key == KeyKind.Imported) state.importedKeyId else null, session)
    val errors = AddMachineForm.errors(input)
    val route = state.route(host)

    fun connect() {
        showErrors = true
        if (!errors.any && !(key == KeyKind.Phone && state.publicKeyLine == null) && !(key == KeyKind.Imported && state.importedKeyId == null)) onConnect(input)
    }

    Column(modifier.fillMaxSize().imePadding().padding(horizontal = PaddockTokens.spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(PaddockTokens.spacing.touchTarget).minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack).semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Text("←", style = PaddockTokens.type.screenTitle, color = c.title) }
            Text("Add a machine", style = PaddockTokens.type.screenTitle, color = c.title, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Paddock reaches the machine that runs herdr over SSH. Over a VPN such as Tailscale it works from anywhere; on the same Wi-Fi a LAN address is enough.",
                style = PaddockTokens.type.body, color = c.dim,
            )
            Field("Host or IP address", host, { host = it }, error = if (showErrors) errors.host else null, keyboardType = KeyboardType.Uri, placeholder = "192.168.1.20 or box.example.ts.net")
            RouteHint(route, state.permissionDenied, onOpenSettings)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("User", user, { user = it }, Modifier.weight(2f), error = if (showErrors) errors.user else null)
                Field("Port", port, { port = it }, Modifier.weight(1f), error = if (showErrors) errors.port else null, keyboardType = KeyboardType.Number)
            }

            Field(
                "herdr session (optional)", session, { session = it }, error = if (showErrors) errors.session else null,
                placeholder = "default", imeAction = ImeAction.Done, onDone = ::connect,
            )

            Kicker("Sign in with")
            ChoiceCard("This phone's key", "A key made on this phone that never leaves it. You authorize it on the machine once.", key == KeyKind.Phone, { key = KeyKind.Phone })
            ChoiceCard(
                "An imported key",
                if (state.importedKeyId != null) "Your own key, stored encrypted on this phone." else "Your own private key, stored encrypted on this phone. None imported yet.",
                key == KeyKind.Imported, { key = KeyKind.Imported },
            )
            if (key == KeyKind.Phone) PhoneKeySection(state, showQr, { showQr = it }, onGenerateKey, onCopyPublicKey)
            if (key == KeyKind.Imported) ImportedKeySection(state, onImportKey)
            if (key == KeyKind.Imported && state.importedKeyId == null && showErrors) Banner("Import a key before connecting, or use this phone's key.")
            if (key == KeyKind.Phone && state.publicKeyLine == null && showErrors) Banner("Create this phone's key first, then authorize it on the machine and press Connect.")

            Text(
                "The first connection shows the machine's fingerprint before anything is trusted. Nothing signs in until you accept it.",
                style = PaddockTokens.type.secondary, color = c.dim,
            )
            if (state.connectError != null) Banner(state.connectError)
        }
        Column(Modifier.padding(vertical = 10.dp)) {
            PaddockButton(if (state.connecting) "Connecting…" else "Connect", ::connect, enabled = !state.connecting)
        }
    }
}

@Composable
private fun RouteHint(route: RouteNote, denied: Boolean, onOpenSettings: () -> Unit) {
    val c = PaddockTokens.colors
    when (route) {
        RouteNote.Empty -> Text("Away from home, use the machine's VPN address.", style = PaddockTokens.type.secondary, color = c.dim)
        RouteNote.NotLocal -> Text("Reached through a name or VPN route: no local-network access needed.", style = PaddockTokens.type.secondary, color = c.dim)
        RouteNote.LocalReady -> Text("A local-network address. It works on the same network as the machine, not away from home.", style = PaddockTokens.type.secondary, color = c.dim)
        RouteNote.LocalNeedsGrant ->
            if (denied) Banner(
                "Local-network access is off, so Paddock cannot reach this address. Turn it on in the app's settings, or use the machine's VPN address.",
                actionLabel = "Open settings", onAction = onOpenSettings,
            ) else Text(
                "This is a local-network address. When you press Connect, Android asks to let Paddock reach devices on your network. It is used only to connect to this machine.",
                style = PaddockTokens.type.secondary, color = c.dim,
            )
    }
}

@Composable
private fun ImportedKeySection(state: AddMachineState, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.importedKeyId != null) {
            if (state.importedKeySummary != null) Fact("Imported key", state.importedKeySummary)
            PaddockButton("Replace the imported key", onImport, kind = ButtonKind.Quiet)
        } else PaddockButton("Import a private key", onImport, kind = ButtonKind.Quiet)
    }
}

@Composable
private fun PhoneKeySection(state: AddMachineState, showQr: Boolean, onShowQr: (Boolean) -> Unit, onGenerate: () -> Unit, onCopy: (String) -> Unit) {
    val c = PaddockTokens.colors
    val line = state.publicKeyLine
    if (line == null) {
        PaddockButton("Create this phone's key", onGenerate, kind = ButtonKind.Quiet)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.keyBacking != null) Text(backingText(state.keyBacking), style = PaddockTokens.type.secondary, color = c.dim)
        Fact("Public key. Add this line to ~/.ssh/authorized_keys on the machine", line)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaddockButton("Copy", { onCopy(line) }, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PaddockButton(if (showQr) "Hide QR" else "Show as QR", { onShowQr(!showQr) }, Modifier.weight(1f), kind = ButtonKind.Quiet)
        }
        if (showQr) {
            val qr = remember(line) { runCatching { QrCode.encodeText(line) }.getOrNull() }
            if (qr != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrView(qr, "QR code of this phone's public key") }
            else Text("This key is too long for a QR code. Use Copy.", style = PaddockTokens.type.secondary, color = c.dim)
        }
    }
}
