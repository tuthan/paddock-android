package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.SessionCatalog
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.herdr.decodeResult
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.lifecycle.Lease
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.PluginLocation
import io.github.tuthan.paddock.relay.PluginLocator
import io.github.tuthan.paddock.relay.PluginNotes
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayRefused
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.terminal.SshControlHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** What one host's screen has to show, from "connecting" to a live monitor. */
sealed interface HostPhase {
    data object Connecting : HostPhase

    /** The pinned relay script is not on the host (or a different file is). Nothing is written until the user agrees. */
    data class NeedsRelayInstall(
        val destination: String, val expectedSha256: String, val replacing: Boolean,
        /** Set when the Paddock herdr plugin is installed on the host but carries a relay that is not the pinned one: what was found and how to fix it. */
        val pluginNote: String? = null,
    ) : HostPhase

    data object InstallingRelay : HostPhase

    /** Connected, but herdr could not be used: not on the PATH, no running session, a refused relay. A host fact, not an agent state. */
    data class Problem(val message: String) : HostPhase

    data class Monitoring(val host: MonitoredHost) : HostPhase

    /** The connection is down. [retryAtMillis] is null when only the user can fix it. */
    data class Failed(val reason: DownReason, val retryAtMillis: Long?) : HostPhase
}

/** Which herdr session to monitor: [wanted] by name, else the running default, else the only running one. Null when none qualifies. */
fun chooseSession(catalog: SessionCatalog, wanted: String? = null): SessionEntry? {
    if (wanted != null) return catalog.sessions.firstOrNull { it.name == wanted && it.running }
    val running = catalog.sessions.filter { it.running }
    return running.firstOrNull { it.default } ?: running.singleOrNull()
}

/**
 * Takes one host from a lease to a live [MonitoredHost]: waits for the connection, checks the relay on the host, asks
 * (through [phase]) before installing it, picks the herdr session, and starts monitoring. When the connection is replaced
 * or lost it stops the old monitor; the next connection starts a fresh one. It reads and writes nothing about agents.
 *
 * The controller lives as long as the machine is watched, not as long as a screen is visible: [pause] releases the
 * claim on the connection when the app is hidden and [resume] claims it again. A quick return finds the owner still
 * holding the same session, so the monitor, the home and any open output screen carry on untouched; after the owner's
 * grace the session closes, the monitor stops, and the next [resume] connects again.
 */
class HostSessionController(
    private val scope: CoroutineScope,
    val profile: HostProfile,
    /** Claims the profile's connection; called on [start] and on every [resume]. */
    private val acquire: () -> Lease,
    private val ledger: Ledger,
    private val clock: Clock,
    private val foreground: StateFlow<Boolean>,
    private val relayScript: ByteArray,
    private val relaySha256: String,
    private val sessionName: String? = null,
    /** Absolute path of herdr on the host, or null to look in the usual places. */
    private val herdr: String? = null,
    /** The pinned `paddock-control.py` and its hash; null leaves terminals observe-only. Installed only when the user asks for control. */
    private val controlScript: ByteArray? = null,
    private val controlSha256: String? = null,
    /** The phone's operation journal; without one the host is read-only (no prompt, key or focus operations). */
    private val journal: OperationJournal? = null,
    /** The pinned `paddock-alert-relay.py` and its hash (Phase 07); installed only when the user asks on the alert relay screen. */
    private val alertScript: ByteArray? = null,
    private val alertSha256: String? = null,
    /** The pinned `paddock-decide.py`, `paddock-claude-permission-hook.py` and (beside them) the opencode plugin with their hashes (Phase 08); installed only when the user asks on the answers screen. */
    private val decideScript: ByteArray? = null,
    private val decideSha256: String? = null,
    private val hookScript: ByteArray? = null,
    private val hookSha256: String? = null,
    /** The pinned opencode plugin (the answers screen installs it beside the hook); null on a build that ships none. */
    private val opencodeScript: ByteArray? = null,
    private val opencodeSha256: String? = null,
    /** Where start-agent sagas are kept (Phase 09); null leaves the monitored host without the start-agent flow. */
    private val sagaStore: io.github.tuthan.paddock.ops.SagaStore? = null,
    /**
     * Reads which network interface this phone reached the machine on and whether it can be woken (Phase 14); null skips it. Run once
     * per bring-up, after the relay check and before the first read, over the connection already open, so a slow host delays the first
     * read by up to [wakeCaptureTimeoutMillis]. Best effort: whatever it does (fail, hang, answer badly) never changes the phase.
     */
    private val wakeCapture: (suspend (SshSession) -> io.github.tuthan.paddock.wake.WakeReading)? = null,
    private val wakeCaptureTimeoutMillis: Long = 5_000,
    /**
     * Asks the machine which OS it is, for the icon on its card (`uname -s`); null skips it. Run once per bring-up beside the first read, not
     * before it, so it never delays the herd. Best effort: a machine that cannot answer is "not known", never a failed connection.
     */
    private val osProbe: (suspend (SshSession) -> io.github.tuthan.paddock.hostprofile.HostOs?)? = null,
    private val osProbeTimeoutMillis: Long = 5_000,
) {
    private val _os = MutableStateFlow<io.github.tuthan.paddock.hostprofile.HostOs?>(null)
    /** What the last bring-up learned about the machine's OS; null until a probe has answered (a machine that cannot tell leaves it null). */
    val os: StateFlow<io.github.tuthan.paddock.hostprofile.HostOs?> = _os.asStateFlow()

    private val _wake = MutableStateFlow<io.github.tuthan.paddock.wake.WakeReading?>(null)
    /** What the last bring-up read about waking this machine; null until a capture has finished (a capture cut off at the timeout leaves it as it was). */
    val wake: StateFlow<io.github.tuthan.paddock.wake.WakeReading?> = _wake.asStateFlow()

    private val _phase = MutableStateFlow<HostPhase>(HostPhase.Connecting)
    val phase: StateFlow<HostPhase> = _phase.asStateFlow()

    private val _spaces = MutableStateFlow<HostSpaces?>(null)
    /**
     * This host's sessions for the Spaces screen (Phase 09), available as soon as herdr is found on the connection, whether or not a
     * session could be chosen to monitor: a stopped session can still be read and deleted. Null while the connection is not up.
     */
    val spaces: StateFlow<HostSpaces?> = _spaces.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var current: MonitoredHost? = null
    private var held: Lease? = null
    private var stopped = false
    /** The lease whose state is followed. It stays set after [pause], so the session closing after the grace is still seen. */
    private val leases = MutableStateFlow<Lease?>(null)
    /** Completed by [installRelay] while the bring-up waits for the user's agreement. */
    private val consent = MutableStateFlow<CompletableDeferred<Unit>?>(null)
    /** Counts the user's "Try again" on a setup problem: each step runs the bring-up once more on the connection already held. */
    private val setupTries = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        job = scope.launch {
            // Same connection, same session object: a resume within the grace re-emits an equal state and changes nothing.
            leases.filterNotNull().flatMapLatest { it.state }.distinctUntilChanged().combine(setupTries) { c, _ -> c }.collectLatest { c ->
                stopHost()
                when (c) {
                    Connection.Idle, Connection.Connecting -> _phase.value = HostPhase.Connecting
                    is Connection.Failed -> _phase.value = HostPhase.Failed(c.reason, c.retryAtMillis)
                    // Runs inside collectLatest: a replaced or lost connection cancels a bring-up, including one waiting for consent.
                    is Connection.Connected -> bringUp(c.session)
                }
            }
        }
        resume()
    }

    /** The app is visible: claim the connection again. Harmless when already claimed. */
    fun resume() = synchronized(lock) {
        if (stopped || held != null) return@synchronized
        val lease = acquire()
        held = lease
        leases.value = lease
    }

    /** The app is hidden: release the claim. The owner closes the session after its grace; the monitor stops when it does. */
    fun pause() = synchronized(lock) { held?.release(); held = null }

    /**
     * "Try again" on a [HostPhase.Problem]: herdr missing or stopped, no session, a relay that did not take. The connection is
     * healthy, so refreshing it (which leaves a healthy connection alone) would never run the setup again; this does. False,
     * and nothing changes, in any other phase.
     */
    fun retrySetup(): Boolean {
        if (_phase.value !is HostPhase.Problem) return false
        _phase.value = HostPhase.Connecting
        setupTries.update { it + 1 }
        return true
    }

    /** The user agreed to install the pinned relay shown in [HostPhase.NeedsRelayInstall]. */
    fun installRelay() { consent.value?.complete(Unit) }

    fun stop() {
        synchronized(lock) { stopped = true }
        job?.cancel(); job = null
        stopHost()
        pause()
    }

    private suspend fun discoverHerdr(session: SshSession): String? = io.github.tuthan.paddock.cli.HerdrLocator.find(session)

    private fun stopHost() { _spaces.value = null; synchronized(lock) { current.also { current = null } }?.stop() }

    private suspend fun bringUp(session: SshSession) {
        val installer: RelayInstaller
        val home: String
        val herdrPath: String?
        val plugin: PluginLocation?
        try {
            herdrPath = herdr ?: discoverHerdr(session)
            plugin = herdrPath?.let { PluginLocator.find(session, it) }
            installer = RelayInstaller(session, relayScript, relaySha256, plugin = plugin)
            home = installer.homeDirectory()
            val state = installer.state(home)
            if (state != RelayState.Current) {
                val asked = CompletableDeferred<Unit>()
                consent.value = asked
                _phase.value = HostPhase.NeedsRelayInstall(
                    installer.destination(home), relaySha256, replacing = state is RelayState.Mismatch,
                    pluginNote = installer.pluginMismatch?.let { PluginNotes.mismatch(it) },
                )
                try { asked.await() } finally { consent.compareAndSet(asked, null) }
                _phase.value = HostPhase.InstallingRelay
                try {
                    installer.install(home)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RelayRefused) {
                    _phase.value = HostPhase.Problem("The relay on the host did not match after installing. Nothing was started.")
                    return
                } catch (e: Throwable) {
                    _phase.value = HostPhase.Problem("Could not install the relay: ${e.message ?: e.javaClass.simpleName}".take(200))
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _phase.value = HostPhase.Problem("Could not check the host: ${e.message ?: e.javaClass.simpleName}".take(200))
            return
        }
        try {
            proceed(session, installer, home, herdrPath, plugin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _phase.value = HostPhase.Problem("Could not start watching the host: ${e.message ?: e.javaClass.simpleName}".take(200))
        }
    }

    private suspend fun captureWake(session: SshSession) {
        val capture = wakeCapture ?: return
        try {
            kotlinx.coroutines.withTimeoutOrNull(wakeCaptureTimeoutMillis) { capture(session) }?.let { _wake.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Waking is a convenience: a failed read is "not read yet", never a failed connection.
        }
    }

    private fun probeOs(session: SshSession) {
        val probe = osProbe ?: return
        scope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(osProbeTimeoutMillis) { probe(session) }?.let { _os.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // An icon is a convenience: a failed probe is "not known", never a failed connection.
            }
        }
    }

    private suspend fun proceed(session: SshSession, installer: RelayInstaller, home: String, herdr: String?, plugin: PluginLocation?) {
        val path = installer.verifiedPath(home)
        probeOs(session)
        captureWake(session)
        if (herdr == null) {
            _phase.value = HostPhase.Problem(io.github.tuthan.paddock.cli.HerdrLocator.notFoundMessage())
            return
        }
        val cli = HerdrCli(herdr, "default")
        journal?.let { _spaces.value = HostSpaces(session, herdr, it, profile.hostId, clock, epoch = { current?.reconciler?.installed?.value?.epoch ?: 0L }) }
        val outcome = CliResult.classify(session.exec(cli.sessionList()))
        val catalog = (outcome as? CliOutcome.Ok)?.let { PaddockJson.decodeResult<SessionCatalog>(it.stdout).getOrNull() }
        if (catalog == null) {
            _phase.value = HostPhase.Problem("herdr did not answer on the host. Is it installed and on the PATH for SSH commands?")
            return
        }
        val chosen = chooseSession(catalog, sessionName)
        if (chosen == null) {
            _phase.value = HostPhase.Problem(noSessionMessage(catalog))
            return
        }
        // The relay is checked again before each reconnect of this monitor, not only now.
        val host = MonitoredHost(
            scope, profile, chosen.name, session, path, chosen.socketPath, ledger, clock, foreground, herdr,
            beforeReconnect = { installer.verifiedPath(home) },
            controlHelper = controlScript?.let { SshControlHelper(RelayInstaller(session, it, controlSha256!!, fileName = "paddock-control.py", plugin = plugin)) },
            journal = journal,
            alertRelay = alertScript?.let { io.github.tuthan.paddock.alerts.AlertRelayHost(session, RelayInstaller(session, it, alertSha256!!, fileName = "paddock-alert-relay.py")) },
            answerHost = if (decideScript != null && hookScript != null) io.github.tuthan.paddock.answers.AnswerHost(
                session, RelayInstaller(session, decideScript, decideSha256!!, fileName = "paddock-decide.py"),
                RelayInstaller(session, hookScript, hookSha256!!, fileName = "paddock-claude-permission-hook.py"), clock,
                herdrSession = chosen.name, phoneLabel = profile.name.take(64),
                plugin = opencodeScript?.let { RelayInstaller(session, it, opencodeSha256!!, fileName = "paddock-opencode-permission.js") },
            ) else null,
            sagaStore = sagaStore,
        )
        synchronized(lock) {
            if (stopped) return
            current = host
            host.start()
        }
        _phase.value = HostPhase.Monitoring(host)
    }

    private fun noSessionMessage(catalog: SessionCatalog): String {
        val running = catalog.sessions.filter { it.running }.map { it.name }
        return when {
            sessionName != null -> "herdr session \"$sessionName\" is not running on the host."
            running.size > 1 -> "${running.size} herdr sessions are running (${running.take(4).joinToString(", ")}) and none is the default. Choose the session to watch for this machine."
            else -> "No herdr session is running on the host."
        }
    }
}
