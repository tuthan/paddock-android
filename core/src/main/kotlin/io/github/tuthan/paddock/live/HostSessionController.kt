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
    data class NeedsRelayInstall(val destination: String, val expectedSha256: String, val replacing: Boolean) : HostPhase

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
) {
    private val _phase = MutableStateFlow<HostPhase>(HostPhase.Connecting)
    val phase: StateFlow<HostPhase> = _phase.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var current: MonitoredHost? = null
    private var held: Lease? = null
    private var stopped = false
    /** The lease whose state is followed. It stays set after [pause], so the session closing after the grace is still seen. */
    private val leases = MutableStateFlow<Lease?>(null)
    /** Completed by [installRelay] while the bring-up waits for the user's agreement. */
    private val consent = MutableStateFlow<CompletableDeferred<Unit>?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        job = scope.launch {
            // Same connection, same session object: a resume within the grace re-emits an equal state and changes nothing.
            leases.filterNotNull().flatMapLatest { it.state }.distinctUntilChanged().collectLatest { c ->
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

    /** The user agreed to install the pinned relay shown in [HostPhase.NeedsRelayInstall]. */
    fun installRelay() { consent.value?.complete(Unit) }

    fun stop() {
        synchronized(lock) { stopped = true }
        job?.cancel(); job = null
        stopHost()
        pause()
    }

    /** A non-interactive SSH command often has a short PATH, so the usual install locations are checked by absolute path. */
    private suspend fun discoverHerdr(session: SshSession): String? {
        val script = "for p in \"\$HOME/.local/bin/herdr\" \"\$HOME/.cargo/bin/herdr\" /usr/local/bin/herdr /usr/bin/herdr; do [ -x \"\$p\" ] && { printf %s \"\$p\"; exit 0; }; done; exit 1"
        val r = session.exec(listOf("sh", "-c", script), limits = io.github.tuthan.paddock.ports.ExecLimits(stdoutMax = 4096, stderrMax = 4096))
        if (r.exit != 0) return null
        return r.stdout.toString(Charsets.UTF_8).trim().takeIf { it.startsWith("/") && '\n' !in it }
    }

    private fun stopHost() { synchronized(lock) { current.also { current = null } }?.stop() }

    private suspend fun bringUp(session: SshSession) {
        val installer: RelayInstaller
        val home: String
        try {
            installer = RelayInstaller(session, relayScript, relaySha256)
            home = installer.homeDirectory()
            val state = installer.state(home)
            if (state != RelayState.Current) {
                val asked = CompletableDeferred<Unit>()
                consent.value = asked
                _phase.value = HostPhase.NeedsRelayInstall(installer.destination(home), relaySha256, replacing = state is RelayState.Mismatch)
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
            proceed(session, installer, home)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _phase.value = HostPhase.Problem("Could not start watching the host: ${e.message ?: e.javaClass.simpleName}".take(200))
        }
    }

    private suspend fun proceed(session: SshSession, installer: RelayInstaller, home: String) {
        val path = installer.verifiedPath(home)
        val herdr = herdr ?: discoverHerdr(session)
        if (herdr == null) {
            _phase.value = HostPhase.Problem("herdr was not found on the host (looked in ~/.local/bin, ~/.cargo/bin, /usr/local/bin and /usr/bin).")
            return
        }
        val cli = HerdrCli(herdr, "default")
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
            controlHelper = controlScript?.let { SshControlHelper(RelayInstaller(session, it, controlSha256!!, fileName = "paddock-control.py")) },
            journal = journal,
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
