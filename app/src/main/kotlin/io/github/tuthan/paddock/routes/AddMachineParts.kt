package io.github.tuthan.paddock.routes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.discovery.FinderController
import io.github.tuthan.paddock.discovery.FinderPhase
import io.github.tuthan.paddock.discovery.FinderText
import io.github.tuthan.paddock.hostprofile.AddMachineInput
import io.github.tuthan.paddock.hostprofile.PairingCopy
import io.github.tuthan.paddock.hostprofile.PairingLink
import io.github.tuthan.paddock.hostprofile.PairingLinks
import io.github.tuthan.paddock.hostprofile.PairingResult
import io.github.tuthan.paddock.net.LocalNetworkPolicy
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ui.components.SecureWindow
import io.github.tuthan.paddock.ui.screens.FindOnNetwork
import io.github.tuthan.paddock.ui.screens.ImportKey
import io.github.tuthan.paddock.ui.screens.PickedKeyFile
import io.github.tuthan.paddock.ui.screens.ScanLink
import io.github.tuthan.paddock.ui.screens.Welcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_KEY_FILE_BYTES = 64 * 1024

/** The user asked for this read. Only a token that starts with the pairing scheme is looked at, and the text is never echoed. */
internal fun readPairingLink(ctx: Context): PairingResult {
    val text = runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
    return PairingLinks.fromText(text)
}

@Composable
internal fun WelcomeRoute(onEnterAddress: () -> Unit, onFind: () -> Unit, onScan: () -> Unit, onPairing: (PairingLink) -> Unit) {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    Welcome(
        onEnterAddress = onEnterAddress,
        onFind = onFind,
        onScan = onScan,
        onPasteLink = {
            when (val r = readPairingLink(ctx)) {
                is PairingResult.Valid -> { notice = null; onPairing(r.link) }
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        notice = notice,
    )
}

/**
 * The scanner page. The camera is asked for here, when the scanner opens, and never before; a refusal leaves Allow the camera, Open
 * settings and Paste a pairing link. A code that is not a pairing link says so and the scan goes on (the camera is not restarted, and the same
 * code left in view is not announced again); a good one goes to [onLink]. "Paste a pairing link" reads the clipboard here, the same as the
 * paste buttons elsewhere, so the camera is never the only way.
 */
@Composable
internal fun ScanRoute(gate: LocalNetworkGate, onLink: (PairingLink) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val resumes = rememberResumes()
    val granted = remember(tick, resumes) { ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; permission.launch(android.Manifest.permission.CAMERA) } }
    ScanLink(
        cameraGranted = granted,
        onRequestCamera = { permission.launch(android.Manifest.permission.CAMERA) },
        onOpenSettings = { ctx.startActivity(gate.settingsIntent()) },
        onPayload = { text ->
            when (val r = PairingLinks.fromText(text)) {
                is PairingResult.Valid -> onLink(r.link)
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        onPaste = {
            when (val r = readPairingLink(ctx)) {
                is PairingResult.Valid -> onLink(r.link)
                is PairingResult.Rejected -> notice = PairingCopy.invalid(r.reason)
            }
        },
        onBack = onBack, notice = notice,
    )
}

/** The finder page over [FinderController]. Nothing runs until Start; leaving the page stops whatever runs and releases the network. */
@Composable
internal fun FindRoute(finder: FinderController, gate: LocalNetworkGate, onPick: (AddMachineInput) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by finder.state.collectAsState()
    var port by rememberSaveable { mutableStateOf("") }
    var denied by rememberSaveable { mutableStateOf(false) }
    val typed = FinderText.port(port).port
    val resumes = rememberResumes()
    // Said again when the port changes (the sentence names the ports), and when the grant is turned on in the system settings and the user returns.
    LaunchedEffect(port) { finder.refresh(typed) }
    LaunchedEffect(resumes) { if (finder.state.value.phase == FinderPhase.Idle) { if (denied && !gate.lanAccessMissing()) denied = false; finder.refresh(typed) } }
    DisposableEffect(Unit) { onDispose { finder.close() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        finder.refresh(typed)
    }
    FindOnNetwork(
        state, port, { port = it.take(5) },
        onStart = { finder.start(typed) },
        onCancel = { scope.launch { finder.cancel() } },
        onAllow = { permission.launch(LocalNetworkPolicy.PERMISSION) },
        onPick = { row -> onPick(AddMachineInput(host = row.address, port = row.port.toString(), name = row.name)) },
        onBack = onBack,
        accessDenied = denied,
        onOpenSettings = { ctx.startActivity(gate.settingsIntent()) },
    )
}

@Composable
internal fun ImportKeyRoute(prefs: SettingsController, importKey: suspend (pem: String, passphrase: String) -> ImportCheck, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by prefs.settings.collectAsState()
    SecureWindow(settings.protectSensitiveScreens)
    // Held in plain remember state on purpose: key text must not reach saved instance state.
    var picked by remember { mutableStateOf<PickedKeyFile?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportCheck?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            pickError = null
            val file = withContext(Dispatchers.IO) { readKeyFile(ctx, uri) }
            if (file == null) pickError = "That file could not be read, or it is too large to be a private key." else picked = file
            result = null
        }
    }
    ImportKey(
        picked, pickError, busy, result,
        onChooseFile = { chooser.launch(arrayOf("*/*")) },
        onClearFile = { picked = null; result = null },
        onImport = { pem, pass ->
            busy = true
            val pasted = picked == null
            scope.launch {
                val check = importKey(pem, pass)
                busy = false
                result = check
                if (check is ImportCheck.Ready) {
                    // A pasted key is still on the clipboard, where any app could read it: take it off.
                    // On the main thread whatever resumed us: before API 28 the clipboard service needs a Looper to be created.
                    if (pasted) withContext(Dispatchers.Main) { clearClipboardIfKey(ctx) }
                    onDone()
                }
            }
        },
        onBack = onBack,
    )
}

/** Reads at most [MAX_KEY_FILE_BYTES]; a larger file is refused rather than truncated. */
private fun readKeyFile(ctx: Context, uri: android.net.Uri): PickedKeyFile? = runCatching {
    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "key file"
    // InputStream.readNBytes is API 33 and minSdk is 26: a bounded loop, reading one byte past the limit to detect a larger file.
    val buf = ByteArray(MAX_KEY_FILE_BYTES + 1)
    try {
        val n = ctx.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            total
        } ?: return null
        if (n > MAX_KEY_FILE_BYTES) null else PickedKeyFile(name, String(buf, 0, n, Charsets.UTF_8))
    } finally {
        buf.fill(0)
    }
}.getOrNull()

/** Clears the clipboard when it holds a private key (the one just pasted). Anything else on it is left alone. */
private fun clearClipboardIfKey(ctx: Context) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val text = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull() ?: return
    if ("PRIVATE KEY-----" !in text) return
    if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
}
