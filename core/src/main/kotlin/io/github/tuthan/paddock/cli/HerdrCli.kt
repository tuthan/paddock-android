package io.github.tuthan.paddock.cli

import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.PaddockJson
import io.github.tuthan.paddock.herdr.ProtocolError
import io.github.tuthan.paddock.ports.ExecResult

/** Where `agent read` takes its text from. The wire names are herdr's. */
enum class ReadSource(val wire: String) { Detection("detection"), Recent("recent"), RecentUnwrapped("recent-unwrapped") }

/**
 * Fixed argv templates for the herdr CLI, absolute path first and `--session <name>` second so a stray default
 * session is impossible. Nothing here accepts free text: ids are validated against herdr's id alphabet and a value
 * that could be read as a flag is refused before it is sent.
 */
class HerdrCli(private val herdr: String, private val session: String) {
    init {
        require(herdr.startsWith("/")) { "herdr path must be absolute" }
        require(SESSION_NAME.matches(session)) { "invalid session name" }
    }

    private fun scoped(vararg rest: String) = listOf(herdr, "--session", session) + rest

    fun status() = scoped("status")
    /** Session catalogue is host-wide, so it is not scoped to one session. */
    fun sessionList() = listOf(herdr, "session", "list", "--json")
    fun apiSnapshot() = scoped("api", "snapshot")
    fun agentList() = scoped("agent", "list")
    fun agentGet(paneId: String) = scoped("agent", "get", id(paneId))
    fun workspaceList() = scoped("workspace", "list")
    fun tabList() = scoped("tab", "list")
    fun paneGet(paneId: String) = scoped("pane", "get", id(paneId))

    /** `agent read` prints plain text, not the socket's JSON. [lines] is bounded so a caller cannot ask for a transcript. */
    fun agentRead(paneId: String, source: ReadSource, lines: Int = 40, ansi: Boolean = false): List<String> {
        require(lines in 1..MAX_READ_LINES) { "lines out of range" }
        return scoped("agent", "read", id(paneId), "--source", source.wire, "--lines", lines.toString()) + if (ansi) listOf("--format", "ansi") else emptyList()
    }

    private fun id(value: String): String { require(ID.matches(value)) { "invalid id"}; return value }

    companion object {
        const val MAX_READ_LINES = 500
        /** `w2:p1`, `w2:t1`, `term_65cbe353cc3172`: letters, digits, `_`, `:`, `.`, `-`, never leading `-`. */
        val ID = Regex("[A-Za-z0-9_][A-Za-z0-9_:.-]{0,63}")
        /** One rule for every place a session name is accepted (the profile form included): starts with a letter or digit. */
        val SESSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}

/** What a finished CLI call means. */
sealed interface CliOutcome {
    /** Exit 0. [stdout] is JSON for most commands and text for `status` and `agent read`. */
    data class Ok(val stdout: String, val stderr: String) : CliOutcome
    /** A non-zero exit that carried herdr's `{"error":{…}}`, on either stream. */
    data class Failure(val code: String, val message: String) : CliOutcome
    /** Exit 2: herdr rejected our usage. A bug in this client; the caller disables the command until the pin is revisited. */
    data class ClientBug(val stderr: String) : CliOutcome
    /** Non-zero exit with nothing parseable. */
    data class Unparsed(val exit: Int, val stderr: String) : CliOutcome
}

object CliResult {
    /** Truncated output is never trusted as a whole answer, so it is reported as unparsed. */
    fun classify(r: ExecResult): CliOutcome {
        val out = r.stdout.toString(Charsets.UTF_8); val err = r.stderr.toString(Charsets.UTF_8)
        if (r.exit == 0) return if (r.stdoutTruncated) CliOutcome.Unparsed(0, "stdout truncated") else CliOutcome.Ok(out, err)
        if (r.exit == 2) return CliOutcome.ClientBug(err.trim().take(300))
        for (text in listOf(err, out)) {
            val failure = try { Envelope.parse(text.trim()) as? Message.Failure } catch (_: ProtocolError) { null }
            if (failure != null) return CliOutcome.Failure(failure.code, failure.message)
        }
        return CliOutcome.Unparsed(r.exit, err.trim().take(300))
    }

    /** Parses the JSON stdout of an Ok result as one envelope and decodes its result as [T]. */
    inline fun <reified T> decode(ok: CliOutcome.Ok, expectedType: String): T =
        (Envelope.parse(ok.stdout.trim(), io.github.tuthan.paddock.herdr.Budgets.SNAPSHOT_LINE) as? Message.Success
            ?: throw ProtocolError.UnknownShape(emptySet())).decode<T>(expectedType, PaddockJson)
}

/** `herdr status`, which prints text with indented `key: value` lines under `client:`, `server:` and `update:` headings. */
data class HerdrStatus(
    val clientVersion: String,
    val channel: String?,
    val protocol: Int,
    val endpointProtocolGeneration: Int?,
    /** `running` when a server answers; anything else is shown as a distinct machine fact, never as a socket error. */
    val serverStatus: String?,
    val serverVersion: String?,
    val endpointCompatible: Boolean?,
    val privateProtocol: Int?,
    val privateProtocolCompatible: Boolean?,
    val socket: String?,
    val restartNeeded: Boolean?,
    val serverBinaryStale: Boolean?,
) {
    val serverRunning: Boolean get() = serverStatus == "running"
}

object StatusParser {
    /** Unknown sections and keys are ignored; a missing client version or protocol is a decode error. */
    fun parse(text: String): HerdrStatus {
        val sections = HashMap<String, HashMap<String, String>>()
        var section = ""
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            if (!raw.first().isWhitespace() && raw.trimEnd().endsWith(":")) { section = raw.trim().removeSuffix(":"); continue }
            val i = raw.indexOf(':'); if (i < 0) continue
            sections.getOrPut(section) { HashMap() }[raw.substring(0, i).trim()] = raw.substring(i + 1).trim()
        }
        val client = sections["client"].orEmpty(); val server = sections["server"].orEmpty(); val update = sections["update"].orEmpty()
        fun missing(what: String): Nothing = throw ProtocolError.Decode("herdr status", IllegalArgumentException("missing $what"))
        return HerdrStatus(
            clientVersion = client["version"] ?: missing("client.version"),
            channel = client["channel"],
            protocol = client["protocol"]?.toIntOrNull() ?: missing("client.protocol"),
            endpointProtocolGeneration = client["endpoint_protocol_generation"]?.toIntOrNull(),
            serverStatus = server["status"], serverVersion = server["version"],
            endpointCompatible = server["endpoint_compatible"]?.yesNo(),
            privateProtocol = server["private_protocol"]?.toIntOrNull(),
            privateProtocolCompatible = server["private_protocol_compatible"]?.yesNo(),
            socket = server["socket"],
            restartNeeded = update["restart_needed"]?.yesNo(), serverBinaryStale = update["server_binary_stale"]?.yesNo(),
        )
    }
    private fun String.yesNo(): Boolean? = when (lowercase()) { "yes", "true" -> true; "no", "false" -> false; else -> null }
}
