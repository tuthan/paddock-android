package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.HostProfileStore
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.lifecycle.Lease
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What keeps the home-screen widgets' cache (Phase 10): written by the open app as its herd changes ([track]), and by the periodic job's short read
 * ([refresh]). The cache is only ever a copy of the herd list the user is looking at. [locked] is the widgets' own answer (Pro, from the saved file),
 * [redraw] repaints the placed widgets, [acquire] claims a connection to a machine and [removing] says a machine is being removed.
 */
class WidgetService(
    private val store: WidgetCacheStore,
    /** One short read of a machine over a session already open ([WidgetRefresher.refresh]); it writes the cache itself. */
    private val read: suspend (HostProfile, SshSession, String) -> WidgetRefresher.Outcome,
    private val clock: Clock,
    private val acquire: (HostProfile) -> Lease,
    private val profiles: HostProfileStore,
    private val watchedProfileId: suspend () -> String?,
    private val inForeground: () -> Boolean,
    private val removing: (String) -> Boolean,
    private val locked: () -> Boolean,
    private val redraw: () -> Unit,
    private val connectMillis: Long = CONNECT_MILLIS,
    /** The name a machine has now (by profile id), or null when there is no such machine: the cache's label follows a default name that took the hostname without a reconnect. */
    private val machineName: (String) -> String? = { null },
) {
    @Volatile private var last: WidgetCache? = null

    /**
     * Keeps the widgets' cache as fresh as the open app's own herd: written when what the widgets show changes, and at most once a minute
     * otherwise (so "as of" moves while nothing else does). The cache is only ever a copy of the herd list the user is looking at.
     */
    suspend fun track(host: MonitoredHost) =
        track(host.home, host.profile.id, host.profile.name, host.sessionName) { host.reconciler.installed.value?.readAtMillis }

    internal suspend fun track(home: Flow<HomeModel?>, profileId: String, machine: String, session: String, readAtMillis: () -> Long?) {
        home.collect { current ->
            if (current == null) return@collect
            val readAt = readAtMillis() ?: clock.nowMillis()
            val next = WidgetCacheBuilder.from(current, profileId, machineName(profileId) ?: machine, session, readAt)
            val prev = last
            if (prev != null && prev.copy(readAtMillis = 0) == next.copy(readAtMillis = 0) && next.readAtMillis - prev.readAtMillis < TOUCH_MILLIS) return@collect
            last = next
            if (runCatching { store.save(next) }.isSuccess) runCatching { redraw() }
        }
    }

    /** The watched machine is no longer tracked (stopped, removed or switched): the next herd read writes the cache again. */
    fun stopTracking() { last = null }

    /** A removed machine's herd must not stay on a widget: its copy goes, and the widgets are redrawn. */
    suspend fun forget(profileId: String) {
        withContext(Dispatchers.IO) {
            if (store.load()?.hostId == profileId) { store.clear(); runCatching { redraw() } }
        }
    }

    /**
     * The widgets' background read, run by the periodic job: one short SSH read of the machine the cache already knows, on a connection this
     * claims and releases. It does nothing while the app is in front (its own read keeps the cache), nothing for a machine the phone has not
     * read before (so it can never raise a first-trust prompt for a key nobody has seen), and nothing with a cache that is wrong when the
     * read fails: the old cache stays and its time says so. Returns a line for the log.
     */
    suspend fun refresh(): String {
        // A locked widget draws nothing from the cache (vault M8), so a background SSH read for it would cost battery and network for no one.
        // The widgets' own answer (the saved file, by Entitlements.hasPro with the flavor's constants), not the view: a job that starts a cold process runs before start() has loaded the view, and a Pro holder's widget must not go stale for that.
        if (locked()) return "skipped: widgets are Pro and this phone does not hold it"
        if (inForeground()) return "skipped: the app is in front and keeps the cache itself"
        val all = runCatching { profiles.list() }.getOrDefault(emptyList())
        val watched = runCatching { watchedProfileId() }.getOrNull()
        val profile = all.firstOrNull { it.id == watched } ?: all.firstOrNull() ?: return "skipped: no machine"
        val cache = store.load()?.takeIf { it.hostId == profile.id } ?: return "skipped: nothing has been read from ${profile.name} yet"
        val lease = acquire(profile)
        try {
            val state = withTimeoutOrNull(connectMillis) { lease.state.first { it is Connection.Connected || it is Connection.Failed } }
            val connected = state as? Connection.Connected ?: return "skipped: not connected (${state ?: "timed out"})"
            val out = read(profile, connected.session, cache.session)
            // The machine may have been removed while this job read it (it saves the cache itself): what it saved is forgotten again, so a removed machine's herd never stays on a widget.
            if (removing(profile.id) || profiles.get(profile.id) == null) {
                runCatching { store.clear(); redraw() }
                return "skipped: ${profile.name} was removed meanwhile"
            }
            return when (out) {
                is WidgetRefresher.Outcome.Updated -> { redraw(); "updated: ${out.cache.counts}" }
                is WidgetRefresher.Outcome.Unchanged -> "unchanged: ${out.reason}"
            }
        } finally { lease.release() }
    }

    companion object {
        /** The widget cache is rewritten when nothing changed only if its read is this much newer. */
        const val TOUCH_MILLIS = 60_000L
        /** How long the widgets' background read waits for a connection before it gives up until the next period. */
        const val CONNECT_MILLIS = 25_000L
    }
}
