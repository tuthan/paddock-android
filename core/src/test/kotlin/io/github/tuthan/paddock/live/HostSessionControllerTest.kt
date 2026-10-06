package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.herdr.SessionCatalog
import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.lifecycle.Lease
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import io.github.tuthan.paddock.relay.sha256Hex
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeLease(initial: Connection = Connection.Connecting, val flow: MutableStateFlow<Connection> = MutableStateFlow(initial)) : Lease {
    val released = AtomicBoolean(false)
    override val state: StateFlow<Connection> get() = flow
    override fun release() { released.set(true) }
}

class HostSessionControllerTest {
    private fun entry(name: String, default: Boolean = false, running: Boolean = true) = SessionEntry(name, default, running, "/d/$name", "/d/$name/herdr.sock")

    @Test fun theNamedRunningSessionWinsAndANamedStoppedOneIsNotChosen() {
        val c = SessionCatalog(listOf(entry("default", default = true), entry("work"), entry("old", running = false)))
        assertEquals("work", chooseSession(c, "work")!!.name)
        assertNull(chooseSession(c, "old"))
        assertNull(chooseSession(c, "missing"))
    }

    @Test fun withoutANameTheRunningDefaultIsChosenElseTheOnlyRunningOne() {
        assertEquals("default", chooseSession(SessionCatalog(listOf(entry("a"), entry("default", default = true))))!!.name)
        assertEquals("a", chooseSession(SessionCatalog(listOf(entry("a"), entry("b", running = false))))!!.name)
        assertNull(chooseSession(SessionCatalog(listOf(entry("a"), entry("b")))), "two running, none default: no silent guess")
        assertNull(chooseSession(SessionCatalog(listOf(entry("default", default = true, running = false)))))
        assertNull(chooseSession(SessionCatalog(emptyList())))
    }

    // ---- the phases, against a scripted host ----

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = io.github.tuthan.paddock.wake.WakeReading(io.github.tuthan.paddock.wake.WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 1), answered = true)

    @Test fun theWakeReadingRunsOncePerBringUpAfterTheRelayCheckAndIsExposed() = runBlocking<Unit> {
        herdrFound = false
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val session = host(sha256sum = { order += "relay-check"; result(0, "$sha  x\n") })
        val c = controller(FakeLease(Connection.Connected(session, 1)), wakeCapture = { order += "capture"; target }).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        assertEquals(1, order.count { it == "capture" })
        assertTrue(order.indexOf("relay-check") < order.indexOf("capture"), order.toString())
        assertEquals(target, c.wake.value)
        c.stop()
    }

    @Test fun aFailingWakeReadingDoesNotFailTheBringUp() = runBlocking<Unit> {
        herdrFound = false
        val session = host(sha256sum = { result(0, "$sha  x\n") })
        val c = controller(FakeLease(Connection.Connected(session, 1)), wakeCapture = { error("boom") }).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        // The phase is the host fact about herdr, not "could not start watching".
        assertTrue((c.phase.value as HostPhase.Problem).message.contains("~/.local/bin"))
        assertNull(c.wake.value)
        c.stop()
    }

    @Test fun aWakeReadingThatNeverReturnsIsGivenUpOn() = runBlocking<Unit> {
        herdrFound = false
        val session = host(sha256sum = { result(0, "$sha  x\n") })
        val c = controller(FakeLease(Connection.Connected(session, 1)), wakeCapture = { kotlinx.coroutines.awaitCancellation() }, wakeTimeout = 50).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        assertNull(c.wake.value)
        c.stop()
    }

    private val clock = Clock { System.currentTimeMillis() }
    private val profile = HostProfile("laptop", "Laptop", "10.0.0.2", 22, "jdoe")
    private val script = "print('relay')\n".toByteArray()
    private val sha = sha256Hex(script)
    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun controller(
        lease: FakeLease,
        wakeCapture: (suspend (io.github.tuthan.paddock.ports.SshSession) -> io.github.tuthan.paddock.wake.WakeReading)? = null,
        wakeTimeout: Long = 5_000,
    ) = HostSessionController(scope, profile, { lease }, ledger, clock, MutableStateFlow(true), script, sha, wakeCapture = wakeCapture, wakeCaptureTimeoutMillis = wakeTimeout)

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(3_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private var herdrFound = true

    private fun host(sha256sum: (List<String>) -> ExecResult, other: (List<String>) -> ExecResult = { result(1) }) = FakeSession(onExec = { argv, _ ->
        when {
            argv.firstOrNull() == "sh" && argv.getOrNull(2)?.contains("for p in") == true -> if (herdrFound) result(0, "/usr/bin/herdr") else result(1)
            argv.firstOrNull() == "sh" && argv.getOrNull(2)?.contains("printf %s \"\$HOME\"") == true -> result(0, "/home/jdoe")
            argv.firstOrNull() == "sha256sum" -> sha256sum(argv)
            else -> other(argv)
        }
    })

    @Test fun aFailedConnectionIsReportedWithItsReasonAndWhetherItRetries() = runBlocking<Unit> {
        val lease = FakeLease(Connection.Failed(DownReason.AuthFailed, null))
        val c = controller(lease).also { it.start() }
        until("failed") { c.phase.value is HostPhase.Failed }
        assertEquals(HostPhase.Failed(DownReason.AuthFailed, null), c.phase.value)
        lease.flow.value = Connection.Connecting
        until("connecting") { c.phase.value == HostPhase.Connecting }
        c.stop()
        assertTrue(lease.released.get(), "stopping gives the connection back")
    }

    @Test fun aMissingRelayAsksBeforeWritingAnythingAndNamesTheFileAndHash() = runBlocking<Unit> {
        val session = host(sha256sum = { result(1, "", "No such file") })
        val lease = FakeLease(Connection.Connected(session, 1))
        val c = controller(lease).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        val p = c.phase.value as HostPhase.NeedsRelayInstall
        assertEquals("/home/jdoe/.local/share/paddock/paddock-relay.py", p.destination)
        assertEquals(sha, p.expectedSha256)
        assertEquals(false, p.replacing)
        assertTrue(session.execs.none { (_, stdin) -> stdin != null }, "nothing was sent to the host before the user agreed")
        c.stop()
    }

    @Test fun aDifferentFileAtTheDestinationIsOfferedAsAReplacementNotSilentlyTrusted() = runBlocking<Unit> {
        val session = host(sha256sum = { result(0, "${"0".repeat(64)}  /home/jdoe/.local/share/paddock/paddock-relay.py\n") })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        assertEquals(true, (c.phase.value as HostPhase.NeedsRelayInstall).replacing)
        c.stop()
    }

    private val pluginDir = "/home/jdoe/.local/share/herdr/plugins/paddock"
    private val pluginListing = """{"id":"cli:plugin","result":{"plugins":[{"plugin_id":"tuthan.paddock","plugin_root":"$pluginDir","version":"0.1.0","enabled":true}],"type":"plugin_list"}}"""
    private val oneSession = """{"sessions":[{"name":"main","default":true,"running":true,"session_dir":"/d/main","socket_path":"/d/main/herdr.sock"}]}"""
    private val pushedPath = "/home/jdoe/.local/share/paddock/paddock-relay.py"

    /** A host with the Paddock plugin installed, whose relay files are [files] (path to hash); a path not in the map is missing. */
    private fun pluginHost(files: Map<String, String>) = host(
        sha256sum = { argv -> files[argv.last()]?.let { result(0, "$it  ${argv.last()}\n") } ?: result(1, "", "No such file") },
        other = { argv -> if (argv.getOrNull(1) == "plugin") result(0, pluginListing) else result(0, oneSession) },
    )

    @Test fun theRelayInThePluginDirectoryIsUsedWithoutAskingOrWritingAnything() = runBlocking<Unit> {
        val session = pluginHost(mapOf("$pluginDir/host/paddock-relay.py" to sha))
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("monitoring") { c.phase.value is HostPhase.Monitoring }
        until("the relay is started") { session.streams.isNotEmpty() }
        assertTrue(session.streams.first().argv.contains("$pluginDir/host/paddock-relay.py"), session.streams.first().argv.toString())
        assertTrue(session.execs.none { (_, stdin) -> stdin != null }, "nothing was written to the host")
        assertEquals(listOf("/usr/bin/herdr", "plugin", "list", "--plugin", "tuthan.paddock", "--json"), session.execs.map { it.first }.single { it.getOrNull(1) == "plugin" })
        c.stop()
    }

    @Test fun aPluginRelayThatIsNotThePinIsNotRunAndTheAskNamesTheVersionFound() = runBlocking<Unit> {
        val session = pluginHost(mapOf("$pluginDir/host/paddock-relay.py" to "0".repeat(64)))
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        val ask = c.phase.value as HostPhase.NeedsRelayInstall
        assertEquals(pushedPath, ask.destination, "the push install is still offered, at the push destination")
        assertEquals(false, ask.replacing)
        assertTrue(ask.pluginNote!!.contains("0.1.0") && ask.pluginNote!!.contains("--ref"), ask.pluginNote)
        assertTrue(session.streams.isEmpty() && session.execs.none { (_, stdin) -> stdin != null }, "nothing ran and nothing was written")
        c.stop()
    }

    @Test fun aPushedPinnedRelayStillRunsWhenThePluginCarriesAnotherOneAndNobodyIsAsked() = runBlocking<Unit> {
        val session = pluginHost(mapOf("$pluginDir/host/paddock-relay.py" to "0".repeat(64), pushedPath to sha))
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("monitoring") { c.phase.value is HostPhase.Monitoring }
        until("the relay is started") { session.streams.isNotEmpty() }
        assertTrue(session.streams.first().argv.contains(pushedPath), session.streams.first().argv.toString())
        c.stop()
    }

    @Test fun aHostWithoutThePluginAsksAsBeforeAndSaysNothingAboutOne() = runBlocking<Unit> {
        val session = host(sha256sum = { result(1, "", "No such file") }, other = { argv -> if (argv.getOrNull(1) == "plugin") result(0, """{"result":{"plugins":[],"type":"plugin_list"}}""") else result(1) })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        assertNull((c.phase.value as HostPhase.NeedsRelayInstall).pluginNote)
        assertEquals(pushedPath, (c.phase.value as HostPhase.NeedsRelayInstall).destination)
        c.stop()
    }

    @Test fun herdrMissingFromEveryUsualPlaceIsAHostFactNamingThePlaces() = runBlocking<Unit> {
        herdrFound = false
        val session = host(sha256sum = { result(0, "$sha  x\n") })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        assertTrue((c.phase.value as HostPhase.Problem).message.contains("~/.local/bin"))
        c.stop()
    }

    @Test fun whenHerdrDoesNotAnswerTheProblemIsAHostFactWithAHint() = runBlocking<Unit> {
        val session = host(sha256sum = { result(0, "$sha  x\n") }, other = { result(127, "", "herdr: not found") })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        assertTrue((c.phase.value as HostPhase.Problem).message.contains("PATH"))
        c.stop()
    }

    @Test fun noRunningSessionIsSaidPlainly() = runBlocking<Unit> {
        val session = host(sha256sum = { result(0, "$sha  x\n") }, other = { result(0, """{"sessions":[]}""") })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        assertEquals("No herdr session is running on the host.", (c.phase.value as HostPhase.Problem).message)
        c.stop()
    }

    @Test fun twoRunningSessionsWithoutADefaultAreNamedSoTheUserCanChoose() = runBlocking<Unit> {
        val catalog = """{"sessions":[{"name":"api","default":false,"running":true,"session_dir":"/d/api","socket_path":"/d/api/herdr.sock"},{"name":"web","default":false,"running":true,"session_dir":"/d/web","socket_path":"/d/web/herdr.sock"}]}"""
        val session = host(sha256sum = { result(0, "$sha  x\n") }, other = { result(0, catalog) })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("problem") { c.phase.value is HostPhase.Problem }
        val message = (c.phase.value as HostPhase.Problem).message
        assertTrue("api" in message && "web" in message, message)
        c.stop()
    }

    @Test fun tryingAgainAfterASetupProblemRunsTheBringUpAgainOnTheSameConnection() = runBlocking<Unit> {
        herdrFound = false
        val session = host(sha256sum = { result(0, "$sha  x\n") }, other = { result(0, """{"sessions":[]}""") })
        val c = controller(FakeLease(Connection.Connected(session, 1))).also { it.start() }
        until("herdr missing") { (c.phase.value as? HostPhase.Problem)?.message?.contains("~/.local/bin") == true }
        val discoveries = session.execs.count { (argv, _) -> argv.getOrNull(2)?.contains("for p in") == true }
        herdrFound = true // the user installs herdr on the host and presses Try again
        assertTrue(c.retrySetup())
        until("the next problem") { (c.phase.value as? HostPhase.Problem)?.message == "No herdr session is running on the host." }
        assertEquals(discoveries + 1, session.execs.count { (argv, _) -> argv.getOrNull(2)?.contains("for p in") == true }, "the setup ran again, once")
        c.stop()
    }

    @Test fun tryingAgainOnlyActsOnASetupProblem() = runBlocking<Unit> {
        val failed = FakeLease(Connection.Failed(DownReason.Timeout, null))
        val c = controller(failed).also { it.start() }
        until("failed") { c.phase.value is HostPhase.Failed }
        assertEquals(false, c.retrySetup(), "a failed connection is refreshed by the owner, not by the setup")
        assertIs<HostPhase.Failed>(c.phase.value)
        c.stop()
        val asking = controller(FakeLease(Connection.Connected(host(sha256sum = { result(1) }), 1))).also { it.start() }
        until("ask") { asking.phase.value is HostPhase.NeedsRelayInstall }
        assertEquals(false, asking.retrySetup(), "waiting for the user's agreement is not a problem to retry")
        assertIs<HostPhase.NeedsRelayInstall>(asking.phase.value)
        asking.stop()
    }

    /** Leases on one shared connection, as the owner hands them out: each claim is new, the connection is the same. */
    private class Owner(initial: Connection) {
        val connection = MutableStateFlow(initial)
        val leases = java.util.concurrent.CopyOnWriteArrayList<FakeLease>()
        fun acquire(): Lease = FakeLease(flow = connection).also { leases += it }
    }

    @Test fun pausingReleasesTheClaimAndAQuickResumeOnTheSameSessionStartsNothingAgain() = runBlocking<Unit> {
        val session = host(sha256sum = { result(1) })
        val owner = Owner(Connection.Connected(session, 1))
        val c = HostSessionController(scope, profile, owner::acquire, ledger, clock, MutableStateFlow(true), script, sha).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        val checks = session.execs.size
        c.pause()
        assertTrue(owner.leases.single().released.get())
        c.resume()
        assertEquals(2, owner.leases.size, "resuming claims the connection again")
        c.resume()
        assertEquals(2, owner.leases.size, "a second resume is harmless")
        delay(100)
        assertEquals(checks, session.execs.size, "same session: the bring-up is not run again")
        assertIs<HostPhase.NeedsRelayInstall>(c.phase.value)
        c.stop()
    }

    @Test fun whenTheSessionClosesWhilePausedTheNextResumeStartsFresh() = runBlocking<Unit> {
        val session = host(sha256sum = { result(1) })
        val owner = Owner(Connection.Connected(session, 1))
        val c = HostSessionController(scope, profile, owner::acquire, ledger, clock, MutableStateFlow(true), script, sha).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        c.pause()
        owner.connection.value = Connection.Idle // the owner's grace ran out
        until("connecting") { c.phase.value == HostPhase.Connecting }
        c.resume()
        owner.connection.value = Connection.Connected(session, 2)
        until("ask again") { c.phase.value is HostPhase.NeedsRelayInstall }
        c.stop()
        assertTrue(owner.leases.all { it.released.get() })
        c.resume()
        assertEquals(2, owner.leases.size, "a stopped controller claims nothing")
    }

    @Test fun aLostConnectionStopsTheOldPhaseAndReconnectingStartsFromTheCheckAgain() = runBlocking<Unit> {
        val session = host(sha256sum = { result(1) })
        val lease = FakeLease(Connection.Connected(session, 1))
        val c = controller(lease).also { it.start() }
        until("ask") { c.phase.value is HostPhase.NeedsRelayInstall }
        lease.flow.value = Connection.Failed(DownReason.Timeout, 123L)
        until("failed") { c.phase.value is HostPhase.Failed }
        lease.flow.value = Connection.Connected(session, 2)
        until("ask again") { c.phase.value is HostPhase.NeedsRelayInstall }
        assertIs<HostPhase.NeedsRelayInstall>(c.phase.value)
        c.stop()
    }
}
