package io.github.tuthan.paddock.routes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.discovery.FinderController
import io.github.tuthan.paddock.host.ConnectAttempt
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostkey.HostKeyBroker
import io.github.tuthan.paddock.hostprofile.AddMachineForm
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.HostProfileStore
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.hostprofile.PairingResult
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.pairing.PairingCoordinator
import io.github.tuthan.paddock.pairing.PairingState
import io.github.tuthan.paddock.pairing.PairingText
import io.github.tuthan.paddock.pairing.pendingOrNull
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.ImportedKeyInfo
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ssh.PhoneKey
import io.github.tuthan.paddock.ui.commandShareIntent
import io.github.tuthan.paddock.ui.screens.ADD_MACHINE_INTRO
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_INTRO
import io.github.tuthan.paddock.ui.screens.AUTHORIZE_INTRO_IMPORTED
import io.github.tuthan.paddock.ui.screens.AddMachine
import io.github.tuthan.paddock.ui.screens.AddMachineState
import io.github.tuthan.paddock.ui.screens.PairWithDesktop
import io.github.tuthan.paddock.ui.screens.PairingTarget
import io.github.tuthan.paddock.ui.screens.SET_UP_KEY_INTRO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Add machine screens use from the app (the form, Find on the network, Scan and Import key share it): one holder because the screens forward most of
 * it to each other. [of] is the only place this file knows the app graph.
 */
internal class AddMachineDeps(
    val machineBook: MachineBook, val profiles: HostProfileStore, val broker: HostKeyBroker, val hostUi: HostUiModel, val gate: LocalNetworkGate,
    val finder: FinderController, val pairingCoordinator: PairingCoordinator, val phoneKey: PhoneKey, val prefs: SettingsController,
    val retry: () -> Unit, val createPhoneKey: suspend () -> Unit, val sendPairingKey: suspend (host: String, port: Int, sid: String) -> Boolean,
    val importedKey: suspend () -> ImportedKeyInfo?, val importKey: suspend (pem: String, passphrase: String) -> ImportCheck,
) {
    companion object {
        fun of(graph: AppGraph) = AddMachineDeps(
            graph.machineBook, graph.profiles, graph.broker, graph.hostUi, graph.gate, graph.finder, graph.pairingCoordinator, graph.phoneKey, graph.prefs,
            graph::retry, graph::createPhoneKey, graph::sendPairingKey, graph::importedKey, graph::importKey,
        )
    }
}

@Composable
internal fun AddMachineRoute(
    deps: AddMachineDeps, editing: Boolean, pairing: PairingLink?, onBack: () -> Unit,
    attempt: ConnectAttempt?, onAttempt: (ConnectAttempt?) -> Unit, authorizeIntro: Boolean,
    onPairing: (PairingLink) -> Unit, onNotice: (String) -> Unit,
    picked: AddMachineInput?, onPicked: (AddMachineInput?) -> Unit,
) {
    val ctx = LocalContext.current
    val watched by deps.machineBook.profile.collectAsState()
    // Setting up the key keeps the machine (its id, so its pinned host key and history) while it is the same host and port.
    val fixing = watched.takeIf { editing }
    val scope = rememberCoroutineScope()
    var keyTick by remember { mutableIntStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // The refusal is the dialog's answer, not the live state: the grant can be turned on in the app's system settings, so it is
    // read again on every return, and a banner saying "access is off" never outlives the grant.
    val resumes = rememberResumes()
    LaunchedEffect(resumes) { if (denied && !deps.gate.lanAccessMissing()) denied = false }
    var pending by remember { mutableStateOf<AddMachineInput?>(null) }
    // Send the key waits for the local-network grant the way Connect does; kept here so the answer to the dialog can pick it up.
    // It keeps the offer it was asked for with the form: the dialog can stay up while another link arrives, and the key goes to the desktop that was tapped, never to whichever link is current when the dialog is answered.
    var pendingSend by remember { mutableStateOf<Pair<AddMachineInput, PairingLink>?>(null) }
    // The form as typed when the key was sent: what Connect uses once the desktop approves (or when the window ended unheard).
    var sentInput by remember { mutableStateOf<AddMachineInput?>(null) }

    fun finish(input: AddMachineInput) {
        scope.launch {
            // The same machine (host, port and user, or the one being fixed) stays one profile: its id, name and wake facts carry over.
            val known = deps.profiles.list()
            val profile = AddMachineForm.resolve(input, known, fixing) ?: return@launch
            val already = known.any { it.id == profile.id }
            // Set or cleared on every Connect: a link's fingerprints are compared with the key this machine presents, and never outlive the form that carried them.
            deps.broker.expectPairing(profile.id, input.pairedFingerprints)
            // What is on screen now is the last attempt's: only a phase that is not that one can be this attempt's answer.
            deps.hostUi.beginAttempt()
            // Setting up the watched machine's key keeps the user's choice where it was: an alert may have moved the phone to this machine.
            deps.machineBook.add(profile, chosen = MachineRoster.choosesOnAdd(profile, fixing))
            // The same profile is resumed, not rebuilt: ask for the reconnect that picks up the new key.
            if (already) deps.retry()
            // Stay on the form: the root follows the connection and either leaves for Home or shows what went wrong here.
            onAttempt(ConnectAttempt(profile.id))
        }
    }
    fun beginSend(input: AddMachineInput, offer: PairingLink) {
        val port = offer.pairPort ?: return
        val sid = offer.sid ?: return
        sentInput = input
        scope.launch { if (!deps.sendPairingKey(offer.host, port, sid)) onNotice("Create this phone's key first, then send it.") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        val input = pending
        pending = null
        // A refusal is not the end: the screen keeps the recovery row, and a VPN address still works without the grant.
        if (granted && input != null) finish(input)
        val send = pendingSend
        pendingSend = null
        if (granted && send != null) beginSend(send.first, send.second)
    }
    // Keystore reads and key generation can take a while on some phones: off the main thread. An unreadable key (lost
    // Keystore entry, corrupt store) reads as "no key" here; connecting reports it with its own recovery.
    val key by produceState<Pair<String, io.github.tuthan.paddock.ssh.KeyBacking>?>(null, keyTick) {
        value = withContext(Dispatchers.Default) {
            runCatching { if (deps.phoneKey.exists()) deps.phoneKey.publicLine("paddock@phone") to deps.phoneKey.info().backing else null }.getOrNull()
        }
    }
    var importedTick by remember { mutableIntStateOf(0) }
    val imported by produceState<ImportedKeyInfo?>(null, importedTick) { value = runCatching { deps.importedKey() }.getOrNull() }
    var importing by rememberSaveable { mutableStateOf(false) }
    // The form is kept by the holder while the import screen is up, so typed values are still there on return.
    val holder = rememberSaveableStateHolder()
    if (importing) {
        // Back from the import screen returns to the form, not past it.
        BackHandler { importing = false }
        ImportKeyRoute(deps.prefs, deps.importKey, onDone = { importedTick++; importing = false }, onBack = { importing = false })
        return
    }
    // The same for the scanner: opening it leaves the form's typed values under the holder, and a code read replaces them with the link's.
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) {
        BackHandler { scanning = false }
        ScanRoute(deps.gate, onLink = { onPairing(it); scanning = false }, onBack = { scanning = false })
        return
    }
    // The same for Find on this network: the form (and what was typed in it) waits under the holder while the finder is up.
    var finding by rememberSaveable { mutableStateOf(false) }
    if (finding) {
        BackHandler { finding = false }
        FindRoute(deps.finder, deps.gate, onPick = { onAttempt(null); onPicked(it); finding = false }, onBack = { finding = false })
        return
    }
    val state = AddMachineState(
        route = { host -> AddMachineForm.route(host, deps.gate.decide(AddMachineForm.normalizeHost(host))) },
        resolveRoute = { host ->
            val h = AddMachineForm.normalizeHost(host)
            val (endpoint, grant) = deps.gate.resolvedRoute(h)
            AddMachineForm.route(host, grant, endpoint)
        },
        publicKeyLine = key?.first,
        keyBacking = key?.second,
        importedKeyId = imported?.id,
        importedKeySummary = imported?.let { "${it.keyType} · ${it.fingerprint}" },
        permissionDenied = denied,
        pairOfferHost = pairing?.takeIf { it.pairPort != null && it.sid != null }?.host,
        connecting = attempt?.connecting == true,
        connectError = attempt?.error,
        connectFix = attempt?.fix,
    )
    val pairState by deps.pairingCoordinator.state.collectAsState()
    val pairNow = rememberNow()
    val pairView = PairingText.view(pairState, pairNow)
    // Approved: the key is authorized on the machine, so connect with the form as it was sent. The request is done with either way.
    LaunchedEffect(pairState) {
        val approved = pairState as? PairingState.Approved ?: return@LaunchedEffect
        val input = sentInput
        sentInput = null
        deps.pairingCoordinator.clear()
        if (input != null) finish(input) else onNotice("The desktop approved this phone's key. Press Connect to sign in.")
    }
    if (pairView != null && !pairView.approved) {
        val p = pairState.pendingOrNull
        // The system Back gesture is the header arrow's twin: a request that is still going is cancelled (so a late approval cannot connect a
        // phone that has left this page), a finished one is dismissed.
        BackHandler { scope.launch { if (pairView.active) deps.pairingCoordinator.cancel() else deps.pairingCoordinator.clear() } }
        PairWithDesktop(
            pairView, PairingTarget(p?.let { "${it.host}:${it.port}" }.orEmpty(), p?.fingerprint.orEmpty()),
            onCancel = { scope.launch { deps.pairingCoordinator.cancel() } },
            onBack = { scope.launch { deps.pairingCoordinator.clear() } },
            onConnect = { val input = sentInput; sentInput = null; scope.launch { deps.pairingCoordinator.clear() }; if (input != null) finish(input) },
        )
        return
    }
    // Its own saved form: setting up a key starts from the watched machine's values, not from a half-typed new one.
    holder.SaveableStateProvider(fixing?.let { "key-${it.id}" } ?: pairing?.let { "pair-${it.hashCode()}" } ?: "add-machine") {
        AddMachine(
            state,
            onConnect = { input ->
                // Decided from where the name resolves (a LAN hostname needs the grant too); the lookup is bounded and off the main thread.
                scope.launch { if (deps.gate.needsRequest(AddMachineForm.normalizeHost(input.host))) { pending = input; permission.launch(LocalNetworkPolicy.PERMISSION) } else finish(input) }
            },
            onSendKey = onSendKey@{ input ->
                // The offer is read now, at the tap: what is sent and to whom is what was on screen when it was pressed.
                val offer = pairing?.takeIf { it.pairPort != null && it.sid != null } ?: return@onSendKey
                // The TCP connect to the desktop is a LAN connection like Connect's: ask for the local-network grant first, then send.
                scope.launch { if (deps.gate.needsRequest(AddMachineForm.normalizeHost(offer.host))) { pendingSend = input to offer; permission.launch(LocalNetworkPolicy.PERMISSION) } else beginSend(input, offer) }
            },
            onGenerateKey = { scope.launch { deps.createPhoneKey(); keyTick++ } },
            onCopyPublicKey = { line -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock public key", line)) },
            onCopyCommand = { command -> (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock authorize command", command)) },
            onShareCommand = { command -> ctx.startActivity(Intent.createChooser(commandShareIntent(command), "Share the command")) },
            onPastePairingLink = {
                when (val r = readPairingLink(ctx)) {
                    is PairingResult.Valid -> onPairing(r.link)
                    is PairingResult.Rejected -> onNotice(PairingCopy.invalid(r.reason))
                }
            },
            onOpenSettings = { ctx.startActivity(deps.gate.settingsIntent()) },
            onBack = onBack,
            onImportKey = { importing = true },
            // Not while a key is being set up for the watched machine, or under a pairing link: the host there is already decided.
            onFind = if (fixing == null && pairing == null) ({ finding = true }) else null,
            onScan = if (fixing == null) ({ scanning = true }) else null,
            picked = picked, onPickedApplied = { onPicked(null) },
            initial = fixing?.let { AddMachineInput(it.host, it.port.toString(), it.user, it.key, it.importedKeyId, it.session ?: "") } ?: pairing?.toInput() ?: AddMachineInput(),
            title = when { fixing != null && authorizeIntro -> if (fixing.key == KeyKind.Imported) "Authorize the key" else "Authorize this phone"; fixing != null -> "Set up the key"; else -> "Add a machine" },
            intro = when { fixing != null && authorizeIntro -> if (fixing.key == KeyKind.Imported) AUTHORIZE_INTRO_IMPORTED else AUTHORIZE_INTRO; fixing != null -> SET_UP_KEY_INTRO; else -> ADD_MACHINE_INTRO },
        )
    }
}
