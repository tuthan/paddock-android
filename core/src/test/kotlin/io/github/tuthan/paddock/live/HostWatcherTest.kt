package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.hostprofile.HostOs
import io.github.tuthan.paddock.ledger.InMemoryLedgerStore
import io.github.tuthan.paddock.ledger.Ledger
import io.github.tuthan.paddock.relay.sha256Hex
import io.github.tuthan.paddock.wake.WakeTarget
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/** The one controller the phone keeps for the watched machine, and what follows it: when it is reused, replaced, paused and stopped, with real controllers over leases that never connect. */
class HostWatcherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private val ledger = Ledger(InMemoryLedgerStore()) { System.currentTimeMillis() }
    private val script = "print('relay')\n".toByteArray()
    private val sha = sha256Hex(script)

    private val alpha = HostProfile("alpha", "Alpha", "10.0.0.2", 22, "jdoe")
    private val leases = CopyOnWriteArrayList<Pair<String, FakeLease>>()
    private val events = CopyOnWriteArrayList<String>()
    private val removing = mutableSetOf<String>()
    private val followed = CopyOnWriteArrayList<HostSessionController>()
    private val cancelledFollows = CopyOnWriteArrayList<HostSessionController>()

    private fun watcher() = HostWatcher(
        scope, isRemoving = { it in removing },
        create = { p -> val lease = FakeLease(); leases += p.id to lease; HostSessionController(scope, p, { lease }, ledger, { System.currentTimeMillis() }, MutableStateFlow(true), script, sha) },
        attach = { events += "attach:${it.profile.id}" }, detach = { events += "detach" }, becameWatched = { events += "watched:$it" }, stopped = { events += "stopped" },
        follows = listOf(
            { c -> followed += c; try { awaitCancellation() } finally { cancelledFollows += c } },
            { _ -> events += "second follow" },
        ),
    )

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(3_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    @Test fun watchingAMachineBuildsAttachesAndStartsItsControllerAndStartsEveryFollow() = runBlocking<Unit> {
        val w = watcher()
        w.watch(alpha)
        assertEquals(listOf("watched:alpha", "attach:alpha"), events.toList().take(2))
        assertEquals(alpha, w.current!!.profile)
        until("both follows") { followed.size == 1 && "second follow" in events }
        assertSame(w.current, followed.single())
        assertEquals(1, leases.size, "one claim for the one controller")
    }

    @Test fun theSameConnectionIsResumedNotReplacedWhenOnlyWhatWasReadAboutItChanged() = runBlocking<Unit> {
        val w = watcher()
        w.watch(alpha)
        until("follow") { followed.size == 1 }
        val first = w.current
        val read = alpha.copy(wake = WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 1), detectedOs = HostOs.Mac, os = HostOs.Linux)
        w.watch(read)
        w.watch(alpha)
        assertSame(first, w.current)
        assertEquals(1, leases.size, "no second controller, no second claim")
        assertEquals(1, events.count { it.startsWith("watched:") })
        assertEquals(1, followed.size, "the follows were not restarted")
        assertTrue(cancelledFollows.isEmpty())
    }

    @Test fun aNamePaddockChoseFollowingTheMachineKeepsTheConnectionAndOneTheUserTypedDoesNot() = runBlocking<Unit> {
        val w = watcher()
        val byAddress = alpha.copy(name = alpha.host)
        w.watch(byAddress)
        until("follow") { followed.size == 1 }
        val first = w.current
        w.watch(byAddress.copy(name = "devbox", detectedName = "devbox"))
        w.watch(byAddress.copy(name = "devbox-2", detectedName = "devbox-2"))
        assertSame(first, w.current, "the hostname was adopted and followed: no new controller")
        assertEquals(1, leases.size)
        assertEquals(1, followed.size)
        w.watch(byAddress.copy(name = "Build server", detectedName = "devbox-2"))
        assertNotSame(first, w.current, "a name the user typed is part of the connection")
    }

    @Test fun aChangeThatNeedsANewConnectionReplacesTheControllerAndStopsTheOldOnesFollows() = runBlocking<Unit> {
        val w = watcher()
        w.watch(alpha)
        until("follow") { followed.size == 1 }
        val first = w.current!!
        for (changed in listOf(alpha.copy(name = "Alpha 2"), alpha.copy(port = 2222), alpha.copy(session = "work"), alpha.copy(host = "10.0.0.3"))) {
            val before = w.current!!
            w.watch(changed)
            assertNotSame(before, w.current, changed.toString())
            assertEquals(changed, w.current!!.profile)
        }
        until("the first controller's follow to be cancelled") { first in cancelledFollows }
        until("the last follow") { followed.size == 5 }
        assertTrue(leases.first().second.released.get(), "the replaced controller let go of its connection")
        // The same machine under a new name or address is not "another machine": the watched tap's facts are kept for it.
        assertEquals(1, events.count { it.startsWith("watched:") })
    }

    @Test fun anotherMachineSaysItBecameWatched() = runBlocking<Unit> {
        val w = watcher()
        w.watch(alpha)
        w.watch(HostProfile("beta", "Beta", "10.0.0.9", 22, "jdoe"))
        assertEquals(listOf("watched:alpha", "watched:beta"), events.filter { it.startsWith("watched:") })
        assertEquals("beta", w.current!!.profile.id)
    }

    @Test fun aMachineBeingRemovedIsNeverWatchedAndTheOldWatchStays() = runBlocking<Unit> {
        val w = watcher()
        removing += "alpha"
        w.watch(alpha)
        assertNull(w.current)
        assertTrue(events.isEmpty() && leases.isEmpty())
        removing.clear()
        w.watch(alpha)
        val first = w.current
        removing += "alpha"
        w.watch(alpha.copy(port = 2222))
        assertSame(first, w.current, "a watch that is refused changes nothing")
    }

    @Test fun stoppingEndsTheControllerAndEveryFollowTellsTheOwnerThenDetachesAndAllowsAFreshWatch() = runBlocking<Unit> {
        val w = watcher()
        w.watch(alpha)
        until("follow") { followed.size == 1 }
        val c = w.current!!
        events.clear()
        w.stop()
        assertNull(w.current)
        assertEquals(listOf("stopped", "detach"), events.toList())
        until("the follow to be cancelled") { c in cancelledFollows }
        assertTrue(leases.single().second.released.get(), "stopping lets go of the connection")
        w.stop()
        w.watch(alpha)
        assertNotSame(c, w.current)
        assertEquals(2, leases.size)
    }

    @Test fun pauseReleasesTheClaimAndNothingBreaksWithoutAController() = runBlocking<Unit> {
        val w = watcher()
        w.pause(); assertFalse(w.retrySetup()); w.installRelay()
        w.watch(alpha)
        w.pause()
        assertTrue(leases.single().second.released.get())
        assertFalse(w.retrySetup(), "a controller that is not in a problem has nothing to run again")
    }
}
