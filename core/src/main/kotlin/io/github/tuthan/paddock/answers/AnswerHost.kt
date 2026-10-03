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

/** What the phone can say about the two scripts on one host. */
data class AnswerSetup(val decide: RelayState, val hook: RelayState, val decideDestination: String, val hookDestination: String)

/**
 * The host side of guarded answers on one connection: the two pinned scripts (the hook that publishes a request and the writer that
 * lists and answers requests), installed only on the user's say-so exactly like the other host scripts, and the two calls the phone
 * makes, `list` and `decide`. Both run `paddock-decide.py` and nothing else; no call reaches herdr and no key is ever sent.
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
) : AnswerPort {
    private var home: String? = null
    private suspend fun home(): String = home ?: decide.homeDirectory().also { home = it }

    val decideSha256: String get() = decide.expectedSha256
    val hookSha256: String get() = hook.expectedSha256
    suspend fun hookDestination(): String = hook.destination(home())
    suspend fun decideDestination(): String = decide.destination(home())

    suspend fun inspect(): AnswerSetup {
        val h = home()
        return AnswerSetup(decide.state(h), hook.state(h), decide.destination(h), hook.destination(h))
    }

    /** Writes both pinned scripts (owner-only, hash checked after). Called only after the user agreed to the shown hashes. */
    suspend fun install() {
        val h = home()
        decide.install(h)
        hook.install(h)
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
 * The text the user pastes on the host, shown in full before it can be copied: the hook's configuration (written only if none
 * exists, so nothing the user set is overwritten) and the registration to merge into `~/.claude/settings.json`. Paddock does not
 * edit Claude Code's settings.
 */
object AnswerSetupText {
    const val MAX_WINDOW_SECONDS = 300
    const val DEFAULT_WINDOW_SECONDS = 60

    fun configCommand(windowSeconds: Int): String {
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "the window is 1 to $MAX_WINDOW_SECONDS seconds" }
        return buildString {
            appendLine("# Paddock: how long a phone has to answer a Claude Code permission prompt (0 turns it off).")
            appendLine("mkdir -p ~/.config/paddock")
            appendLine("[ -e ~/.config/paddock/hook.toml ] || printf 'window_seconds = $windowSeconds\\n' > ~/.config/paddock/hook.toml")
            append("chmod 600 ~/.config/paddock/hook.toml")
        }
    }

    /** The JSON to add under `hooks.PermissionRequest` in `~/.claude/settings.json`; Claude Code's timeout is the window plus five seconds. */
    fun settingsSnippet(hookPath: String, windowSeconds: Int): String {
        require(hookPath.startsWith("/") && '\n' !in hookPath && '"' !in hookPath && '\\' !in hookPath) { "unexpected hook path" }
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "the window is 1 to $MAX_WINDOW_SECONDS seconds" }
        return buildString {
            appendLine("""{"hooks": {"PermissionRequest": [{"hooks": [{"type": "command",""")
            appendLine("""  "command": "python3 $hookPath",""")
            appendLine("""  "timeout": ${windowSeconds + 5}}]}]}}""")
        }.trimEnd()
    }
}
