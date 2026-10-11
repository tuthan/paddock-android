package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.live.BlockedPreview
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.live.ConnectFix
import io.github.tuthan.paddock.live.DownReasonText
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.relay.HerdrError
import io.github.tuthan.paddock.relay.RelayTimeout
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.ui.screens.HomeUiState
import io.github.tuthan.paddock.ui.screens.clockLabel

/** What one host's screen knows: the controller's phase, the live home, and the last home seen (kept across reconnects, shown dimmed). */
data class HostView(
    val phase: HostPhase? = null,
    val home: HomeModel? = null,
    val freshness: Freshness? = null,
    val lastHome: HomeModel? = null,
    val lastReadAtMillis: Long? = null,
    val blockedPreview: BlockedPreview? = null,
    /** Why the monitor last went stale, when it did: a timeout, a herdr error, an unreadable answer, a lost stream. */
    val lastLoss: Throwable? = null,
    /** The herdr version of the last read, for Settings' About. */
    val herdrVersion: String? = null,
    /** The machine's sessions for the Spaces screen (Phase 09); null while the connection is not up. */
    val spaces: io.github.tuthan.paddock.live.HostSpaces? = null,
    /**
     * The failure the machine is down with, kept while the next attempt runs. A connection that keeps failing goes Failed, then Connecting for up to
     * the connect timeout, then Failed again; without this the banner and its buttons (Wake the machine, Try again) were there for the few seconds of the
     * wait and gone for the attempt, again and again. It is set by a failure, kept through [HostPhase.Connecting], and cleared by anything that means the
     * machine answered (a relay question, a problem, monitoring) or by switching machines.
     */
    val failure: HostPhase.Failed? = null,
)

/**
 * The failure a view keeps when its phase becomes [phase]: a failure is the new one, the next attempt ([HostPhase.Connecting]) keeps [previous], and every
 * other phase (a relay question, a problem, monitoring) means the machine answered, so nothing is kept.
 */
fun failureAfter(previous: HostPhase.Failed?, phase: HostPhase?): HostPhase.Failed? = when (phase) {
    is HostPhase.Failed -> phase
    HostPhase.Connecting -> previous
    else -> null
}

/** What tapping the degraded banner's action does. */
enum class Recovery { OpenSettings, ReviewKey, Retry, InstallRelay, SetUpKey, Wake, ShowCommand }

/** [secondary] is the banner's second action, shown beside [recovery] (Try again beside Wake the machine). */
data class HostScreen(val state: HomeUiState, val recovery: Recovery? = null, val relayPrompt: HostPhase.NeedsRelayInstall? = null, val secondary: Recovery? = null)

/** Turns a [HostView] into what Home draws. Pure: the same view and clock always give the same screen. */
object HomeUiMapper {
    /** What went wrong with a live monitor, as a host fact: herdr silent, herdr refusing, an answer Paddock cannot read, or the stream lost. */
    fun staleReason(name: String, loss: Throwable?, lastRead: String?): String {
        val since = lastRead?.let { " Last read at $it." } ?: ""
        return when (loss) {
            is RelayTimeout -> "herdr on $name is not answering. Paddock is reconnecting.$since"
            is HerdrError -> "herdr on $name refused a read (${loss.code}). Paddock is reconnecting.$since"
            is ProtocolError -> "$name sent an answer Paddock can't read. Paddock is reconnecting; if this keeps happening, herdr and Paddock may need updating.$since"
            else -> "The connection to herdr on $name was lost. Paddock is reconnecting.$since"
        }
    }

    /** [screen] with [line] after a degraded banner's reason (what Paddock is doing about it meanwhile); any other screen, or no line, is unchanged. */
    fun withLine(screen: HostScreen, line: String?): HostScreen {
        val degraded = screen.state as? HomeUiState.Degraded ?: return screen
        return if (line == null) screen else screen.copy(state = degraded.copy(reason = "${degraded.reason} $line"))
    }

    /**
     * [wakeAvailable] is true when this phone has what it takes to send a wake packet for this machine and the last tap is old enough
     * to send another: a failure that may mean "the machine is asleep" then offers Wake the machine, with Try again beside it.
     */
    fun map(name: String, v: HostView, nowMillis: Long, clock: (Long) -> String = ::clockLabel, wakeAvailable: Boolean = false): HostScreen {
        val age = v.lastReadAtMillis?.let { (nowMillis - it).coerceAtLeast(0) }
        fun degraded(reason: String, label: String? = null, recovery: Recovery? = null, prompt: HostPhase.NeedsRelayInstall? = null) =
            HostScreen(HomeUiState.Degraded(name, v.lastHome, reason, age, label), recovery, prompt)
        fun waiting(): HostScreen = if (v.lastHome == null) HostScreen(HomeUiState.Loading(name)) else degraded("Reconnecting to ${name}.")

        return when (val p = v.phase) {
            // While the next attempt runs the banner of the failure stays (its buttons keep their places) instead of giving way to "Reconnecting" or the loader.
            HostPhase.Connecting -> v.failure?.let { failed(it, nowMillis, wakeAvailable, ::degraded, trying = true) } ?: waiting()
            null, HostPhase.InstallingRelay -> waiting()
            is HostPhase.NeedsRelayInstall -> degraded(
                "Paddock has to install its small relay script on $name before it can watch your agents.", "Review the relay", Recovery.InstallRelay, p,
            )
            is HostPhase.Problem -> degraded(p.message, "Try again", Recovery.Retry)
            is HostPhase.Failed -> failed(p, nowMillis, wakeAvailable, ::degraded)
            is HostPhase.Monitoring -> when {
                v.freshness == Freshness.Live && v.home != null && v.lastReadAtMillis != null ->
                    HostScreen(HomeUiState.Live(name, v.home, age ?: 0))
                v.freshness == Freshness.Stale -> degraded(staleReason(name, v.lastLoss, v.lastReadAtMillis?.let(clock)))
                else -> waiting()
            }
        }
    }

    /**
     * The banner for [f]. With [trying] an attempt is running now: the same buttons in the same places as while it waited (so nothing appears or goes),
     * the words say so instead of counting down, and "Try again" is off until the attempt ends.
     */
    private fun failed(
        f: HostPhase.Failed, now: Long, wakeAvailable: Boolean,
        degraded: (String, String?, Recovery?, HostPhase.NeedsRelayInstall?) -> HostScreen,
        trying: Boolean = false,
    ): HostScreen {
        val screen = failedScreen(f, now, wakeAvailable, degraded, trying)
        if (!trying) return screen
        val d = screen.state as? HomeUiState.Degraded ?: return screen
        return screen.copy(state = d.copy(recoveryEnabled = screen.recovery != Recovery.Retry, secondaryEnabled = screen.secondary != Recovery.Retry))
    }

    private fun failedScreen(
        f: HostPhase.Failed, now: Long, wakeAvailable: Boolean,
        degraded: (String, String?, Recovery?, HostPhase.NeedsRelayInstall?) -> HostScreen,
        trying: Boolean,
    ): HostScreen {
        val seconds = if (trying) null else f.retryAtMillis?.let { ((it - now).coerceAtLeast(0) + 999) / 1000 }
        val text = DownReasonText.sentence(f.reason, seconds) + if (trying) " Trying now." else ""
        // A machine that may simply be asleep: these four say "no answer", never "refused" or "wrong key".
        val maybeAsleep = f.reason == DownReason.Timeout || f.reason == DownReason.Closed || f.reason is DownReason.Network
        if (wakeAvailable && maybeAsleep) {
            return degraded(text, "Wake the machine", Recovery.Wake, null).let { it.copy(state = (it.state as HomeUiState.Degraded).copy(secondaryLabel = "Try again"), secondary = Recovery.Retry) }
        }
        if (wakeAvailable && f.reason == DownReason.LocalNetworkTimeout) {
            // The missing grant may be the cause: its fix stays first, and waking is the other possibility.
            return degraded(text, "Open settings", Recovery.OpenSettings, null).let { it.copy(state = (it.state as HomeUiState.Degraded).copy(secondaryLabel = "Wake the machine"), secondary = Recovery.Wake) }
        }
        return when (DownReasonText.fix(f.reason)) {
            ConnectFix.OpenSettings -> degraded(text, "Open settings", Recovery.OpenSettings, null)
            ConnectFix.ReviewKey -> degraded(text, "Review the key", Recovery.ReviewKey, null)
            ConnectFix.SetUpKey -> degraded(text, "Set up the key", Recovery.SetUpKey, null)
            // The machine said no to this phone's key: the way to authorize it comes first, and trying again is the second action.
            ConnectFix.ShowCommand ->
                degraded(text, "Show the command", Recovery.ShowCommand, null).let { it.copy(state = (it.state as HomeUiState.Degraded).copy(secondaryLabel = "Try again"), secondary = Recovery.Retry) }
            // While Paddock is about to try by itself the banner has no button, except for a refusal, which says nothing about when.
            ConnectFix.Retry ->
                if (f.retryAtMillis == null || f.reason == DownReason.Refused) degraded(text, "Try again", Recovery.Retry, null)
                else degraded(text, null, null, null)
            ConnectFix.None -> degraded(text, null, null, null)
        }
    }
}
