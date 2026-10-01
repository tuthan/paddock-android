package io.github.tuthan.paddock.lifecycle

import io.github.tuthan.paddock.hostkey.HostKeyStoreCorrupt
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SecretCorrupt
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Backoff
import io.github.tuthan.paddock.ssh.ConnectFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** What the owner holds for one host profile. */
sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection

    /** [generation] counts connects for this profile since the owner started holding it: it is the reconnect signal. */
    data class Connected(val session: SshSession, val generation: Long) : Connection

    /** [retryAtMillis] is null when only the user can fix it (a changed key, a refused grant, bad auth, an unreadable key or pin store): no retry runs. */
    data class Failed(val reason: DownReason, val retryAtMillis: Long?) : Connection
}

/** Opens a session for [profile]. The adapter supplies auth, the host-key policy and the first-trust prompt. */
fun interface SessionFactory {
    suspend fun connect(profile: HostProfile): SshSession
}

/** A screen's claim on a host connection. Release it when the screen stops. Releasing twice is harmless. */
interface Lease {
    val state: StateFlow<Connection>
    fun release()
}

/**
 * One [SshSession] per host profile while any screen holds a [Lease] on it. After the last release the session is
 * closed once [graceMillis] passes without a new lease: long enough to ride out rotation and a quick app switch,
 * short enough to meet "disconnect within 10 s of background". A lost link reconnects with backoff; a failure only the
 * user can fix stops and waits for [refresh]. A foreground return calls [refresh]; a default-network change calls
 * [onNetworkChanged], because a socket opened on the old network is dead even when its link still reads Up.
 *
 * Nothing here knows Android: the app layer maps screen visibility to leases and connectivity callbacks to the two calls.
 */
class ConnectionOwner(
    private val scope: CoroutineScope,
    private val factory: SessionFactory,
    private val clock: Clock,
    private val graceMillis: Long = 8_000,
    private val newBackoff: () -> Backoff = { Backoff() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private enum class Kick { Refresh, NetworkChanged }

    private sealed interface Wake {
        data object LinkDown : Wake
        data object Timer : Wake
        data class Kicked(val kick: Kick) : Wake
    }

    private inner class Entry(var profile: HostProfile) {
        var leases = 0
        var loop: Job? = null
        var teardown: Job? = null
        val state = MutableStateFlow<Connection>(Connection.Idle)
        val kicks = Channel<Kick>(Channel.CONFLATED)
        val backoff = newBackoff()
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun acquire(profile: HostProfile): Lease = synchronized(lock) {
        val entry = entries.getOrPut(profile.id) { Entry(profile) }
        entry.profile = profile
        entry.leases++
        entry.teardown?.cancel(); entry.teardown = null
        if (entry.loop == null) entry.loop = scope.launch { run(entry) }
        object : Lease {
            private var released = false
            override val state: StateFlow<Connection> get() = entry.state
            override fun release() = synchronized(lock) {
                if (released) return@synchronized
                released = true
                if (--entry.leases == 0) entry.teardown = scope.launch { sleep(graceMillis); closeIfUnclaimed(entry) }
            }
        }
    }

    /** Foreground return or a user retry: reconnects now when the link is down or failed; leaves a healthy session alone. */
    fun refresh(profileId: String) { synchronized(lock) { entries[profileId] }?.kicks?.trySend(Kick.Refresh) }

    /** The app came to the foreground: every held profile gets the same nudge a user retry would. */
    fun refreshAll() { synchronized(lock) { entries.values.toList() }.forEach { it.kicks.trySend(Kick.Refresh) } }

    /** The default network changed: every held session is replaced, without backoff. */
    fun onNetworkChanged() { synchronized(lock) { entries.values.toList() }.forEach { it.kicks.trySend(Kick.NetworkChanged) } }

    private suspend fun closeIfUnclaimed(entry: Entry) {
        val loop = synchronized(lock) {
            if (entry.leases > 0) return
            entry.teardown = null
            entries.remove(entry.profile.id, entry)
            entry.loop.also { entry.loop = null }
        }
        loop?.cancelAndJoin()
        entry.state.value = Connection.Idle
    }

    private suspend fun run(entry: Entry) {
        var generation = 0L
        while (true) {
            entry.state.value = Connection.Connecting
            val session = try {
                factory.connect(entry.profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                afterFailedConnect(entry, e)
                continue
            }
            entry.state.value = Connection.Connected(session, ++generation)
            entry.backoff.succeeded(clock.nowMillis())
            val kick = try {
                holdUntilLost(entry, session)
            } finally {
                withContext(NonCancellable) { runCatching { session.close() } }
            }
            if (kick == Kick.NetworkChanged) { entry.backoff.reset(); continue }
            val reason = (session.link.value as? LinkState.Down)?.reason ?: DownReason.Network("link lost")
            retryWait(entry, reason)
        }
    }

    /** Returns [Kick.NetworkChanged] when the network moved, or null when the link went down. A refresh keeps holding. */
    private suspend fun holdUntilLost(entry: Entry, session: SshSession): Kick? {
        while (true) {
            when (val wake = awaitWake(entry, linkDown = session)) {
                Wake.LinkDown -> return null
                Wake.Timer -> Unit
                is Wake.Kicked -> if (wake.kick == Kick.NetworkChanged) return Kick.NetworkChanged
            }
        }
    }

    private suspend fun afterFailedConnect(entry: Entry, e: Throwable) {
        val (reason, needsUser) = connectFailureOf(e)
        if (needsUser) {
            entry.state.value = Connection.Failed(reason, null)
            entry.kicks.receive() // no timer: asking the user again on a loop would be worse than waiting
            entry.backoff.reset()
        } else retryWait(entry, reason)
    }

    private suspend fun retryWait(entry: Entry, reason: DownReason) {
        val wait = entry.backoff.nextDelayMillis(clock.nowMillis())
        entry.state.value = Connection.Failed(reason, clock.nowMillis() + wait)
        awaitWake(entry, timerMillis = wait)
    }

    companion object {
        /**
         * What a failed connect means for the loop: the reason shown, and whether only the user can fix it (then no timer
         * runs). Key material or pins that cannot be read are data loss or corruption, never the network, so they stop the
         * loop with their own reason instead of retrying forever under a "cannot reach the host" message.
         */
        fun connectFailureOf(e: Throwable): Pair<DownReason, Boolean> = when (e) {
            is ConnectFailure -> e.reason to (
                e is ConnectFailure.AuthFailed || e is ConnectFailure.HostKeyChanged || e is ConnectFailure.HostKeyDeclined ||
                    e is ConnectFailure.BadKey || e is ConnectFailure.Refused || e is ConnectFailure.KeyUnavailable ||
                    e is ConnectFailure.HostKeysUnreadable
                )
            // Raised by a session factory while it loads key material or pins, before the connector runs.
            is SecretCorrupt -> DownReason.KeyUnavailable to true
            is HostKeyStoreCorrupt -> DownReason.HostKeysUnreadable to true
            else -> DownReason.Network(e.message ?: e.javaClass.simpleName) to false
        }
    }

    /** Waits for the first of: [linkDown] going Down, the timer, or a kick. The other waits are cancelled. */
    private suspend fun awaitWake(entry: Entry, linkDown: SshSession? = null, timerMillis: Long? = null): Wake = coroutineScope {
        val down = linkDown?.let { s -> async { s.link.first { it is LinkState.Down }; Wake.LinkDown } }
        val timer = timerMillis?.let { t -> async { sleep(t); Wake.Timer } }
        try {
            select<Wake> {
                if (down != null) down.onAwait { it }
                if (timer != null) timer.onAwait { it }
                entry.kicks.onReceive { Wake.Kicked(it) }
            }
        } finally {
            down?.cancel(); timer?.cancel()
        }
    }
}
