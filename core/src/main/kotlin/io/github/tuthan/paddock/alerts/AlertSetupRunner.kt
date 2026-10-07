package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.relay.RelayRefused
import io.github.tuthan.paddock.relay.RelayState
import kotlin.coroutines.cancellation.CancellationException

/** The steps of turning alerts on, in the order they run. [Register] and [SendAddress] belong to the Paddock-shows-them (UnifiedPush) mode only. */
enum class SetupStep(val label: String) {
    Register("Register with the ntfy app"),
    Install("Install the relay script"),
    SendAddress("Send the address to the machine"),
    Configure("Write the configuration and start the relay"),
}

enum class StepState { Pending, Running, Done, Failed }

sealed interface SetupRun {
    /** Alerts are on. [lingerNote] is set when the machine would not keep the relay running after a logout. */
    data class Done(val lingerNote: String? = null) : SetupRun

    /** It stopped at [step] and said why in words; nothing after that step ran, and what ran before it is safe to leave (each step can run again). */
    data class Stopped(val step: SetupStep, val message: String) : SetupRun
}

/**
 * Turns alerts on for one machine in one go, over the connection already open: the steps the screen used to hand the user one at a time (register, install the
 * pinned relay, send the address, write the configuration and enable the unit) run in order, each reported as it starts and ends, and the first one that
 * fails stops the rest with a sentence. The caller has already shown the user what will be written and had their agreement.
 *
 * The relay is installed only when the pinned script is not already there, and only the pinned script ([io.github.tuthan.paddock.relay.RelayInstaller] checks its
 * hash before and after); nothing is enabled before the relay's own `--check` of the written configuration passes ([AlertSetupScript]).
 */
class AlertSetupRunner(private val relay: AlertRelayHost, private val unit: String, private val scriptSha256: String) {
    /**
     * [obtainEndpoint] is for the Paddock-shows-them mode: it registers with the distributor if needed and returns the address it gave, or null when none came
     * (the distributor did not answer). Ignored for the ntfy-app mode. [onStep] is told each step's state; steps this mode does not have are never reported.
     */
    suspend fun run(setup: AlertSetup, obtainEndpoint: suspend () -> String?, onStep: (SetupStep, StepState) -> Unit): SetupRun {
        val push = setup.mode == DeliveryMode.Push
        var endpoint: String? = null

        suspend fun step(which: SetupStep, work: suspend () -> String?): SetupRun.Stopped? {
            onStep(which, StepState.Running)
            val failure = try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { e.message?.take(160) ?: e.javaClass.simpleName }
            onStep(which, if (failure == null) StepState.Done else StepState.Failed)
            return failure?.let { SetupRun.Stopped(which, it) }
        }

        if (push) step(SetupStep.Register) {
            endpoint = obtainEndpoint()
            if (endpoint == null) AlertSetupCopy.NO_ENDPOINT else null
        }?.let { return it }

        step(SetupStep.Install) {
            val state = relay.inspectScript()
            if (state == RelayState.Current) null
            else try { relay.install(); null } catch (_: RelayRefused) { AlertSetupCopy.RELAY_DID_NOT_MATCH }
        }?.let { return it }

        if (push) step(SetupStep.SendAddress) {
            val accepted = PushEndpoint.accept(endpoint ?: "")
            if (accepted == null) AlertSetupCopy.ENDPOINT_REFUSED else { relay.writePushEndpoint(accepted); null }
        }?.let { return it }

        var done: SetupRun.Done? = null
        step(SetupStep.Configure) {
            when (val outcome = relay.applySetup(AlertSetupScript.build(unit, setup, scriptSha256))) {
                is SetupOutcome.Done -> { done = SetupRun.Done(outcome.lingerNote); null }
                SetupOutcome.NoSystemd -> AlertSetupCopy.NO_SYSTEMD
                SetupOutcome.NoPython -> AlertSetupCopy.noPython(setup.platform)
                SetupOutcome.NoLoginSession -> AlertSetupCopy.NO_LOGIN_SESSION
                is SetupOutcome.CheckFailed -> AlertSetupCopy.checkFailed(outcome.lines)
                is SetupOutcome.Failed -> AlertSetupCopy.failed(outcome.message)
            }
        }?.let { return it }
        return done ?: SetupRun.Done()
    }
}

/** The sentences the setup says when a step stops. Plain words, no exit codes. */
object AlertSetupCopy {
    const val NO_ENDPOINT = "The ntfy app did not give Paddock an address. Open the ntfy app once, check its server in its settings, then try again."
    const val RELAY_DID_NOT_MATCH = "The relay on the machine did not match after installing. Nothing was started."
    const val ENDPOINT_REFUSED = "The address the ntfy app gave is not one the relay would use (it must be https). Nothing was written."
    const val NO_SYSTEMD = "This machine has no systemd user manager, so Paddock cannot start the relay as a service here. If it is a Mac, open this machine's page and choose the macOS icon, then try again. Otherwise use \"Show the commands\" and run the relay your own way."
    const val NO_PYTHON = "This machine has no python3. The relay needs Python 3.11 or newer."
    const val NO_PYTHON_MAC = "No Python 3.11 or newer was found on this Mac (the one macOS ships is 3.9). Install one, for example with `brew install python`, then try again."
    const val NO_LOGIN_SESSION = "Nobody is logged in to this Mac's desktop, so a LaunchAgent has no session to start in. Log in at the Mac once (or turn on automatic login), then try again. The configuration is already written."
    fun noPython(platform: ServicePlatform) = if (platform == ServicePlatform.Launchd) NO_PYTHON_MAC else NO_PYTHON

    /** The relay's service on this platform, as the confirmation and the details name it. */
    fun serviceFact(platform: ServicePlatform) =
        if (platform == ServicePlatform.Launchd) "~/Library/LaunchAgents/${AlertSetupScript.LAUNCH_LABEL}.plist, then loaded and started with launchctl (log: ~/Library/Logs/paddock-alert-relay.log)"
        else "~/.config/systemd/user/paddock-alert-relay.service, then enabled and started with systemctl --user"
    fun afterLogoutFact(platform: ServicePlatform) =
        if (platform == ServicePlatform.Launchd) "It runs while you are logged in to the Mac's desktop and starts again at login (automatic login keeps an unattended Mac going)"
        else "loginctl enable-linger, so the relay keeps running"
    fun serviceLabel(platform: ServicePlatform) = if (platform == ServicePlatform.Launchd) "LaunchAgent" else "systemd user unit"
    const val NO_DISTRIBUTOR = "Paddock needs the ntfy app (or another UnifiedPush app) installed on this phone to show alerts itself. Install it, or let the ntfy app show them."
    const val BAD_TOKEN = "That is not an access token."
    fun checkFailed(lines: List<String>) = "The relay refused the configuration" + if (lines.isEmpty()) "." else ": ${lines.joinToString("; ")}."
    fun failed(message: String) = "The setup stopped: $message"
}

/** What the relay on a machine and the phone's own record add up to, in the one word the screen's status line starts from. */
enum class AlertStatus {
    /** Nothing is set up, here or on the machine. */
    Off,

    /** Set up from this phone and running. */
    On,

    /** Running, but not set up from this phone (by hand, or from another phone): Paddock cannot say how it delivers. */
    OnByHand,

    /** Set up from this phone, and something is not as it was left: the relay is not running, its check fails, or the address file is gone. */
    NeedsAttention;

    companion object {
        /**
         * [running] is the relay as the machine reports it: the pinned script is there, its configuration checks out and its unit is active. [saved] is
         * this phone's record of the last setup. [addressOnHost] is whether the address file exists (the Paddock-shows-them mode only); null when it could not be asked.
         */
        fun of(running: Boolean, saved: SavedAlertSetup?, addressOnHost: Boolean?): AlertStatus = when {
            running && saved == null -> OnByHand
            running && saved!!.mode == DeliveryMode.Push && addressOnHost == false -> NeedsAttention
            running -> On
            saved != null -> NeedsAttention
            else -> Off
        }
    }
}

/**
 * The setup form's rules, so what the screen lets the user start is what the setup will accept. [ownServer] is "my own server" against the public one;
 * [distributors] is how many UnifiedPush apps are installed (the Paddock-shows-them mode needs one).
 */
object AlertSetupForm {
    /** [serverUrl] is the accepted server (null when the mode has none, or the address is not usable yet); [urlError] is shown only once something was typed. */
    data class Check(val serverUrl: String?, val urlError: String?, val tokenError: String?, val modeError: String?, val needsServer: Boolean = false) {
        val canStart get() = modeError == null && urlError == null && tokenError == null && (!needsServer || serverUrl != null)
    }

    fun check(mode: DeliveryMode, ownServer: Boolean, ownUrl: String, token: String, distributors: Int): Check = when {
        mode == DeliveryMode.Push -> Check(null, null, null, if (distributors == 0) AlertSetupCopy.NO_DISTRIBUTOR else null)
        !ownServer -> Check(NtfyServer.PUBLIC, null, null, null)
        else -> {
            val url = NtfyServer.normalize(ownUrl)
            Check(
                serverUrl = (url as? NtfyServer.Result.Ok)?.url,
                urlError = (url as? NtfyServer.Result.Bad)?.why?.takeIf { ownUrl.isNotBlank() },
                tokenError = if (token.isNotEmpty() && !NtfyServer.validToken(token)) AlertSetupCopy.BAD_TOKEN else null,
                modeError = null, needsServer = true,
            )
        }
    }

    /** Words for where the alerts go: the public server, the user's own, or (for the address a distributor gave) a host. */
    fun serverWords(url: String) = if (NtfyServer.isPublic(url)) "the public ntfy.sh server" else "your server, ${NtfyServer.hostOf(url)}"
}
