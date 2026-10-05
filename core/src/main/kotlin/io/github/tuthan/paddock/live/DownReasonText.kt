package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.ports.DownReason

/** What the person can do about a failed connection, as the one control that fits. */
enum class ConnectFix {
    /** The machine did not accept this phone's key: show the command that authorizes it. */
    ShowCommand,
    OpenSettings,
    ReviewKey,
    SetUpKey,
    Retry,
    None,
}

/**
 * The sentence for each way a connection can fail and the fix that goes with it. One place, so Home's banner and the Add machine
 * form never say different things about the same failure.
 */
object DownReasonText {
    /** [retryInSeconds] is the wait before Paddock tries by itself again, or null when it is not going to. */
    fun sentence(reason: DownReason, retryInSeconds: Long? = null): String {
        val retry = retryInSeconds?.let { " Trying again in $it s." } ?: ""
        return when (reason) {
            DownReason.PermissionDenied -> "Local-network access is off, so Paddock cannot reach this address."
            DownReason.HostKeyChanged -> "The host's key changed. Nothing was signed in."
            DownReason.AuthFailed -> "The host did not accept this phone's key. Authorize it on the host, then try again."
            DownReason.Refused -> "The connection was not accepted."
            DownReason.KeyUnavailable -> "The key stored on this phone can't be read. Import it again or create a new phone key, then authorize it on the host."
            DownReason.HostKeysUnreadable ->
                "Saved host keys can't be read, so Paddock can't check this host's identity. Nothing was signed in. " +
                    "Clearing Paddock's storage in system settings resets them; then add the machine and authorize this phone again."
            DownReason.Timeout -> "The host did not answer in time.$retry"
            DownReason.LocalNetworkTimeout ->
                "The host did not answer in time. If it is on your local network, check that Paddock has local-network access.$retry"
            is DownReason.Network -> "Cannot reach the host: ${reason.message}.$retry".replace("..", ".")
            DownReason.Closed -> "Disconnected.$retry"
        }
    }

    fun fix(reason: DownReason): ConnectFix = when (reason) {
        DownReason.PermissionDenied, DownReason.HostKeysUnreadable, DownReason.LocalNetworkTimeout -> ConnectFix.OpenSettings
        DownReason.HostKeyChanged -> ConnectFix.ReviewKey
        DownReason.AuthFailed -> ConnectFix.ShowCommand
        DownReason.KeyUnavailable -> ConnectFix.SetUpKey
        DownReason.Refused, DownReason.Timeout, is DownReason.Network, DownReason.Closed -> ConnectFix.Retry
    }
}
