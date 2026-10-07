package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.AgentRowModel
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.ledger.ObservationKind
import io.github.tuthan.paddock.cli.ReadSource
import io.github.tuthan.paddock.output.Ansi
import io.github.tuthan.paddock.output.AgentOutputReader
import io.github.tuthan.paddock.output.OutputRead
import io.github.tuthan.paddock.output.OutputFeed
import io.github.tuthan.paddock.ops.AgentOperations
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationRecord
import io.github.tuthan.paddock.ops.SendController
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Observation
import io.github.tuthan.paddock.reconcile.Reconciler
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.relay.RelayClient
import io.github.tuthan.paddock.terminal.ControlHelperPort
import io.github.tuthan.paddock.terminal.TerminalOptions
import io.github.tuthan.paddock.terminal.TerminalSession
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.EpochTracker
import io.github.tuthan.paddock.attention.ObservedAt
import io.github.tuthan.paddock.herdr.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One host's live monitor: a [SessionMonitor] and [Reconciler] over an established [SshSession], the ledger fed from
 * what the reconciler observes, the home list derived from the installed snapshot, and output feeds for single
 * terminals. It owns no connection: the caller holds the lease and builds a new instance for a new session.
 *
 * Ledger rules kept here: only observations the reconciler made between two reads of one epoch are recorded (so a
 * reconnect never invents a change), `Connected` and `Disconnected` follow freshness going live and going stale (and
 * [stop] records the Disconnected for a live monitor, so time the phone was not watching always shows as a gap), and
 * [markSeen] is local: it writes the ledger and never reaches herdr. Epochs come from the ledger, so they never repeat
 * across connections or app restarts; an acknowledged Done carries into a new epoch only when its first read still
 * shows that terminal Done at the same `state_change_seq`.
 */
class MonitoredHost(
    private val scope: CoroutineScope,
    val profile: HostProfile,
    val sessionName: String,
    private val session: SshSession,
    relayPath: String,
    val socketPath: String,
    private val ledger: Ledger,
    private val clock: Clock,
    foreground: StateFlow<Boolean>,
    herdr: String,
    /** Applied to every authoritative read. Only a test uses it, to stand in for a state herdr cannot be told to report. */
    private val transformRead: (io.github.tuthan.paddock.herdr.Snapshot) -> io.github.tuthan.paddock.herdr.Snapshot = { it },
    /** Runs before each reconnect of the monitor; the controller re-verifies the relay on the host here. */
    beforeReconnect: suspend () -> Unit = {},
    /** The host's pinned control helper; null on a build that has none, which leaves terminals observe-only. */
    private val controlHelper: ControlHelperPort? = null,
    private val terminalOptions: TerminalOptions = TerminalOptions(),
    /** The phone's operation journal; null leaves this host read-only (no prompt, key or focus operations). */
    journal: OperationJournal? = null,
    /** The alert relay's installer and checker on this host (Phase 07); null on a build that ships none. */
    val alertRelay: io.github.tuthan.paddock.alerts.AlertRelayHost? = null,
    /** The permission-request writer and hook on this host (Phase 08); null on a build that ships neither, which leaves Claude Code prompts to the terminal. */
    val answerHost: io.github.tuthan.paddock.answers.AnswerHost? = null,
    /** Where start-agent sagas are kept (Phase 09); null leaves the host without the start-agent flow. */
    sagaStore: io.github.tuthan.paddock.ops.SagaStore? = null,
) {
    private val relay = RelayClient(session, relayPath, socketPath)
    private val readSnapshot = SessionMonitor.snapshotReader(relay)
    val monitor = SessionMonitor(
        scope, relay,
        Reconciler(
            clock, profile.hostId, sessionName, read = { transformRead(readSnapshot()) },
            epochs = EpochTracker { ledger.allocateEpoch(profile.hostId, sessionName) },
        ),
        foreground, clock, beforeReconnect = beforeReconnect,
    )
    val reconciler: Reconciler get() = monitor.reconciler

    /** Prompt, Esc, Ctrl+C and desktop focus for this host's agents, journaled; null when no journal was given. */
    val operations: AgentOperations? = journal?.let { AgentOperations(relay, it, { reconciler.installed.value }, clock) }

    /** Workspace and tab focus (Phase 09), journaled; null when no journal was given. */
    val spaceOps: io.github.tuthan.paddock.ops.SpaceOperations? = journal?.let { io.github.tuthan.paddock.ops.SpaceOperations(relay, it, { reconciler.installed.value }, profile.hostId, sessionName) }

    /**
     * The start-agent saga (Phase 09): a place, a check, `agent start`, an optional first prompt, each step journaled with its ids
     * kept on disk. Its first prompt goes through [operations], after the new agent shows in the installed read. Null without a
     * journal or a saga store.
     */
    val saga: io.github.tuthan.paddock.ops.StartAgentSaga? = if (journal != null && sagaStore != null) io.github.tuthan.paddock.ops.StartAgentSaga(
        io.github.tuthan.paddock.ops.RelaySagaHost(relay, session, HerdrCli(herdr, sessionName)), { terminalId, text -> promptNewAgent(terminalId, text) }, journal, sagaStore, clock,
        epoch = { reconciler.installed.value?.epoch ?: 0L },
    ) else null

    /** Waits (a few seconds) for the herd to list a just-started agent, then sends the first prompt by the Phase 06 path, readiness check included. */
    private suspend fun promptNewAgent(terminalId: String, text: String): io.github.tuthan.paddock.ops.PromptStep {
        val ops = operations ?: return io.github.tuthan.paddock.ops.PromptStep.Held("This host cannot send prompts. Nothing was sent.")
        var installed = reconciler.installed.value
        var waited = 0
        while (installed?.snapshot?.agents?.any { it.terminalId == terminalId } != true && waited < 15_000) {
            if (waited % 3_000 == 0) refresh()
            kotlinx.coroutines.delay(500); waited += 500
            installed = reconciler.installed.value
        }
        val i = installed?.snapshot?.agents?.any { it.terminalId == terminalId } == true
        if (!i) return io.github.tuthan.paddock.ops.PromptStep.Held("The new agent did not show in the herd within 15 seconds, so the first prompt was not sent.")
        val key = io.github.tuthan.paddock.identity.TerminalKey(io.github.tuthan.paddock.identity.TargetRef(profile.hostId, sessionName, terminalId), installed!!.epoch)
        val result = ops.prompt(key, text)
        return if (result is io.github.tuthan.paddock.ops.OperationResult.Acknowledged<*>) io.github.tuthan.paddock.ops.PromptStep.Sent
        else io.github.tuthan.paddock.ops.PromptStep.Held(io.github.tuthan.paddock.ops.OperationPresenter().line(io.github.tuthan.paddock.ops.OperationKind.Prompt, result).text)
    }

    /** Runs those operations in this host's scope and keeps the newest outcome per terminal; null with [operations]. */
    val sends: SendController? = operations?.let { SendController(scope, it) }

    /**
     * Yes and No for Claude Code permission requests the hook published, journaled; null without a journal or without the host scripts.
     * It reads and writes request files through [answerHost] and holds no herdr client, so it cannot send a key.
     */
    val answers: io.github.tuthan.paddock.answers.AnswerController? = if (journal != null && answerHost != null) io.github.tuthan.paddock.answers.AnswerController(
        scope, answerHost, journal, { reconciler.installed.value },
        { id -> reconciler.installed.value?.snapshot?.agents?.firstOrNull { it.terminalId == id }?.agentStatus }, clock,
        agentKind = { id -> reconciler.installed.value?.snapshot?.agents?.firstOrNull { it.terminalId == id }?.agent },
    ) else null

    /** Every row of the operation journal, oldest first: the composer's gate and Activity read it. Empty without a journal. */
    val operationRecords: StateFlow<List<OperationRecord>> = journal?.records ?: MutableStateFlow(emptyList<OperationRecord>()).asStateFlow()

    /** Why the journal's saved rows cannot be read (every operation is off then), or null. Always null without a journal. */
    val journalUnreadable: StateFlow<String?> = journal?.unreadable ?: MutableStateFlow<String?>(null).asStateFlow()
    val freshness: StateFlow<Freshness> get() = monitor.freshness
    /** Why the monitor last went stale (a dropped stream, a failed or unreadable read), for the host's banner. */
    val lastLoss: StateFlow<Throwable?> get() = monitor.lastLoss

    /** The user asked to read the herd again (pull to refresh): one more authoritative read, single-flight as always. */
    fun refresh() = reconciler.invalidate("user")
    private val cli = HerdrCli(herdr, sessionName)
    private val jobs = ArrayList<Job>()
    private val live = MutableStateFlow(false)
    /** Whether a Connected was recorded without its Disconnected yet; flipped atomically so stop and the collector never both record. */
    private val wasLive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Bumped by [markSeen] so a home built from the same snapshot is rebuilt with the new fact. */
    private val seenVersion = MutableStateFlow(0)

    private data class FirstSeen(val status: AgentStatus, val seq: Long?, val at: Long, val epoch: Long)

    /** When the phone first saw each terminal in its current state. */
    private val firstSeen = HashMap<String, FirstSeen>()

    /**
     * Parent of the eagerly shared [home]. [stop] cancels it: `stateIn` in the app's own scope would keep its collector, this
     * host, its last snapshot and its session alive for as long as the app runs, one more for every replacement host.
     */
    private val sharing = SupervisorJob(scope.coroutineContext[Job])

    /** The home list, null before the first authoritative read. Rebuilt on every installed read and every Done tap. */
    val home: StateFlow<HomeModel?> = combine(reconciler.installed, seenVersion) { installed, _ ->
        installed?.let {
            val doneSeqs = it.snapshot.agents.filter { a -> a.agentStatus == AgentStatus.Done && a.stateChangeSeq != null }.associate { a -> a.terminalId to a.stateChangeSeq!! }
            ledger.onInstalled(profile.hostId, sessionName, it.epoch, doneSeqs)
            val observed = observedAt(it)
            AttentionModel.home(it.snapshot, it.readAtMillis, profile.hostId, sessionName, it.epoch, observedAt = observed, seen = ledger.seenLookup(profile.hostId, sessionName, it.epoch))
        }
    }.stateIn(CoroutineScope(scope.coroutineContext + sharing), SharingStarted.Eagerly, null)

    /**
     * "Observed N ago" is the time since the phone first saw the state, never a server duration. A state seen in an
     * earlier read keeps its time while herdr's `state_change_seq` for that terminal is unchanged (also across a
     * reconnect: the seq is herdr's own count of state changes); a new state or a new seq starts the clock at this read.
     */
    private fun observedAt(i: io.github.tuthan.paddock.reconcile.Installed): ObservedAt = synchronized(firstSeen) {
        val current = i.snapshot.agents.associateBy { it.terminalId }
        firstSeen.keys.retainAll(current.keys)
        for ((id, a) in current) {
            val prior = firstSeen[id]
            // Without a seq, a state is only known unchanged within one epoch: it may have changed and back while disconnected.
            val unchanged = prior != null && prior.status == a.agentStatus && prior.seq == a.stateChangeSeq && (a.stateChangeSeq != null || prior.epoch == i.epoch)
            if (!unchanged) firstSeen[id] = FirstSeen(a.agentStatus, a.stateChangeSeq, i.readAtMillis, i.epoch)
        }
        val copy = firstSeen.mapValues { it.value.at }
        ObservedAt { copy[it] }
    }

    private val _blockedPreview = MutableStateFlow<BlockedPreview?>(null)

    /**
     * The captured prompt of the first blocked agent in the home order, read once when that agent (or its state change)
     * first appears and again on [refreshPreview]. Null when nothing is blocked. Never polled and never stored.
     */
    val blockedPreview: StateFlow<BlockedPreview?> = _blockedPreview.asStateFlow()

    private val promptReader by lazy { AgentOutputReader(session, cli, lines = 12, source = ReadSource.Detection) }
    @Volatile private var previewJob: Job? = null
    /** The agent the preview is for now; a read that finishes for another one is dropped. */
    @Volatile private var previewKey: Key? = null

    private data class Key(val terminalId: String, val seq: Long?)

    fun start() {
        monitor.start()
        jobs += scope.launch {
            home.map { h -> h?.rows?.firstOrNull { it.state == io.github.tuthan.paddock.attention.StateWord.Blocked }?.let { Key(it.key.target.terminalId, it.stateChangeSeq) } }
                .distinctUntilChanged()
                .collectLatest { key -> previewKey = key; if (key == null) _blockedPreview.value = null else loadPreview(key) }
        }
        jobs += scope.launch { reconciler.observations.collect { record(it) } }
        jobs += scope.launch {
            freshness.collect { f ->
                live.value = f == Freshness.Live
                val epoch = reconciler.installed.value?.epoch ?: 0
                if (f == Freshness.Live && wasLive.compareAndSet(false, true)) ledger.observeHost(ObservationKind.Connected, profile.hostId, sessionName, epoch)
                else if (f == Freshness.Stale && wasLive.compareAndSet(true, false)) ledger.observeHost(ObservationKind.Disconnected, profile.hostId, sessionName, epoch)
            }
        }
    }

    /** Stops monitoring. A monitor that was live records the Disconnected that its stale transition would have. */
    fun stop() {
        closeTerminal()
        jobs.forEach { it.cancel() }; jobs.clear()
        previewJob?.cancel()
        sharing.cancel()
        monitor.stop()
        live.value = false
        if (wasLive.compareAndSet(true, false)) ledger.observeHost(ObservationKind.Disconnected, profile.hostId, sessionName, reconciler.installed.value?.epoch ?: 0)
    }

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

    private suspend fun loadPreview(key: Key) {
        if (previewKey == key) _blockedPreview.value = BlockedPreview(key.terminalId, key.seq, PreviewState.Loading)
        val paneId = reconciler.installed.value?.snapshot?.panes?.firstOrNull { it.terminalId == key.terminalId }?.paneId
        val state = if (paneId == null) PreviewState.Unavailable else try {
            when (val r = promptReader.read(paneId)) {
                // The CLI answer carries no id: keep it only if the pane still holds this terminal after the read.
                is OutputRead.Text -> if (paneOf(key.terminalId) != paneId) PreviewState.Unavailable
                    else PreviewState.Showing(PreviewText.trim(Ansi.parse(r.text, maxLines = 40)), clock.nowMillis())
                else -> PreviewState.Unavailable
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { PreviewState.Unavailable }
        if (previewKey == key) _blockedPreview.value = BlockedPreview(key.terminalId, key.seq, state)
    }

    private fun paneOf(terminalId: String) = reconciler.installed.value?.snapshot?.panes?.firstOrNull { it.terminalId == terminalId }?.paneId

    /** Reads the prompt again for the current first blocked agent, for a Review prompt tap. */
    fun refreshPreview() {
        val key = previewKey ?: return
        previewJob?.cancel()
        previewJob = scope.launch { loadPreview(key) }
    }

    /** A feed for one terminal; the caller starts it, drives visibility and stops it. */
    fun outputFeed(terminalId: String): OutputFeed {
        val reader = AgentOutputReader(session, cli)
        return OutputFeed(scope, TargetRef(profile.hostId, sessionName, terminalId), reconciler.installed, reader::read, clock, live = live)
    }

    private val terminalLock = Any()
    private var terminal: TerminalSession? = null

    /**
     * The one terminal session of this host. A host has at most one terminal channel open: asking for another closes the
     * first (which releases control if it held it), so two screens can never both write to a terminal. The pane is looked
     * up by terminal id each time a channel opens, so a pane id that moved is followed and a closed pane is reported.
     */
    fun terminalSession(terminalId: String): TerminalSession = synchronized(terminalLock) {
        terminal?.close()
        TerminalSession(scope, session, cli, { paneOf(terminalId) }, controlHelper, clock, options = terminalOptions).also { terminal = it }
    }

    /** Closes the terminal session, if any, when its screen is done with it. */
    fun closeTerminal(which: TerminalSession? = null) = synchronized(terminalLock) {
        val t = terminal ?: return@synchronized
        if (which != null && t !== which) { which.close(); return@synchronized }
        t.close(); terminal = null
    }
}
