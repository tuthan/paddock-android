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
    /**
     * The phone key or the stored imported key cannot be read or cannot sign (missing after app-data loss, corrupt, or refused
     * by the Keystore). Raised before or during authentication; the connection, if any, is closed.
     */
    class KeyUnavailable(detail: String, cause: Throwable? = null) : ConnectFailure("key unavailable: $detail", DownReason.KeyUnavailable, cause)
    /** The pin store cannot be read; no host key was trusted and nothing was authenticated. */
    class HostKeysUnreadable(cause: Throwable?) : ConnectFailure("saved host keys cannot be read", DownReason.HostKeysUnreadable, cause)
    class Unreachable(cause: Throwable) : ConnectFailure("cannot reach host: ${cause.message}", DownReason.Network(cause.message ?: cause.javaClass.simpleName), cause)
    /** [reason] is [DownReason.Timeout], or [DownReason.LocalNetworkTimeout] when the gate says the OS may have dropped the connect. */
    class TimedOut(cause: Throwable? = null, reason: DownReason = DownReason.Timeout) : ConnectFailure("connection timed out", reason, cause)
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

    /** Asked when a connect to [target] timed out before the server answered; a non-null reason replaces [DownReason.Timeout]. */
    suspend fun timeoutHint(target: SshTarget): DownReason? = null

    companion object {
        val Open = ConnectGate { null }
    }
}
