package io.github.tuthan.paddock.demo

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.cli.HerdrLocator
import io.github.tuthan.paddock.hostprofile.HostNameProbe
import io.github.tuthan.paddock.hostprofile.HostOsProbe
import io.github.tuthan.paddock.cli.argvToCommand
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An [SshSession] to the demo machine ([Demo]): what herdr and the relay script would answer over SSH, answered from [herd] in memory. It matches each
 * [exec] and [openStream] against the fixed commands the app sends (the same argv templates the app builds: [HerdrCli], [HerdrLocator], the probes, the
 * relay's own command line); anything else is refused, never guessed at. There is no `java.net` here and no file: it cannot reach a network or a disk
 * (`DemoSessionTest` reads this source to prove it, and the release smoke samples the app's sockets while the demo runs).
 *
 * [relaySha256] is the hash of the relay script the app bundles: the answer to the check that the relay on the machine is the pinned one, so the install
 * screen is never shown. Nothing the demo is asked to install is written anywhere.
 */
class DemoSession(private val herd: DemoHerd, private val relaySha256: String, private val clock: Clock) : SshSession {
    private val _link = MutableStateFlow<LinkState>(LinkState.Up(clock.nowMillis()))
    override val link: StateFlow<LinkState> = _link

    /** The commands that matched nothing, newest last and bounded: a test asserts this stays empty for everything the app does in the demo. */
    val unanswered = CopyOnWriteArrayList<List<String>>()

    private class Reply(val exit: Int, val out: String = "", val err: String = "")

    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
        // The real session sends one quoted command line and refuses an argument no login shell would read the same way; so does this one.
        argvToCommand(argv)
        val r = answer(argv) ?: run { remember(argv); Reply(127, err = "demo: not available\n") }
        return ExecResult(r.exit, r.out.toByteArray(), r.err.toByteArray(), false, false, Duration.ZERO)
    }

    override suspend fun openStream(argv: List<String>): StreamChannel {
        argvToCommand(argv)
        if (argv.size == 3 && argv[0] == "python3" && argv[1] == Demo.RELAY_PATH && argv[2] == Demo.SOCKET_PATH) return RelayChannel()
        observe(argv)?.let { return it }
        remember(argv)
        throw IOException("not in the demo")
    }

    override suspend fun close() { _link.value = LinkState.Down(DownReason.Closed, clock.nowMillis()) }

    private fun remember(argv: List<String>) { if (unanswered.size < MAX_REMEMBERED) unanswered += argv }

    // ---- exec ----

    private fun answer(argv: List<String>): Reply? {
        if (argv.size == 3 && argv[0] == "sh" && argv[1] == "-c") return when (argv[2]) {
            HerdrLocator.SCRIPT -> Reply(0, Demo.HERDR_PATH)
            "printf %s \"\$HOME\"" -> Reply(0, Demo.HOME)
            HostOsProbe.COMMAND -> Reply(0, "Linux\n")
            HostNameProbe.COMMAND -> Reply(0, Demo.HOSTNAME + "\n")
            else -> null
        }
        if (argv.size == 3 && argv[0] == "sha256sum" && argv[1] == "--") {
            return if (argv[2] == Demo.RELAY_PATH) Reply(0, "$relaySha256  ${argv[2]}\n") else Reply(1, err = "sha256sum: ${argv[2]}: No such file or directory\n")
        }
        // The terminal's size, which Paddock asks for before it offers control (PtyProbe): the pane's PTY is as big as the herd says.
        if (argv.size == 2 && argv[0] == "cat") herd.shellPids().entries.firstOrNull { "/proc/${it.value}/stat" == argv[1] }?.let { (pane, pid) ->
            val ttyNr = (136 shl 8) or herd.paneIds.indexOf(pane)
            return Reply(0, "$pid (bash) S 1 $pid $pid $ttyNr $pid 4194304 0 0 0 0 0 0 0 0 20 0 1 0 1 0 0\n")
        }
        if (argv.size == 4 && argv[0] == "stty" && argv[1] == "-F" && argv[3] == "size" && argv[2].startsWith("/dev/pts/")) return Reply(0, "${DemoHerd.ROWS} ${DemoHerd.COLS}\n")
        if (argv.firstOrNull() != Demo.HERDR_PATH) return null
        val rest = argv.drop(1)
        if (rest.size >= 2 && rest[0] == "session") return when {
            rest == listOf("session", "list", "--json") -> Reply(0, sessionList())
            rest.size == 4 && rest[1] in setOf("stop", "delete") && rest[3] == "--json" -> refusal("cli:session:${rest[1]}")
            else -> null
        }
        if (rest.firstOrNull() == "plugin") return if (rest == listOf("plugin", "list", "--plugin", io.github.tuthan.paddock.relay.PluginLocator.PLUGIN_ID, "--json")) Reply(0, PLUGINS) else null
        if (rest.size < 3 || rest[0] != "--session" || rest[1] != Demo.SESSION) return null
        return cli(rest.drop(2))
    }

    /** What `herdr --session demo <args>` answers. */
    private fun cli(args: List<String>): Reply? = when {
        args == listOf("api", "snapshot") -> Reply(0, line(success("cli:api:snapshot", "session_snapshot") { put("snapshot", herd.snapshot()) }))
        args == listOf("agent", "list") -> Reply(0, line(success("cli:agent:list", "agent_list") { put("agents", JsonArray(herd.paneIds.mapNotNull { herd.agent(it) })) }))
        args.size == 3 && args[0] == "agent" && args[1] == "get" -> herd.agent(args[2])
            ?.let { agent -> Reply(0, line(success("cli:agent:get", "agent_info") { put("agent", agent) })) } ?: notFound("cli:agent:get", "agent_not_found")
        args.size >= 7 && args[0] == "agent" && args[1] == "read" && args[3] == "--source" && args[5] == "--lines" -> read(args)
        args.size == 3 && args[0] == "pane" && args[1] == "get" -> herd.agent(args[2])
            ?.let { agent -> Reply(0, line(success("cli:pane:get", "pane_info") { put("pane", agent) })) } ?: notFound("cli:pane:get", "pane_not_found")
        args.size == 4 && args[0] == "pane" && args[1] == "process-info" && args[2] == "--pane" -> herd.shellPids()[args[3]]
            ?.let { pid -> Reply(0, line(success("cli:pane:process_info", "pane_process_info") { put("process_info", buildJsonObject { put("pane_id", args[3]); put("shell_pid", pid); put("foreground_process_group_id", pid) }) })) }
            ?: notFound("cli:pane:process_info", "pane_not_found")
        args.firstOrNull() in REFUSED -> refusal("cli:${args[0]}")
        else -> null
    }

    private fun read(args: List<String>): Reply {
        val source = args[4]
        val lines = args[6].toIntOrNull() ?: return Reply(2, err = "demo: bad --lines\n")
        val text = herd.read(args[2], detection = source == "detection", lines = lines.coerceIn(1, HerdrCli.MAX_READ_LINES), ansi = args.size > 8 && args[7] == "--format" && args[8] == "ansi")
        return if (text == null) notFound("cli:agent:read", "agent_not_found") else Reply(0, text)
    }

    private fun sessionList(): String = buildJsonObject {
        put("sessions", JsonArray(listOf(buildJsonObject {
            put("default", true); put("name", Demo.SESSION); put("running", true); put("session_dir", Demo.SESSION_DIR); put("socket_path", Demo.SOCKET_PATH)
        })))
    }.toString()

    private fun notFound(id: String, code: String) = Reply(1, out = line(failure(id, code, "No such agent in the demo.")))

    private fun refusal(id: String) = Reply(1, out = line(failure(id, DemoCopy.REFUSAL_CODE, DemoCopy.REFUSAL)))

    // ---- streams ----

    private fun observe(argv: List<String>): StreamChannel? {
        // /bin/sh -c <watch script> paddock-observe herdr --session demo terminal session observe <pane> --cols N --rows M
        if (argv.size != 15 || argv[0] != "/bin/sh" || argv[1] != "-c" || argv[2] != HerdrCli.OBSERVE_WATCH || argv[3] != "paddock-observe") return null
        val a = argv.drop(4)
        if (a[0] != Demo.HERDR_PATH || a[1] != "--session" || a[2] != Demo.SESSION || a.subList(3, 6) != listOf("terminal", "session", "observe") || a[7] != "--cols" || a[9] != "--rows") return null
        val cols = a[8].toIntOrNull() ?: return null
        val rows = a[10].toIntOrNull() ?: return null
        return ObserveChannel(a[6], cols, rows)
    }

    /** `terminal session observe`: one full frame of the agent's screen, then silence, as a pane that does not draw. Input is ignored (the demo never takes control). */
    private inner class ObserveChannel(private val pane: String, private val cols: Int, private val rows: Int) : StreamChannel {
        private val done = CompletableDeferred<Unit>()
        override val stdout: Flow<ByteArray> = flow {
            emit(line(herd.frame(pane, cols, rows) ?: buildJsonObject { put("type", "terminal.closed"); put("reason", "pane not found") }).toByteArray())
            done.await()
        }
        override val stderr: Flow<ByteArray> = emptyFlow()
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun closeStdin() = Unit
        override suspend fun awaitExit(): Int { done.await(); return 0 }
        override suspend fun close() { done.complete(Unit) }
    }

    /**
     * The relay script's channel: one JSON request line on stdin and its answers on stdout. A call gets one answer and the stream ends (the real relay
     * serves one request per connection); `events.subscribe` is acknowledged and then tells of every status change of the herd, until it is closed.
     */
    private inner class RelayChannel : StreamChannel {
        private val request = CompletableDeferred<JsonObject?>()
        private val pending = StringBuilder()
        private val done = CompletableDeferred<Unit>()
        @Volatile private var closed = false

        override val stdout: Flow<ByteArray> = flow {
            val req = request.await() ?: return@flow
            val id = req["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val method = req["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (method == "events.subscribe") {
                emit(line(success(id, "subscription_started") {}).toByteArray())
                var told = herd.statuses()
                while (!closed) {
                    delay(POLL_MILLIS)
                    val now = herd.statuses()
                    for ((pane, status) in now) if (told[pane] != status) herd.eventFor(pane, status)?.let { emit(line(it).toByteArray()) }
                    told = now
                }
            } else {
                emit(line(relayAnswer(id, method, req["params"] as? JsonObject)).toByteArray())
                done.complete(Unit)
            }
        }
        override val stderr: Flow<ByteArray> = emptyFlow()

        override suspend fun write(bytes: ByteArray) {
            pending.append(bytes.toString(Charsets.UTF_8))
            val end = pending.indexOf("\n")
            if (end < 0 || request.isCompleted) return
            request.complete(runCatching { Json.parseToJsonElement(pending.substring(0, end)).jsonObject }.getOrNull())
        }
        override suspend fun closeStdin() = Unit
        override suspend fun awaitExit(): Int { done.await(); return 0 }
        override suspend fun close() { closed = true; request.complete(null); done.complete(Unit) }
    }

    private fun relayAnswer(id: String, method: String, params: JsonObject?): JsonObject {
        val target = params?.get("target")?.jsonPrimitive?.contentOrNull
        return when (method) {
            "session.snapshot" -> success(id, "session_snapshot") { put("snapshot", herd.snapshot()) }
            "agent.get" -> herd.agent(target.orEmpty())?.let { agent -> success(id, "agent_info") { put("agent", agent) } } ?: failure(id, "agent_not_found", "No such agent in the demo.")
            "agent.prompt" -> {
                val text = params?.get("text")?.jsonPrimitive?.contentOrNull
                if (target != null && text != null && herd.prompt(target, text)) success(id, "agent_prompted") { put("agent", herd.agent(target)!!) }
                else failure(id, "agent_not_found", "No such agent in the demo.")
            }
            else -> failure(id, DemoCopy.REFUSAL_CODE, DemoCopy.REFUSAL)
        }
    }

    // ---- the wire's shapes ----

    private fun success(id: String, type: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject { put("id", id); put("result", buildJsonObject { put("type", type); fill() }) }

    private fun failure(id: String, code: String, message: String): JsonObject =
        buildJsonObject { put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) }) }

    private fun line(o: JsonObject) = o.toString() + "\n"

    private companion object {
        const val POLL_MILLIS = 250L
        const val MAX_REMEMBERED = 50
        /** `herdr plugin list --plugin tuthan.paddock --json` on a machine where the plugin is not installed. */
        const val PLUGINS = "{\"id\":\"cli:plugin\",\"result\":{\"plugins\":[],\"type\":\"plugin_list\"}}\n"
        /** CLI commands the demo knows and refuses by name: starting an agent, a worktree or a tab, and the focus and rename calls. */
        val REFUSED = setOf("agent", "worktree", "tab", "workspace")
    }
}
