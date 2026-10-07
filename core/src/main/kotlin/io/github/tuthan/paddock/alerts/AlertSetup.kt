package io.github.tuthan.paddock.alerts

import java.util.Base64
import kotlinx.serialization.Serializable

/** How an alert reaches the phone. The relay's `delivery` key says which. */
@Serializable
enum class DeliveryMode(val wire: String) {
    /** A UnifiedPush distributor on the phone (the ntfy app can be one) wakes Paddock, which raises the notification itself. Android only. */
    Push("unifiedpush"),

    /** The ntfy app shows the message the relay posted to a topic. Works with the ntfy app on Android and on an iPhone. */
    NtfyApp("ntfy"),
}

/**
 * The ntfy server a phone's alerts go through: the public one ([PUBLIC]) or the user's own. Pure rules, so what the setup screen accepts is what the relay's
 * own configuration check accepts, and the topic is made here, not typed.
 */
object NtfyServer {
    const val PUBLIC = "https://ntfy.sh"
    private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:[0-9]{1,5})?")
    private val PATH = Regex("(/[A-Za-z0-9._~-]+)*")
    private val TOKEN = Regex("[A-Za-z0-9_.~-]{1,512}")
    private val TOPIC = Regex("[A-Za-z0-9_-]{1,64}")

    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Bad(val why: String) : Result
    }

    /**
     * [raw] as a server address: https only (a phone that is away cannot reach a plain-http server safely, and the relay refuses to send a token over it), a
     * host with an optional port and path, nothing else. A bare host ("ntfy.example.org") is taken as https. The trailing slash goes.
     */
    fun normalize(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Bad("Enter the server's address, like ntfy.example.org.")
        if (text.startsWith("http://", ignoreCase = true)) return Result.Bad("Use an https address, not http. Alerts and any token must not cross the network in the clear.")
        val withScheme = if (text.startsWith("https://", ignoreCase = true)) text else if ("://" in text) return Result.Bad("Use an https:// address.") else "https://$text"
        val rest = withScheme.substring("https://".length).trimEnd('/')
        val hostPart = rest.substringBefore('/')
        val path = if ('/' in rest) "/" + rest.substringAfter('/') else ""
        if (!HOST.matches(hostPart) || hostPart.substringAfter(':', "").let { it.isNotEmpty() && it.toInt() !in 1..65535 }) return Result.Bad("That is not a server address.")
        if (!PATH.matches(path)) return Result.Bad("Use a plain address: host, optional port and path, no query or login.")
        return Result.Ok("https://" + hostPart.lowercase() + path)
    }

    fun isPublic(url: String) = hostOf(url).equals("ntfy.sh", ignoreCase = true)

    /** The host (and port) of [url] or of an endpoint a distributor gave, for the words "ntfy.sh" or "your server, ntfy.example.org". */
    fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/').substringBefore('?')

    /** An unguessable topic: 24 random bytes, URL-safe, 32 characters. The topic is the only secret of the ntfy-app mode, so it is made, never chosen. */
    fun newTopic(random: ByteArray): String {
        require(random.size >= 16) { "a topic needs at least 128 bits" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random).take(64)
    }

    fun validTopic(topic: String) = TOPIC.matches(topic)

    /** A token for a server that wants one: the characters ntfy uses, nothing a configuration file or a header could be broken with. */
    fun validToken(token: String) = TOKEN.matches(token)

    /** The link that opens the ntfy app on its subscribe screen for [topic] on [url] (`ntfy://host/topic`; the ntfy app handles it on Android). */
    fun subscribeLink(url: String, topic: String): String = "ntfy://" + url.removePrefix("https://") + "/" + topic

    /** The same subscription as a web address: what a browser, or an app that does not know `ntfy://`, can open. */
    fun webLink(url: String, topic: String): String = "$url/$topic"
}

/**
 * Which service manager keeps the relay running on the machine: systemd's user manager on Linux, a launchd LaunchAgent on a Mac. Decided from what the machine
 * said it is (`uname -s`, [io.github.tuthan.paddock.hostprofile.HostOs]); anything that is not a Mac gets the systemd script, as before.
 */
enum class ServicePlatform {
    Systemd, Launchd;

    companion object {
        fun of(os: io.github.tuthan.paddock.hostprofile.HostOs?) = if (os == io.github.tuthan.paddock.hostprofile.HostOs.Mac) Launchd else Systemd
    }
}

/**
 * Everything the setup writes on the machine, as the app decided it. [token] is for a self-hosted server that needs one (public ntfy.sh takes none).
 * Nothing in here is free text that could break a configuration file: each field is checked by its own rule when the script is built.
 */
data class AlertSetup(
    val profileId: String,
    /** The machine's name on this phone; it is the title of the alert ("Paddock: attention on <label>"), cut to the relay's 40 characters. */
    val label: String,
    val mode: DeliveryMode,
    val socketPath: String,
    val ntfyUrl: String = NtfyServer.PUBLIC,
    val ntfyTopic: String = "",
    val ntfyToken: String = "",
    val platform: ServicePlatform = ServicePlatform.Systemd,
) {
    companion object {
        const val ENDPOINT_FILE = "~/.config/paddock/push-endpoint.json"
    }
}

/** What running the setup script on the machine came to. [lines] are the relay's own reason codes (never secrets) for a refused configuration. */
sealed interface SetupOutcome {
    /** The relay is configured, started and set to come back. [lingerNote] is set when the machine would not keep it running after a logout. */
    data class Done(val lingerNote: String? = null) : SetupOutcome
    data object NoSystemd : SetupOutcome
    data object NoPython : SetupOutcome

    /** A Mac with nobody logged in to its desktop: a LaunchAgent has no session to load into. */
    data object NoLoginSession : SetupOutcome
    data class CheckFailed(val lines: List<String>) : SetupOutcome
    data class Failed(val message: String) : SetupOutcome
}

/**
 * The script that turns alerts on for one machine: it writes the relay's unit and configuration, checks the configuration with the relay's own `--check`,
 * and only then enables and restarts the unit. The same text is what the app runs (over SSH, on one confirmation that lists it) and what a user can copy and
 * read first, so what is shown is what runs. It never starts anything before the check passes, keeps a copy of a configuration it replaces
 * (`alert-relay.toml.bak`), and writes everything owner-only.
 *
 * Nothing in it is interpolated from free text: the unit is the pinned file, every value goes through a rule of [NtfyServer] or an escape, and a here-document
 * terminator inside a pinned file is refused. Commands that could read the script's own input run with their input closed, because the script arrives on stdin.
 */
object AlertSetupScript {
    private const val UNIT_FILE = "~/.config/systemd/user/paddock-alert-relay.service"

    /** The LaunchAgent's label (and its file name) on a Mac. */
    const val LAUNCH_LABEL = "io.github.tuthan.paddock-alert-relay"
    private const val PLIST_FILE = "~/Library/LaunchAgents/$LAUNCH_LABEL.plist"
    private const val MAC_LOG = "Library/Logs/paddock-alert-relay.log"
    private const val CONFIG = "~/.config/paddock/alert-relay.toml"
    private const val RELAY = "~/.local/share/paddock/paddock-alert-relay.py"

    private val PROFILE = Regex("[a-z0-9][a-z0-9-]{0,40}")

    /** A TOML basic string for [text]: backslash and quote escaped, control characters dropped, so a name with a quote in it cannot end the string early. */
    fun tomlString(text: String): String =
        "\"" + text.filterNot { Character.isISOControl(it) }.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The relay's configuration for [setup]. Every key is one the relay's own parser accepts for that delivery (the test runs its `parse_config` on this). */
    fun config(setup: AlertSetup): String {
        require(PROFILE.matches(setup.profileId)) { "unexpected machine id" }
        require(setup.socketPath.startsWith("/") && setup.socketPath.endsWith("/herdr.sock") && setup.socketPath.none { Character.isISOControl(it) || it == '"' || it == '\\' }) { "unexpected socket path" }
        val label = setup.label.filterNot { Character.isISOControl(it) }.trim().take(40).ifBlank { setup.profileId }
        return buildString {
            appendLine("# Written by Paddock. Paddock rewrites this file when you change the alert setup; the previous one is kept as alert-relay.toml.bak.")
            appendLine("socket = ${tomlString(setup.socketPath)}")
            appendLine("profile = ${tomlString(setup.profileId)}")
            appendLine("label = ${tomlString(label)}")
            appendLine("delivery = ${tomlString(setup.mode.wire)}")
            appendLine()
            when (setup.mode) {
                DeliveryMode.NtfyApp -> {
                    val url = (NtfyServer.normalize(setup.ntfyUrl) as? NtfyServer.Result.Ok)?.url ?: throw IllegalArgumentException("unexpected server address")
                    require(NtfyServer.validTopic(setup.ntfyTopic)) { "unexpected topic" }
                    require(setup.ntfyToken.isEmpty() || NtfyServer.validToken(setup.ntfyToken)) { "unexpected token" }
                    appendLine("[ntfy]")
                    appendLine("url = ${tomlString(url)}")
                    appendLine("topic = ${tomlString(setup.ntfyTopic)}")
                    if (setup.ntfyToken.isNotEmpty()) appendLine("token = ${tomlString(setup.ntfyToken)}")
                }
                DeliveryMode.Push -> {
                    appendLine("[unifiedpush]")
                    appendLine("endpoint_file = ${tomlString(AlertSetup.ENDPOINT_FILE)}")
                }
            }
        }
    }

    /**
     * Shell that sets `PY` to a Python 3.11 or newer on a Mac, or says `paddock-setup: no-python` and exits 21. The relay reads its configuration with `tomllib`, which
     * Python 3.11 brought; an SSH command's PATH on a Mac is only `/usr/bin:/bin:/usr/sbin:/sbin`, so Homebrew's and MacPorts' folders are named, and the system
     * `/usr/bin/python3` is skipped on purpose (it is 3.9, and running it on a Mac without the developer tools opens an install dialog). Run with its input closed.
     */
    val FIND_PYTHON: String = run {
        val d = "\$"
        listOf(
            "PATH=\"${d}HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:${d}HOME/.pyenv/shims:${d}PATH\"",
            "PY=",
            "for c in python3.14 python3.13 python3.12 python3.11 python3; do",
            "  p=${d}(command -v ${d}c 2>/dev/null) || continue",
            "  [ \"${d}p\" = /usr/bin/python3 ] && continue",
            "  if \"${d}p\" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' </dev/null >/dev/null 2>&1; then PY=${d}p; break; fi",
            "done",
            "[ -n \"${d}PY\" ] || { echo 'paddock-setup: no-python'; exit 21; }",
        ).joinToString("\n")
    }

    /** The script. [unit] is the pinned unit file, [scriptSha256] the pinned relay's hash, named in the first line so the text says which relay it is for. */
    fun build(unit: String, setup: AlertSetup, scriptSha256: String): String =
        if (setup.platform == ServicePlatform.Launchd) buildLaunchd(setup, scriptSha256) else buildSystemd(unit, setup, scriptSha256)

    /**
     * The same setup for a Mac: the relay runs as a launchd LaunchAgent in the user's login session instead of a systemd user unit. The plist is written here, with the
     * absolute paths of the Python found on the machine, because launchd expands neither `~` nor `$HOME`. It is loaded into `gui/<uid>`, which exists while the user is
     * logged in at the Mac's desktop; with nobody logged in the script stops there and says so. Nothing starts before the relay's own `--check` passes.
     */
    private fun buildLaunchd(setup: AlertSetup, scriptSha256: String): String {
        val config = config(setup)
        require("PADDOCK_CONF" !in config) { "a here-document terminator inside the configuration" }
        val d = "\$"
        return buildString {
            appendLine("# Paddock alert relay setup for ${setup.profileId} (${setup.mode.wire}), relay script sha256 $scriptSha256, macOS (launchd)")
            appendLine("# Writes the LaunchAgent and the configuration, checks the configuration, then loads and starts the agent. Nothing starts before the check passes.")
            appendLine("[ \"${d}(uname -s)\" = Darwin ] || { echo 'paddock-setup: wrong-os'; exit 24; }")
            appendLine(FIND_PYTHON)
            appendLine("case \"${d}HOME${d}PY\" in *'&'*|*'<'*|*'>'*|*'\"'*) echo 'paddock-setup: odd-path'; exit 25;; esac")
            appendLine("set -e")
            appendLine("umask 077")
            appendLine("mkdir -p ~/Library/LaunchAgents ~/Library/Logs ~/.config/paddock")
            appendLine("cat > $CONFIG.new <<'PADDOCK_CONF'")
            append(config.trimEnd('\n')).append('\n')
            appendLine("PADDOCK_CONF")
            appendLine("chmod 600 $CONFIG.new")
            appendLine("if [ -e $CONFIG ] && ! cmp -s $CONFIG $CONFIG.new </dev/null; then cp -p $CONFIG $CONFIG.bak; fi")
            appendLine("mv -f $CONFIG.new $CONFIG")
            appendLine("\"${d}PY\" $RELAY --check </dev/null || { echo 'paddock-setup: check-failed'; exit 22; }")
            appendLine("cat > $PLIST_FILE <<PADDOCK_PLIST")
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            appendLine("<plist version=\"1.0\">")
            appendLine("<dict>")
            appendLine("  <key>Label</key><string>$LAUNCH_LABEL</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine("    <string>${d}PY</string>")
            appendLine("    <string>${d}HOME/.local/share/paddock/paddock-alert-relay.py</string>")
            appendLine("    <string>--config</string>")
            appendLine("    <string>${d}HOME/.config/paddock/alert-relay.toml</string>")
            appendLine("  </array>")
            appendLine("  <key>RunAtLoad</key><true/>")
            appendLine("  <key>KeepAlive</key><true/>")
            appendLine("  <key>ThrottleInterval</key><integer>5</integer>")
            appendLine("  <key>StandardOutPath</key><string>${d}HOME/$MAC_LOG</string>")
            appendLine("  <key>StandardErrorPath</key><string>${d}HOME/$MAC_LOG</string>")
            appendLine("</dict>")
            appendLine("</plist>")
            appendLine("PADDOCK_PLIST")
            appendLine("DOM=\"gui/${d}(id -u)\"")
            appendLine("launchctl bootout \"${d}DOM/$LAUNCH_LABEL\" </dev/null >/dev/null 2>&1 || true")
            appendLine("launchctl enable \"${d}DOM/$LAUNCH_LABEL\" </dev/null >/dev/null 2>&1 || true")
            appendLine("launchctl bootstrap \"${d}DOM\" $PLIST_FILE </dev/null || { echo 'paddock-setup: no-login-session'; exit 26; }")
            appendLine("launchctl kickstart -k \"${d}DOM/$LAUNCH_LABEL\" </dev/null")
            appendLine("echo 'paddock-setup: login-only'")
            appendLine("echo 'paddock-setup: done'")
        }
    }

    private fun buildSystemd(unit: String, setup: AlertSetup, scriptSha256: String): String {
        require("PADDOCK_UNIT" !in unit) { "a here-document terminator inside a pinned file" }
        val config = config(setup)
        require("PADDOCK_CONF" !in config) { "a here-document terminator inside the configuration" }
        val d = "\$"
        return buildString {
            appendLine("# Paddock alert relay setup for ${setup.profileId} (${setup.mode.wire}), relay script sha256 $scriptSha256")
            appendLine("# Writes the unit and the configuration, checks the configuration, then enables and (re)starts the unit. Nothing starts before the check passes.")
            appendLine("command -v python3 >/dev/null 2>&1 || { echo 'paddock-setup: no-python'; exit 21; }")
            appendLine("command -v systemctl >/dev/null 2>&1 || { echo 'paddock-setup: no-systemd'; exit 20; }")
            appendLine("set -e")
            appendLine("umask 077")
            appendLine("mkdir -p ~/.config/systemd/user ~/.config/paddock")
            appendLine("cat > $UNIT_FILE <<'PADDOCK_UNIT'")
            append(unit.trimEnd('\n')).append('\n')
            appendLine("PADDOCK_UNIT")
            appendLine("cat > $CONFIG.new <<'PADDOCK_CONF'")
            append(config.trimEnd('\n')).append('\n')
            appendLine("PADDOCK_CONF")
            appendLine("chmod 600 $CONFIG.new")
            appendLine("if [ -e $CONFIG ] && ! cmp -s $CONFIG $CONFIG.new </dev/null; then cp -p $CONFIG $CONFIG.bak; fi")
            appendLine("mv -f $CONFIG.new $CONFIG")
            appendLine("python3 $RELAY --check </dev/null || { echo 'paddock-setup: check-failed'; exit 22; }")
            appendLine("export XDG_RUNTIME_DIR=\"${d}{XDG_RUNTIME_DIR:-/run/user/${d}(id -u)}\"")
            appendLine("export DBUS_SESSION_BUS_ADDRESS=\"${d}{DBUS_SESSION_BUS_ADDRESS:-unix:path=${d}XDG_RUNTIME_DIR/bus}\"")
            appendLine("systemctl --user daemon-reload </dev/null")
            appendLine("systemctl --user enable paddock-alert-relay.service </dev/null")
            appendLine("systemctl --user restart paddock-alert-relay.service </dev/null")
            appendLine("loginctl enable-linger \"${d}USER\" </dev/null || echo 'paddock-setup: linger-failed'")
            appendLine("echo 'paddock-setup: done'")
        }
    }

    /** Reads what the script said. The relay's `--check` reasons arrive on stderr, one `paddock-alert-relay: ...` line each, and are never secrets. */
    fun outcome(exit: Int, stdout: String, stderr: String): SetupOutcome {
        val lines = stderr.lines().map { it.trim() }.filter { it.startsWith("paddock-alert-relay:") }.map { it.removePrefix("paddock-alert-relay:").trim().take(200) }.take(4)
        return when {
            "paddock-setup: no-python" in stdout -> SetupOutcome.NoPython
            "paddock-setup: no-systemd" in stdout -> SetupOutcome.NoSystemd
            "paddock-setup: check-failed" in stdout -> SetupOutcome.CheckFailed(lines)
            "paddock-setup: no-login-session" in stdout -> SetupOutcome.NoLoginSession
            "paddock-setup: wrong-os" in stdout -> SetupOutcome.Failed("This machine is not a Mac, so the Mac setup cannot run on it. Open its page and set the icon to Linux.")
            "paddock-setup: odd-path" in stdout -> SetupOutcome.Failed("A folder on this Mac has a character that cannot go in the LaunchAgent file (& < > or a quote). Nothing was started.")
            exit == 0 && "paddock-setup: done" in stdout ->
                SetupOutcome.Done(lingerNote = if ("paddock-setup: login-only" in stdout) MAC_LOGIN_NOTE else LINGER_NOTE.takeIf { "paddock-setup: linger-failed" in stdout })
            else -> SetupOutcome.Failed(stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.take(160) ?: "the setup stopped (exit $exit)")
        }
    }

    /**
     * Turns the relay off: stops and disables the unit and removes the address file (the capability for the phone's alerts). The configuration and the unit file stay, so
     * turning alerts on again keeps the topic. One constant script, nothing interpolated.
     */
    fun turnOff(platform: ServicePlatform = ServicePlatform.Systemd): String {
        val d = "\$"
        if (platform == ServicePlatform.Launchd) return buildString {
            appendLine("[ \"${d}(uname -s)\" = Darwin ] || { echo 'paddock-setup: wrong-os'; exit 24; }")
            appendLine("DOM=\"gui/${d}(id -u)\"")
            appendLine("launchctl bootout \"${d}DOM/$LAUNCH_LABEL\" </dev/null >/dev/null 2>&1")
            // `disable` is what keeps the agent from coming back at the next login: its plist stays on the Mac (turning alerts on again reuses it).
            appendLine("launchctl disable \"${d}DOM/$LAUNCH_LABEL\" </dev/null >/dev/null 2>&1")
            appendLine("rm -f ~/.config/paddock/push-endpoint.json")
            appendLine("if launchctl print \"${d}DOM/$LAUNCH_LABEL\" </dev/null >/dev/null 2>&1; then echo 'paddock-setup: still-running'; exit 23; fi")
            appendLine("echo 'paddock-setup: off'")
        }
        return buildString {
            appendLine("command -v systemctl >/dev/null 2>&1 || { echo 'paddock-setup: no-systemd'; exit 20; }")
            appendLine("export XDG_RUNTIME_DIR=\"${d}{XDG_RUNTIME_DIR:-/run/user/${d}(id -u)}\"")
            appendLine("export DBUS_SESSION_BUS_ADDRESS=\"${d}{DBUS_SESSION_BUS_ADDRESS:-unix:path=${d}XDG_RUNTIME_DIR/bus}\"")
            appendLine("systemctl --user disable --now paddock-alert-relay.service </dev/null >/dev/null 2>&1")
            appendLine("rm -f ~/.config/paddock/push-endpoint.json")
            appendLine("if systemctl --user is-active --quiet paddock-alert-relay.service </dev/null; then echo 'paddock-setup: still-running'; exit 23; fi")
            appendLine("echo 'paddock-setup: off'")
        }
    }

    fun turnOffOutcome(exit: Int, stdout: String): SetupOutcome = when {
        "paddock-setup: no-systemd" in stdout -> SetupOutcome.NoSystemd
        "paddock-setup: wrong-os" in stdout -> SetupOutcome.Failed("This machine is not a Mac, so the Mac way of turning the relay off cannot run on it.")
        exit == 0 && "paddock-setup: off" in stdout -> SetupOutcome.Done()
        "paddock-setup: still-running" in stdout -> SetupOutcome.Failed("the relay is still running on the machine")
        else -> SetupOutcome.Failed("the machine did not confirm that the relay is off")
    }

    /** Said after a Mac setup: a LaunchAgent lives in the login session, which is the Mac's equivalent of a user manager that stops at logout. */
    const val MAC_LOGIN_NOTE = "The relay runs while you are logged in to this Mac's desktop and starts again at your next login. For a Mac nobody logs in to, turn on automatic login in System Settings > Users & Groups."

    const val LINGER_NOTE = "The relay runs now but will stop when you log out of the machine. Run `sudo loginctl enable-linger \$USER` there to keep it running."

    /** A host's test alert: reads the same configuration the relay does and posts one message the way the relay would. Run with `python3 -`, the script on stdin. */
    const val TEST_PY = """
import json, os, sys, tomllib, urllib.request, urllib.parse, random
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
try:
    path = os.path.expanduser("~/.config/paddock/alert-relay.toml")
    with open(path, "rb") as f: cfg = tomllib.load(f)
    opener = urllib.request.build_opener(NoRedirect)
    if cfg["delivery"] == "ntfy":
        t = cfg["ntfy"]
        body = {"topic": t["topic"], "title": "Paddock: test on %s" % cfg.get("label", "this machine"), "message": "This is a test alert. Paddock is set up.", "priority": 3, "tags": ["paddock"]}
        req = urllib.request.Request(t["url"].rstrip("/"), data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        if t.get("token"): req.add_header("Authorization", "Bearer " + t["token"])
    else:
        with open(os.path.expanduser(cfg["unifiedpush"]["endpoint_file"])) as f: endpoint = json.load(f)["endpoint"]
        body = {"v": 1, "h": cfg["profile"], "n": "test%d" % random.randrange(10**9)}
        req = urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with opener.open(req, timeout=15) as r: print("paddock-test: ok %d" % r.status)
except Exception as e:
    print("paddock-test: error %s" % type(e).__name__)
    sys.exit(1)
"""

    sealed interface TestOutcome {
        data object Sent : TestOutcome
        data class Failed(val reason: String) : TestOutcome
    }

    fun testOutcome(exit: Int, stdout: String): TestOutcome = when {
        exit == 0 && "paddock-test: ok" in stdout -> TestOutcome.Sent
        "paddock-setup: no-python" in stdout -> TestOutcome.Failed("no Python 3.11 or newer on the machine")
        else -> TestOutcome.Failed(stdout.lines().firstOrNull { it.startsWith("paddock-test: error") }?.removePrefix("paddock-test: error")?.trim()?.take(60)?.ifBlank { null } ?: "the machine could not send it")
    }
}
