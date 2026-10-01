package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.attention.StateWord
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.live.FakeLease
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The whole bring-up against the disposable `paddock-test` session: relay missing, user agrees, relay installed under a
 * temporary HOME (never the real one), session chosen by name, home list populated from a real agent.
 */
class HostSessionLiveTest {
    private lateinit var env: PaddockTest
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var base: String
    private val fakeHome = Files.createTempDirectory("paddock-home").toFile()

    @Before fun setUp() = runBlocking<Unit> { env = PaddockTest.orSkip(); base = env.basePane() }
    @After fun tearDown() { if (::env.isInitialized) runBlocking { runCatching { env.releaseAgent(base) } }; scope.coroutineContext[Job]?.cancel(); fakeHome.deleteRecursively() }

    @Test fun relayMissingThenInstalledThenMonitoringWithARealAgentOnTheHome() = runBlocking<Unit> {
        val script = File(env.relayScript).readBytes()
        val session = LocalProcessSession(mapOf("HOME" to fakeHome.absolutePath))
        val lease = FakeLease(Connection.Connected(session, 1))
        val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
        val c = HostSessionController(
            scope, HostProfile("local", "Local", "127.0.0.1", 22, "tester"), lease, ledger, Clock { System.currentTimeMillis() },
            MutableStateFlow(true), script, sha256Hex(script), sessionName = env.sessionName, herdr = env.herdr,
        ).also { it.start() }

        withTimeout(15_000) { while (c.phase.value !is HostPhase.NeedsRelayInstall) delay(20) }
        val ask = c.phase.value as HostPhase.NeedsRelayInstall
        assertEquals("${fakeHome.absolutePath}/.local/share/paddock/paddock-relay.py", ask.destination)
        assertTrue(!File(ask.destination).exists(), "nothing is written before the user agrees")

        c.installRelay()
        withTimeout(30_000) { while (c.phase.value !is HostPhase.Monitoring) delay(20) }
        val file = File(ask.destination)
        assertEquals(sha256Hex(script), sha256Hex(file.readBytes()))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))

        val host = (c.phase.value as HostPhase.Monitoring).host
        assertEquals(env.sessionName, host.sessionName)
        env.reportAgent(base, "blocked")
        withTimeout(15_000) { while (host.home.value?.rows?.any { it.paneId == base && it.state == StateWord.Blocked } != true) delay(20) }
        c.stop()
    }
}
