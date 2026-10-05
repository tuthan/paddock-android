package io.github.tuthan.paddock.host

import androidx.compose.runtime.saveable.Saver
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.DownReason

/**
 * A Connect on Add machine that has not finished: the machine is saved and the form waits to hear whether the connection came up.
 * Held at the root, above the screens, because saving the first machine ends the "no machines" state and would otherwise take the
 * form away in the middle of the attempt.
 */
data class ConnectAttempt(val profileId: String, val error: String? = null, val fix: ConnectFix? = null) {
    val connecting: Boolean get() = error == null

    companion object {
        /** Survives rotation and process death; null is "no attempt". */
        val Saver: Saver<ConnectAttempt?, Any> = Saver(
            save = { a -> if (a == null) emptyList<String?>() else listOf(a.profileId, a.error, a.fix?.name) },
            restore = { v ->
                @Suppress("UNCHECKED_CAST") val l = v as List<String?>
                if (l.isEmpty()) null else ConnectAttempt(l[0]!!, l[1], l[2]?.let { runCatching { ConnectFix.valueOf(it) }.getOrNull() })
            },
        )
    }
}

sealed interface ConnectOutcome {
    /** The SSH connection came up and the key was accepted: whatever Home says next (a relay to install, herdr to find) is Home's to say. */
    data object Connected : ConnectOutcome
    data class Failed(val reason: DownReason) : ConnectOutcome
}

object ConnectOutcomes {
    /** True while the connection is still being made; a caller remembers that it saw this before it trusts a later answer. */
    fun waiting(phase: HostPhase?): Boolean = phase == null || phase == HostPhase.Connecting || phase == HostPhase.InstallingRelay

    /**
     * What [phase] means for the attempt, or null while it is not decided. A failure or a setup problem seen before any "connecting"
     * is the previous attempt's, so it is not an answer ([sawConnecting]); a live monitor is, wherever it came from.
     */
    fun outcome(phase: HostPhase?, sawConnecting: Boolean): ConnectOutcome? = when (phase) {
        null, HostPhase.Connecting, HostPhase.InstallingRelay -> null
        is HostPhase.Monitoring -> ConnectOutcome.Connected
        is HostPhase.NeedsRelayInstall, is HostPhase.Problem -> if (sawConnecting) ConnectOutcome.Connected else null
        is HostPhase.Failed -> if (sawConnecting) ConnectOutcome.Failed(phase.reason) else null
    }

    const val STILL_WAITING = "Still waiting for the machine to answer. It is saved: check the address, then press Connect again."
}
