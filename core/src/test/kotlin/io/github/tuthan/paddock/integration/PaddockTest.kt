package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.cli.CliOutcome
import io.github.tuthan.paddock.cli.CliResult
import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.herdr.Envelope
import io.github.tuthan.paddock.herdr.Message
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.herdr.SnapshotResult
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.relay.RelayClient
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * Guards and helpers for tests that touch a real herdr. They run only against a disposable session: the socket in
 * `PADDOCK_TEST_SOCKET` must be `.../sessions/paddock-test[-suffix]/herdr.sock`, never the default session, and the
 * test is skipped (not failed) when it is unset.
 */
class PaddockTest private constructor(val socket: String) {
    val sessionName: String = SOCKET_RE.matchEntire(socket)!!.groupValues[1]
    val session = LocalProcessSession()
    val herdr: String = System.getenv("PADDOCK_HERDR") ?: "/usr/bin/herdr"
    val cli = HerdrCli(herdr, sessionName)
    val relayScript: String = File(System.getProperty("paddock.repoRoot"), "host/paddock-relay.py").absolutePath
    val relay = RelayClient(session, relayScript, socket)
    private val source = "paddock-it-${System.currentTimeMillis()}"
    private var seq = 0

    suspend fun snapshot(): Snapshot = SessionMonitor.snapshotReader(relay)()

    /** Same thing through the CLI, as a second opinion on the relay path. */
    suspend fun cliSnapshot(): Snapshot {
        val ok = CliResult.classify(session.exec(cli.apiSnapshot())) as CliOutcome.Ok
        return CliResult.decode<SnapshotResult>(ok, "session_snapshot").snapshot
    }

    private suspend fun run(argv: List<String>): String {
        val r = session.exec(argv)
        check(r.exit == 0) { "herdr ${argv.drop(3)} failed (${r.exit}): ${r.stderr.toString(Charsets.UTF_8)} ${r.stdout.toString(Charsets.UTF_8).take(200)}" }
        return r.stdout.toString(Charsets.UTF_8)
    }

    private fun scoped(vararg rest: String) = listOf(herdr, "--session", sessionName) + rest

    suspend fun reportAgent(pane: String, state: String, message: String = "{\"kind\":\"it\"}") {
        run(scoped("pane", "report-agent", pane, "--source", source, "--agent", "fake", "--state", state, "--message", message, "--seq", (++seq).toString()))
    }
    suspend fun releaseAgent(pane: String) { run(scoped("pane", "release-agent", pane, "--source", source, "--agent", "fake", "--seq", (++seq).toString())) }

    suspend fun split(pane: String): String {
        val out = run(scoped("pane", "split", pane, "--direction", "right", "--no-focus"))
        return (Envelope.parse(out.trim()) as Message.Success).result["pane"]!!.jsonObject["pane_id"]!!.jsonPrimitive.content
    }
    suspend fun close(pane: String) { run(scoped("pane", "close", pane)) }
    suspend fun moveToNewTab(pane: String) { run(scoped("pane", "move", pane, "--new-tab")) }

    /** The pane every test starts from: the first pane of the session's first workspace. */
    suspend fun basePane(): String = snapshot().panes.first().paneId

    companion object {
        private val SOCKET_RE = Regex(".*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\\.sock")

        /** Null (and the test skipped) unless a disposable-session socket is configured. */
        fun orSkip(): PaddockTest {
            val s = System.getenv("PADDOCK_TEST_SOCKET")
            Assume.assumeTrue("PADDOCK_TEST_SOCKET not set; integration test skipped", !s.isNullOrBlank())
            require(SOCKET_RE.matches(s!!)) { "PADDOCK_TEST_SOCKET must be a paddock-test session socket, got a different session" }
            return PaddockTest(s)
        }
    }
}
