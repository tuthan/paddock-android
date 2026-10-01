package io.github.tuthan.paddock.reconcile

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeStream
import io.github.tuthan.paddock.relay.RelayClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A host that answers the relay's requests from a snapshot the test controls. */
private class ScriptedHost {
    @Volatile var snapshot = Snapshot("0.9.1", 22, panes = listOf(Pane("w1:p1", "term_a", "w1", "w1:t1")))
    val lifecycle = CopyOnWriteArrayList<FakeStream>()
    val status = CopyOnWriteArrayList<FakeStream>()
    val snapshotCalls = AtomicInteger()
    @Volatile var refuseStatus = false
    /** Refuse the next status request with `pane_not_found`, as herdr does when a named pane closed meanwhile. */
    @Volatile var refuseStatusOnce: (() -> Unit)? = null
    /** Answer `session.snapshot` with this error instead of the snapshot. */
    @Volatile var snapshotError: String? = null

    val session = FakeSession(onStream = { st ->
        st.onRequest = { line ->
            val req = Json.parseToJsonElement(line).jsonObject; val id = req["id"]!!.jsonPrimitive.content
            when (req["method"]!!.jsonPrimitive.content) {
                "session.snapshot" -> snapshotError?.let { code -> snapshotCalls.incrementAndGet(); st.feed("""{"id":"$id","error":{"code":"$code","message":"not now"}}""" + "\n"); st.end() } ?: run { snapshotCalls.incrementAndGet(); st.feed("""{"id":"$id","result":{"type":"session_snapshot","snapshot":${Json { encodeDefaults = true }.encodeToString(snapshot)}}}""" + "\n"); st.end() }
                "events.subscribe" -> {
                    val isStatus = line.contains("agent_status_changed")
                    val once = if (isStatus) refuseStatusOnce else null
                    if (once != null) { refuseStatusOnce = null; once(); st.feed("""{"id":"$id","error":{"code":"pane_not_found","message":"pane not found"}}""" + "\n") }
                    else if (isStatus && refuseStatus) st.feed("""{"id":"","error":{"code":"invalid_request","message":"bad pane"}}""" + "\n")
                    else { st.feed("""{"id":"$id","result":{"type":"subscription_started"}}""" + "\n"); (if (isStatus) status else lifecycle) += st }
                }
            }
        }
    })
}

class SessionMonitorTest {
    private val host = ScriptedHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock { System.currentTimeMillis() }
    private val fg = MutableStateFlow(true)
    private val sleeps = CopyOnWriteArrayList<Long>()
    private var n = 0
    private val relay = RelayClient(host.session, "/r.py", "/s/herdr.sock", ids = { "id-${++n}" })

    private fun monitor(): SessionMonitor {
        val rec = Reconciler(clock, HostProfileId("laptop"), "main", read = SessionMonitor.snapshotReader(relay), sleep = { sleeps += it; delay(5) })
        return SessionMonitor(scope, relay, rec, fg, clock, sleep = { sleeps += it; delay(20) })
    }

    @After fun stop() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun until(what: String, cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(5) }.also { }

    @Test fun goesLiveOnlyAfterAcknowledgementAndAReadThatFollowsIt() = runBlocking<Unit> {
        val m = monitor(); m.start()
        until("live") { m.freshness.value == Freshness.Live }
        assertEquals(1, host.lifecycle.size)
        assertNotNull(m.reconciler.installed.value)
        assertEquals(24, Regex("\"type\":\"").findAll(host.lifecycle[0].writtenText()).count())
        m.stop()
    }

    @Test fun aLifecycleEventTriggersAnAuthoritativeReadNotAnEventReplay() = runBlocking<Unit> {
        val m = monitor(); m.start(); until("live") { m.freshness.value == Freshness.Live }
        val before = host.snapshotCalls.get()
        host.snapshot = host.snapshot.copy(panes = host.snapshot.panes + Pane("w1:p2", "term_b", "w1", "w1:t1"))
        // The event's own payload is a lie on purpose: only the snapshot may change what is installed.
        host.lifecycle[0].feed("""{"event":"pane_created","data":{"pane":{"pane_id":"w1:p99","terminal_id":"term_x","workspace_id":"w1","tab_id":"w1:t1"}}}""" + "\n")
        until("read") { host.snapshotCalls.get() > before && m.reconciler.installed.value!!.snapshot.panes.size == 2 }
        assertEquals(setOf("term_a", "term_b"), m.reconciler.installed.value!!.snapshot.panes.map { it.terminalId }.toSet())
        m.stop()
    }

    @Test fun statusStreamFollowsTheAgentPanesAndIsReplacedWhenTheyChange() = runBlocking<Unit> {
        host.snapshot = host.snapshot.copy(agents = listOf(Agent("w1:p1", "term_a", "w1", "w1:t1", agent = "fake", agentStatus = AgentStatus.Working)))
        val m = monitor(); m.start(); until("status 1") { host.status.size == 1 && m.freshness.value == Freshness.Live }
        assertTrue(host.status[0].writtenText().contains("w1:p1"))
        host.snapshot = host.snapshot.copy(agents = host.snapshot.agents + Agent("w1:p2", "term_b", "w1", "w1:t1", agent = "fake"))
        host.lifecycle[0].feed("""{"event":"pane_agent_detected","data":{"pane_id":"w1:p2","workspace_id":"w1"}}""" + "\n")
        until("status 2") { host.status.size == 2 }
        assertTrue(host.status[1].writtenText().let { it.contains("w1:p1") && it.contains("w1:p2") })
        until("old closed") { host.status[0].closed }
        m.stop()
    }

    @Test fun lostEventsMakeTheSessionStaleThenAReconnectStartsANewEpochAndGoesLiveAgain() = runBlocking<Unit> {
        val m = monitor(); m.start(); until("live") { m.freshness.value == Freshness.Live }
        assertEquals(1L, m.reconciler.installed.value!!.epoch)
        // Something changes while the stream is gone: the reconcile after reconnect must catch it up.
        host.snapshot = host.snapshot.copy(panes = host.snapshot.panes + Pane("w1:p2", "term_b", "w1", "w1:t1"))
        host.lifecycle[0].feed("""{"event":"events_lost","data":{}}""" + "\n")
        until("stale seen") { m.lastLoss.value is io.github.tuthan.paddock.relay.EventsLost }
        until("second lifecycle stream") { host.lifecycle.size == 2 }
        until("live again") { m.freshness.value == Freshness.Live && m.reconciler.installed.value!!.epoch == 2L }
        assertEquals(2, m.reconciler.installed.value!!.snapshot.panes.size)
        assertTrue(sleeps.any { it == 1_000L }, "a reconnect waits the first backoff step, saw $sleeps")
        m.stop()
    }

    @Test fun aDroppedLifecycleStreamIsHandledTheSameWay() = runBlocking<Unit> {
        val m = monitor(); m.start(); until("live") { m.freshness.value == Freshness.Live }
        host.lifecycle[0].end()
        until("reconnected") { host.lifecycle.size == 2 && m.freshness.value == Freshness.Live }
        assertTrue(m.lastLoss.value is io.github.tuthan.paddock.relay.RelayUnavailable)
        m.stop()
    }

    @Test fun aFailingReadMakesTheSessionStaleWithTheReasonAndItRecovers() = runBlocking<Unit> {
        val m = monitor(); m.start(); until("live") { m.freshness.value == Freshness.Live }
        host.snapshotError = "server_busy"
        host.lifecycle[0].feed("""{"event":"pane_updated","data":{"pane_id":"w1:p1"}}""" + "\n")
        until("stale") { m.freshness.value == Freshness.Stale }
        assertTrue((m.lastLoss.value as? io.github.tuthan.paddock.relay.HerdrError)?.code == "server_busy", "the reason is kept: ${m.lastLoss.value}")
        host.snapshotError = null
        until("live again") { m.freshness.value == Freshness.Live }
        m.stop()
    }

    @Test fun unmappedEventsAreCountedAndDoNotTriggerAReadButMappedOnesDo() = runBlocking<Unit> {
        fg.value = false // no heartbeat: this harness's sleep is 5 ms, so a foreground heartbeat would read constantly
        val m = monitor(); m.start(); until("live") { m.freshness.value == Freshness.Live }
        delay(100); val before = host.snapshotCalls.get()
        repeat(3) { host.lifecycle[0].feed("""{"event":"pane.something_new","data":{}}""" + "\n") }
        until("counted") { m.ignoredEvents == 3L }
        delay(150)
        assertEquals(before, host.snapshotCalls.get(), "an event this build does not know is not a reason to read")
        host.lifecycle[0].feed("""{"event":"pane_updated","data":{"pane_id":"w1:p1"}}""" + "\n")
        until("read") { host.snapshotCalls.get() > before }
        m.stop()
    }

    @Test fun aRefusedStatusRequestIsRetriedWithThePanesFromAFreshRead() = runBlocking<Unit> {
        host.snapshot = host.snapshot.copy(agents = listOf(Agent("w1:p1", "term_a", "w1", "w1:t1", agent = "fake")))
        // The pane closes between the read that named it and the request; another agent appears.
        host.refuseStatusOnce = {
            host.snapshot = host.snapshot.copy(
                panes = listOf(Pane("w1:p2", "term_b", "w1", "w1:t1")),
                agents = listOf(Agent("w1:p2", "term_b", "w1", "w1:t1", agent = "fake")),
            )
        }
        val m = monitor(); m.start()
        until("covered") { host.status.isNotEmpty() && m.freshness.value == Freshness.Live }
        val written = host.status.last().writtenText()
        assertTrue(written.contains("w1:p2") && !written.contains("w1:p1"), "the retry names the session as it is now: $written")
        assertTrue(m.lastLoss.value == null, "one refusal is handled without a reconnect: ${m.lastLoss.value}")
        m.stop()
    }
}
