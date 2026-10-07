package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.HostRefusal
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayRefused
import io.github.tuthan.paddock.relay.RelayState
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The writer refused or could not publish a decision; [code] is one of the [AnswerCodes]. Certain: no decision was published. */
class DecisionRefused(code: String, message: String) : HostRefusal(code, message)

/** The codes the journal keeps for a decision the writer answered with an exit status, and the sentence for each. */
object AnswerCodes {
    const val GONE = "request_gone"
    const val EXPIRED = "request_expired"
    const val TOO_LARGE = "request_too_large"
    const val MISMATCH = "request_mismatch"
    const val HOST_IO = "request_host_io"
    const val USAGE = "request_usage"

    fun forExit(exit: Int): String? = when (exit) {
        3 -> GONE
        4 -> EXPIRED
        5 -> TOO_LARGE
        6 -> MISMATCH
        7 -> HOST_IO
        2 -> USAGE
        else -> null
    }

    /** What a refused answer means for the person, by code. Null for a code that is not one of these. */
    fun sentence(code: String): String? = when (code) {
        GONE -> "Lost: the request was already answered, had expired, or was replaced. If you did not answer it on the desktop, look there."
        EXPIRED -> "Expired: the request's window had passed. Answer on the desktop."
        TOO_LARGE -> "The input is too large to show in full, so Paddock does not answer it. Answer on the desktop."
        MISMATCH -> "The request on the host is not the one this screen showed, so nothing was written. Review the request again."
        HOST_IO -> "The host could not write the decision, so nothing was decided here. Answer on the desktop."
        USAGE -> "The host refused the answer as malformed, so nothing was decided here."
        else -> NotAnswerable.entries.firstOrNull { it.code == code }?.sentence
    }
}

/**
 * What the phone can say about the scripts on one host: the writer, the hook and (for opencode) the plugin. [opencode] and [opencodeDestination] are
 * null on a build that ships no plugin.
 */
data class AnswerSetup(
    val decide: RelayState, val hook: RelayState, val decideDestination: String, val hookDestination: String,
    val opencode: RelayState? = null, val opencodeDestination: String? = null,
)

/**
 * The host side of guarded answers on one connection: the pinned scripts (the hook that publishes a request, the writer that lists and
 * answers requests, and the opencode plugin that hands opencode's requests to the hook), installed only on the user's say-so exactly like
 * the other host scripts, and the two calls the phone makes, `list` and `decide`. Both run `paddock-decide.py` and nothing else; no call reaches herdr and no key is ever sent.
 *
 * Every call checks the script's hash and runs it in one command, so a copy that is not the pinned script is never run. The pane id
 * and the request id travel as argv words to a fixed script, never inside a shell string; the answer's own fields travel on stdin.
 */
class AnswerHost(
    private val ssh: SshSession,
    private val decide: RelayInstaller,
    private val hook: RelayInstaller,
    private val clock: Clock,
    /** The herdr session's name as the hook sees it (`HERDR_SESSION`, or "default"); it is the first directory under the runtime root. */
    val herdrSession: String,
    private val phoneLabel: String = "phone",
    /** The opencode plugin; null on a build that ships none. */
    private val plugin: RelayInstaller? = null,
) : AnswerPort {
    private var home: String? = null
    private suspend fun home(): String = home ?: decide.homeDirectory().also { home = it }

    val decideSha256: String get() = decide.expectedSha256
    val hookSha256: String get() = hook.expectedSha256
    val opencodeSha256: String? get() = plugin?.expectedSha256
    suspend fun hookDestination(): String = hook.destination(home())
    suspend fun decideDestination(): String = decide.destination(home())

    suspend fun inspect(): AnswerSetup {
        val h = home()
        return AnswerSetup(decide.state(h), hook.state(h), decide.destination(h), hook.destination(h), plugin?.state(h), plugin?.destination(h))
    }

    /** Writes every pinned script (owner-only, hash checked after). Called only after the user agreed to the shown hashes. */
    suspend fun install() {
        val h = home()
        decide.install(h)
        hook.install(h)
        plugin?.install(h)
    }

    /** One `list` of the pane's request files: a single exec, bounded, answered by the pinned script or refused. */
    override suspend fun list(paneId: String): RequestListing {
        val started = clock.nowMillis()
        val r = run(listOf("list", herdrSession, paneId), null)
        val received = clock.nowMillis()
        if (r.exit != 0) throw refusal(r.exit, r.stderr)
        return RequestListing.parse(r.stdout.toString(Charsets.UTF_8), received, (received - started).coerceAtLeast(0))
            ?: throw IllegalStateException("the host's list of requests was not in a form this version understands")
    }

    /**
     * Claims [requestId] and writes the decision, as one exec. Returns when the writer printed `decided`. A refusal from the writer
     * (gone, expired, too large, mismatch) is a [DecisionRefused]; a link that dies after the command went out throws something else,
     * and the caller's journal row then stays Unknown, to be settled by reading the files again.
     */
    override suspend fun decide(paneId: String, claudeSessionId: String, requestId: String, behavior: Behavior, beforeWrite: suspend () -> Unit) {
        val stdin = buildJsonObject {
            put("behavior", behavior.wire)
            put("phone", phoneLabel)
            put("at", java.time.Instant.ofEpochMilli(clock.nowMillis()).toString())
        }.toString().toByteArray(Charsets.UTF_8)
        beforeWrite()
        val r = run(listOf("decide", herdrSession, paneId, claudeSessionId, requestId), stdin)
        if (r.exit != 0) throw refusal(r.exit, r.stderr)
    }

    private suspend fun run(args: List<String>, stdin: ByteArray?): io.github.tuthan.paddock.ports.ExecResult {
        val path = decide.destination(home())
        return ssh.exec(listOf("sh", "-c", RUN, "paddock", path, decide.expectedSha256) + args, stdin = stdin, limits = if (args.first() == "list") LIST_LIMITS else DECIDE_LIMITS)
    }

    private fun refusal(exit: Int, stderr: ByteArray): Exception {
        if (exit == NOT_PINNED) return RelayRefused(RelayState.Missing)
        val code = AnswerCodes.forExit(exit)
            ?: return IllegalStateException("the request writer ended with status $exit" + stderr.toString(Charsets.UTF_8).lineSequence().firstOrNull { it.startsWith("paddock-decide:") }?.let { ": ${it.take(160)}" }.orEmpty())
        return DecisionRefused(code, AnswerCodes.sentence(code).orEmpty())
    }

    companion object {
        /** Exit status of the wrapper when the file on the host is missing or is not the pinned script. */
        const val NOT_PINNED = 90
        /**
         * Checks the hash, then runs the script with the remaining words. `$0` is a label, `$1` the path and `$2` its pinned hash. The text holds
         * no single quote and no backslash: the exec layer refuses an argument with either (no quoting form carries them through every login shell).
         */
        private const val RUN = "p=\$1; h=\$2; shift 2; echo \"\$h  \$p\" | sha256sum -c --status - 2>/dev/null || exit $NOT_PINNED; exec python3 \"\$p\" \"\$@\""
        private val LIST_LIMITS = ExecLimits(stdoutMax = 512 * 1024, stderrMax = 4096, deadline = 10.seconds)
        private val DECIDE_LIMITS = ExecLimits(stdoutMax = 4096, stderrMax = 4096, deadline = 10.seconds)
    }
}

/**
 * The text the user pastes on the host, shown in full before it can be copied: the hook's configuration (written only if none exists, so
 * nothing the user set is overwritten) and, per agent, how to register the hook. Paddock edits no agent's settings.
 */
object AnswerSetupText {
    const val MAX_WINDOW_SECONDS = 300
    const val DEFAULT_WINDOW_SECONDS = 60
    /** Codex shows no prompt of its own while the hook waits, so its window is a delay of that prompt and its default is short. */
    const val CODEX_WINDOW_SECONDS = 20
    /** Codex's timeout beyond its window: the hook ends by itself a little after the window, so Codex never has to kill it. */
    const val CODEX_TIMEOUT_MARGIN = 10
    const val OPENCODE_PLUGIN_LINK = "~/.config/opencode/plugins/paddock-opencode-permission.js"

    fun configCommand(windowSeconds: Int, codexWindowSeconds: Int = minOf(windowSeconds, CODEX_WINDOW_SECONDS)): String {
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "the window is 1 to $MAX_WINDOW_SECONDS seconds" }
        require(codexWindowSeconds in 0..MAX_WINDOW_SECONDS) { "Codex's window is 0 to $MAX_WINDOW_SECONDS seconds" }
        return buildString {
            appendLine("# Paddock: how long a phone has to answer an agent's permission prompt (0 turns it off). Codex has a shorter window of its own.")
            appendLine("mkdir -p ~/.config/paddock")
            appendLine("[ -e ~/.config/paddock/hook.toml ] || printf 'window_seconds = $windowSeconds\\ncodex_window_seconds = $codexWindowSeconds\\n' > ~/.config/paddock/hook.toml")
            append("chmod 600 ~/.config/paddock/hook.toml")
        }
    }

    private fun checkHook(hookPath: String) = require(hookPath.startsWith("/") && '\n' !in hookPath && '"' !in hookPath && '\\' !in hookPath) { "unexpected hook path" }

    /** The JSON to add under `hooks.PermissionRequest` in `~/.claude/settings.json`; Claude Code's timeout is the window plus five seconds. */
    fun settingsSnippet(hookPath: String, windowSeconds: Int): String {
        checkHook(hookPath)
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "the window is 1 to $MAX_WINDOW_SECONDS seconds" }
        return buildString {
            appendLine("""{"hooks": {"PermissionRequest": [{"hooks": [{"type": "command",""")
            appendLine("""  "command": "python3 $hookPath",""")
            appendLine("""  "timeout": ${windowSeconds + 5}}]}]}}""")
        }.trimEnd()
    }

    /**
     * The JSON to add under `hooks.PermissionRequest` in `~/.codex/hooks.json`. The same hook, told which agent it serves with `--agent codex`; the
     * timeout is Codex's window plus [CODEX_TIMEOUT_MARGIN] seconds. Codex hashes the command and the timeout when the hook is trusted, so a change to
     * either asks for a new review.
     */
    fun codexSnippet(hookPath: String, codexWindowSeconds: Int): String {
        checkHook(hookPath)
        require(codexWindowSeconds in 1..MAX_WINDOW_SECONDS) { "Codex's window is 1 to $MAX_WINDOW_SECONDS seconds" }
        return buildString {
            appendLine("""{"hooks": {"PermissionRequest": [{"hooks": [{"type": "command",""")
            appendLine("""  "command": "python3 $hookPath --agent codex",""")
            appendLine("""  "timeout": ${codexWindowSeconds + CODEX_TIMEOUT_MARGIN}}]}]}}""")
        }.trimEnd()
    }

    /** The one command that registers the opencode plugin: a link from opencode's plugin directory to the pinned file Paddock installed. */
    fun opencodeCommand(pluginPath: String): String {
        require(pluginPath.startsWith("/") && pluginPath.none { it == '\n' || it == '\'' || it == '\\' }) { "unexpected plugin path" }
        return buildString {
            appendLine("# Paddock: let the phone answer opencode's permission prompts. The file is the one Paddock installed; it is linked, not copied.")
            appendLine("mkdir -p ~/.config/opencode/plugins")
            append("ln -sf '$pluginPath' $OPENCODE_PLUGIN_LINK")
        }
    }
}
