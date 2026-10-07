package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.hostprofile.HostProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one host the phone watches: the [HostSessionController] for it, and everything that follows that controller ([follows]: alerts, the widgets' cache,
 * what a bring-up read about waking and the OS, a pairing request). One controller lives while its machine is watched: paused when the app is hidden,
 * resumed when it returns, replaced only for another machine or for a change that needs a new connection ([HostProfile.sameConnectionAs]).
 *
 * [create] builds the controller for a profile, [attach] and [detach] hand it to the screens' model, [becameWatched] says a machine other than the
 * previous one is watched now, and [stopped] says the watch ended (so what was kept about the watched machine can go).
 */
class HostWatcher(
    private val scope: CoroutineScope,
    private val isRemoving: (String) -> Boolean,
    private val create: (HostProfile) -> HostSessionController,
    private val attach: (HostSessionController) -> Unit,
    private val detach: () -> Unit,
    private val becameWatched: (String) -> Unit,
    private val stopped: () -> Unit,
    private val follows: List<suspend (HostSessionController) -> Unit>,
) {
    private val lock = Any()
    @Volatile private var controller: HostSessionController? = null
    private var jobs: List<Job> = emptyList()

    /** The controller watching now, or null. */
    val current: HostSessionController? get() = controller

    /** Resumes the controller already watching [profile]; for any other profile, stops it and starts a new one. A machine being removed is never watched. */
    fun watch(profile: HostProfile) {
        synchronized(lock) {
            if (isRemoving(profile.id)) return
            val c = controller
            if (c != null && c.profile.sameConnectionAs(profile)) { c.resume(); return }
            c?.stop()
            // The watched tap's facts were about the machine before; this machine's send-only facts said it was not watched.
            if (c?.profile?.id != profile.id) becameWatched(profile.id)
            val next = create(profile)
            controller = next
            attach(next)
            next.start()
            jobs.forEach { it.cancel() }
            jobs = follows.map { follow -> scope.launch { follow(next) } }
        }
    }

    /** The connection to the watched machine and everything that follows it, stopped (Remove of the machine being watched). */
    fun stop() {
        synchronized(lock) {
            controller?.stop(); controller = null
            jobs.forEach { it.cancel() }; jobs = emptyList()
            stopped()
            detach()
        }
    }

    /** The app is hidden or covered: the connection is released (the owner closes it after its grace). */
    fun pause() { controller?.pause() }

    /** True when a setup problem on a healthy connection ran the setup again. */
    fun retrySetup(): Boolean = controller?.retrySetup() == true

    fun installRelay() { controller?.installRelay() }
}
