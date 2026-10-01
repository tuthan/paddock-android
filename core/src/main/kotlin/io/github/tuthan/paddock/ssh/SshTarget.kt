package io.github.tuthan.paddock.ssh

import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.hostkey.PresentedHostKey
import io.github.tuthan.paddock.ports.DownReason

data class SshTarget(val profileId: String, val host: String, val port: Int, val user: String) {
    /** The string stored beside a pin; informational, the pin is keyed by profile. */
    val endpoint: String get() = "$host:$port"
}

/** Everything that can stop a connection attempt, each mapped to the [DownReason] the link reports. */
sealed class ConnectFailure(message: String, val reason: DownReason, cause: Throwable? = null) : Exception(message, cause) {
    /** The gate refused before any socket opened (for example the Android 17 local-network grant is missing). */
    class Refused(reason: DownReason) : ConnectFailure("connection refused by the gate: $reason", reason)
    /** The host key differs from the pin. Nothing was authenticated. Carries both keys for the UI. */
    class HostKeyChanged(val pin: PinnedHostKey, val presented: PresentedHostKey) :
        ConnectFailure("host key changed: pinned ${pin.fingerprint}, presented ${presented.fingerprint}", DownReason.HostKeyChanged)
    /** First contact and the user declined (or no one was there to ask). Nothing was authenticated. */
    class HostKeyDeclined(val presented: PresentedHostKey) :
        ConnectFailure("host key not accepted: ${presented.algorithm} ${presented.fingerprint}", DownReason.Refused)
    class AuthFailed : ConnectFailure("authentication failed", DownReason.AuthFailed)
    /** The imported key could not be used (wrong or missing passphrase, not a key, unsupported). Detected before any socket opens. */
    class BadKey(val detail: String) : ConnectFailure("imported key unusable: $detail", DownReason.AuthFailed)
    class Unreachable(cause: Throwable) : ConnectFailure("cannot reach host: ${cause.message}", DownReason.Network(cause.message ?: cause.javaClass.simpleName), cause)
    class TimedOut(cause: Throwable? = null) : ConnectFailure("connection timed out", DownReason.Timeout, cause)
}

/** The link is not Up (never connected, lost, or closed); nothing was sent. */
class SessionDown(val reason: DownReason) : java.io.IOException("session is down: $reason")

/** A command ran past its deadline (opening the channel included); the channel was closed, the remote process was not signalled. */
class ExecTimedOut(argv: List<String>) : Exception("command timed out: ${argv.firstOrNull() ?: "<empty>"}")

/** Every channel slot on the session stayed taken for [waitedMillis]; nothing was sent. Usually a caller that never closes its streams. */
class ChannelsBusy(slots: Int, val waitedMillis: Long) : java.io.IOException("all $slots SSH channels stayed busy for $waitedMillis ms")

/** Asked before any socket opens. Returns null to allow, or the reason to refuse. */
fun interface ConnectGate {
    suspend fun check(target: SshTarget): DownReason?

    companion object {
        val Open = ConnectGate { null }
    }
}
