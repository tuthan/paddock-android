package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.ledger.ObservationKind
import io.github.tuthan.paddock.output.AgentOutputReader
import io.github.tuthan.paddock.output.OutputFeed
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Observation
import io.github.tuthan.paddock.reconcile.Reconciler
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.relay.RelayClient
import io.github.tuthan.paddock.identity.TargetRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One host's live monitor: a [SessionMonitor] and [Reconciler] over an established [SshSession], the ledger fed from
 * what the reconciler observes, the home list derived from the installed snapshot, and output feeds for single
 * terminals. It owns no connection: the caller holds the lease and builds a new instance for a new session.
 *
 * Ledger rules kept here: only observations the reconciler made between two reads of one epoch are recorded (so a
 * reconnect never invents a change), `Connected` and `Disconnected` follow freshness going live and going stale, and
 * [markSeen] is local: it writes the ledger and never reaches herdr.
 */
class MonitoredHost(
    private val scope: CoroutineScope,
    val profile: HostProfile,
    val sessionName: String,
    private val session: SshSession,
    relayPath: String,
    socketPath: String,
    private val ledger: Ledger,
    private val clock: Clock,
    foreground: StateFlow<Boolean>,
    herdr: String = "herdr",
    /** Applied to every authoritative read. Only a test uses it, to stand in for a state herdr cannot be told to report. */
    private val transformRead: (io.github.tuthan.paddock.herdr.Snapshot) -> io.github.tuthan.paddock.herdr.Snapshot = { it },
) {
    private val relay = RelayClient(session, relayPath, socketPath)
    private val readSnapshot = SessionMonitor.snapshotReader(relay)
    val monitor = SessionMonitor(
        scope, relay,
        Reconciler(clock, profile.hostId, sessionName, read = { transformRead(readSnapshot()) }),
        foreground, clock,
    )
    val reconciler: Reconciler get() = monitor.reconciler
    val freshness: StateFlow<Freshness> get() = monitor.freshness
    private val cli = HerdrCli(herdr, sessionName)
    private val jobs = ArrayList<Job>()

    /** Bumped by [markSeen] so a home built from the same snapshot is rebuilt with the new fact. */
    private val seenVersion = MutableStateFlow(0)

    /** The home list, null before the first authoritative read. Rebuilt on every installed read and every Done tap. */
    val home: StateFlow<HomeModel?> = combine(reconciler.installed, seenVersion) { installed, _ ->
        installed?.let {
            AttentionModel.home(it.snapshot, it.readAtMillis, profile.hostId, sessionName, it.epoch, seen = ledger.seenLookup(profile.hostId, sessionName, it.epoch))
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun start() {
        monitor.start()
        jobs += scope.launch { reconciler.observations.collect { record(it) } }
        jobs += scope.launch {
            var wasLive = false
            freshness.collect { f ->
                val epoch = reconciler.installed.value?.epoch ?: 0
                if (f == Freshness.Live && !wasLive) { wasLive = true; ledger.observeHost(ObservationKind.Connected, profile.hostId, sessionName, epoch) }
                else if (f == Freshness.Stale && wasLive) { wasLive = false; ledger.observeHost(ObservationKind.Disconnected, profile.hostId, sessionName, epoch) }
            }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear(); monitor.stop() }

    private fun record(o: Observation) {
        when (o) {
            is Observation.PaneAppeared -> ledger.observe(ObservationKind.AgentAppeared, o.key)
            is Observation.PaneVanished -> ledger.observe(ObservationKind.AgentGone, o.key)
            is Observation.StatusChanged -> ledger.observe(ObservationKind.StateChanged, o.key, "${o.from.wire} -> ${o.to.wire}")
            is Observation.TitleChanged -> Unit // a title is content the user wrote or an agent set: not a state fact, never stored
        }
    }

    /** A Done tap. Local only: the ledger marks it seen and the home is rebuilt; nothing is sent to herdr. */
    fun markSeen(row: AgentRowModel) {
        val seq = row.stateChangeSeq ?: return
        ledger.markSeen(row.key, seq)
        seenVersion.value++
    }

    /** A feed for one terminal; the caller starts it, drives visibility and stops it. */
    fun outputFeed(terminalId: String): OutputFeed {
        val reader = AgentOutputReader(session, cli)
        return OutputFeed(scope, TargetRef(profile.hostId, sessionName, terminalId), reconciler.installed, reader::read, clock)
    }
}
