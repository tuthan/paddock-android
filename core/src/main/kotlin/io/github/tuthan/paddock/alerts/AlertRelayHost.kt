package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayState
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the host's service manager says about the relay: systemd's user manager on Linux, launchd on a Mac. Reading it only asks; [AlertSetupScript] is what enables it. */
enum class ServiceState(val label: String) {
    Active("running"), Inactive("installed, not running"), Failed("failed"), NotInstalled("not installed"),
    /** systemd could not be asked from an SSH command here (no user bus), or answered in a way this build does not know. */
    Unknown("not known from here"),
}

/** The script's own answer to `--check`: 0 config and herdr fine, 2 config problem, 3 herdr not reachable. [lines] are its reason codes, never secrets. */
data class RelayCheck(val exit: Int, val lines: List<String>) {
    val ok get() = exit == 0
}

data class AlertRelayStatus(val script: RelayState, val destination: String, val check: RelayCheck?, val service: ServiceState)

/**
 * The alert relay on one host: whether the pinned script is there, what its own `--check` says about the configuration, and
 * whether the unit runs. It installs only the pinned script, on the user's say-so, exactly as the other host scripts are
 * installed; the unit (or LaunchAgent) and the configuration are written by the setup script ([AlertSetupScript]) after the user's one confirmation.
 * The relay is run only when its hash matches the pin.
 */
class AlertRelayHost(private val ssh: SshSession, private val installer: RelayInstaller) {
    val expectedSha256: String get() = installer.expectedSha256
    private var home: String? = null
    private suspend fun home(): String = home ?: installer.homeDirectory().also { home = it }

    suspend fun destination(): String = installer.destination(home())

    private var platform: ServicePlatform? = null

    /**
     * Which service manager this machine has, asked once over the open connection (`uname -s`): a Mac runs the relay as a launchd agent, anything else as a systemd
     * user unit. A machine that would not say is taken for Linux and asked again next time, so a slow answer is not remembered as a wrong one.
     */
    suspend fun platform(): ServicePlatform {
        platform?.let { return it }
        val os = io.github.tuthan.paddock.hostprofile.HostOsProbe.read(ssh) ?: return ServicePlatform.Systemd
        return ServicePlatform.of(os).also { platform = it }
    }

    suspend fun inspect(): AlertRelayStatus {
        val home = home()
        val state = installer.state(home)
        val check = if (state == RelayState.Current) check(installer.destination(home)) else null
        return AlertRelayStatus(state, installer.destination(home), check, service())
    }

    suspend fun install() = installer.install(home())

    /** Whether the pinned script is on the host, a different file is, or nothing is; reads only the file's hash. */
    suspend fun inspectScript(): RelayState = installer.state(home())

    /** Whether the phone has already written an address for the relay to post to. Reads nothing from it. */
    suspend fun hasPushEndpoint(): Boolean = ssh.exec(listOf("sh", "-c", "test -s \"$PUSH_ENDPOINT\""), limits = LIMITS).exit == 0

    /**
     * Writes the UnifiedPush address the distributor gave this phone, where the relay reads it: on stdin (never in a command line, so
     * it is not in the host's process list), mode 600, replaced atomically. The address is a capability: whoever holds it can send this
     * phone an alert hint, so Paddock writes it only after the user agrees, and only an address [PushEndpoint] accepts.
     */
    suspend fun writePushEndpoint(endpoint: String) {
        val accepted = requireNotNull(PushEndpoint.accept(endpoint)) { "not an address the relay would use" }
        val body = JsonObject(mapOf("endpoint" to JsonPrimitive(accepted))).toString().toByteArray(Charsets.UTF_8)
        val r = ssh.exec(
            listOf("sh", "-c", "umask 077 && mkdir -p \"${'$'}HOME/.config/paddock\" && cat > \"$PUSH_ENDPOINT.tmp\" && mv -f \"$PUSH_ENDPOINT.tmp\" \"$PUSH_ENDPOINT\""),
            stdin = body, limits = LIMITS,
        )
        check(r.exit == 0) { "writing the address failed (exit ${r.exit})" }
    }

    /** Removes the address, so the relay has nowhere to post and its queue expires. */
    suspend fun removePushEndpoint() {
        val r = ssh.exec(listOf("sh", "-c", "rm -f \"$PUSH_ENDPOINT\""), limits = LIMITS)
        check(r.exit == 0) { "removing the address failed (exit ${r.exit})" }
    }

    /**
     * Runs the setup [script] ([AlertSetupScript.build]) on the machine, over one SSH command with the script on stdin (never in a command line, so a token in it is not in
     * the host's process list). The caller has shown the user exactly this text and had their agreement. The script itself refuses to start anything before the
     * relay's own `--check` passes.
     */
    suspend fun applySetup(script: String): SetupOutcome {
        val r = ssh.exec(listOf("sh", "-s"), stdin = script.toByteArray(Charsets.UTF_8), limits = SETUP_LIMITS)
        return AlertSetupScript.outcome(r.exit, r.stdout.toString(Charsets.UTF_8), r.stderr.toString(Charsets.UTF_8))
    }

    /** Stops and disables the relay and removes the address file ([AlertSetupScript.turnOff]); the configuration and the unit stay, so turning it on again keeps the topic. */
    suspend fun turnOff(): SetupOutcome {
        val r = ssh.exec(listOf("sh", "-s"), stdin = AlertSetupScript.turnOff(platform()).toByteArray(Charsets.UTF_8), limits = SETUP_LIMITS)
        return AlertSetupScript.turnOffOutcome(r.exit, r.stdout.toString(Charsets.UTF_8))
    }

    /** Posts one test message from the machine the way the relay would, so a success proves the machine reaches the server and the address is right. */
    suspend fun sendTest(): AlertSetupScript.TestOutcome {
        // On a Mac the Python that can read the configuration (3.11+) is not the one an SSH command finds first, so it is looked for in the usual folders. Arguments
        // cannot carry quotes or line breaks over SSH, so that script goes on stdin, with the test program as a here-document.
        val r = if (platform() == ServicePlatform.Launchd) {
            val text = AlertSetupScript.FIND_PYTHON + "\nexec \"\$PY\" - <<'PADDOCK_TEST'\n" + AlertSetupScript.TEST_PY.trim() + "\nPADDOCK_TEST\n"
            ssh.exec(listOf("sh", "-s"), stdin = text.toByteArray(Charsets.UTF_8), limits = SETUP_LIMITS)
        } else ssh.exec(listOf("python3", "-"), stdin = AlertSetupScript.TEST_PY.toByteArray(Charsets.UTF_8), limits = SETUP_LIMITS)
        return AlertSetupScript.testOutcome(r.exit, r.stdout.toString(Charsets.UTF_8))
    }

    private suspend fun check(path: String): RelayCheck {
        val mac = platform() == ServicePlatform.Launchd
        if (mac) require(SAFE_PATH.matches(path)) { "unexpected relay path on the host" }
        val r = if (mac) ssh.exec(listOf("sh", "-s"), stdin = (AlertSetupScript.FIND_PYTHON + "\nexec \"\$PY\" \"$path\" --check </dev/null\n").toByteArray(Charsets.UTF_8), limits = LIMITS)
        else ssh.exec(listOf("python3", path, "--check"), limits = LIMITS)
        if (mac && r.exit == 21) return RelayCheck(21, listOf(AlertSetupCopy.NO_PYTHON_MAC))
        val lines = r.stderr.toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX).trim().take(200) }.take(4)
        return RelayCheck(r.exit, lines)
    }

    private suspend fun service(): ServiceState {
        val r = if (platform() == ServicePlatform.Launchd) ssh.exec(listOf("sh", "-s"), stdin = LAUNCHD_STATE.toByteArray(Charsets.UTF_8), limits = LIMITS) else ssh.exec(listOf("systemctl", "--user", "is-active", UNIT), limits = LIMITS)
        return when (r.stdout.toString(Charsets.UTF_8).trim()) {
            "active" -> ServiceState.Active
            "inactive" -> ServiceState.Inactive
            "failed" -> ServiceState.Failed
            "unknown" -> ServiceState.NotInstalled
            else -> ServiceState.Unknown
        }
    }

    companion object {
        const val UNIT = "paddock-alert-relay.service"
        /** Where the relay's example configuration points `endpoint_file`; the shell expands `$HOME`. */
        private const val PUSH_ENDPOINT = "\$HOME/.config/paddock/push-endpoint.json"
        private const val PREFIX = "paddock-alert-relay:"
        /** What a path on the host may hold to go into a script in double quotes: the relay's own location under HOME, nothing a shell would act on. */
        private val SAFE_PATH = Regex("/[A-Za-z0-9._/+@ -]*")
        /** The agent's state in the words `systemctl is-active` uses: no plist is "unknown", a plist that is not loaded or not running is "inactive". */
        private val LAUNCHD_STATE =
            "f=\"\$HOME/Library/LaunchAgents/${AlertSetupScript.LAUNCH_LABEL}.plist\"; [ -e \"\$f\" ] || { echo unknown; exit 0; }; " +
                "out=\$(launchctl print \"gui/\$(id -u)/${AlertSetupScript.LAUNCH_LABEL}\" </dev/null 2>/dev/null) || { echo inactive; exit 0; }; " +
                "case \"\$out\" in *'state = running'*) echo active;; *) echo inactive;; esac"
        private val LIMITS = ExecLimits(stdoutMax = 4096, stderrMax = 4096, deadline = 15.seconds)
        /** Setup waits for a unit to restart and a server to answer, so it gets longer than a read. */
        private val SETUP_LIMITS = ExecLimits(stdoutMax = 8192, stderrMax = 8192, deadline = 60.seconds)
    }
}
