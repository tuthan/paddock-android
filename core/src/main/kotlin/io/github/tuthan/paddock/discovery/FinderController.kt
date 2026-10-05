package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.ports.LanPaths
import io.github.tuthan.paddock.ports.Sockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FinderPhase { Idle, Scanning, Done, Cancelled }

/**
 * What Find on this network shows. [sentence] is said before a scan and stays above its result; [canStart] is false when the network does
 * not allow a scan (and [needsGrant] says whether the answer is the local-network grant). While scanning, [done] of [total] attempts are made.
 */
data class FinderState(
    val phase: FinderPhase = FinderPhase.Idle,
    val sentence: String = "",
    val canStart: Boolean = false,
    val needsGrant: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val rows: List<FoundHost> = emptyList(),
    /** A note about what was not done (mDNS unavailable), shown with the result; null when everything that was asked ran. */
    val note: String? = null,
)

/**
 * Runs one finder at a time: decides from the networks the phone is on whether a scan is allowed, probes the subnet's addresses for an SSH
 * banner, listens for mDNS announcements in the same time, and merges both into rows. Nothing runs until [start], and [cancel] stops
 * everything. The platform parts are arguments, so the whole thing is tested against loopback.
 */
class FinderController(
    private val scope: CoroutineScope,
    private val paths: LanPaths,
    sockets: Sockets,
    private val grantMissing: () -> Boolean,
    /** Pins TCP probes to the network being scanned (null clears it). */
    private val bindProbeTo: (String?) -> Unit = {},
    private val mdns: (() -> Flow<NsdService>)? = null,
    connectTimeoutMillis: Int = SshProbe.DEFAULT_CONNECT_MILLIS,
    bannerTimeoutMillis: Int = SshProbe.DEFAULT_BANNER_MILLIS,
    /** The ports looked at for a typed one: SSH's own and the typed one. A test narrows it so a real sshd on the machine cannot answer. */
    private val portsFor: (Int?) -> List<Int> = FinderRules::ports,
    /** How long mDNS listening continues after the last address has been tried. */
    private val mdnsGraceMillis: Long = 1_500,
) {
    private val probe = SshProbe(sockets, connectTimeoutMillis, bannerTimeoutMillis)
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state.asStateFlow()
    private var job: Job? = null

    /** The rows of the scan that is running, or null: a result that arrives after its scan was closed or replaced is dropped. */
    @Volatile private var active: FoundHosts? = null

    /** Reads the networks again and says what a scan would do. Does nothing while one runs. */
    fun refresh(typedPort: Int?) {
        if (_state.value.phase == FinderPhase.Scanning) return
        val decision = FinderRules.decide(paths.paths(), grantMissing())
        _state.value = FinderState(
            phase = FinderPhase.Idle,
            sentence = if (decision is FinderDecision.Ready) FinderRules.beforeScan(decision.subnet, portsFor(typedPort)) else FinderRules.sentence(decision),
            canStart = decision is FinderDecision.Ready, needsGrant = decision is FinderDecision.NeedsGrant,
        )
    }

    fun start(typedPort: Int?) {
        if (_state.value.phase == FinderPhase.Scanning) return
        val decision = FinderRules.decide(paths.paths(), grantMissing())
        if (decision !is FinderDecision.Ready) { refresh(typedPort); return }
        val subnet = decision.subnet
        val ports = portsFor(typedPort)
        val sentence = FinderRules.beforeScan(subnet, ports)
        val addresses = subnet.hosts(setOf(subnet.address))
        val found = FoundHosts()
        active = found
        _state.value = FinderState(FinderPhase.Scanning, sentence, canStart = false, total = addresses.size * ports.size)
        bindProbeTo(decision.path.id)
        job = scope.launch {
            var released = false
            // Only the scan that is still the shown one clears the pin: one cut off by close() has been released there already, and a new scan
            // may have pinned the network again since (this job unwinds only once the probes in flight have ended).
            fun release() { if (!released) { released = true; if (active === found) bindProbeTo(null) } }
            val listening = mdns?.let { browse ->
                launch {
                    try {
                        browse().catch { change(found) { s -> s.copy(note = MDNS_UNAVAILABLE) } }.collect { service ->
                            // Anyone on the network can announce anything: only a machine on the scanned subnet, other than the phone, is a row.
                            if (FinderRules.announcedOnNetwork(subnet, service.address)) merge(found) { found.add(service) }
                        }
                    } catch (e: CancellationException) { throw e }
                    // Not only a missing permission: whatever the listener does wrong must end the listening, not the scan or the app.
                    catch (e: Exception) { change(found) { s -> s.copy(note = MDNS_UNAVAILABLE) } }
                }
            }
            try {
                var failed = false
                try {
                    probe.scan(addresses, ports).collect { e ->
                        when (e) {
                            is ProbeEvent.Progress -> change(found) { s -> s.copy(done = e.done, total = e.total) }
                            is ProbeEvent.Hit -> merge(found) { found.add(e.hit) }
                        }
                    }
                    release()
                    // A scan of a quiet network ends in well under a second, before an announcement could arrive: listening goes on a little longer.
                    if (listening != null) delay(mdnsGraceMillis)
                } catch (e: CancellationException) { throw e }
                // This job runs on the app's scope, which has no handler: a bug here must end the scan, not the process.
                catch (e: Exception) { failed = true; release() }
                change(found) { s ->
                    s.copy(phase = FinderPhase.Done, rows = found.list, done = if (failed) s.done else s.total, note = if (failed) SCAN_FAILED else s.note, canStart = true)
                }
            } finally {
                // Cancelled coroutines cannot suspend, so without NonCancellable the join would throw and the network pin would never be released.
                withContext(NonCancellable) { listening?.cancelAndJoin(); release() }
            }
        }
    }

    /** One change to the state, made only while [found] is still the scan that is shown; several threads call this at once. */
    private fun change(found: FoundHosts, to: (FinderState) -> FinderState) { _state.update { if (active === found) to(it) else it } }

    /** Adds to [found] and publishes the list as that add left it, under one lock, so two sources cannot publish each other's rows away. */
    private fun merge(found: FoundHosts, add: () -> Boolean) {
        synchronized(found) { if (add()) change(found) { s -> s.copy(rows = found.list) } }
    }

    /**
     * Stops the scan and the listening, and returns once they have: a probe already connecting or reading is not interruptible, so this
     * waits for it, at most the connect and banner timeouts. What was found so far stays.
     */
    suspend fun cancel() {
        val j = job ?: return
        job = null
        j.cancelAndJoin()
        val s = _state.value
        if (s.phase == FinderPhase.Scanning) _state.value = s.copy(phase = FinderPhase.Cancelled, canStart = true)
    }

    /** For when the screen goes away: stops without waiting. A scan cut off this way is not shown as running the next time the screen opens. */
    fun close() {
        active = null
        job?.cancel(); job = null; bindProbeTo(null)
        if (_state.value.phase == FinderPhase.Scanning) _state.value = FinderState()
    }

    companion object {
        const val SCAN_FAILED = "The scan stopped on an unexpected error, so the list may be incomplete."
        const val MDNS_UNAVAILABLE = "Names announced on the network could not be listened for, so only addresses that answered are shown."
    }
}
