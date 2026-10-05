package io.github.tuthan.paddock

import android.app.Application
import io.github.tuthan.paddock.alerts.AlertArrival
import io.github.tuthan.paddock.alerts.AlertContent
import io.github.tuthan.paddock.alerts.LocalAlertRules
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.notify.AndroidAlertNotifier
import io.github.tuthan.paddock.notify.NotificationAccessReader
import io.github.tuthan.paddock.reconcile.Observation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import io.github.tuthan.paddock.alerts.AlertEvent
import io.github.tuthan.paddock.alerts.AlertInbox
import io.github.tuthan.paddock.alerts.AlertOutcome
import io.github.tuthan.paddock.alerts.AlertReads
import io.github.tuthan.paddock.alerts.AlertResolver
import io.github.tuthan.paddock.alerts.FilePushStore
import io.github.tuthan.paddock.alerts.MachineOutcome
import io.github.tuthan.paddock.alerts.PushRegistry
import io.github.tuthan.paddock.alerts.Unobserved
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.notify.UnifiedPushConnector
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostkey.ChangedKey
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.GateContext
import io.github.tuthan.paddock.billing.GateNotice
import io.github.tuthan.paddock.billing.GateDecision
import io.github.tuthan.paddock.billing.ProCapability
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.billing.ProSession
import io.github.tuthan.paddock.billing.ProView
import io.github.tuthan.paddock.billing.Entitlements
import io.github.tuthan.paddock.billing.FileEntitlementStore
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
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.live.MonitoredHost
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tuthan.paddock.pairing.pendingOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** Where the machine list stands at start-up. */
enum class Boot { Loading, NoMachines, Ready }

/**
 * The app's object graph and the one host it watches. Process-wide so a rotation or a trip to another app does not rebuild
 * stores or lose the last home. The connection itself follows visibility: it is held only while an activity is started, and
 * [ConnectionOwner] closes it a few seconds after the last release.
 */
/** How long after a Wake tap the app keeps watching the connection to fill in "the machine answered" and "herdr reachable". */
private const val WAKE_FOLLOW_MILLIS = 180_000L

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
    val lanPaths = io.github.tuthan.paddock.net.AndroidLanPaths(app)
    val sockets = io.github.tuthan.paddock.net.AndroidSockets(lanPaths)
    /** Find on this network (Phase 14): nothing runs until the page's Start. The probe is pinned to the network it scans, and mDNS is browsed beside it. */
    val finder = io.github.tuthan.paddock.discovery.FinderController(
        scope, lanPaths, sockets, grantMissing = { gate.lanAccessMissing() }, bindProbeTo = { sockets.tcpPathId = it },
        mdns = { io.github.tuthan.paddock.net.NsdBrowser(app).browse() },
    )
    /** Sending this phone's public key to a desktop's `pair` popup (Phase 14). The request is on disk before its first byte, so a restart reopens it within its window. */
    val pairingCoordinator = io.github.tuthan.paddock.pairing.PairingCoordinator(
        io.github.tuthan.paddock.pairing.FilePendingPairingStore(File(app.filesDir, "pending-pairing.json")),
        io.github.tuthan.paddock.pairing.PairingClient(sockets), clock, scope,
    )
    // After a restart: a request still inside its window is reopened, asking the desktop first whether the first send ever arrived.
    init { scope.launch { runCatching { pairingCoordinator.resume() } } }
    val ledger = Ledger(FileLedgerStore(File(files, "ledger.json"))) { System.currentTimeMillis() }
    private val settingsStore = FileAppSettingsStore(File(files, "settings.json"))
    /** Pro: the last verified answer from the store account, kept in a plain file; the foss build has no store and every capability unlocked (Phase 13). */
    val entitlements = Entitlements(Distribution.billing(app), FileEntitlementStore(File(files, FileEntitlementStore.FILE_NAME)), clock, Distribution.UNLOCKED)
    /** What the phone asked of each terminal, written before it asks. Never deleted for an unknown outcome. */
    val journal = OperationJournal(FileJournalStore(File(files, "operations.json")), clock)
    /** Start-agent sagas with every id herdr returned (Phase 09); no prompt text is ever written here. */
    private val sagaStore = io.github.tuthan.paddock.ops.FileSagaStore(File(files, "sagas.json"))
    private val snippetStore = FileSnippetStore(File(files, "snippets.json"))
    /** Manual input (Esc and Ctrl+C) lives as long as the process and is never restored: a killed app starts with it off. */
    val manualInput = ManualInputMode()
    /** Where a tapped alert enters: parsed, deduped, then resolved against a fresh read by [resolveAlert]. */
    val alerts = AlertInbox(clock)

    /** Pairing links from a tap or a paste: they only fill in Add machine, never connect or trust. */
    val pairing = io.github.tuthan.paddock.hostprofile.PairingInbox()
    /** Notifications Paddock raises itself, only while it is open but not in front (the watching mode of Phase 07). */
    private val notifier = AndroidAlertNotifier(app)
    val notificationAccess = NotificationAccessReader(app)
    private val alertRules = LocalAlertRules()
    /** Connector mode: registrations with a UnifiedPush distributor, one per machine. A push raises a generic notification through [notifier]. */
    private val pushRegistry = PushRegistry(FilePushStore(File(files, "push.json")), clock = clock)
    val push = UnifiedPushConnector(
        app, pushRegistry, scope, clock,
        machineName = { id -> profiles.get(id)?.name },
        show = { notifier.show(it) },
        herdInFront = { triggers.interactive.value },
        hideOnLockScreen = { runCatching { settingsStore.load().hidePromptOnLockScreen }.getOrDefault(true) },
    )
    @Volatile private var alertWatcher: Job? = null

    /** What the home-screen widgets draw (Phase 10): the last read of the watched machine, written by the open app and by [refreshWidgetCache]. */
    private val widgetStore = io.github.tuthan.paddock.widget.WidgetCaches.store(app)
    private val widgetRefresher = io.github.tuthan.paddock.widget.WidgetRefresher(widgetStore, clock) { host, session ->
        ledger.installedEpoch(host, session)?.let { ledger.seenLookup(host, session, it) } ?: io.github.tuthan.paddock.attention.SeenLookup { null }
    }
    @Volatile private var widgetWatcher: Job? = null
    @Volatile private var wakeWatcher: Job? = null
    @Volatile private var wakeFollow: Job? = null
    @Volatile private var pairingWatcher: Job? = null
    private val _wakeFacts = MutableStateFlow<io.github.tuthan.paddock.wake.WakeFacts?>(null)
    /** What the last Wake tap on the watched machine came to; null before any tap and after switching machines. */
    val wakeFacts: StateFlow<io.github.tuthan.paddock.wake.WakeFacts?> = _wakeFacts.asStateFlow()
    @Volatile private var lastWidgetCache: io.github.tuthan.paddock.widget.WidgetCache? = null

    private val connector = SshlibConnector(hostKeyPolicy, clock, gate)
    val owner = ConnectionOwner(scope, SessionFactory { profile -> connect(profile) }, clock)
    val triggers = AndroidTriggers(app, owner)

    private val relayScript: ByteArray = app.assets.open("paddock-relay.py").use { it.readBytes() }
    private val relayPin: String = app.assets.open("paddock-relay.sha256").use { it.readBytes().toString(Charsets.UTF_8).trim() }
    init { check(sha256Hex(relayScript) == relayPin) { "the bundled relay does not match its pin" } }
    private val controlScript: ByteArray = app.assets.open("paddock-control.py").use { it.readBytes() }
    private val controlPin: String = app.assets.open("paddock-control.sha256").use { it.readBytes().toString(Charsets.UTF_8).trim() }
    init { check(sha256Hex(controlScript) == controlPin) { "the bundled control helper does not match its pin" } }

    /** The alert relay's script, unit and example configuration, each checked against its pin (host/SOURCE.json) when the graph is built. */
    private fun pinnedAsset(name: String, pin: String): ByteArray = app.assets.open(name).use { it.readBytes() }.also {
        val want = app.assets.open(pin).use { p -> p.readBytes().toString(Charsets.UTF_8).trim() }
        check(sha256Hex(it) == want) { "the bundled $name does not match its pin" }
    }
    private val alertScript: ByteArray = pinnedAsset("paddock-alert-relay.py", "paddock-alert-relay.py.sha256")
    val alertScriptSha256: String = sha256Hex(alertScript)
    val alertUnit: String = pinnedAsset("paddock-alert-relay.service", "paddock-alert-relay.service.sha256").toString(Charsets.UTF_8)
    val alertConfigExample: String = pinnedAsset("alert-relay.example.toml", "alert-relay.example.toml.sha256").toString(Charsets.UTF_8)

    /** The permission-request writer and the Claude Code hook (Phase 08), each checked against its pin when the graph is built. */
    private val decideScript: ByteArray = pinnedAsset("paddock-decide.py", "paddock-decide.py.sha256")
    val decideScriptSha256: String = sha256Hex(decideScript)
    private val hookScript: ByteArray = pinnedAsset("paddock-claude-permission-hook.py", "paddock-claude-permission-hook.py.sha256")
    val hookScriptSha256: String = sha256Hex(hookScript)

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

    /** Everything Pro does between the screens and the store (Phase 13): one store action at a time, nothing a store call throws reaches the process, prices only when shown. */
    private val proSession = ProSession(entitlements, scope, clock, Distribution.UNLOCKED)
    /** What Settings and the Pro gate show about Pro: the saved state first, then whatever the store said. */
    val pro: StateFlow<ProView> = proSession.view

    private val _gateRequest = MutableStateFlow<String?>(null)
    /** The Pro capability the user just chose without having Pro; the gate sheet shows while it is set and the user is idle. */
    val gateRequest: StateFlow<String?> = _gateRequest.asStateFlow()

    private val gateNoticeHolder = GateNotice(scope)
    /** Why the last tap on a Pro control did nothing (a deferral, see [ProGate.deferNotice]); `PaddockRoot` shows it as a notice, and it goes by itself after [GateNotice.SHOW_MILLIS]. */
    val gateNotice: StateFlow<String?> = gateNoticeHolder.text

    private val _snippets = MutableStateFlow<List<String>>(emptyList())
    /** The user's own prompt snippets: on this phone only, never synced. */
    val snippets: StateFlow<List<String>> = _snippets.asStateFlow()

    fun start() {
        triggers.install()
        // The herd in front of the user is the alert: notifications raised while it was away are cleared when it returns.
        scope.launch { triggers.interactive.collect { if (it) runCatching { notifier.cancelAll() } } }
        proSession.start(triggers.foreground)
        // Whatever an earlier purchase said is not about this opening of the gate sheet; the store's prices are asked for the first time it is shown.
        scope.launch { _gateRequest.collect { request -> proSession.gateChanged(opened = request != null) } }
        // Widgets are a Pro capability: when the answer to "does this phone hold Pro" changes they are redrawn. This is only the trigger, by the same rule
        // (Entitlements.hasPro): what is drawn is decided by PaddockWidgets.locked from the saved file, which is written before this state moves. With no widget placed the redraw does nothing.
        scope.launch { pro.map { entitlements.hasPro(it.state) }.distinctUntilChanged().collect { runCatching { io.github.tuthan.paddock.widget.PaddockWidgets.updateAll(app) } } }
        scope.launch {
            _settings.value = settingsStore.load()
            _snippets.value = runCatching { snippetStore.load() }.getOrDefault(emptyList())
            val all = runCatching { profiles.list() }.getOrDefault(emptyList())
            // The machine watched last time, not whichever sorts first.
            val watched = all.firstOrNull { it.id == _settings.value.watchedProfileId } ?: all.firstOrNull()
            _profile.value = watched
            _boot.value = if (watched == null) Boot.NoMachines else Boot.Ready
            runCatching { push.resume() }
            // The connection is claimed while the app is visible and released when it is not (the owner closes it after its grace).
            triggers.foreground.collectLatest { visible -> if (visible) _profile.value?.let { watch(it) } else controller?.pause() }
        }
    }

    /** Asks the store what the account owns (app start, a store change, a return to the foreground); waits behind a store action in flight. */
    suspend fun verifyPro() = proSession.verify()

    fun restorePurchases() = proSession.restore()

    /** The store's prices, asked for the first time Settings' Pro card or the gate sheet is shown (a no-op after that and in a build without a store). */
    fun loadPrices() = proSession.loadPrices()

    fun buyTip(productId: String) = proSession.buyTip(productId)

    fun buyPro() = proSession.buyPro { _gateRequest.value = null }

    /**
     * The user chose a capability. Returns whether it may run now; when it is a Pro one without Pro and the user is idle, the gate
     * sheet is asked for instead. [pendingAnswerOnScreen] is the one fact only the screen knows: the decision sheet or guarded answers is up.
     */
    fun requestCapability(capabilityId: String, pendingAnswerOnScreen: Boolean): Boolean {
        val context = gateContext(pendingAnswerOnScreen)
        val decision = ProGate.decide(capabilityId, entitlements.hasPro(pro.value.state), context)
        // A deferral shows no sheet, but a tap that does nothing and says nothing reads as a broken app: the notice says why, in one sentence.
        gateNoticeHolder.decided(decision, context)
        if (decision == GateDecision.SHOW_GATE) _gateRequest.value = capabilityId
        return decision == GateDecision.PROCEED
    }

    fun dismissGate() { _gateRequest.value = null }

    fun dismissGateNotice() = gateNoticeHolder.dismiss()

    /** Whether a control for [capability] carries its Pro label and does not run: gated, and neither held nor unlocked by the build. A screen passes the [pro] value it collected, so it follows a purchase. */
    fun locked(capability: ProCapability, view: ProView = pro.value): Boolean = ProGate.locked(capability.id, entitlements.hasPro(view.state))

    /** What the user is in the middle of; the rule, including which journal rows still count as running, is [ProGate.context]. */
    fun gateContext(pendingAnswerOnScreen: Boolean): GateContext =
        ProGate.context(pendingAnswerOnScreen, manualInput.current.value != null, journal.records.value, clock.nowMillis())

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

    /** Makes [id] the watched machine, as tapping an alert for it needs. Null when this phone has no such machine. */
    suspend fun watchProfile(id: String): HostProfile? {
        val profile = profiles.get(id) ?: return null
        if (_profile.value?.id != profile.id) {
            _settings.value = _settings.value.copy(watchedProfileId = profile.id)
            runCatching { settingsStore.save(_settings.value) }
            _profile.value = profile
            if (triggers.foreground.value) watch(profile)
        }
        return profile
    }

    /**
     * The arrival rule for an alert (Phase 07): switch to the machine it names, wait for a read this phone made after the alert
     * arrived on a live connection, and resolve the target against that read. A null outcome means no such read came in time.
     */
    suspend fun resolveAlert(event: AlertEvent.Arrived): AlertResolution {
        val hint = event.hint
        val fresh = freshHerd(hint.target.host.value, event.arrivedAtMillis)
            ?: return AlertResolution(AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine), "this phone", null, null)
        val snapshot = fresh.snapshot ?: return AlertResolution(null, fresh.profile.name, null, fresh.host)
        val outcome = AlertResolver.resolve(hint, fresh.profile.hostId, fresh.host!!.sessionName, snapshot)
        val terminal = when (outcome) { is AlertOutcome.Current -> outcome.terminalId; is AlertOutcome.Changed -> outcome.terminalId; is AlertOutcome.NoLongerObserved -> null }
        return AlertResolution(outcome, fresh.profile.name, terminal?.let { id -> fresh.host.home.value?.rows?.firstOrNull { it.key.target.terminalId == id } }, fresh.host)
    }

    /** What a push that named no terminal found: the same arrival rule, answered with counts from the fresh read. A null outcome means no such read came in time. */
    suspend fun resolveMachine(event: AlertEvent.MachineWoke): MachineResolution {
        val fresh = freshHerd(event.hint.host.value, event.arrivedAtMillis) ?: return MachineResolution(null, "this phone", unknownMachine = true)
        return MachineResolution(fresh.snapshot?.let { AlertResolver.resolveMachine(it) }, fresh.profile.name, unknownMachine = false)
    }

    private class FreshHerd(val profile: HostProfile, val host: MonitoredHost?, val snapshot: Snapshot?)

    /**
     * The arrival rule: switch to [profileId]'s machine, wait for a read the phone made after [arrivedAtMillis] on a live
     * connection. Null when the phone has no such machine; a null snapshot when no such read came within the arrival timeout.
     */
    private suspend fun freshHerd(profileId: String, arrivedAtMillis: Long): FreshHerd? {
        val profile = watchProfile(profileId) ?: return null
        val deadline = clock.nowMillis() + AlertArrival.TIMEOUT_MILLIS
        val host = withTimeoutOrNull(AlertArrival.TIMEOUT_MILLIS) {
            hostUi.view.map { (it.phase as? HostPhase.Monitoring)?.host }.first { it != null && it.profile.id == profile.id }
        } ?: return FreshHerd(profile, null, null)
        host.refresh()
        val installed = AlertArrival.freshRead(AlertReads(host.sessionName, host.freshness, host.reconciler.installed), arrivedAtMillis, (deadline - clock.nowMillis()).coerceAtLeast(1_000))
        return FreshHerd(profile, host, installed?.snapshot)
    }

    /** The saved sagas, for Activity while no host is being monitored (a restart, a lost connection). */
    fun savedSagas(): List<io.github.tuthan.paddock.ops.SagaRecord> = runCatching { sagaStore.load().records }.getOrDefault(emptyList())

    /** Resumes the controller already watching [profile]; for any other profile, stops it and starts a new one. */
    private fun watch(profile: HostProfile) = synchronized(lock) {
        val c = controller
        // What was read about waking the machine changes on every bring-up and is no reason to reconnect.
        if (c != null && c.profile.copy(wake = null) == profile.copy(wake = null)) { c.resume(); return@synchronized }
        c?.stop()
        if (c?.profile?.id != profile.id) _wakeFacts.value = null
        val next = HostSessionController(scope, profile, { owner.acquire(profile) }, ledger, clock, triggers.foreground, relayScript, relayPin, sessionName = profile.session, controlScript = controlScript, controlSha256 = controlPin, journal = journal, alertScript = alertScript, alertSha256 = alertScriptSha256,
            decideScript = decideScript, decideSha256 = decideScriptSha256, hookScript = hookScript, hookSha256 = hookScriptSha256, sagaStore = sagaStore,
            wakeCapture = { session -> io.github.tuthan.paddock.wake.WakeCapture.capture(session, clock, profiles.get(profile.id)?.wake?.relay) })
        controller = next
        hostUi.attach(next)
        next.start()
        alertWatcher?.cancel()
        alertWatcher = scope.launch { next.phase.collectLatest { p -> if (p is HostPhase.Monitoring) raiseAlerts(p.host) } }
        widgetWatcher?.cancel()
        widgetWatcher = scope.launch { next.phase.collectLatest { p -> if (p is HostPhase.Monitoring) trackWidgetCache(p.host) } }
        wakeWatcher?.cancel()
        wakeWatcher = scope.launch { next.wake.filterNotNull().collect { saveWake(next.profile, it) } }
        // A live connection ends any pairing request: the key is no longer what stands between this phone and the machine.
        pairingWatcher?.cancel()
        // Only this machine's: another machine coming up (a reconnect after a Wi-Fi blip) says nothing about a request made for a different desktop.
        pairingWatcher = scope.launch {
            next.phase.collectLatest { p ->
                if (p !is HostPhase.Monitoring) return@collectLatest
                val pending = pairingCoordinator.state.value.pendingOrNull ?: return@collectLatest
                if (pending.host.trim().equals(p.host.profile.host.trim(), ignoreCase = true)) pairingCoordinator.clear()
            }
        }
    }

    /**
     * Keeps what a bring-up read about waking the machine, with the relay the user saved. Skipped when the stored profile is no longer
     * the machine that was read (the user edited its address meanwhile).
     */
    private suspend fun saveWake(read: HostProfile, target: io.github.tuthan.paddock.wake.WakeTarget) {
        val stored = profiles.get(read.id) ?: return
        if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port) return
        val merged = stored.copy(wake = target.copy(relay = stored.wake?.relay))
        if (merged == stored) return
        profiles.put(merged)
        if (_profile.value?.id == merged.id) _profile.value = merged
    }

    /**
     * Sends this phone's own public key to the desktop that showed the pairing code. Only the phone key is ever sent: an imported key
     * can be any type and its public line is not kept. False when the phone has no key yet.
     */
    suspend fun sendPairingKey(host: String, port: Int, sid: String): Boolean {
        // Off the main thread (a Keystore read), and never creating a key as a side effect: the Send offer is drawn under this phone's key, so a missing one is the caller's to say.
        val line = withContext(Dispatchers.Default) { if (phoneKey.exists()) runCatching { phoneKey.publicLine("paddock@phone") }.getOrNull() else null } ?: return false
        val key = io.github.tuthan.paddock.ssh.AuthorizedKey.parse(line) ?: return false
        pairingCoordinator.start(host, port, sid, line, key.fingerprint)
        return true
    }

    /** True when this phone can send a wake packet for [profile]: its hardware address is read, and a LAN path or a saved relay can carry it. */
    fun wakeReady(profile: HostProfile): Boolean {
        val target = profile.wake?.takeIf { it.available } ?: return false
        return target.relay != null || io.github.tuthan.paddock.wake.WakePaths.candidates(lanPaths.paths(), profile.host, target.iface).any { !it.tunnel && it.hasIpv4 }
    }

    /** One Wake tap on the watched machine: send, show the three facts, ask for a reconnect at once. A second tap inside the guard does nothing. */
    fun wake() { scope.launch { wakeNow() } }

    private suspend fun wakeNow() {
        val profile = _profile.value ?: return
        val target = (profiles.get(profile.id) ?: profile).wake ?: return
        if (_wakeFacts.value?.canWakeAgain(clock.nowMillis()) == false) return
        val result = withContext(Dispatchers.IO) {
            val candidates = io.github.tuthan.paddock.wake.WakePaths.candidates(lanPaths.paths(), profile.host, target.iface)
            io.github.tuthan.paddock.wake.WakeSender(sockets, permissionMissing = { gate.lanAccessMissing() }).send(target, candidates, target.relay)
        }
        val facts = io.github.tuthan.paddock.wake.WakeFacts(clock.nowMillis(), result)
        _wakeFacts.value = facts
        if (facts.transmitted) { retry(); followWake(facts) }
    }

    /** Fills in the two later facts as the connection comes up, for at most three minutes after the tap. */
    private fun followWake(first: io.github.tuthan.paddock.wake.WakeFacts) {
        wakeFollow?.cancel()
        wakeFollow = scope.launch {
            withTimeoutOrNull(WAKE_FOLLOW_MILLIS) {
                hostUi.view.first { v ->
                    val now = clock.nowMillis()
                    val p = v.phase
                    // Any phase past Connecting means the SSH connect itself answered; only a live read means herdr did.
                    val answered = p is HostPhase.InstallingRelay || p is HostPhase.NeedsRelayInstall || p is HostPhase.Problem || p is HostPhase.Monitoring
                    val reachable = p is HostPhase.Monitoring && v.freshness == io.github.tuthan.paddock.reconcile.Freshness.Live
                    var done = false
                    _wakeFacts.update { f ->
                        if (f == null || f.sentAtMillis != first.sentAtMillis) { done = true; f }
                        else f.copy(
                            answeredAtMillis = f.answeredAtMillis ?: now.takeIf { answered },
                            reachableAtMillis = f.reachableAtMillis ?: now.takeIf { reachable },
                        ).also { done = it.answeredAtMillis != null && it.reachableAtMillis != null }
                    }
                    done
                }
            }
        }
    }

    /**
     * Saves, replaces or clears the relay for waking [profileId] from away. Before any reading the profile gets a "not read yet" target
     * that carries only the relay; the next bring-up fills in the rest and keeps it.
     */
    suspend fun setWakeRelay(profileId: String, relay: io.github.tuthan.paddock.wake.WakeRelay?) {
        val stored = profiles.get(profileId) ?: return
        val base = stored.wake ?: io.github.tuthan.paddock.wake.WakeTarget.unavailable("Not read yet: connect once so Paddock can read the machine's network interface.", clock.nowMillis())
        val merged = stored.copy(wake = base.copy(relay = relay))
        profiles.put(merged)
        if (_profile.value?.id == merged.id) _profile.value = merged
    }

    /**
     * Watching mode: a terminal the open app saw become Blocked or Done raises a notification, unless alerts are off or the
     * herd is in front. The reconciler only reports changes between two reads of one epoch, so a reconnect invents none.
     */
    private suspend fun raiseAlerts(host: MonitoredHost) {
        host.reconciler.observations.collect { o ->
            if (o !is Observation.StatusChanged) return@collect
            val settings = _settings.value
            val agent = host.reconciler.installed.value?.snapshot?.agents?.firstOrNull { it.terminalId == o.key.target.terminalId }
            val alert = alertRules.alertFor(
                o.to, agent, o.key.target, host.profile.name, clock.nowMillis() / 1000, settings.localAlerts, triggers.interactive.value, agent?.let { AttentionModel.title(it) },
            ) ?: return@collect
            runCatching { notifier.show(AlertContent.of(alert, settings.hidePromptOnLockScreen)) }
        }
    }

    /**
     * Keeps the widgets' cache as fresh as the open app's own herd: written when what the widgets show changes, and at most once a minute
     * otherwise (so "as of" moves while nothing else does). The cache is only ever a copy of the herd list the user is looking at.
     */
    private suspend fun trackWidgetCache(host: MonitoredHost) {
        host.home.collect { home ->
            if (home == null) return@collect
            val readAt = host.reconciler.installed.value?.readAtMillis ?: clock.nowMillis()
            val next = io.github.tuthan.paddock.widget.WidgetCacheBuilder.from(home, host.profile.id, host.profile.name, host.sessionName, readAt)
            val prev = lastWidgetCache
            if (prev != null && prev.copy(readAtMillis = 0) == next.copy(readAtMillis = 0) && next.readAtMillis - prev.readAtMillis < WIDGET_TOUCH_MILLIS) return@collect
            lastWidgetCache = next
            if (runCatching { widgetStore.save(next) }.isSuccess) runCatching { io.github.tuthan.paddock.widget.PaddockWidgets.updateAll(app) }
        }
    }

    /**
     * The widgets' background read, run by the periodic job: one short SSH read of the machine the cache already knows, on a connection this
     * claims and releases. It does nothing while the app is in front (its own read keeps the cache), nothing for a machine the phone has not
     * read before (so it can never raise a first-trust prompt for a key nobody has seen), and nothing with a cache that is wrong when the
     * read fails: the old cache stays and its time says so. Returns a line for the log.
     */
    suspend fun refreshWidgetCache(): String {
        // A locked widget draws nothing from the cache (vault M8), so a background SSH read for it would cost battery and network for no one.
        // The widgets' own answer (the saved file, by Entitlements.hasPro with the flavor's constants), not the view: a job that starts a cold process runs before start() has loaded the view, and a Pro holder's widget must not go stale for that.
        if (io.github.tuthan.paddock.widget.PaddockWidgets.locked(app)) return "skipped: widgets are Pro and this phone does not hold it"
        if (triggers.foreground.value) return "skipped: the app is in front and keeps the cache itself"
        val all = runCatching { profiles.list() }.getOrDefault(emptyList())
        val watched = runCatching { settingsStore.load().watchedProfileId }.getOrNull()
        val profile = all.firstOrNull { it.id == watched } ?: all.firstOrNull() ?: return "skipped: no machine"
        val cache = widgetStore.load()?.takeIf { it.hostId == profile.id } ?: return "skipped: nothing has been read from ${profile.name} yet"
        val lease = owner.acquire(profile)
        try {
            val state = withTimeoutOrNull(WIDGET_CONNECT_MILLIS) { lease.state.first { it is io.github.tuthan.paddock.lifecycle.Connection.Connected || it is io.github.tuthan.paddock.lifecycle.Connection.Failed } }
            val connected = state as? io.github.tuthan.paddock.lifecycle.Connection.Connected ?: return "skipped: not connected (${state ?: "timed out"})"
            return when (val out = widgetRefresher.refresh(profile, connected.session, cache.session)) {
                is io.github.tuthan.paddock.widget.WidgetRefresher.Outcome.Updated -> { io.github.tuthan.paddock.widget.PaddockWidgets.updateAll(app); "updated: ${out.cache.counts}" }
                is io.github.tuthan.paddock.widget.WidgetRefresher.Outcome.Unchanged -> "unchanged: ${out.reason}"
            }
        } finally { lease.release() }
    }

    /** "Try again": a setup problem on a healthy connection runs the setup again; anything else refreshes the connection. */
    fun retry() {
        if (controller?.retrySetup() == true) return
        _profile.value?.let { owner.refresh(it.id) }
    }
    fun installRelay() { controller?.installRelay() }

    /** Saved in the graph's scope: the herd redraws at once and leaving Settings straight after cannot drop the write. */
    fun setAgentGlyphs(on: Boolean) {
        _settings.value = _settings.value.copy(agentGlyphs = on)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    suspend fun setProtectSensitive(on: Boolean) {
        _settings.value = _settings.value.copy(protectSensitiveScreens = on)
        settingsStore.save(_settings.value)
    }

    /** Saved in the graph's scope, so leaving the screen straight after an edit cannot drop the write. */
    fun setSnippets(items: List<String>) {
        _snippets.value = items
        scope.launch { runCatching { snippetStore.save(items) } }
    }

    fun setLocalAlerts(on: Boolean) {
        _settings.value = _settings.value.copy(localAlerts = on)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    fun setHidePromptOnLockScreen(on: Boolean) {
        _settings.value = _settings.value.copy(hidePromptOnLockScreen = on)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    /** The permission dialog was shown once: from now on a refusal without a rationale means the system will not show it again. */
    fun notePermissionAsked() {
        if (_settings.value.notificationPermissionAsked) return
        _settings.value = _settings.value.copy(notificationPermissionAsked = true)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    fun setKeepPromptText(on: Boolean) {
        _settings.value = _settings.value.copy(keepPromptText = on)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    /** The user agreed once to what desktop focus does; the first tap asks until this is set. */
    fun setDesktopFocusConfirmed() {
        _settings.value = _settings.value.copy(desktopFocusConfirmed = true)
        scope.launch { runCatching { settingsStore.save(_settings.value) } }
    }

    /**
     * Replaces the pin with the key the user was shown ([shown], the warning the dialog drew), then reconnects, so the new pin
     * is checked by a fresh connection. Returns false and changes nothing when that warning is no longer the one on record
     * (cleared by a good connect, or replaced by a later failed attempt that may carry another key): the tap approved a key,
     * and only that key.
     */
    suspend fun replaceKey(shown: ChangedKey): Boolean {
        val c = broker.takeChanged(shown.profileId, shown.id) ?: return false
        try {
            hostKeyPolicy.replaceChanged(c.profileId, c.endpoint, c.pin, c.presented)
        } catch (e: Throwable) {
            broker.restoreChanged(c)
            throw e
        }
        owner.refresh(c.profileId)
        return true
    }

    private suspend fun connect(profile: HostProfile) = run {
        val target = profile.toTarget()
        val auth: SshAuth = when (profile.key) {
            KeyKind.Phone -> SshAuth.Phone(phoneKey.privateKey(), phoneKey.info().publicKey)
            KeyKind.Imported -> importedKeys.load(profile.importedKeyId!!) ?: throw ConnectFailure.BadKey("the imported key is missing")
        }
        try {
            // A connect that got through presented the pinned key (or was pinned just now): an older changed-key warning is stale.
            connector.connect(target, auth) { presented -> broker.askFirstTrust(profile.id, target.endpoint, presented) }
                .also { broker.clearChanged(profile.id) }
        } catch (e: ConnectFailure.HostKeyChanged) {
            broker.recordChanged(profile.id, target.endpoint, e)
            throw e
        }
    }
}

/** What an arrived alert turned into. [row] is the agent's row when there is one; [host] lets the caller acknowledge a Done. */
class AlertResolution(val outcome: AlertOutcome?, val machine: String, val row: AgentRowModel?, val host: MonitoredHost?)

/** What a push for a whole machine turned into. [outcome] is null when the read did not come in time; [unknownMachine] when this phone has no such machine. */
class MachineResolution(val outcome: MachineOutcome?, val machine: String, val unknownMachine: Boolean)

const val IMPORTED_KEY_ID = "imported"

/** The widget cache is rewritten when nothing changed only if its read is this much newer. */
private const val WIDGET_TOUCH_MILLIS = 60_000L
/** How long the widgets' background read waits for a connection before it gives up until the next period. */
private const val WIDGET_CONNECT_MILLIS = 25_000L
