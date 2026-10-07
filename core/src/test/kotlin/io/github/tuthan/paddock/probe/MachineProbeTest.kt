package io.github.tuthan.paddock.probe

import io.github.tuthan.paddock.hostkey.PresentedHostKey
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.ssh.ConnectFailure
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

private val FIXTURES = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")

/** A host that answers the three commands the probe sends, in the words herdr 0.9.1 uses, and records what it was asked. */
private class FakeHost(
    val agentList: String = File(FIXTURES, "agent-list-blocked.json").readText(),
    val sessions: String = """{"sessions":[{"name":"main","default":true,"running":true,"session_dir":"/d/main","socket_path":"/d/main/herdr.sock"}]}""",
    val herdrFound: Boolean = true,
) : SshSession {
    val commands = CopyOnWriteArrayList<List<String>>()
    val closed = AtomicInteger()
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
        commands += argv
        val line = argv.joinToString(" ")
        return when {
            argv.firstOrNull() == "sh" -> if (herdrFound) ok("/usr/bin/herdr") else ExecResult(1, ByteArray(0), ByteArray(0), false, false, Duration.ZERO)
            "session list" in line -> ok(sessions)
            "agent list" in line -> ok(agentList)
            else -> error("the probe sent something it should not: $line")
        }
    }
    override suspend fun openStream(argv: List<String>): StreamChannel = error("the probe never streams")
    override suspend fun close() { closed.incrementAndGet() }
    private fun ok(text: String) = ExecResult(0, text.toByteArray(), ByteArray(0), false, false, Duration.ZERO)
    val asked: List<String> get() = commands.map { it.joinToString(" ") }
}

class MachineProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val beta = HostProfile("beta", "Beta server", "10.0.0.9", 22, "jdoe")
    private val gamma = HostProfile("gamma", "Gamma", "10.0.0.10", 22, "jdoe")

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun probe(open: suspend (HostProfile) -> SshSession, parallel: Int = 2, lookTimeoutMillis: Long = 5_000) =
        MachineProbe(clock, open, tickMillis = 10, parallel = parallel, lookTimeoutMillis = lookTimeoutMillis)

    private fun running(p: MachineProbe, vararg targets: HostProfile): Job = scope.launch { p.run(targets.toList()) }

    private fun <T : Any> await(what: String, read: () -> T?): T = runBlocking {
        withTimeout(5_000) { var v = read(); while (v == null) { delay(10); v = read() }; v }
    }.also { assertNotNull(it, what) }

    @Test fun itCountsTheBlockedAgentsOfTheMachineAndClosesTheSession() {
        val host = FakeHost()
        val p = probe({ host })
        val job = running(p, beta)
        val state = await("a reading") { p.states.value["beta"]?.takeIf { it.reading != null } }
        job.cancel()
        assertEquals(1, state.reading!!.needYou)
        assertNull(state.problem)
        assertEquals(0, state.failures)
        await("the session closed") { host.closed.get().takeIf { it > 0 } }
    }

    @Test fun anAgentThatIsNotBlockedIsNotCounted() {
        for (name in listOf("agent-list-idle.json", "agent-list-working.json", "agent-list-unknown.json")) {
            val p = probe({ FakeHost(agentList = File(FIXTURES, name).readText()) })
            val job = running(p, beta)
            val state = await("a reading for $name") { p.states.value["beta"]?.takeIf { it.reading != null } }
            job.cancel()
            assertEquals(0, state.reading!!.needYou, name)
        }
    }

    @Test fun itSendsOnlyReadsAndAsksForTheMachinesChosenSession() {
        val host = FakeHost(sessions = """{"sessions":[{"name":"main","default":true,"running":true,"session_dir":"/d/m","socket_path":"/d/m/s"},{"name":"work","default":false,"running":true,"session_dir":"/d/w","socket_path":"/d/w/s"}]}""")
        val p = probe({ host })
        val job = running(p, beta.copy(session = "work"))
        await("a reading") { p.states.value["beta"]?.reading }
        job.cancel()
        // Locating herdr, listing sessions, listing agents: no write, no start, no install, no stream.
        assertEquals(
            listOf("/usr/bin/herdr session list --json", "/usr/bin/herdr --session work agent list"),
            host.asked.filter { it.startsWith("/usr/bin/herdr") },
        )
        assertTrue(host.asked.filter { !it.startsWith("/usr/bin/herdr") }.all { it.startsWith("sh -c for p in") || it.startsWith("sh -c s=") })
    }

    @Test fun anUnreachableMachineIsSaidSoAndKeepsItsLastReading() {
        var up = true
        val p = probe({ if (up) FakeHost() else throw IOException("no route to host") })
        val job = running(p, beta)
        val good = await("a reading") { p.states.value["beta"]?.takeIf { it.reading != null } }.reading!!
        up = false
        p.lookAgainNow()
        val down = await("the failure") { p.states.value["beta"]?.takeIf { it.problem != null } }
        job.cancel()
        assertEquals(ProbeProblem.Unreachable, down.problem)
        assertEquals(good, down.reading, "the last good count stays, so the chip can say how old it is")
        assertEquals(1, down.failures)
    }

    @Test fun aKeyThePhoneHasNotTrustedIsAProblemTheUserMustLookAtNeverAPrompt() {
        for (failure in listOf(ConnectFailure.HostKeyDeclined(PresentedHostKey("ssh-ed25519", ByteArray(32))), ConnectFailure.AuthFailed(), ConnectFailure.BadKey("unreadable"), ConnectFailure.Refused(DownReason.PermissionDenied), ConnectFailure.KeyUnavailable("gone"))) {
            val p = probe({ throw failure })
            val job = running(p, beta)
            val state = await("the problem for $failure") { p.states.value["beta"]?.takeIf { it.problem != null } }
            job.cancel()
            assertEquals(ProbeProblem.NeedsLook, state.problem, failure.toString())
            assertEquals(ProbeSchedule.SLOWEST_MILLIS, ProbeSchedule.nextDelayMillis(state.problem, state.failures), "nothing here fixes itself, so it is not retried every half minute")
        }
    }

    @Test fun hostsThatAnswerButHaveNothingToReadSayWhy() {
        fun problemOf(host: FakeHost, profile: HostProfile = beta): ProbeProblem? {
            val p = probe({ host })
            val job = running(p, profile)
            val state = await("a problem") { p.states.value[profile.id]?.takeIf { it.problem != null || it.reading != null } }
            job.cancel()
            return state.problem
        }
        assertEquals(ProbeProblem.NoHerdr, problemOf(FakeHost(herdrFound = false)))
        assertEquals(ProbeProblem.NoHerdr, problemOf(FakeHost(sessions = "not json")))
        assertEquals(ProbeProblem.NoSession, problemOf(FakeHost(sessions = """{"sessions":[]}""")))
        assertEquals(ProbeProblem.NoSession, problemOf(FakeHost(), beta.copy(session = "absent")))
        val two = """{"sessions":[{"name":"a","default":false,"running":true,"session_dir":"/d/a","socket_path":"/d/a/s"},{"name":"b","default":false,"running":true,"session_dir":"/d/b","socket_path":"/d/b/s"}]}"""
        assertEquals(ProbeProblem.NoSession, problemOf(FakeHost(sessions = two)), "several sessions and none chosen is not a guess")
        assertEquals(ProbeProblem.NoHerdr, problemOf(FakeHost(agentList = "{}")))
    }

    @Test fun aLookThatNeverAnswersIsUnreachableAndItsSessionIsClosed() {
        val gate = CompletableDeferred<Unit>()
        val host = object : SshSession {
            val closed = AtomicInteger()
            override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
            override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult { gate.await(); error("unreachable") }
            override suspend fun openStream(argv: List<String>): StreamChannel = error("no")
            override suspend fun close() { closed.incrementAndGet() }
        }
        val p = probe({ host }, lookTimeoutMillis = 100)
        val job = running(p, beta)
        val state = await("the timeout") { p.states.value["beta"]?.takeIf { it.problem != null } }
        job.cancel()
        assertEquals(ProbeProblem.Unreachable, state.problem)
        await("the session closed") { host.closed.get().takeIf { it > 0 } }
    }

    @Test fun aGoodLookIsNotRepeatedBeforeTheInterval() {
        val opened = AtomicInteger()
        val p = probe({ opened.incrementAndGet(); FakeHost() })
        val job = running(p, beta)
        await("a reading") { p.states.value["beta"]?.reading }
        runBlocking { delay(300) } // thirty ticks
        job.cancel()
        assertEquals(1, opened.get())
    }

    @Test fun restartingTheProbeDoesNotLookAgainAtAMachineLookedAtJustNow() {
        val opened = AtomicInteger()
        val p = probe({ opened.incrementAndGet(); FakeHost() })
        val first = running(p, beta)
        await("a reading") { p.states.value["beta"]?.reading }
        first.cancel()
        val second = running(p, beta)
        runBlocking { delay(200) }
        second.cancel()
        assertEquals(1, opened.get(), "switching the watched machine restarts the probe; the machines it kept looking at are not looked at twice")
    }

    @Test fun lookAgainNowMakesTheNextTickLookAtEveryMachine() {
        val opened = AtomicInteger()
        val p = probe({ opened.incrementAndGet(); FakeHost() })
        val job = running(p, beta, gamma)
        await("two readings") { if (p.states.value.values.count { it.reading != null } == 2) true else null }
        p.lookAgainNow()
        await("two more looks") { opened.get().takeIf { it >= 4 } }
        job.cancel()
    }

    @Test fun noMoreThanTheLimitLookAtOnce() {
        val live = AtomicInteger(); val most = AtomicInteger()
        val machines = (1..5).map { HostProfile("m$it", "M$it", "10.0.0.$it", 22, "jdoe") }
        val p = probe({
            val now = live.incrementAndGet(); most.updateAndGet { maxOf(it, now) }
            delay(80); live.decrementAndGet(); FakeHost()
        }, parallel = 2)
        val job = running(p, *machines.toTypedArray())
        await("every machine read") { if (p.states.value.values.count { it.reading != null } == 5) true else null }
        job.cancel()
        assertEquals(2, most.get())
    }

    @Test fun cancellingStopsEveryLookAndClosesWhatItHolds() {
        val hosts = ConcurrentHashMap<String, FakeHost>()
        val slow = object : SshSession {
            val closed = AtomicInteger()
            override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
            override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult { delay(60_000); error("unreachable") }
            override suspend fun openStream(argv: List<String>): StreamChannel = error("no")
            override suspend fun close() { closed.incrementAndGet() }
        }
        val p = probe({ hosts.getOrPut(it.id) { FakeHost() }; slow })
        val job = running(p, beta)
        await("the look started") { hosts["beta"] }
        job.cancel()
        await("the session closed") { slow.closed.get().takeIf { it > 0 } }
        assertFalse(p.states.value.containsKey("beta"), "a cancelled look reports nothing")
    }

    @Test fun forgettingAMachineDropsEverythingAboutIt() {
        val p = probe({ FakeHost() })
        val job = running(p, beta)
        await("a reading") { p.states.value["beta"]?.reading }
        job.cancel()
        p.forget("beta")
        assertTrue(p.states.value.isEmpty())
    }
}
