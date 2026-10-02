package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A host replaced by the next connection must leave nothing running in the app's scope. */
class MonitoredHostLifecycleTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    private val profile = HostProfile("laptop", "Laptop", "10.0.0.2", 22, "jdoe")
    @After fun stop() { appScope.cancel() }

    private fun host() = MonitoredHost(appScope, profile, "default", FakeSession(onExec = { _, _ -> result(1) }), "/r/relay.py", "/s/herdr.sock", ledger, clock, MutableStateFlow(true), "/usr/bin/herdr")

    private val activeInAppScope get() = appScope.coroutineContext[Job]!!.children.count { it.isActive }

    private suspend fun untilNoneActive() {
        // Cancellation completes on the dispatcher, not inside stop().
        try { withTimeout(3_000) { while (activeInAppScope > 0) delay(10) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("$activeInAppScope jobs still active in the app's scope after stop()") }
    }

    @Test fun aRunningHostHoldsJobsInTheAppScopeSoTheMeasurementSeesSomething() = runBlocking<Unit> {
        val h = host().also { it.start() }
        assertTrue(activeInAppScope > 0, "a started host runs something")
        h.stop()
        untilNoneActive()
    }

    @Test fun threeStoppedHostsLeaveNoCollectorBehind() = runBlocking<Unit> {
        repeat(3) { host().also { h -> h.start(); delay(30); h.stop() } }
        untilNoneActive()
        assertEquals(0, activeInAppScope)
    }

    @Test fun aHostStoppedBeforeItStartedLeavesNothingBehindEither() = runBlocking<Unit> {
        host().stop()
        untilNoneActive()
    }
}
