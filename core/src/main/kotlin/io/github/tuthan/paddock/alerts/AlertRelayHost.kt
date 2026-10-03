package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayState
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the host's systemd user manager says about the relay's unit. Paddock only asks; enabling the unit is the user's step. */
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
 * installed; the unit and the configuration are written by the user from the setup commands, and the app never enables anything.
 * The script is run only when its hash matches the pin.
 */
class AlertRelayHost(private val ssh: SshSession, private val installer: RelayInstaller) {
    val expectedSha256: String get() = installer.expectedSha256
    private var home: String? = null
    private suspend fun home(): String = home ?: installer.homeDirectory().also { home = it }

    suspend fun destination(): String = installer.destination(home())

    suspend fun inspect(): AlertRelayStatus {
        val home = home()
        val state = installer.state(home)
        val check = if (state == RelayState.Current) check(installer.destination(home)) else null
        return AlertRelayStatus(state, installer.destination(home), check, service())
    }

    suspend fun install() = installer.install(home())

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

    private suspend fun check(path: String): RelayCheck {
        val r = ssh.exec(listOf("python3", path, "--check"), limits = LIMITS)
        val lines = r.stderr.toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX).trim().take(200) }.take(4)
        return RelayCheck(r.exit, lines)
    }

    private suspend fun service(): ServiceState {
        val r = ssh.exec(listOf("systemctl", "--user", "is-active", UNIT), limits = LIMITS)
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
        private val LIMITS = ExecLimits(stdoutMax = 4096, stderrMax = 4096, deadline = 15.seconds)
    }
}

/**
 * The commands the user pastes on the host, built from the pinned unit file and the pinned example configuration. They write two
 * files, the unit and (only when none exists) the configuration with this phone's machine id and the session's socket filled in,
 * and then enable the unit, which is the user's step. Both files go in through quoted here-documents, so nothing in them is
 * expanded by the shell, and the text is shown on screen before it can be copied.
 */
object AlertRelaySetup {
    fun commands(unit: String, exampleConfig: String, profileId: String, socketPath: String, scriptSha256: String): String {
        require("PADDOCK_UNIT" !in unit && "PADDOCK_CONF" !in exampleConfig) { "a here-document terminator inside a pinned file" }
        require(socketPath.startsWith("/") && '\n' !in socketPath && '"' !in socketPath) { "unexpected socket path" }
        val config = exampleConfig
            .replace(Regex("(?m)^socket = \".*\"$"), "socket = \"$socketPath\"")
            .replace(Regex("(?m)^profile = \".*\"$"), "profile = \"$profileId\"")
        return buildString {
            appendLine("# Paddock alert relay, script sha256 $scriptSha256")
            appendLine("# 1. The user unit, as shipped with Paddock:")
            appendLine("mkdir -p ~/.config/systemd/user ~/.config/paddock")
            appendLine("cat > ~/.config/systemd/user/paddock-alert-relay.service <<'PADDOCK_UNIT'")
            append(unit.trimEnd('\n')).append('\n')
            appendLine("PADDOCK_UNIT")
            appendLine("# 2. The configuration, written only if you have none yet. Then edit the ntfy url and topic in it:")
            appendLine("[ -e ~/.config/paddock/alert-relay.toml ] || {")
            appendLine("cat > ~/.config/paddock/alert-relay.toml <<'PADDOCK_CONF'")
            append(config.trimEnd('\n')).append('\n')
            appendLine("PADDOCK_CONF")
            appendLine("chmod 600 ~/.config/paddock/alert-relay.toml; }")
            appendLine("# 3. Your step: check it, then start it and keep it running without a login session:")
            appendLine("python3 ~/.local/share/paddock/paddock-alert-relay.py --check")
            appendLine("systemctl --user daemon-reload && systemctl --user enable --now paddock-alert-relay.service")
            appendLine("loginctl enable-linger \"${'$'}USER\"")
        }
    }
}
