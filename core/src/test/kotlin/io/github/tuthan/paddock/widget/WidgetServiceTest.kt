package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.HomeModel
import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.InMemoryHostProfileStore
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.lifecycle.Connection
import io.github.tuthan.paddock.lifecycle.Lease
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.relay.FakeSession
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * What the graph used to decide about the widgets' cache: when the open app rewrites it, and every reason the periodic job does nothing (Phase 10,
 * AC-10.1). The read itself ([WidgetRefresher]) is tested in [WidgetTest]; here it is a function the test answers.
 */
class WidgetServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    @Volatile private var now = 1_000_000L
    private val store = InMemoryWidgetCacheStore()
    private val profiles = InMemoryHostProfileStore()
    private val alpha = HostProfile("alpha", "Alpha", "alpha.lan", user = "u")
    private val beta = HostProfile("beta", "Beta", "beta.lan", user = "u")

    private var locked = false
    private var foreground = false
    private var watched: String? = "alpha"
    private val removingNow = mutableSetOf<String>()
    private val redraws = AtomicInteger()
    private val reads = mutableListOf<String>()
    private var outcome: WidgetRefresher.Outcome = WidgetRefresher.Outcome.Unchanged("same")

    private class FakeLease(initial: Connection) : Lease {
        override val state = MutableStateFlow(initial)
        @Volatile var released = 0
        override fun release() { released++ }
    }
    private var lease = FakeLease(Connection.Connected(FakeSession(), 1))
    private val acquired = mutableListOf<String>()

    private fun service(connectMillis: Long = 200, machineName: (String) -> String? = { null }) = WidgetService(
        store, read = { p, _: SshSession, session -> reads += "${p.id}/$session"; outcome }, clock = { now }, acquire = { acquired += it.id; lease }, profiles = profiles,
        watchedProfileId = { watched }, inForeground = { foreground }, removing = { it in removingNow }, locked = { locked }, redraw = { redraws.incrementAndGet() }, connectMillis = connectMillis,
        machineName = machineName,
    )

    private fun agent(pane: String, status: AgentStatus, title: String? = null) =
        Agent(paneId = pane, terminalId = "term_${pane.replace(':', '_')}", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = status, stateChangeSeq = 1, terminalTitleStripped = title)
    private fun home(vararg a: Agent): HomeModel = AttentionModel.home(Snapshot("0.9.1", 22, agents = a.toList()), now, HostProfileId("alpha"), "main", 1, seen = SeenLookup { null })

    private fun cache(host: String = "alpha", session: String = "main") = WidgetCache(hostId = host, machine = host, session = session, readAtMillis = 5, counts = WidgetCounts())

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(3_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    // ---- the open app's own writes -----------------------------------------------------------------------------------------

    @Test fun theHerdIsWrittenWhenItChangesAndAtMostOnceAMinuteOtherwise() = runBlocking<Unit> {
        val w = service()
        // A working agent's title is in the herd but not on a widget, so changing it makes a new herd with the same cache.
        fun herd(workingTitle: String, blocked: Boolean = true) =
            home(agent("w1:p1", if (blocked) AgentStatus.Blocked else AgentStatus.Done, "approve"), agent("w1:p2", AgentStatus.Working, workingTitle))
        val flow = MutableStateFlow<HomeModel?>(null)
        var readAt: Long? = now
        val job = scope.launch { w.track(flow, "alpha", "Alpha", "main") { readAt } }
        delay(50)
        assertNull(store.load(), "no herd yet, nothing written")
        flow.value = herd("one")
        until("the first write") { store.load() != null }
        assertEquals(1, store.load()!!.counts.needsYou)
        assertEquals(1, redraws.get())
        // Another read a few seconds later with nothing different on a widget: not a new write.
        readAt = now + 10_000
        flow.value = herd("two")
        delay(100)
        assertEquals(1, redraws.get(), "nothing the widgets show changed, and the read is under a minute newer")
        assertEquals(now, store.load()!!.readAtMillis)
        // A minute later, still the same: only the time moves.
        readAt = now + WidgetService.TOUCH_MILLIS
        flow.value = herd("three")
        until("the touch") { store.load()!!.readAtMillis == now + WidgetService.TOUCH_MILLIS }
        assertEquals(2, redraws.get())
        // What the widgets show changes: written at once, however recent the last write.
        readAt = now + WidgetService.TOUCH_MILLIS + 1
        flow.value = herd("three", blocked = false)
        until("the change") { store.load()!!.counts.done == 1 }
        assertEquals(3, redraws.get())
        job.cancel()
    }

    @Test fun theCacheNamesTheMachineByWhatItIsCalledNowAndByTheTrackedNameWhenThePhoneDoesNotKnowIt() = runBlocking<Unit> {
        var names = mapOf("alpha" to "devbox")
        val w = service(machineName = { names[it] })
        val flow = MutableStateFlow<HomeModel?>(null)
        val job = scope.launch { w.track(flow, "alpha", "192.168.1.5", "main") { now } }
        delay(50)
        flow.value = home(agent("w1:p1", AgentStatus.Blocked, "approve"))
        until("the first write") { store.load() != null }
        assertEquals("devbox", store.load()!!.machine, "the connection's own copy of the name is the old one")
        names = emptyMap()
        flow.value = home(agent("w1:p1", AgentStatus.Done, "approve"))
        until("the second write") { store.load()!!.counts.done == 1 }
        assertEquals("192.168.1.5", store.load()!!.machine)
        job.cancel()
    }

    @Test fun withoutAReadTimeTheClockIsTheReadTimeAndAStoppedTrackerWritesAgain() = runBlocking<Unit> {
        val w = service()
        val herd = MutableStateFlow<HomeModel?>(home(agent("w1:p1", AgentStatus.Working)))
        val first = scope.launch { w.track(herd, "alpha", "Alpha", "main") { null } }
        until("a write") { store.load() != null }
        assertEquals(now, store.load()!!.readAtMillis)
        first.cancel()
        // The watched machine was stopped and watched again: the next read is written even though nothing changed.
        w.stopTracking()
        redraws.set(0)
        val second = scope.launch { w.track(herd, "alpha", "Alpha", "main") { null } }
        until("a write after stopTracking") { redraws.get() == 1 }
        second.cancel()
    }

    @Test fun aWriteThatFailsRedrawsNothing() = runBlocking<Unit> {
        val failing = object : WidgetCacheStore by store { override fun save(cache: WidgetCache) { throw java.io.IOException("disk full") } }
        val w = WidgetService(failing, read = { _, _, _ -> outcome }, clock = { now }, acquire = { lease }, profiles = profiles, watchedProfileId = { watched },
            inForeground = { foreground }, removing = { false }, locked = { locked }, redraw = { redraws.incrementAndGet() })
        val herd = MutableStateFlow<HomeModel?>(home(agent("w1:p1", AgentStatus.Working)))
        val job = scope.launch { w.track(herd, "alpha", "Alpha", "main") { null } }
        delay(100)
        assertEquals(0, redraws.get())
        job.cancel()
    }

    // ---- a removed machine's copy ------------------------------------------------------------------------------------------

    @Test fun forgettingAMachineClearsItsCopyAndOnlyItsCopy() = runBlocking<Unit> {
        val w = service()
        store.save(cache("alpha"))
        w.forget("beta")
        assertNotNull(store.load(), "another machine's copy stays")
        assertEquals(0, redraws.get())
        w.forget("alpha")
        assertNull(store.load())
        assertEquals(1, redraws.get())
    }

    // ---- the periodic job: every reason it does nothing ---------------------------------------------------------------------

    @Test fun aLockedWidgetCostsNoConnection() = runBlocking {
        locked = true
        assertEquals("skipped: widgets are Pro and this phone does not hold it", service().refresh())
        assertTrue(acquired.isEmpty())
    }

    @Test fun anAppInFrontKeepsTheCacheItself() = runBlocking {
        foreground = true
        assertEquals("skipped: the app is in front and keeps the cache itself", service().refresh())
        assertTrue(acquired.isEmpty())
    }

    @Test fun noMachineAndNoCacheForTheWatchedMachineAreSkippedWithoutConnecting() = runBlocking {
        assertEquals("skipped: no machine", service().refresh())
        profiles.put(alpha)
        assertEquals("skipped: nothing has been read from Alpha yet", service().refresh())
        store.save(cache("beta"))
        assertEquals("skipped: nothing has been read from Alpha yet", service().refresh(), "another machine's copy is not this one's")
        assertTrue(acquired.isEmpty(), "never a connection for a machine the phone has not read, so never a first-trust prompt")
    }

    @Test fun theWatchedMachineIsReadAndWithNoneWatchedTheFirstIs() = runBlocking {
        profiles.put(alpha); profiles.put(beta)
        store.save(cache("beta", session = "work"))
        watched = "beta"
        outcome = WidgetRefresher.Outcome.Unchanged("same")
        assertEquals("unchanged: same", service().refresh())
        assertEquals(listOf("beta/work"), reads)
        watched = null
        store.save(cache("alpha"))
        service().refresh()
        assertEquals("alpha/main", reads.last(), "no saved choice: the first machine")
        assertEquals(1 + 1, acquired.size)
        assertEquals(2, lease.released, "every claim is released")
    }

    @Test fun aReadThatUpdatedRedrawsAndSaysWhatItFound() = runBlocking {
        profiles.put(alpha)
        store.save(cache("alpha"))
        outcome = WidgetRefresher.Outcome.Updated(cache("alpha").copy(counts = WidgetCounts(needsYou = 2)))
        assertEquals("updated: ${WidgetCounts(needsYou = 2)}", service().refresh())
        assertEquals(1, redraws.get())
    }

    @Test fun aConnectionThatDoesNotComeUpInTimeIsSkippedAndReleased() = runBlocking {
        profiles.put(alpha)
        store.save(cache("alpha"))
        lease = FakeLease(Connection.Connecting)
        assertEquals("skipped: not connected (timed out)", service(connectMillis = 50).refresh())
        assertEquals(1, lease.released)
        assertTrue(reads.isEmpty())
    }

    @Test fun aConnectionThatFailedIsSkippedWithWhatItSaid() = runBlocking {
        profiles.put(alpha)
        store.save(cache("alpha"))
        lease = FakeLease(Connection.Failed(io.github.tuthan.paddock.ports.DownReason.AuthFailed, null))
        val line = service().refresh()
        assertTrue(line.startsWith("skipped: not connected (Failed("), line)
        assertEquals(1, lease.released)
    }

    @Test fun aMachineRemovedWhileTheJobReadItLeavesNothingOnTheWidgets() = runBlocking {
        profiles.put(alpha)
        store.save(cache("alpha"))
        outcome = WidgetRefresher.Outcome.Updated(cache("alpha"))
        removingNow += "alpha"
        assertEquals("skipped: Alpha was removed meanwhile", service().refresh())
        assertNull(store.load(), "what the job saved is forgotten again")
        assertEquals(1, redraws.get())
        removingNow.clear()
        store.save(cache("alpha"))
        profiles.remove("alpha")
        assertEquals("skipped: no machine", service().refresh())
    }
}
