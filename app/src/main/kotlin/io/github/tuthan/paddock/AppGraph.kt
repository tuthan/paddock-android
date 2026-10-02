package io.github.tuthan.paddock

import android.app.Application
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyBroker
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostprofile.FileHostProfileStore
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.ledger.FileLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.AndroidTriggers
import io.github.tuthan.paddock.lifecycle.ConnectionOwner
import io.github.tuthan.paddock.lifecycle.SessionFactory
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.ops.FileJournalStore
import io.github.tuthan.paddock.ops.FileSnippetStore
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.sha256Hex
import io.github.tuthan.paddock.settings.AppSettings
import io.github.tuthan.paddock.settings.FileAppSettingsStore
import io.github.tuthan.paddock.ssh.ConnectFailure
import io.github.tuthan.paddock.ssh.ImportedKeyStore
import io.github.tuthan.paddock.ssh.KeystoreSecretStore
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ssh.PhoneKey
import io.github.tuthan.paddock.ssh.SshAuth
import io.github.tuthan.paddock.ssh.SshlibConnector
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the machine list stands at start-up. */
enum class Boot { Loading, NoMachines, Ready }

/**
 * The app's object graph and the one host it watches. Process-wide so a rotation or a trip to another app does not rebuild
 * stores or lose the last home. The connection itself follows visibility: it is held only while an activity is started, and
 * [ConnectionOwner] closes it a few seconds after the last release.
 */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = Clock { System.currentTimeMillis() }
    private val files: File = app.filesDir

    val profiles = FileHostProfileStore(File(files, "host-profiles.json"))
    private val hostKeyStore = FileHostKeyStore(File(files, "host-keys.json"))
    val hostKeyPolicy = HostKeyPolicy(hostKeyStore) { System.currentTimeMillis() }
    val broker = HostKeyBroker()
    val phoneKey = PhoneKey()
    val importedKeys = ImportedKeyStore(KeystoreSecretStore(File(files, "secrets")))
    val gate = LocalNetworkGate(app)
    val ledger = Ledger(FileLedgerStore(File(files, "ledger.json"))) { System.currentTimeMillis() }
    private val settingsStore = FileAppSettingsStore(File(files, "settings.json"))
    /** What the phone asked of each terminal, written before it asks. Never deleted for an unknown outcome. */
    val journal = OperationJournal(FileJournalStore(File(files, "operations.json")), clock)
    private val snippetStore = FileSnippetStore(File(files, "snippets.json"))
    /** Manual input (Esc and Ctrl+C) lives as long as the process and is never restored: a killed app starts with it off. */
    val manualInput = ManualInputMode()

    private val connector = SshlibConnector(hostKeyPolicy, clock, gate)
    val owner = ConnectionOwner(scope, SessionFactory { profile -> connect(profile) }, clock)
    val triggers = AndroidTriggers(app, owner)

    private val relayScript: ByteArray = app.assets.open("paddock-relay.py").use { it.readBytes() }
    private val relayPin: String = app.assets.open("paddock-relay.sha256").use { it.readBytes().toString(Charsets.UTF_8).trim() }
    init { check(sha256Hex(relayScript) == relayPin) { "the bundled relay does not match its pin" } }
    private val controlScript: ByteArray = app.assets.open("paddock-control.py").use { it.readBytes() }
    private val controlPin: String = app.assets.open("paddock-control.sha256").use { it.readBytes().toString(Charsets.UTF_8).trim() }
    init { check(sha256Hex(controlScript) == controlPin) { "the bundled control helper does not match its pin" } }

    private val _boot = MutableStateFlow(Boot.Loading)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _profile = MutableStateFlow<HostProfile?>(null)
    val profile: StateFlow<HostProfile?> = _profile.asStateFlow()

    val hostUi = HostUiModel(scope)
    /** The open terminal session, kept through a rotation so turning the phone does not release control. */
    val terminals = io.github.tuthan.paddock.host.Retained<io.github.tuthan.paddock.terminal.TerminalSession>(scope) { it.close() }
    private val lock = Any()
    /** Lives while its machine is watched: paused when the app is hidden, resumed when it returns, replaced only for another machine. */
    @Volatile private var controller: HostSessionController? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _snippets = MutableStateFlow<List<String>>(emptyList())
    /** The user's own prompt snippets: on this phone only, never synced. */
    val snippets: StateFlow<List<String>> = _snippets.asStateFlow()

    fun start() {
        triggers.install()
        scope.launch {
            _settings.value = settingsStore.load()
            _snippets.value = runCatching { snippetStore.load() }.getOrDefault(emptyList())
            val all = runCatching { profiles.list() }.getOrDefault(emptyList())
            // The machine watched last time, not whichever sorts first.
            val watched = all.firstOrNull { it.id == _settings.value.watchedProfileId } ?: all.firstOrNull()
            _profile.value = watched
            _boot.value = if (watched == null) Boot.NoMachines else Boot.Ready
            // The connection is claimed while the app is visible and released when it is not (the owner closes it after its grace).
            triggers.foreground.collectLatest { visible -> if (visible) _profile.value?.let { watch(it) } else controller?.pause() }
        }
    }

    /** The one imported-key slot: importing again replaces the key, and profiles that use it keep working. */
    suspend fun importedKey(): io.github.tuthan.paddock.ssh.ImportedKeyInfo? = withContext(Dispatchers.IO) { importedKeys.info(IMPORTED_KEY_ID) }

    /** Checks and stores a private key. An encrypted key's passphrase is kept because connecting never asks for one. */
    suspend fun importKey(pem: String, passphrase: String): io.github.tuthan.paddock.ssh.ImportCheck {
        val chars = pem.toCharArray()
        // Parsing, decrypting and the Keystore calls are slow on some phones: never on the main thread.
        try { return withContext(Dispatchers.Default) { importedKeys.import(IMPORTED_KEY_ID, chars, passphrase.ifEmpty { null }, rememberPassphrase = true) } } finally { chars.fill('\u0000') }
    }

    /**
     * The user asked for this phone's key. An entry that exists but cannot be read can never sign, so it is replaced; the
     * new key has to be authorized on the host again. A connect never does this on its own.
     */
    suspend fun createPhoneKey() = withContext(Dispatchers.Default) {
        runCatching { phoneKey.getOrCreate() }.recoverCatching { e ->
            if (e !is ConnectFailure.KeyUnavailable || !phoneKey.exists()) throw e
            phoneKey.delete()
            phoneKey.getOrCreate()
        }
    }

    /** Saves [profile], makes it the watched machine and connects. */
    suspend fun addMachine(profile: HostProfile) {
        profiles.put(profile)
        _settings.value = _settings.value.copy(watchedProfileId = profile.id)
        settingsStore.save(_settings.value)
        _profile.value = profile
        _boot.value = Boot.Ready
        if (triggers.foreground.value) watch(profile)
    }

    /** Resumes the controller already watching [profile]; for any other profile, stops it and starts a new one. */
    private fun watch(profile: HostProfile) = synchronized(lock) {
        val c = controller
        if (c != null && c.profile == profile) { c.resume(); return@synchronized }
        c?.stop()
        val next = HostSessionController(scope, profile, { owner.acquire(profile) }, ledger, clock, triggers.foreground, relayScript, relayPin, sessionName = profile.session, controlScript = controlScript, controlSha256 = controlPin, journal = journal)
        controller = next
        hostUi.attach(next)
        next.start()
    }

    fun retry() { _profile.value?.let { owner.refresh(it.id) } }
    fun installRelay() { controller?.installRelay() }

    suspend fun setProtectSensitive(on: Boolean) {
        _settings.value = _settings.value.copy(protectSensitiveScreens = on)
        settingsStore.save(_settings.value)
    }

    /** Saved in the graph's scope, so leaving the screen straight after an edit cannot drop the write. */
    fun setSnippets(items: List<String>) {
        _snippets.value = items
        scope.launch { runCatching { snippetStore.save(items) } }
    }

    fun setKeepPromptText(on: Boolean) {
        _settings.value = _settings.value.copy(keepPromptText = on)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    /** Replaces the pin with the key the host presented, after the user chose to; then reconnects. */
    suspend fun replaceKey(profile: HostProfile) {
        val changed = broker.changed.value[profile.id] ?: return
        hostKeyPolicy.replaceChanged(profile.id, changed.endpoint, changed.presented)
        broker.clearChanged(profile.id)
        owner.refresh(profile.id)
    }

    private suspend fun connect(profile: HostProfile) = run {
        val target = profile.toTarget()
        val auth: SshAuth = when (profile.key) {
            KeyKind.Phone -> SshAuth.Phone(phoneKey.privateKey(), phoneKey.info().publicKey)
            KeyKind.Imported -> importedKeys.load(profile.importedKeyId!!) ?: throw ConnectFailure.BadKey("the imported key is missing")
        }
        try {
            connector.connect(target, auth) { presented -> broker.askFirstTrust(profile.id, target.endpoint, presented) }
        } catch (e: ConnectFailure.HostKeyChanged) {
            broker.recordChanged(profile.id, target.endpoint, e)
            throw e
        }
    }
}

const val IMPORTED_KEY_ID = "imported"
