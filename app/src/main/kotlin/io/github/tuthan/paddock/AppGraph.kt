package io.github.tuthan.paddock

import android.app.Application
import io.github.tuthan.paddock.alerts.AlertInbox
import io.github.tuthan.paddock.alerts.AlertService
import io.github.tuthan.paddock.alerts.AlertSetupStore
import io.github.tuthan.paddock.alerts.FileAlertSetupStore
import io.github.tuthan.paddock.alerts.FilePushStore
import io.github.tuthan.paddock.alerts.PushRegistry
import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.billing.Distribution
import io.github.tuthan.paddock.billing.Entitlements
import io.github.tuthan.paddock.billing.FileEntitlementStore
import io.github.tuthan.paddock.billing.ProCapabilities
import io.github.tuthan.paddock.billing.ProService
import io.github.tuthan.paddock.discovery.FinderController
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.host.Retained
import io.github.tuthan.paddock.hostkey.ChangedKey
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyBroker
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostprofile.FileHostProfileStore
import io.github.tuthan.paddock.hostprofile.HostOsProbe
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.KeyKind
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.hostprofile.MachineRemoval
import io.github.tuthan.paddock.hostprofile.PairingInbox
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ledger.FileLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.AndroidTriggers
import io.github.tuthan.paddock.lifecycle.ConnectionOwner
import io.github.tuthan.paddock.lifecycle.SessionFactory
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.live.HostWatcher
import io.github.tuthan.paddock.net.AndroidLanPaths
import io.github.tuthan.paddock.net.AndroidSockets
import io.github.tuthan.paddock.net.NsdBrowser
import io.github.tuthan.paddock.notify.AndroidAlertNotifier
import io.github.tuthan.paddock.notify.NotificationAccessReader
import io.github.tuthan.paddock.notify.UnifiedPushConnector
import io.github.tuthan.paddock.ops.FileJournalStore
import io.github.tuthan.paddock.ops.FileSagaStore
import io.github.tuthan.paddock.ops.FileSnippetStore
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.SagaRecord
import io.github.tuthan.paddock.pairing.FilePendingPairingStore
import io.github.tuthan.paddock.pairing.PairingClient
import io.github.tuthan.paddock.pairing.PairingCoordinator
import io.github.tuthan.paddock.pairing.pendingOrNull
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.probe.MachineProbe
import io.github.tuthan.paddock.probe.ProbeTargets
import io.github.tuthan.paddock.relay.PinnedHostScripts
import io.github.tuthan.paddock.settings.FileAppSettingsStore
import io.github.tuthan.paddock.settings.SettingsController
import io.github.tuthan.paddock.ssh.AuthorizedKey
import io.github.tuthan.paddock.ssh.ConnectFailure
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ssh.ImportedKeyInfo
import io.github.tuthan.paddock.ssh.ImportedKeyStore
import io.github.tuthan.paddock.ssh.KeystoreSecretStore
import io.github.tuthan.paddock.ssh.LocalNetworkGate
import io.github.tuthan.paddock.ssh.PhoneKey
import io.github.tuthan.paddock.ssh.SshAuth
import io.github.tuthan.paddock.ssh.SshlibConnector
import io.github.tuthan.paddock.terminal.TerminalSession
import io.github.tuthan.paddock.wake.WakeCapture
import io.github.tuthan.paddock.wake.WakeLink
import io.github.tuthan.paddock.wake.WakeService
import io.github.tuthan.paddock.widget.PaddockWidgets
import io.github.tuthan.paddock.widget.WidgetCaches
import io.github.tuthan.paddock.widget.WidgetRefresher
import io.github.tuthan.paddock.widget.WidgetService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val lanPaths = AndroidLanPaths(app)
    val sockets = AndroidSockets(lanPaths)
    /** Find on this network (Phase 14): nothing runs until the page's Start. The probe is pinned to the network it scans, and mDNS is browsed beside it. */
    val finder = FinderController(
        scope, lanPaths, sockets, grantMissing = { gate.lanAccessMissing() }, bindProbeTo = { sockets.tcpPathId = it },
        mdns = { NsdBrowser(app).browse() },
    )
    /** Sending this phone's public key to a desktop's `pair` popup (Phase 14). The request is on disk before its first byte, so a restart reopens it within its window. */
    val pairingCoordinator = PairingCoordinator(
        FilePendingPairingStore(File(app.filesDir, "pending-pairing.json")),
        PairingClient(sockets), clock, scope,
    )
    // After a restart: a request still inside its window is reopened, asking the desktop first whether the first send ever arrived.
    init { scope.launch { runCatching { pairingCoordinator.resume() } } }
    val ledger = Ledger(FileLedgerStore(File(files, "ledger.json"))) { System.currentTimeMillis() }
    /** Pro: the last verified answer from the store account, kept in a plain file; the foss build has no store and every capability unlocked (Phase 13). */
    val entitlements = Entitlements(Distribution.billing(app), FileEntitlementStore(File(files, FileEntitlementStore.FILE_NAME)), clock, Distribution.UNLOCKED)
    /** What the phone asked of each terminal, written before it asks. Never deleted for an unknown outcome. */
    val journal = OperationJournal(FileJournalStore(File(files, "operations.json")), clock)
    /** Start-agent sagas with every id herdr returned (Phase 09); no prompt text is ever written here. */
    private val sagaStore = FileSagaStore(File(files, "sagas.json"))
    /** Manual input (Esc and Ctrl+C) lives as long as the process and is never restored: a killed app starts with it off. */
    val manualInput = ManualInputMode()
    /** Where a tapped alert enters: parsed, deduped, then resolved against a fresh read by [AlertService.resolveAlert]. */
    val alerts = AlertInbox(clock)

    /** Pairing links from a tap or a paste: they only fill in Add machine, never connect or trust. */
    val pairing = PairingInbox()
    /** Notifications Paddock raises itself, only while it is open but not in front (the watching mode of Phase 07). */
    private val notifier = AndroidAlertNotifier(app)
    val notificationAccess = NotificationAccessReader(app)
    /** Connector mode: registrations with a UnifiedPush distributor, one per machine. A push raises a generic notification through [notifier]. */
    private val pushRegistry = PushRegistry(FilePushStore(File(files, "push.json")), clock = clock)
    /** What was last set up for each machine's alerts (how they arrive, the server and topic), so the subscribe link can be shown again and a second run keeps the topic. */
    val alertSetups: AlertSetupStore = FileAlertSetupStore(File(files, "alert-setup.json"))
    val push: UnifiedPushConnector = UnifiedPushConnector(
        app, pushRegistry, scope, clock,
        machineName = { id -> profiles.get(id)?.name },
        show = { notifier.show(it) },
        herdInFront = { triggers.interactive.value && !appLock.locked.value },
        watchedProfile = { machineBook.profile.value?.id },
        hideOnLockScreen = { runCatching { prefs.stored().hidePromptOnLockScreen }.getOrDefault(true) },
    )

    /** What the home-screen widgets draw (Phase 10): the last read of the watched machine, written by the open app and by [WidgetService.refresh]. */
    val widgets: WidgetService = WidgetService(
        store = WidgetCaches.store(app),
        read = WidgetRefresher(WidgetCaches.store(app), clock) { host, session ->
            ledger.installedEpoch(host, session)?.let { ledger.seenLookup(host, session, it) } ?: SeenLookup { null }
        }::refresh,
        clock = clock, acquire = { profile -> owner.acquire(profile) }, profiles = profiles,
        watchedProfileId = { prefs.stored().watchedProfileId }, inForeground = { triggers.foreground.value }, removing = { machineBook.isRemoving(it) },
        locked = { PaddockWidgets.locked(app) }, redraw = { PaddockWidgets.updateAll(app) },
    )

    private val connector = SshlibConnector(hostKeyPolicy, clock, gate)
    val owner: ConnectionOwner = ConnectionOwner(scope, SessionFactory { profile -> connect(profile) }, clock)
    val triggers: AndroidTriggers = AndroidTriggers(app, owner, onNetworkChanged = { probe.lookAgainNow() })

    /**
     * The light look at the machines this phone is not watching, for the counts on Home's chips (Pro, `hosts.merged`). Its own short sessions, never the
     * owner's: it holds no lease on anything, and it never asks the user ([connectForProbe]).
     */
    val probe: MachineProbe = MachineProbe(clock, open = { profile -> connectForProbe(profile) })

    /** Whether Paddock's screens are covered by the lock (Settings > Privacy > Lock Paddock). Elapsed real time, so the date changing cannot stretch the timeout. */
    val appLock = AppLock { android.os.SystemClock.elapsedRealtime() }

    /** Every script, unit file and example configuration Paddock installs on a machine, each checked against its pin (host/SOURCE.json) when the graph is built. */
    val hostScripts = PinnedHostScripts { name -> app.assets.open(name).use { it.readBytes() } }

    /** Settings and the user's snippets, and what writes them ([SettingsController]). */
    val prefs = SettingsController(scope, FileAppSettingsStore(File(files, "settings.json")), FileSnippetStore(File(files, "snippets.json")), appLock)

    /** Pro: the store, the gate and the sheet that asks for it (Phase 13). */
    val proService = ProService(entitlements, scope, clock, manualInput, journal, Distribution.UNLOCKED)

    /** The saved machines and the one watched ([MachineBook]); what it must tell the rest of the app is [MachineBook.Effects]. */
    val machineBook: MachineBook = MachineBook(
        profiles, prefs, scope, clock,
        /**
         * Everything this phone holds about a machine, forgotten before its profile goes. Not here, on purpose: this phone's own key and the one imported key
         * (every machine shares them), and the operation journal (what the phone sent, with no machine text; adding the same machine again finds an unknown outcome there).
         */
        removal = MachineRemoval(
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
        ) { id -> profiles.remove(id) },
        effects = object : MachineBook.Effects {
            override val inForeground: Boolean get() = triggers.foreground.value
            override fun watch(profile: HostProfile) = hostWatcher.watch(profile)
            override fun stopWatching() = hostWatcher.stop()
            override suspend fun dropConnection(id: String) = owner.drop(id)
            override fun becameWatched(id: String) = wakes.watching(id)
            // Its Wake facts and their flow go with it: a later machine with the same id starts with none.
            override fun forgotten(id: String) { wakes.forget(id); probe.forget(id) }
            override fun watchedRemoved() = hostUi.reset()
        },
    )

    val hostUi = HostUiModel(scope)
    /** Wake for every saved machine: the watched one through its tap, any other as a send only ([WakeService]). */
    val wakes: WakeService = WakeService(
        scope, clock, lanPaths, sockets, permissionMissing = { gate.lanAccessMissing() }, watched = machineBook.profile, profile = { id -> profiles.get(id) },
        link = { hostUi.view.value.let { WakeLink.of(it.phase, it.freshness) } }, links = hostUi.view.map { WakeLink.of(it.phase, it.freshness) },
        reconnect = { retry() },
    )
    /** What an arrived alert turns into, and the notifications the open app raises itself ([AlertService]). */
    val alertService: AlertService = AlertService(
        clock, settings = { prefs.value }, interactive = { triggers.interactive.value }, show = { notifier.show(it) },
        switchTo = { id -> machineBook.watch(id, chosen = false) }, monitored = hostUi.view.map { (it.phase as? HostPhase.Monitoring)?.host },
    )
    /** The one machine watched now: its connection controller, and what follows that controller's phases and readings ([HostWatcher]). */
    val hostWatcher: HostWatcher = HostWatcher(
        scope, isRemoving = { machineBook.isRemoving(it) },
        create = { profile ->
            HostSessionController(
                scope, profile, { owner.acquire(profile) }, ledger, clock, triggers.foreground, hostScripts.relay, hostScripts.relaySha256, sessionName = profile.session,
                controlScript = hostScripts.control, controlSha256 = hostScripts.controlSha256, journal = journal, alertScript = hostScripts.alert, alertSha256 = hostScripts.alertSha256,
                decideScript = hostScripts.decide, decideSha256 = hostScripts.decideSha256, hookScript = hostScripts.hook, hookSha256 = hostScripts.hookSha256,
                opencodeScript = hostScripts.opencode, opencodeSha256 = hostScripts.opencodeSha256, sagaStore = sagaStore,
                wakeCapture = { session -> WakeCapture.capture(session, clock, profiles.get(profile.id)?.wake?.relay) },
                osProbe = { session -> HostOsProbe.read(session) },
            )
        },
        attach = { hostUi.attach(it) }, detach = { hostUi.detach() }, becameWatched = { wakes.watching(it) },
        stopped = { wakes.forgetWatched(); widgets.stopTracking() },
        follows = listOf(
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) alertService.raise(p.host) } },
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) widgets.track(p.host) } },
            { c -> c.wake.filterNotNull().collect { machineBook.saveWake(c.profile, it) } },
            { c -> c.os.filterNotNull().collect { machineBook.saveDetectedOs(c.profile, it) } },
            // A live connection ends any pairing request: the key is no longer what stands between this phone and the machine.
            { c -> c.phase.collectLatest { p -> if (p is HostPhase.Monitoring) pairingCoordinator.connectedTo(p.host.profile.host) } },
        ),
    )
    /** The open terminal session, kept through a rotation so turning the phone does not release control. */
    val terminals = Retained<TerminalSession>(scope) { it.close() }

    /**
     * Starts what runs for the life of the process. Each step only starts a collector; none of them waits for another, and the machines and settings
     * they read are empty until [startLoad] has read the files, so each acts when its state fills.
     */
    fun start() {
        triggers.install()
        startLock()
        startAlertClearing()
        startChipProbe()
        proService.start(triggers.foreground)
        startWidgetRedraw()
        startLoad()
    }

    /** Leaving and coming back is what asks again: the lock counts the time the app was out of sight (it ignores a rotation, and the credential screen's own trip). */
    private fun startLock() {
        scope.launch { triggers.foreground.collect { visible -> if (visible) appLock.shown(prefs.value.appLock, prefs.value.appLockAfterSeconds) else appLock.hidden() } }
    }

    /**
     * The herd in front of the user is the alert: notifications raised while it was away are cleared when it returns.
     * Coming to the front clears the notifications of the machine now on screen, and only those: another machine's alert is not on this screen
     * (see AlertScreen). Switching the watched machine while in front clears the new machine's.
     */
    private fun startAlertClearing() {
        scope.launch {
            combine(triggers.interactive, appLock.locked, machineBook.profile) { front, locked, watched -> if (front && !locked) watched?.id else null }
                .distinctUntilChanged().collect { id -> if (id != null) runCatching { notifier.cancelFor(id) } }
        }
    }

    /**
     * The chips of the machines not watched say how many agents need you (Pro): looked at only while the herd is in front and not covered by the lock.
     * The look is one short session per machine every half minute; the push alerts are what reaches the user in the background.
     */
    private fun startChipProbe() {
        scope.launch {
            combine(triggers.interactive, appLock.locked, machineBook.machines, machineBook.profile, proService.view) { front, covered, all, watched, view ->
                ProbeTargets.of(front, covered, proService.locked(ProCapabilities.HOSTS_MERGED, view), all, watched?.id)
            }.distinctUntilChanged().collectLatest { targets -> if (targets.isNotEmpty()) probe.run(targets) }
        }
    }

    /**
     * Widgets are a Pro capability: when the answer to "does this phone hold Pro" changes they are redrawn. This is only the trigger, by the same rule
     * ([Entitlements.hasPro]): what is drawn is decided by [PaddockWidgets.locked] from the saved file, which is written before this state moves. With no widget placed the redraw does nothing.
     */
    private fun startWidgetRedraw() {
        scope.launch { proService.view.map { proService.hasPro(it) }.distinctUntilChanged().collect { runCatching { PaddockWidgets.updateAll(app) } } }
    }

    /** Reads the settings and the saved machines, then follows the app's visibility: the one place the files are read and a machine first watched. */
    private fun startLoad() {
        scope.launch {
            prefs.load()
            machineBook.load()
            runCatching { push.resume() }
            // The connection is claimed while the app is visible and released when it is not (the owner closes it after its grace).
            // Held while the app is visible and uncovered: behind the lock screen nothing connects, so nothing asks a question (a host key, a key) the user cannot see.
            combine(triggers.foreground, appLock.locked) { visible, locked -> visible && !locked }.distinctUntilChanged()
                .collectLatest { active -> if (active) machineBook.profile.value?.let { hostWatcher.watch(it) } else hostWatcher.pause() }
        }
    }

    /** The one imported-key slot: importing again replaces the key, and profiles that use it keep working. */
    suspend fun importedKey(): ImportedKeyInfo? = withContext(Dispatchers.IO) { importedKeys.info(IMPORTED_KEY_ID) }

    /** Checks and stores a private key. An encrypted key's passphrase is kept because connecting never asks for one. */
    suspend fun importKey(pem: String, passphrase: String): ImportCheck {
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

    /** The saved sagas, for Activity while no host is being monitored (a restart, a lost connection). */
    fun savedSagas(): List<SagaRecord> = runCatching { sagaStore.load().records }.getOrDefault(emptyList())

    /**
     * Sends this phone's own public key to the desktop that showed the pairing code. Only the phone key is ever sent: an imported key
     * can be any type and its public line is not kept. False when the phone has no key yet.
     */
    suspend fun sendPairingKey(host: String, port: Int, sid: String): Boolean {
        // Off the main thread (a Keystore read), and never creating a key as a side effect: the Send offer is drawn under this phone's key, so a missing one is the caller's to say.
        val line = withContext(Dispatchers.Default) { if (phoneKey.exists()) runCatching { phoneKey.publicLine("paddock@phone") }.getOrNull() else null } ?: return false
        val key = AuthorizedKey.parse(line) ?: return false
        pairingCoordinator.start(host, port, sid, line, key.fingerprint)
        return true
    }

    /** "Try again": a setup problem on a healthy connection runs the setup again; anything else refreshes the connection. */
    fun retry() {
        if (hostWatcher.retrySetup()) return
        machineBook.profile.value?.let { owner.refresh(it.id) }
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

const val IMPORTED_KEY_ID = "imported"
