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
import androidx.compose.runtime.produceState
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
import io.github.tuthan.paddock.hostprofile.AuthorizeCommand
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.RouteNote
import io.github.tuthan.paddock.qr.QrCode
import io.github.tuthan.paddock.ssh.AuthorizedKey
import io.github.tuthan.paddock.ssh.KeyBacking
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.withMono
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.components.ChoiceCard
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.QrView
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.delay
import io.github.tuthan.paddock.live.ConnectFix
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect

/**
 * What the screen needs from outside: the phone's public key once it exists, where it is held, whether an imported
 * key is available, whether the local-network grant was refused, and whether a connect is in flight.
 */
data class AddMachineState(
    /** The route hint from the typed text alone, shown at once. */
    val route: (String) -> RouteNote,
    val publicKeyLine: String? = null,
    val keyBacking: KeyBacking? = null,
    val importedKeyId: String? = null,
    val permissionDenied: Boolean = false,
    val connecting: Boolean = false,
    val connectError: String? = null,
    /** What would fix [connectError]: [ConnectFix.ShowCommand] puts the error above the authorize command and scrolls to it. */
    val connectFix: ConnectFix? = null,
    /** `ssh-ed25519 · SHA256:…` of the stored imported key, shown so the user can tell which key is in use. */
    val importedKeySummary: String? = null,
    /** The route hint from where the host resolves, asked once typing settles; null keeps the text-only hint. */
    val resolveRoute: (suspend (String) -> RouteNote)? = null,
    /** Why the pasted text could not be used as a pairing link, or null. Never the text itself. */
    val pairingNotice: String? = null,
    /** The desktop's host when a pairing link says its `pair` popup is listening: offers Send the key under this phone's key. Null otherwise. */
    val pairOfferHost: String? = null,
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
    modifier: Modifier = Modifier,
    onImportKey: () -> Unit = {},
    /** Copies the whole command that authorizes this phone (see [AuthorizeCommand]); the bare key line goes through [onCopyPublicKey]. */
    onCopyCommand: (String) -> Unit = {},
    /** Offers the same command text to the Android share sheet, and nothing else. */
    onShareCommand: (String) -> Unit = {},
    /** Reads a pairing link from the clipboard; null hides the button. */
    onPastePairingLink: (() -> Unit)? = null,
    /** Sends this phone's key to the desktop the pairing link named, with the form as typed; null leaves the button out. */
    onSendKey: ((AddMachineInput) -> Unit)? = null,
    initial: AddMachineInput = AddMachineInput(),
    title: String = "Add a machine",
    intro: String = ADD_MACHINE_INTRO,
) {
    val c = PaddockTokens.colors
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var session by rememberSaveable { mutableStateOf(initial.session) }
    var key by rememberSaveable { mutableStateOf(initial.key) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    // A pairing link's fingerprints apply to the host and port the link named; once either is changed they are not compared.
    val linked = initial.pairedFingerprints
    val linkApplies = linked != null && host.trim() == initial.host && port.trim() == initial.port
    val input = AddMachineInput(host, port, user, key, if (key == KeyKind.Imported) state.importedKeyId else null, session, if (linkApplies) linked else null)
    val errors = AddMachineForm.errors(input)
    val typed = state.route(host)
    // The text-only hint shows while typing; the resolved one replaces it once the host has been still for a moment.
    val route by produceState(typed, host, typed) {
        value = typed
        val resolve = state.resolveRoute ?: return@produceState
        if (host.isBlank()) return@produceState
        delay(ROUTE_SETTLE_MILLIS)
        value = resolve(host)
    }

    fun connect() {
        showErrors = true
        if (!errors.any && !(key == KeyKind.Phone && state.publicKeyLine == null) && !(key == KeyKind.Imported && state.importedKeyId == null)) onConnect(input)
    }

    val commandAnchor = remember { BringIntoViewRequester() }
    val showCommandFix = state.connectError != null && state.connectFix == ConnectFix.ShowCommand
    LaunchedEffect(state.connectError, state.connectFix) {
        // After the first layout: a request made before the anchor is placed has nowhere to scroll to.
        if (showCommandFix) { delay(ANCHOR_SETTLE_MILLIS); commandAnchor.bringIntoView() }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        ScreenHeader(title, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(intro, style = PaddockTokens.type.body, color = c.dim)
            if (linked != null) {
                if (linkApplies) {
                    Note(PairingCopy.FILLED_IN, icon = PaddockIcons.Key)
                    Fact(if (linked.size == 1) "Host key fingerprint in the link" else "Host key fingerprints in the link", linked.joinToString("\n"))
                } else Note(PairingCopy.FIELDS_CHANGED, icon = PaddockIcons.Warning)
            } else if (onPastePairingLink != null) {
                PaddockButton("Paste a pairing link", onPastePairingLink, kind = ButtonKind.Ghost, icon = PaddockIcons.Copy)
            }
            if (state.pairingNotice != null) Banner(state.pairingNotice)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Host or IP address", host, { host = it }, error = if (showErrors) errors.host else null, keyboardType = KeyboardType.Uri, placeholder = "192.168.1.20 or box.example.ts.net", mono = true)
                RouteHint(route, state.permissionDenied, onOpenSettings)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("User", user, { user = it }, Modifier.weight(2f), error = if (showErrors) errors.user else null, mono = true)
                Field("Port", port, { port = it }, Modifier.weight(1f), error = if (showErrors) errors.port else null, keyboardType = KeyboardType.Number, mono = true)
            }

            Field(
                "herdr session (optional)", session, { session = it }, error = if (showErrors) errors.session else null,
                placeholder = "default", imeAction = ImeAction.Done, onDone = ::connect, mono = true,
            )

            // With this phone's key the sentence sits inside the key section, directly above the command it points at, and the screen scrolls
            // to that group; otherwise (an imported key, no key yet) it leads the sign-in block.
            val sentenceInKeySection = showCommandFix && key == KeyKind.Phone && state.publicKeyLine != null
            Column(
                if (showCommandFix && !sentenceInKeySection) Modifier.bringIntoViewRequester(commandAnchor) else Modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (showCommandFix && !sentenceInKeySection) Banner(if (key == KeyKind.Phone) AUTHORIZE_FAILED_PHONE_KEY else AUTHORIZE_FAILED_IMPORTED_KEY)
            Kicker("Sign in with")
            ChoiceCard("This phone's key", "A key made on this phone that never leaves it. You authorize it on the machine once.", key == KeyKind.Phone, { key = KeyKind.Phone })
            ChoiceCard(
                "An imported key",
                if (state.importedKeyId != null) "Your own key, stored encrypted on this phone." else "Your own private key, stored encrypted on this phone. None imported yet.",
                key == KeyKind.Imported, { key = KeyKind.Imported },
            )
            if (key == KeyKind.Phone) PhoneKeySection(
                state, showQr, { showQr = it }, onGenerateKey, onCopyPublicKey, onCopyCommand, onShareCommand, if (sentenceInKeySection) AUTHORIZE_FAILED_PHONE_KEY else null, commandAnchor,
                sendKeyTo = if (linkApplies && onSendKey != null) state.pairOfferHost else null, onSendKey = { if (!errors.any) onSendKey?.invoke(input) else showErrors = true },
            )
            if (key == KeyKind.Imported) ImportedKeySection(state, onImportKey)
            if (key == KeyKind.Imported && state.importedKeyId == null && showErrors) Banner("Import a key before connecting, or use this phone's key.")
            if (key == KeyKind.Phone && state.publicKeyLine == null && showErrors) Banner("Create this phone's key first, then authorize it on the machine and press Connect.")
            }

            Note(
                "The first connection shows the machine's fingerprint before anything is trusted. Nothing signs in until you accept it.",
                icon = PaddockIcons.Warning,
            )
        }
        Column(Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The authorize failure is shown above the command it points at; every other failure is pinned here, above Connect, where it cannot be below the fold.
            if (state.connectError != null && !showCommandFix) {
                if (state.connectFix == ConnectFix.OpenSettings) Banner(state.connectError, actionLabel = "Open settings", onAction = onOpenSettings)
                else Banner(state.connectError)
            }
            PaddockButton(if (state.connecting) "Connecting…" else "Connect", ::connect, enabled = !state.connecting, icon = PaddockIcons.Key)
        }
    }
}

private const val ROUTE_SETTLE_MILLIS = 500L

/** Long enough for the first layout of the form; the scroll to the command is requested after it. */
private const val ANCHOR_SETTLE_MILLIS = 120L

const val ADD_MACHINE_INTRO =
    "Paddock reaches the machine that runs herdr over SSH. Over a VPN such as Tailscale it works from anywhere; on the same Wi-Fi a LAN address is enough."

/** Opened from Home's "Show the command": the machine refused this phone's key, so the way to authorize it is the first thing to read. */
const val AUTHORIZE_INTRO =
    "The machine did not accept this phone's key yet. Run the command below on the machine once, then press Connect."

const val AUTHORIZE_FAILED_PHONE_KEY = "The machine did not accept this phone's key. Run this on the machine, then press Connect again."
const val AUTHORIZE_FAILED_IMPORTED_KEY = "The machine did not accept this key. Authorize it on the machine, or use this phone's key instead, then press Connect again."

const val SET_UP_KEY_INTRO =
    "The key this machine signs in with can't be read on this phone. Create a new phone key or import yours again, authorize it on the machine, then press Connect."

@Composable
private fun RouteHint(route: RouteNote, denied: Boolean, onOpenSettings: () -> Unit) {
    when (route) {
        RouteNote.Empty -> Note("Same network, or a VPN route such as Tailscale or WireGuard. Desktop SSH aliases do not apply here.")
        RouteNote.NotLocal -> Note("Reached through a name or VPN route: no local-network access needed.")
        RouteNote.LocalReady -> Note("A local-network address. It works on the same network as the machine, not away from home.")
        RouteNote.LocalNeedsGrant ->
            if (denied) Banner(
                "Local-network access is off, so Paddock cannot reach this address. Turn it on in the app's settings, or use the machine's VPN address.",
                actionLabel = "Open settings", onAction = onOpenSettings,
            ) else Note(
                "This is a local-network address. When you press Connect, Android asks to let Paddock reach devices on your network. It is used only to connect to this machine.",
            )
    }
}

@Composable
private fun ImportedKeySection(state: AddMachineState, onImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.importedKeyId != null) {
            if (state.importedKeySummary != null) Fact("Imported key", state.importedKeySummary)
            PaddockButton("Replace the imported key", onImport, kind = ButtonKind.Secondary, icon = PaddockIcons.File)
        } else PaddockButton("Import a private key", onImport, kind = ButtonKind.Secondary, icon = PaddockIcons.File)
    }
}

@Composable
private fun PhoneKeySection(
    state: AddMachineState, showQr: Boolean, onShowQr: (Boolean) -> Unit, onGenerate: () -> Unit, onCopy: (String) -> Unit,
    onCopyCommand: (String) -> Unit, onShareCommand: (String) -> Unit,
    /** The sentence that says the machine refused this key, drawn directly above the command, and the anchor the screen scrolls to. */
    refusedSentence: String? = null, anchor: BringIntoViewRequester? = null,
    /** The desktop to send the key to, when the pairing link named one that is still what the fields say. */
    sendKeyTo: String? = null, onSendKey: () -> Unit = {},
) {
    val c = PaddockTokens.colors
    val line = state.publicKeyLine
    if (line == null) {
        PaddockButton("Create this phone's key", onGenerate, kind = ButtonKind.Secondary, icon = PaddockIcons.Key)
        return
    }
    // A command exists only for a line the key parser accepts; for anything else the bare line is all there is.
    val key = remember(line) { AuthorizedKey.parse(line) }
    val command = remember(key) { key?.let(AuthorizeCommand::forKey) }
    var copied by remember(line) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.keyBacking != null) Text(backingText(state.keyBacking), style = PaddockTokens.type.secondary, color = c.dim)
        if (command != null) {
            Column(if (anchor != null) Modifier.bringIntoViewRequester(anchor) else Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (refusedSentence != null) Banner(refusedSentence)
            Fact("Command to run on the machine", command)
            ButtonPair(
                { m -> PaddockButton(if (copied) "Copied" else "Copy", { onCopyCommand(command); copied = true }, m, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Copy) },
                { m -> PaddockButton("Share", { onShareCommand(command) }, m, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Send) },
            )
            }
            Note(withMono("Run it once, in a shell you already trust on the machine. It creates ~/.ssh if needed and adds this key to ~/.ssh/authorized_keys unless it is already there.", "~/.ssh/authorized_keys", "~/.ssh"))
            if (key != null) Fact("Key fingerprint", key.fingerprint)
        }
        Fact("Public key to authorize", line)
        ButtonPair(
            { m -> PaddockButton("Copy key only", { onCopy(line) }, m, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Copy) },
            { m -> PaddockButton(if (showQr) "Hide QR" else "Show as QR", { onShowQr(!showQr) }, m, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Qr) },
        )
        // An addition, never a replacement: the command, Copy, Share, Copy key only and Show as QR above are all still here.
        if (sendKeyTo != null) {
            PaddockButton("Send the key to $sendKeyTo", onSendKey, kind = ButtonKind.Secondary, icon = PaddockIcons.Send)
            Note("The desktop shows this phone's fingerprint and asks its owner to approve it. Only this phone's public key is sent.")
            // The desktop's camera reads the same QR that Show as QR draws below; this is the same switch, put where the pairing path is.
            PaddockButton(if (showQr) "Hide the QR" else "Show the key to the desktop's camera", { onShowQr(!showQr) }, kind = ButtonKind.Secondary, icon = PaddockIcons.Qr)
        }
        if (command == null) Note(withMono("Append it to ~/.ssh/authorized_keys on the machine, from a shell you already trust.", "~/.ssh/authorized_keys"))
        if (showQr) {
            BrightnessBoost()
            val qr = remember(line) { runCatching { QrCode.encodeText(line) }.getOrNull() }
            if (qr != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrView(qr, "QR code of this phone's public key") }
            else Text("This key is too long for a QR code. Use Copy key only.", style = PaddockTokens.type.secondary, color = c.dim)
        }
    }
}

/**
 * Full screen brightness for as long as it is composed, then the window's own setting again. A QR held up to a camera is read far more
 * reliably from a bright screen than from one dimmed by the phone's auto-brightness. It touches this window only, never the system setting.
 */
@Composable
fun BrightnessBoost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(context) {
        val window = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity }?.let { (it as android.app.Activity).window }
        val before = window?.attributes?.screenBrightness
        if (window != null) window.attributes = window.attributes.also { it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        onDispose { if (window != null && before != null) window.attributes = window.attributes.also { it.screenBrightness = before } }
    }
}
