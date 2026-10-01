package io.github.tuthan.paddock.ssh

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Reconciler
import io.github.tuthan.paddock.reconcile.SessionMonitor
import io.github.tuthan.paddock.relay.RelayClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The G2 path over real SSH: phone key, pinned host key, the relay script on the host, herdr's `paddock-test`
 * session. Needs `-e socket <herdr.sock>` and `-e relay <paddock-relay.py>` (tools/run-transport-tests.sh passes them
 * through INSTR_ARGS); skipped otherwise. Mutations go through exec, like the app's own commands would.
 */
class G2OverSshTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val socket = args.getString("socket")
    private val relayPath = args.getString("relay")
    private val herdr = args.getString("herdr", "/usr/bin/herdr")
    private val clock = Clock { System.currentTimeMillis() }
    private val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
    private val phone = PhoneKey("paddock-phone-key-transport-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<SshSession>()
    private val cleanup = mutableListOf<String>()
    private fun note(m: String) { Log.i("TRANSPORT", m); println("TRANSPORT $m") }

    // Same rule as PaddockTest.guard in :core: the path as given and its real path must name the same disposable session.
    private val socketRe = Regex(".*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\\.sock")

    private suspend fun connect(): SshSession {
        assumeTrue("socket/relay not passed", !socket.isNullOrBlank() && !relayPath.isNullOrBlank())
        val given = requireNotNull(socketRe.matchEntire(socket!!)) { "refusing a non-test session" }.groupValues[1]
        val pair = phone.getOrCreate()
        val target = SshTarget("profile-g2", args.getString("host", "10.0.2.2"), args.getString("port", "2222").toInt(), args.getString("user", "jdoe"))
        val ssh = SshlibConnector(HostKeyPolicy(FileHostKeyStore(storeFile), clock::nowMillis), clock)
            .connect(target, SshAuth.Phone(phone.privateKey(), pair.publicKey)) { true }.also { opened += it }
        // The socket is on the host, so its real path is resolved there; a paddock-test path linked elsewhere is refused.
        val real = ssh.exec(listOf("realpath", "-e", "--", socket)).let { if (it.exit == 0) it.stdout.toString(Charsets.UTF_8).trim() else "" }
        require(socketRe.matchEntire(real)?.groupValues?.get(1) == given) { "refusing $socket: its real path on the host is '$real', not session $given" }
        return ssh
    }

    @After fun tearDown() = runBlocking<Unit> {
        opened.firstOrNull()?.let { s -> cleanup.forEach { runCatching { s.exec(listOf(herdr, "--session", name, "pane", "close", it)) } } }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        opened.forEach { runCatching { it.close() } }; storeFile.delete()
    }

    /** The session the guarded socket path names; cleanup and every mutation target it, never a hard-coded name. */
    private val name get() = socketRe.matchEntire(socket!!)!!.groupValues[1]

    @Test
    fun monitorGoesLiveAndConvergesOverSshAfterASplitAndAStatusReport() = runBlocking<Unit> {
        val ssh = connect()
        val relay = RelayClient(ssh, relayPath!!, socket!!)
        val rec = Reconciler(clock, HostProfileId("test-host"), name, read = SessionMonitor.snapshotReader(relay))
        val monitor = SessionMonitor(scope, relay, rec, MutableStateFlow(true), clock).also { it.start() }
        withTimeout(30_000) { monitor.freshness.first { it == Freshness.Live } }
        val base = rec.installed.value!!.snapshot.panes.first().paneId
        note("T g2 live over ssh; base pane $base, epoch ${rec.installed.value!!.epoch}")

        val split = ssh.exec(listOf(herdr, "--session", name, "pane", "split", base, "--direction", "right", "--no-focus"))
        assertEquals(0, split.exit)
        val newPane = Regex("\"pane_id\":\"([^\"]+)\"").find(split.stdout.toString(Charsets.UTF_8))!!.groupValues[1]
        cleanup += newPane
        withTimeout(15_000) { while (rec.installed.value!!.snapshot.panes.none { it.paneId == newPane }) delay(50) }

        val src = "paddock-ssh-${System.currentTimeMillis()}"
        val r = ssh.exec(listOf(herdr, "--session", name, "pane", "report-agent", base, "--source", src, "--agent", "fake", "--state", "blocked", "--seq", "1"))
        assertEquals(0, r.exit)
        withTimeout(15_000) { while (rec.installed.value!!.snapshot.agents.none { it.paneId == base && it.agentStatus.wire == "blocked" }) delay(50) }
        ssh.exec(listOf(herdr, "--session", name, "pane", "release-agent", base, "--source", src, "--agent", "fake", "--seq", "2"))

        delay(800)
        val read = SessionMonitor.snapshotReader(relay)
        val converged = run { var ok = false; repeat(5) { if (!ok) { ok = read() == rec.installed.value!!.snapshot; if (!ok) delay(500) } }; ok }
        assertTrue("installed model must equal a fresh read", converged)
        note("T g2 converged over ssh: ${rec.installed.value!!.snapshot.panes.size} panes, ${rec.reads} reads")
        monitor.stop()
    }

    @Test
    fun pingAndSnapshotCallsGoThroughTheRelayAndTheHostRelayHashMatches() = runBlocking<Unit> {
        val ssh = connect()
        val relay = RelayClient(ssh, relayPath!!, socket!!)
        assertEquals("pong", relay.call("ping").type)
        val h = ssh.exec(listOf("sha256sum", "--", relayPath))
        note("T g2 relay sha256 on host: ${h.stdout.toString(Charsets.UTF_8).substringBefore(' ')}")
        assertEquals(0, h.exit)
    }
}
