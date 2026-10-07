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
import io.github.tuthan.paddock.alerts.AlertService
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
import io.github.tuthan.paddock.billing.ProCopy
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProGate
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.billing.ProView
import io.github.tuthan.paddock.billing.Entitlements
import io.github.tuthan.paddock.billing.FileEntitlementStore
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyBroker
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostprofile.FileHostProfileStore
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ledger.FileLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.AndroidTriggers
import io.github.tuthan.paddock.lifecycle.ConnectionOwner
import io.github.tuthan.paddock.lifecycle.SessionFactory
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.live.HostWatcher
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ops.FileJournalStore
import io.github.tuthan.paddock.ops.FileSnippetStore
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.PinnedHostScripts
import io.github.tuthan.paddock.wake.WakeLink
import io.github.tuthan.paddock.wake.WakeService
import io.github.tuthan.paddock.widget.WidgetService
import io.github.tuthan.paddock.settings.AppSettings
import io.github.tuthan.paddock.settings.SettingsController
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tuthan.paddock.pairing.pendingOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Pro: the last verified answer from the store account, kept in a plain file; the foss build has no store and every capability unlocked (Phase 13). */
    val entitlements = Entitlements(Distribution.billing(app), FileEntitlementStore(File(files, FileEntitlementStore.FILE_NAME)), clock, Distribution.UNLOCKED)
    /** What the phone asked of each terminal, written before it asks. Never deleted for an unknown outcome. */
    val journal = OperationJournal(FileJournalStore(File(files, "operations.json")), clock)
    /** Start-agent sagas with every id herdr returned (Phase 09); no prompt text is ever written here. */
    private val sagaStore = io.github.tuthan.paddock.ops.FileSagaStore(File(files, "sagas.json"))
    /** Manual input (Esc and Ctrl+C) lives as long as the process and is never restored: a killed app starts with it off. */
    val manualInput = ManualInputMode()
    /** Where a tapped alert enters: parsed, deduped, then resolved against a fresh read by [resolveAlert]. */
    val alerts = AlertInbox(clock)

    /** Pairing links from a tap or a paste: they only fill in Add machine, never connect or trust. */
    val pairing = io.github.tuthan.paddock.hostprofile.PairingInbox()
    /** Notifications Paddock raises itself, only while it is open but not in front (the watching mode of Phase 07). */
    private val notifier = AndroidAlertNotifier(app)
    val notificationAccess = NotificationAccessReader(app)
    /** Connector mode: registrations with a UnifiedPush distributor, one per machine. A push raises a generic notification through [notifier]. */
    private val pushRegistry = PushRegistry(FilePushStore(File(files, "push.json")), clock = clock)
    /** What was last set up for each machine's alerts (how they arrive, the server and topic), so the subscribe link can be shown again and a second run keeps the topic. */
    val alertSetups: io.github.tuthan.paddock.alerts.AlertSetupStore = io.github.tuthan.paddock.alerts.FileAlertSetupStore(File(files, "alert-setup.json"))
    val push = UnifiedPushConnector(
        app, pushRegistry, scope, clock,
        machineName = { id -> profiles.get(id)?.name },
        show = { notifier.show(it) },
        herdInFront = { triggers.interactive.value && !appLock.locked.value },
        watchedProfile = { _profile.value?.id },
        hideOnLockScreen = { runCatching { prefs.stored().hidePromptOnLockScreen }.getOrDefault(true) },
    )

    /** What the home-screen widgets draw (Phase 10): the last read of the watched machine, written by the open app and by [WidgetService.refresh]. */
    val widgets = WidgetService(
        store = io.github.tuthan.paddock.widget.WidgetCaches.store(app),
        read = io.github.tuthan.paddock.widget.WidgetRefresher(io.github.tuthan.paddock.widget.WidgetCaches.store(app), clock) { host, session ->
            ledger.installedEpoch(host, session)?.let { ledger.seenLookup(host, session, it) } ?: io.github.tuthan.paddock.attention.SeenLookup { null }
        }::refresh,
        clock = clock, acquire = { profile -> owner.acquire(profile) }, profiles = profiles,
        watchedProfileId = { prefs.stored().watchedProfileId }, inForeground = { triggers.foreground.value }, removing = { it in removing },
        locked = { io.github.tuthan.paddock.widget.PaddockWidgets.locked(app) }, redraw = { io.github.tuthan.paddock.widget.PaddockWidgets.updateAll(app) },
    )

    private val connector = SshlibConnector(hostKeyPolicy, clock, gate)
    val owner = ConnectionOwner(scope, SessionFactory { profile -> connect(profile) }, clock)
    val triggers = AndroidTriggers(app, owner, onNetworkChanged = { probe.lookAgainNow() })

    /**
     * The light look at the machines this phone is not watching, for the counts on Home's chips (Pro, `hosts.merged`). Its own short sessions, never the
     * owner's: it holds no lease on anything, and it never asks the user ([connectForProbe]).
     */
    val probe = io.github.tuthan.paddock.probe.MachineProbe(clock, open = { profile -> connectForProbe(profile) })

    /** Whether Paddock's screens are covered by the lock (Settings > Privacy > Lock Paddock). Elapsed real time, so the date changing cannot stretch the timeout. */
    val appLock = io.github.tuthan.paddock.applock.AppLock { android.os.SystemClock.elapsedRealtime() }

    /** Every script, unit file and example configuration Paddock installs on a machine, each checked against its pin (host/SOURCE.json) when the graph is built. */
    val hostScripts = PinnedHostScripts { name -> app.assets.open(name).use { it.readBytes() } }

    private val _boot = MutableStateFlow(Boot.Loading)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _profile = MutableStateFlow<HostProfile?>(null)
    val profile: StateFlow<HostProfile?> = _profile.asStateFlow()

    private val _machines = MutableStateFlow<List<HostProfile>>(emptyList())
    /** Every machine saved on this phone, by name: the Machines screen, each machine's page and Home's chips. Only one is watched ([profile]). */
    val machines: StateFlow<List<HostProfile>> = _machines.asStateFlow()

    /** One change to the saved machines at a time: add, switch and remove each read and write the profile store and the watched machine. */
    private val machineLock = kotlinx.coroutines.sync.Mutex()
    /** Machines being removed: nothing may start watching them (a foreground flip does `hostWatcher.watch(_profile.value)`, and the removed machine is still that until the end). */
    private val removing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _removingMachine = MutableStateFlow<String?>(null)
    /**
     * The machine a removal is running for, or null. Held here, not in the screen, because the removal runs in [scope] and outlives a rotation: the
     * Machines screen and a machine's page keep Watch and Remove… waiting for as long as it runs, whichever composition draws them.
     */
    val removingMachine: StateFlow<String?> = _removingMachine.asStateFlow()

    private val _removalOutcome = MutableStateFlow<RemovalOutcome?>(null)
    /**
     * What the last removal asked for from a machine's page ([requestRemoval]) came to, until the root has shown it ([consumeRemoval]). State, not a
     * callback: a rotation or a trip away while the removal runs would leave a callback in a composition that is gone, and "Removed X." would be lost.
     */
    val removalOutcome: StateFlow<RemovalOutcome?> = _removalOutcome.asStateFlow()

    /** One write to [machines] at a time: a re-read of the store must not land after, and so undo, an in-place write made meanwhile ([machineWritten]). */
    private val machinesWrite = kotlinx.coroutines.sync.Mutex()

    private suspend fun refreshMachines() { machinesWrite.withLock { runCatching { profiles.list() }.onSuccess { _machines.value = it } } }

    val hostUi = HostUiModel(scope)
    /** Wake for every saved machine: the watched one through its tap, any other as a send only ([WakeService]). */
    val wakes = WakeService(
        scope, clock, lanPaths, sockets, permissionMissing = { gate.lanAccessMissing() }, watched = _profile, profile = { id -> profiles.get(id) },
        link = { hostUi.view.value.let { WakeLink.of(it.phase, it.freshness) } }, links = hostUi.view.map { WakeLink.of(it.phase, it.freshness) },
        reconnect = { retry() },
    )
    /** What an arrived alert turns into, and the notifications the open app raises itself ([AlertService]). */
    val alertService = AlertService(
        clock, settings = { prefs.value }, interactive = { triggers.interactive.value }, show = { notifier.show(it) },
        switchTo = { id -> watchProfile(id, chosen = false) }, monitored = hostUi.view.map { (it.phase as? HostPhase.Monitoring)?.host },
    )
    /** The one machine watched now: its connection controller, and what follows that controller's phases and readings ([HostWatcher]). */
    val hostWatcher = HostWatcher(
        scope, isRemoving = { it in removing },
        create = { profile ->
            HostSessionController(
                scope, profile, { owner.acquire(profile) }, ledger, clock, triggers.foreground, hostScripts.relay, hostScripts.relaySha256, sessionName = profile.session,
                controlScript = hostScripts.control, controlSha256 = hostScripts.controlSha256, journal = journal, alertScript = hostScripts.alert, alertSha256 = hostScripts.alertSha256,
                decideScript = hostScripts.decide, decideSha256 = hostScripts.decideSha256, hookScript = hostScripts.hook, hookSha256 = hostScripts.hookSha256,
                opencodeScript = hostScripts.opencode, opencodeSha256 = hostScripts.opencodeSha256, sagaStore = sagaStore,
                wakeCapture = { session -> io.github.tuthan.paddock.wake.WakeCapture.capture(session, clock, profiles.get(profile.id)?.wake?.relay) },
                osProbe = { session -> io.github.tuthan.paddock.hostprofile.HostOsProbe.read(session) },
            )
        },
        attach = { hostUi.attach(it) }, detach = { hostUi.detach() }, becameWatched = { wakes.watching(it) },
        stopped = { wakes.forgetWatched(); widgets.stopTracking() },
        follows = listOf(
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) alertService.raise(p.host) } },
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) widgets.track(p.host) } },
            { c -> c.wake.filterNotNull().collect { saveWake(c.profile, it) } },
            { c -> c.os.filterNotNull().collect { saveDetectedOs(c.profile, it) } },
            // A live connection ends any pairing request: the key is no longer what stands between this phone and the machine.
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) pairingCoordinator.connectedTo(p.host.profile.host) } },
        ),
    )
    /** The open terminal session, kept through a rotation so turning the phone does not release control. */
    val terminals = io.github.tuthan.paddock.host.Retained<io.github.tuthan.paddock.terminal.TerminalSession>(scope) { it.close() }

    /** Settings and the user's snippets, and what writes them ([SettingsController]). */
    val prefs = SettingsController(scope, FileAppSettingsStore(File(files, "settings.json")), FileSnippetStore(File(files, "snippets.json")), appLock)
    val settings: StateFlow<AppSettings> get() = prefs.settings
    val snippets: StateFlow<List<String>> get() = prefs.snippets

    /** Pro: the store, the gate and the sheet that asks for it (Phase 13). */
    val proService = ProService(entitlements, scope, clock, manualInput, journal, Distribution.UNLOCKED)

    fun start() {
        triggers.install()
        // Leaving and coming back is what asks again: the lock counts the time the app was out of sight (it ignores a rotation, and the credential screen's own trip).
        scope.launch { triggers.foreground.collect { visible -> if (visible) appLock.shown(prefs.value.appLock, prefs.value.appLockAfterSeconds) else appLock.hidden() } }
        // The herd in front of the user is the alert: notifications raised while it was away are cleared when it returns.
        // Coming to the front clears the notifications of the machine now on screen, and only those: another machine's alert is not on this screen
        // (see AlertScreen). Switching the watched machine while in front clears the new machine's.
        scope.launch {
            kotlinx.coroutines.flow.combine(triggers.interactive, appLock.locked, _profile) { front, locked, watched -> if (front && !locked) watched?.id else null }
                .distinctUntilChanged().collect { id -> if (id != null) runCatching { notifier.cancelFor(id) } }
        }
        // The chips of the machines not watched say how many agents need you (Pro): looked at only while the herd is in front and not covered by the lock.
        // The look is one short session per machine every half minute; the push alerts are what reaches the user in the background.
        scope.launch {
            kotlinx.coroutines.flow.combine(triggers.interactive, appLock.locked, _machines, _profile, proService.view) { front, covered, all, watched, view ->
                io.github.tuthan.paddock.probe.ProbeTargets.of(front, covered, proService.locked(ProCapabilities.HOSTS_MERGED, view), all, watched?.id)
            }.distinctUntilChanged().collectLatest { targets -> if (targets.isNotEmpty()) probe.run(targets) }
        }
        proService.start(triggers.foreground)
        // Widgets are a Pro capability: when the answer to "does this phone hold Pro" changes they are redrawn. This is only the trigger, by the same rule
        // (Entitlements.hasPro): what is drawn is decided by PaddockWidgets.locked from the saved file, which is written before this state moves. With no widget placed the redraw does nothing.
        scope.launch { proService.view.map { proService.hasPro(it) }.distinctUntilChanged().collect { runCatching { io.github.tuthan.paddock.widget.PaddockWidgets.updateAll(app) } } }
        scope.launch {
            prefs.load()
            val read = runCatching { profiles.list() }
            val all = read.getOrDefault(emptyList())
            _machines.value = all
            // The machine watched last time, not whichever sorts first.
            val watched = all.firstOrNull { it.id == prefs.value.watchedProfileId } ?: all.firstOrNull()
            _profile.value = watched
            // Settings written before there was a chosen machine, or naming one removed meanwhile: the watched machine is the choice. Saved once, and
            // never from a list that could not be read (MachineRoster.startupChoice).
            MachineRoster.startupChoice(read.isSuccess, all, prefs.value, watched?.id)?.let { migrated ->
                prefs.update { it.copy(chosenProfileId = migrated.chosenProfileId) }
                prefs.saveQuietly()
            }
            _boot.value = if (watched == null) Boot.NoMachines else Boot.Ready
            runCatching { push.resume() }
            // The connection is claimed while the app is visible and released when it is not (the owner closes it after its grace).
            // Held while the app is visible and uncovered: behind the lock screen nothing connects, so nothing asks a question (a host key, a key) the user cannot see.
            kotlinx.coroutines.flow.combine(triggers.foreground, appLock.locked) { visible, locked -> visible && !locked }.distinctUntilChanged()
                .collectLatest { active -> if (active) _profile.value?.let { hostWatcher.watch(it) } else hostWatcher.pause() }
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

    /**
     * Saves [profile], makes it the watched machine and connects. [chosen]: the user picked it, so it is also the chosen one ([AppSettings.chosenProfileId]).
     * Setting up the watched machine's key passes false ([MachineRoster.choosesOnAdd]): after an alert moved the phone to that machine, fixing its key
     * must not take away the free return to the machine the user chose.
     */
    suspend fun addMachine(profile: HostProfile, chosen: Boolean = true) = machineLock.withLock {
        profiles.put(profile)
        refreshMachines()
        prefs.update { it.copy(watchedProfileId = profile.id, chosenProfileId = if (chosen) profile.id else it.chosenProfileId) }
        prefs.save()
        _profile.value = profile
        _boot.value = Boot.Ready
        if (triggers.foreground.value) hostWatcher.watch(profile)
    }

    /**
     * Everything this phone holds about a machine, forgotten before its profile goes. Not here, on purpose: this phone's own key and the one imported key
     * (every machine shares them), and the operation journal (what the phone sent, with no machine text; adding the same machine again finds an unknown outcome there).
     */
    private val removal by lazy {
        MachineRemoval(
            listOf(
                MachineRemoval.Forget("its alert registration") { id -> push.unregister(id) },
                MachineRemoval.Forget("its alert setup") { id -> alertSetups.remove(id) },
                MachineRemoval.Forget("the host key") { id -> hostKeyStore.remove(id); broker.clearChanged(id); broker.expectPairing(id, null) },
                MachineRemoval.Forget("what the phone saw there") { id -> ledger.forgetHost(HostProfileId(id)); withContext(Dispatchers.IO) { ledger.flush() } },
                MachineRemoval.Forget("its widget copy") { id -> widgets.forget(id) },
                // A request made for this machine's desktop is no longer about anything this phone has.
                MachineRemoval.Forget("a pairing request") { id ->
                    val host = profiles.get(id)?.host?.trim()
                    val pending = pairingCoordinator.state.value.pendingOrNull
                    val sharedWithAnother = profiles.list().any { it.id != id && it.host.trim().equals(host, ignoreCase = true) }
                    if (host != null && pending != null && !sharedWithAnother && pending.host.trim().equals(host, ignoreCase = true)) pairingCoordinator.clear()
                },
            ),
        ) { id -> profiles.remove(id) }
    }

    /**
     * Removes a machine from this phone (see [MachineRemoval]). Removing the watched one stops watching it first, so nothing it writes comes after the
     * forgetting, then watches the next saved machine ([MachineRoster.after]: the chosen machine when it is another one, else the first by name), or none;
     * the user confirmed "Paddock then watches <next>", so it is also the chosen one. Removing the chosen machine while another is watched makes the watched one the choice, as start-up would. When something could not be forgotten
     * the machine stays listed (and watched again) for another try. Free: the user must always be able to forget a machine.
     */
    suspend fun removeMachine(id: String): MachineRemoval.Result = try {
        // Before the lock, so Watch and Remove… wait from the moment it is asked for, also while another change to the machines holds the lock.
        _removingMachine.value = id
        removeMachineLocked(id)
    } finally {
        // Only its own mark: a second removal asked for meanwhile keeps the screens waiting until it ends.
        _removingMachine.compareAndSet(id, null)
    }

    /**
     * Remove… on a machine's page, run in [scope] so leaving the page or turning the phone does not stop it. What it came to is kept in
     * [removalOutcome] until the root has said so.
     */
    fun requestRemoval(id: String, name: String) {
        // Marked at the tap, before the coroutine runs, so the next frame already has Watch and Remove… waiting; removeMachine clears it.
        _removingMachine.value = id
        scope.launch { _removalOutcome.value = RemovalOutcome(id, name, removeMachine(id)) }
    }

    /** The root showed [outcome]: it goes, unless a newer removal has replaced it meanwhile. */
    fun consumeRemoval(outcome: RemovalOutcome) { _removalOutcome.compareAndSet(outcome, null) }

    private suspend fun removeMachineLocked(id: String): MachineRemoval.Result = machineLock.withLock {
        if (profiles.get(id) == null) return@withLock MachineRemoval.Result.Removed
        val wasWatched = _profile.value?.id == id
        val before = _profile.value
        // From here nothing may start watching it, and its connection (and the grace after it) is closed now: a reconnect would raise a first-trust prompt for a key just forgotten.
        removing += id
        try {
            if (wasWatched) hostWatcher.stop()
            owner.drop(id)
            val result = removal.remove(id)
            refreshMachines()
            if (result != MachineRemoval.Result.Removed) {
                removing -= id
                if (wasWatched && before != null && triggers.foreground.value) hostWatcher.watch(before)
                return@withLock result
            }
            // Its Wake facts and their flow go with it: a later machine with the same id starts with none.
            wakes.forget(id)
            probe.forget(id)
            if (wasWatched) {
                val next = MachineRoster.after(_machines.value, id, prefs.value.chosenProfileId)
                prefs.update { it.copy(watchedProfileId = next?.id, chosenProfileId = next?.id) }
                prefs.saveQuietly()
                _profile.value = next
                hostUi.reset()
                if (next == null) _boot.value = Boot.NoMachines else if (triggers.foreground.value) hostWatcher.watch(next)
            } else {
                val chosen = MachineRoster.chosen(_machines.value, prefs.value.chosenProfileId, _profile.value?.id)
                if (chosen != prefs.value.chosenProfileId) {
                    prefs.update { it.copy(chosenProfileId = chosen) }
                    prefs.saveQuietly()
                }
            }
            result
        } finally {
            removing -= id
        }
    }

    /**
     * Makes [id] the watched machine. Null when this phone has no such machine. [chosen]: the user picked it themselves (the machine list's Watch), so it
     * also becomes the chosen machine ([AppSettings.chosenProfileId]). An alert calls this with the default: the machine an alert moves the phone to is
     * never the one a phone without Pro may always return to.
     */
    suspend fun watchProfile(id: String, chosen: Boolean = false): HostProfile? = machineLock.withLock {
        val profile = profiles.get(id) ?: return@withLock null
        val switching = _profile.value?.id != profile.id
        if (switching || (chosen && prefs.value.chosenProfileId != profile.id)) {
            prefs.update { it.copy(watchedProfileId = profile.id, chosenProfileId = if (chosen) profile.id else it.chosenProfileId) }
            prefs.saveQuietly()
        }
        if (switching) {
            _profile.value = profile
            // At the switch, not only when its connection starts (watch runs only in the foreground): from now on this machine's facts are the watched
            // tap's, and its send-only "not watching this machine" line is no longer true. hostWatcher.watch() says it again for a controller it replaces; twice is harmless.
            wakes.watching(profile.id)
            if (triggers.foreground.value) hostWatcher.watch(profile)
        }
        profile
    }

    /** The saved sagas, for Activity while no host is being monitored (a restart, a lost connection). */
    fun savedSagas(): List<io.github.tuthan.paddock.ops.SagaRecord> = runCatching { sagaStore.load().records }.getOrDefault(emptyList())

    /**
     * Keeps what a bring-up read about waking the machine, with the relay the user saved. Skipped when the stored profile is no longer
     * the machine that was read (the user edited its address meanwhile). What to keep is [WakeTarget.afterReading]: a run that failed
     * never replaces a hardware address already read, and a reading that says what is stored is not a write. One critical section in
     * the store, so a profile edit or a relay save made meanwhile is not overwritten with a stale copy.
     */
    private suspend fun saveWake(read: HostProfile, reading: io.github.tuthan.paddock.wake.WakeReading) {
        val written = profiles.update(read.id) { stored ->
            if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port) null
            else io.github.tuthan.paddock.wake.WakeTarget.afterReading(stored.wake, reading)?.let { stored.copy(wake = it) }
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Keeps what a bring-up learned about the machine's OS as [HostProfile.detectedOs]. Skipped when the stored profile is no longer the machine that
     * asked (its address was edited meanwhile), and a reading that says what is stored is not a write. The user's own pick ([HostProfile.os]) is never touched.
     */
    private suspend fun saveDetectedOs(read: HostProfile, os: io.github.tuthan.paddock.hostprofile.HostOs) {
        val written = profiles.update(read.id) { stored ->
            if (!stored.host.equals(read.host, ignoreCase = true) || stored.port != read.port || stored.detectedOs == os) null else stored.copy(detectedOs = os)
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * Renames [profileId] on this phone (the id, the pin and every fact that hangs off it stay). [MachineName] has already judged [name]; the store's own
     * check is the last word. False when there is no such machine. The watched machine reconnects once: its name is part of what its connection was
     * started with (the label the answer host writes), so a rename is the one edit here that is not free.
     */
    suspend fun renameMachine(profileId: String, name: String): Boolean {
        val written = profiles.update(profileId) { stored -> stored.copy(name = name) }
        if (written == null) return profiles.get(profileId)?.name == name
        if (_profile.value?.id == written.id) { _profile.value = written; hostWatcher.watch(written) }
        machineWritten(written)
        return true
    }

    /** The user's own icon for [profileId]: an OS, or null to follow what the machine says. Never reconnects. */
    suspend fun setMachineOs(profileId: String, os: io.github.tuthan.paddock.hostprofile.HostOs?) {
        val written = profiles.update(profileId) { stored -> stored.copy(os = os) } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /**
     * The saved list follows a write to one machine (its wake reading or relay), so the Machines screen and the machine page show it without a
     * re-read of the store. Only a machine still listed is replaced: a removal that finished meanwhile is not undone. Behind [machinesWrite], so a
     * re-read that started before this write reached the store ([refreshMachines]) cannot land after it with the old copy.
     */
    private suspend fun machineWritten(written: HostProfile) { machinesWrite.withLock { _machines.update { all -> all.map { if (it.id == written.id) written else it } } } }

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

    /**
     * Saves, replaces or clears the relay for waking [profileId] from away. Before any reading the profile gets a "not read yet" target
     * that carries only the relay; the next bring-up fills in the rest and keeps it.
     */
    suspend fun setWakeRelay(profileId: String, relay: io.github.tuthan.paddock.wake.WakeRelay?) {
        val written = profiles.update(profileId) { stored ->
            val base = stored.wake ?: io.github.tuthan.paddock.wake.WakeTarget.unavailable("Not read yet: connect once so Paddock can read the machine's network interface.", clock.nowMillis())
            stored.copy(wake = base.copy(relay = relay))
        } ?: return
        if (_profile.value?.id == written.id) _profile.value = written
        machineWritten(written)
    }

    /** "Try again": a setup problem on a healthy connection runs the setup again; anything else refreshes the connection. */
    fun retry() {
        if (hostWatcher.retrySetup()) return
        _profile.value?.let { owner.refresh(it.id) }
    }
    fun installRelay() { hostWatcher.installRelay() }

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
        try {
            // A connect that got through presented the pinned key (or was pinned just now): an older changed-key warning is stale.
            connector.connect(target, authFor(profile)) { presented -> broker.askFirstTrust(profile.id, target.endpoint, presented) }
                .also { broker.clearChanged(profile.id) }
        } catch (e: ConnectFailure.HostKeyChanged) {
            broker.recordChanged(profile.id, target.endpoint, e)
            throw e
        }
    }

    /**
     * The probe's connect (`MachineProbe`): the same key and pins, but it never asks. A host key this phone has not trusted is declined, and a key that
     * changed is only a failure, so a look at a machine that is not watched can raise no first-trust prompt and record no changed-key warning over
     * Home. The user meets both, as always, when they watch that machine.
     */
    private suspend fun connectForProbe(profile: HostProfile) = connector.connect(profile.toTarget(), authFor(profile)) { false }

    private suspend fun authFor(profile: HostProfile): SshAuth = when (profile.key) {
        KeyKind.Phone -> SshAuth.Phone(phoneKey.privateKey(), phoneKey.info().publicKey)
        KeyKind.Imported -> importedKeys.load(profile.importedKeyId!!) ?: throw ConnectFailure.BadKey("the imported key is missing")
    }
}

/**
 * What one Remove… from a machine's page came to ([AppGraph.requestRemoval]): the machine's [id] and the [name] the page showed, and the [result].
 * Not a data class: two removals are two events even when they read alike, so consuming one never clears the other.
 */
class RemovalOutcome(val id: String, val name: String, val result: MachineRemoval.Result)

const val IMPORTED_KEY_ID = "imported"
