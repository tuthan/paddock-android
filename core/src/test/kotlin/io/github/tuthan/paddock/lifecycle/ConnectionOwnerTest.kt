package io.github.tuthan.paddock.lifecycle

import io.github.tuthan.paddock.hostkey.HostKeyStoreCorrupt
import io.github.tuthan.paddock.hostkey.PresentedHostKey
import io.github.tuthan.paddock.ports.SecretCorrupt
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import io.github.tuthan.paddock.ssh.ConnectFailure
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class LinkSession : SshSession {
    val state = MutableStateFlow<LinkState>(LinkState.Up(0))
    val closed = AtomicBoolean(false)
    override val link: StateFlow<LinkState> get() = state
    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult = error("unused")
    override suspend fun openStream(argv: List<String>): StreamChannel = error("unused")
    override suspend fun close() { closed.set(true); state.value = LinkState.Down(DownReason.Closed, 0) }
}

class ConnectionOwnerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val profile = HostProfile("laptop", "Laptop", "10.0.0.2", 22, "jdoe")

    /** What the factory does on each attempt, in order; a Throwable is thrown, a session is returned. */
    private val script = ConcurrentLinkedQueue<Any>()
    private val connects = AtomicInteger()
    private val made = CopyOnWriteArrayList<LinkSession>()
    /** Entries for one profile only, so tests with two profiles do not depend on which connects first. */
    private val scriptFor = java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedQueue<Any>>()
    private val factory = SessionFactory { p ->
        connects.incrementAndGet()
        when (val next = scriptFor[p.id]?.poll() ?: script.poll() ?: LinkSession().also { made += it }) {
            is Throwable -> throw next
            is LinkSession -> next.also { if (it !in made) made += it }
            else -> error("bad script entry")
        }
    }

    private val sleeps = CopyOnWriteArrayList<Long>()
    @Volatile private var gate: CompletableDeferred<Unit>? = null
    private val sleep: suspend (Long) -> Unit = { ms -> sleeps += ms; gate?.await() ?: delay(1) }

    private fun owner(grace: Long? = null) =
        if (grace == null) ConnectionOwner(scope, factory, clock, sleep = sleep)
        else ConnectionOwner(scope, factory, clock, graceMillis = grace, sleep = sleep)

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    private fun connected(l: Lease) = l.state.value as? Connection.Connected

    @Test fun oneSessionServesEveryLeaseAndClosesInsideTheTenSecondBudgetAfterTheLastRelease() = runBlocking<Unit> {
        val o = owner() // the default grace is what is promised
        val a = o.acquire(profile); val b = o.acquire(profile)
        until("connected") { connected(a) != null }
        assertEquals(1, connects.get())
        assertSame(connected(a)!!.session, connected(b)!!.session)
        a.release()
        delay(60)
        assertFalse(made[0].closed.get(), "one lease is still held")
        b.release()
        until("closed") { made[0].closed.get() }
        assertTrue(sleeps.first() <= 10_000, "grace was ${sleeps.first()} ms")
        until("idle") { b.state.value == Connection.Idle }
    }

    @Test fun releasingTwiceCountsOnce() = runBlocking<Unit> {
        val o = owner()
        val a = o.acquire(profile); val b = o.acquire(profile)
        until("connected") { connected(a) != null }
        a.release(); a.release()
        delay(60)
        assertFalse(made[0].closed.get())
        b.release()
    }

    @Test fun aLeaseTakenInsideTheGraceKeepsTheSameSession() = runBlocking<Unit> {
        gate = CompletableDeferred()
        val o = owner(grace = 8_000)
        val a = o.acquire(profile)
        until("connected") { connected(a) != null }
        val first = connected(a)!!.session
        a.release()
        until("grace timer started") { sleeps.contains(8_000) }
        val again = o.acquire(profile)
        gate!!.complete(Unit)
        delay(80)
        assertFalse(made[0].closed.get())
        assertEquals(1, connects.get())
        assertSame(first, connected(again)!!.session)
        again.release()
    }

    @Test fun aLostLinkReconnectsAfterTheFirstBackoffStep() = runBlocking<Unit> {
        val o = owner()
        val l = o.acquire(profile)
        until("gen 1") { connected(l)?.generation == 1L }
        made[0].state.value = LinkState.Down(DownReason.Timeout, 0)
        until("gen 2") { connected(l)?.generation == 2L }
        assertTrue(made[0].closed.get())
        assertEquals(1_000L, sleeps.first())
        assertEquals(2, made.size)
        l.release()
    }

    @Test fun anAuthFailureWaitsForTheUserAndRetriesOnRefresh() = runBlocking<Unit> {
        script += ConnectFailure.AuthFailed()
        val o = owner()
        val l = o.acquire(profile)
        until("failed") { l.state.value is Connection.Failed }
        val f = l.state.value as Connection.Failed
        assertEquals(DownReason.AuthFailed, f.reason)
        assertNull(f.retryAtMillis, "nothing but the user can fix this")
        delay(150)
        assertEquals(1, connects.get(), "no retry loop")
        o.refresh(profile.id)
        until("connected") { connected(l) != null }
        assertEquals(2, connects.get())
        l.release()
    }

    @Test fun aDeclinedFirstTrustDoesNotAskAgainOnItsOwn() = runBlocking<Unit> {
        script += ConnectFailure.HostKeyDeclined(PresentedHostKey("ssh-ed25519", byteArrayOf(1, 2, 3)))
        val o = owner()
        val l = o.acquire(profile)
        until("failed") { l.state.value is Connection.Failed }
        assertNull((l.state.value as Connection.Failed).retryAtMillis)
        delay(150)
        assertEquals(1, connects.get())
        l.release()
    }

    @Test fun aRefusedGrantWaitsForTheUser() = runBlocking<Unit> {
        script += ConnectFailure.Refused(DownReason.PermissionDenied)
        val o = owner()
        val l = o.acquire(profile)
        until("failed") { l.state.value is Connection.Failed }
        val f = l.state.value as Connection.Failed
        assertEquals(DownReason.PermissionDenied, f.reason)
        assertNull(f.retryAtMillis)
        l.release()
    }

    /** G1's app-data-loss case: key material or pins that cannot be read stop with their own reason and never loop. */
    @Test fun unreadableKeysOrPinsWaitForTheUserWithTheirOwnReason() = runBlocking<Unit> {
        val cases = listOf(
            ConnectFailure.KeyUnavailable("the phone key does not exist; create it first") to DownReason.KeyUnavailable,
            SecretCorrupt("imported-imported") to DownReason.KeyUnavailable,
            ConnectFailure.HostKeysUnreadable(IOException("Unexpected JSON token")) to DownReason.HostKeysUnreadable,
            HostKeyStoreCorrupt(IOException("Unexpected JSON token")) to DownReason.HostKeysUnreadable,
        )
        for ((thrown, expected) in cases) {
            val p = HostProfile("p-${expected.javaClass.simpleName}-${thrown.javaClass.simpleName}".lowercase(), "P", "10.0.0.9", 22, "jdoe")
            scriptFor.getOrPut(p.id) { ConcurrentLinkedQueue() } += thrown
            val before = connects.get()
            val l = owner().acquire(p)
            until("failed ${thrown.javaClass.simpleName}") { l.state.value is Connection.Failed }
            val f = l.state.value as Connection.Failed
            assertEquals(expected, f.reason, thrown.javaClass.simpleName)
            assertNull(f.retryAtMillis, "${thrown.javaClass.simpleName}: only the user can fix it")
            delay(100)
            assertEquals(before + 1, connects.get(), "${thrown.javaClass.simpleName}: no retry loop")
            l.release()
        }
    }

    @Test fun failureClassificationKeepsTransientErrorsRetryable() {
        assertEquals(DownReason.Timeout to false, ConnectionOwner.connectFailureOf(ConnectFailure.TimedOut()))
        assertEquals(false, ConnectionOwner.connectFailureOf(ConnectFailure.Unreachable(IOException("no route"))).second)
        assertEquals(false, ConnectionOwner.connectFailureOf(IllegalStateException("something else")).second)
        assertIs<DownReason.Network>(ConnectionOwner.connectFailureOf(IllegalStateException("something else")).first)
    }

    @Test fun anUnreachableHostRetriesAndSaysWhen() = runBlocking<Unit> {
        script += ConnectFailure.Unreachable(IOException("no route"))
        gate = CompletableDeferred()
        val o = owner()
        val l = o.acquire(profile)
        until("failed") { l.state.value is Connection.Failed }
        val f = l.state.value as Connection.Failed
        assertIs<DownReason.Network>(f.reason)
        assertNotNull(f.retryAtMillis)
        assertEquals(1_000L, sleeps.first())
        gate!!.complete(Unit); gate = null
        until("connected") { connected(l) != null }
        assertEquals(2, connects.get())
        l.release()
    }

    @Test fun aNetworkChangeReplacesAHealthySessionWithoutBackoff() = runBlocking<Unit> {
        val o = owner()
        val l = o.acquire(profile)
        until("gen 1") { connected(l)?.generation == 1L }
        o.onNetworkChanged()
        until("gen 2") { connected(l)?.generation == 2L }
        assertTrue(made[0].closed.get(), "the old socket was opened on the old network")
        assertFalse(sleeps.contains(1_000L), "no backoff for a network change")
        l.release()
    }

    @Test fun aRefreshLeavesAHealthySessionAlone() = runBlocking<Unit> {
        val o = owner()
        val l = o.acquire(profile)
        until("connected") { connected(l) != null }
        o.refresh(profile.id)
        delay(150)
        assertEquals(1, connects.get())
        assertFalse(made[0].closed.get())
        assertEquals(1L, connected(l)!!.generation)
        l.release()
    }

    @Test fun aForegroundReturnRetriesEveryFailedProfileAndLeavesHealthyOnesAlone() = runBlocking<Unit> {
        val desk = HostProfile("desk", "Desk", "10.0.0.3", 22, "jdoe")
        scriptFor.getOrPut("desk") { ConcurrentLinkedQueue() } += ConnectFailure.AuthFailed() // refused until the user acts
        val o = owner()
        val a = o.acquire(profile); val b = o.acquire(desk)
        until("laptop up, desk failed") { connected(a) != null && b.state.value is Connection.Failed }
        o.refreshAll()
        until("desk up") { connected(b) != null }
        assertEquals(1L, connected(a)!!.generation, "the healthy session was not replaced")
        a.release(); b.release()
    }

    @Test fun profilesAreIndependent() = runBlocking<Unit> {
        val other = HostProfile("desk", "Desk", "10.0.0.3", 22, "jdoe")
        val o = owner()
        val a = o.acquire(profile); val b = o.acquire(other)
        until("both") { connected(a) != null && connected(b) != null }
        assertEquals(2, connects.get())
        a.release()
        until("a closed") { made.count { it.closed.get() } == 1 }
        assertNotNull(connected(b))
        b.release()
    }
}
