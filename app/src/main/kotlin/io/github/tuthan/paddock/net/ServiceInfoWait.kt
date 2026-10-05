package io.github.tuthan.paddock.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What a platform that reports about one service through a registered callback tells it: each report, the service going, or no registration. */
internal interface ServiceReports<I> {
    fun onUpdated(info: I)
    fun onLost()
    fun onFailed(code: Int)
}

/**
 * Waits for the first report that [pick] turns into a result, or for the service to be lost or the registration to fail (null), or for the
 * caller to be cancelled or time out. [register] starts the reports and returns what stops them (it may throw [IllegalArgumentException] when the
 * platform refuses at once, which is a null result). Whatever ends the wait, the registration it made is stopped before this returns: a
 * registration left behind keeps receiving network announcements for the life of the process.
 *
 * A report [pick] has nothing for (a host whose IPv6 address arrived before its IPv4 one) is not an answer; the wait goes on, and the caller's
 * timeout decides.
 */
internal suspend fun <I, R : Any> awaitServiceInfo(register: (ServiceReports<I>) -> (() -> Unit), pick: (I) -> R?): R? {
    var unregister: (() -> Unit)? = null
    try {
        return suspendCancellableCoroutine<R?> { cont ->
            fun finish(result: R?) { if (cont.isActive) cont.resume(result) }
            val reports = object : ServiceReports<I> {
                override fun onUpdated(info: I) { val picked = pick(info); if (picked != null) finish(picked) }
                override fun onLost() = finish(null)
                override fun onFailed(code: Int) = finish(null)
            }
            try { unregister = register(reports) } catch (_: IllegalArgumentException) { finish(null) }
        }
    } finally {
        unregister?.invoke()
    }
}
