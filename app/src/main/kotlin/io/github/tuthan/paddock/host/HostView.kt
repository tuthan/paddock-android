package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.screens.HomeUiState

/** What one host's screen knows: the controller's phase, the live home, and the last home seen (kept across reconnects, shown dimmed). */
data class HostView(
    val phase: HostPhase? = null,
    val home: HomeModel? = null,
    val freshness: Freshness? = null,
    val lastHome: HomeModel? = null,
    val lastReadAtMillis: Long? = null,
)

/** What tapping the degraded banner's action does. */
enum class Recovery { OpenSettings, ReviewKey, Retry, InstallRelay }

data class HostScreen(val state: HomeUiState, val recovery: Recovery? = null, val relayPrompt: HostPhase.NeedsRelayInstall? = null)

/** Turns a [HostView] into what Home draws. Pure: the same view and clock always give the same screen. */
object HomeUiMapper {
    fun map(name: String, v: HostView, nowMillis: Long): HostScreen {
        val age = v.lastReadAtMillis?.let { (nowMillis - it).coerceAtLeast(0) }
        fun degraded(reason: String, label: String? = null, recovery: Recovery? = null, prompt: HostPhase.NeedsRelayInstall? = null) =
            HostScreen(HomeUiState.Degraded(name, v.lastHome, reason, age, label), recovery, prompt)
        fun waiting(): HostScreen = if (v.lastHome == null) HostScreen(HomeUiState.Loading(name)) else degraded("Reconnecting to ${name}.")

        return when (val p = v.phase) {
            null, HostPhase.Connecting, HostPhase.InstallingRelay -> waiting()
            is HostPhase.NeedsRelayInstall -> degraded(
                "Paddock has to install its small relay script on $name before it can watch your agents.", "Review the relay", Recovery.InstallRelay, p,
            )
            is HostPhase.Problem -> degraded(p.message, "Try again", Recovery.Retry)
            is HostPhase.Failed -> failed(p, nowMillis, ::degraded)
            is HostPhase.Monitoring -> when {
                v.freshness == Freshness.Live && v.home != null && v.lastReadAtMillis != null ->
                    HostScreen(HomeUiState.Live(name, v.home, age ?: 0))
                v.freshness == Freshness.Stale -> degraded("The connection to herdr was lost. Paddock is reconnecting.")
                else -> waiting()
            }
        }
    }

    private fun failed(
        f: HostPhase.Failed, now: Long,
        degraded: (String, String?, Recovery?, HostPhase.NeedsRelayInstall?) -> HostScreen,
    ): HostScreen {
        val retry = f.retryAtMillis?.let { " Trying again in ${((it - now).coerceAtLeast(0) + 999) / 1000} s." } ?: ""
        val reason = f.reason
        return when (reason) {
            DownReason.PermissionDenied -> degraded("Local-network access is off, so Paddock cannot reach this address.", "Open settings", Recovery.OpenSettings, null)
            DownReason.HostKeyChanged -> degraded("The host's key changed. Nothing was signed in.", "Review the key", Recovery.ReviewKey, null)
            DownReason.AuthFailed -> degraded("The host did not accept this phone's key. Authorize it on the host, then try again.", "Try again", Recovery.Retry, null)
            DownReason.Refused -> degraded("The connection was not accepted.", "Try again", Recovery.Retry, null)
            DownReason.Timeout -> degraded("The host did not answer in time.$retry", if (retry.isEmpty()) "Try again" else null, if (retry.isEmpty()) Recovery.Retry else null, null)
            is DownReason.Network -> degraded("Cannot reach the host: ${reason.message}.$retry".replace("..", "."), if (retry.isEmpty()) "Try again" else null, if (retry.isEmpty()) Recovery.Retry else null, null)
            DownReason.Closed -> degraded("Disconnected.$retry", if (retry.isEmpty()) "Try again" else null, if (retry.isEmpty()) Recovery.Retry else null, null)
        }
    }
}
