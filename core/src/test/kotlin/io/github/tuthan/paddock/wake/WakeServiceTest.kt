package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.LanPaths
import io.github.tuthan.paddock.ports.Sockets
import io.github.tuthan.paddock.ports.TcpConnection
import io.github.tuthan.paddock.ports.UdpSender
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/** The wake wiring the app graph used to hold: which machine a tap goes to, from what profile, and whose facts a screen sees. Real taps, a fake socket. */
class WakeServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() { scope.cancel() }

    private class Datagram(val address: String, val port: Int, val broadcast: Boolean)

    private class FakeSockets : Sockets {
        val sent = CopyOnWriteArrayList<Datagram>()
        override fun tcp(): TcpConnection = error("not used")
        override fun udp(path: LanPath?): UdpSender = object : UdpSender {
            override fun send(payload: ByteArray, address: InetAddress, port: Int, broadcast: Boolean) { sent += Datagram(address.hostAddress!!, port, broadcast) }
            override fun close() {}
        }
    }

    private fun lan(address: String = "192.168.1.20") = LanPath("wifi", "wifi", tunnel = false, subnets = listOf(Ipv4Subnet(Ipv4Subnet.literal(address)!!, 24)))

    private fun machine(id: String, host: String, relay: WakeRelay? = null, wake: Boolean = true) = HostProfile(
        id = id, name = id, host = host, user = "u",
        wake = if (wake) WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 1, relay = relay) else null,
    )

    private val sockets = FakeSockets()
    private val alpha = machine("alpha", "192.168.1.30")
    private val beta = machine("beta", "192.168.1.31")
    private val watched = MutableStateFlow<HostProfile?>(alpha)
    private val stored = mutableMapOf("alpha" to alpha, "beta" to beta)
    private val reconnects = AtomicInteger()
    private val link = MutableStateFlow(WakeLink.DOWN)
    private var paths = listOf(lan())
    private var permissionMissing = false

    private fun service() = WakeService(
        scope, { System.currentTimeMillis() }, LanPaths { paths }, sockets, permissionMissing = { permissionMissing }, watched = watched, profile = { stored[it] },
        link = { link.value }, links = link, reconnect = { reconnects.incrementAndGet() },
    )

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(5_000) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what") }
    }

    /** The next facts a screen collecting this flow would see (the per-machine flows only run while someone collects them). */
    private suspend fun StateFlow<WakeFacts?>.next(): WakeFacts = withTimeout(5_000) { first { it != null } }!!

    @Test fun aTapOnTheWatchedMachineSendsAsksForAReconnectAndShowsTheFacts() = runBlocking<Unit> {
        val w = service()
        w.wake("alpha")
        until("alpha's facts") { w.watchedFacts.value != null }
        assertEquals(1, reconnects.get())
        assertFalse(w.watchedFacts.value!!.notWatched)
        assertTrue(sockets.sent.isNotEmpty() && sockets.sent.all { it.broadcast })
        assertEquals(w.watchedFacts.value, w.facts("alpha").value, "the watched machine's screen shows the watched tap's facts")
    }

    @Test fun aTapOnAnotherMachineOnlySendsAndSaysItIsNotWatched() = runBlocking<Unit> {
        val w = service()
        w.wake("beta")
        assertTrue(w.facts("beta").next().notWatched)
        assertEquals(0, reconnects.get(), "the watched machine's connection is not asked to reconnect for another machine")
        assertNull(w.watchedFacts.value)
        assertNull(w.facts("alpha").value)
    }

    @Test fun theSendUsesTheStoredCopyOfTheMachineNotTheWatchedOne() = runBlocking<Unit> {
        // The watched copy has no relay; the store has one, saved since (a tunnel with a relay sends only to the relay).
        paths = listOf(LanPath("wg", "wg", tunnel = true, subnets = listOf(Ipv4Subnet(Ipv4Subnet.literal("10.8.0.2")!!, 24))))
        stored["alpha"] = machine("alpha", "192.168.1.30", relay = WakeRelay("10.8.0.1", 9009))
        val w = service()
        w.wake("alpha")
        until("the relay send") { sockets.sent.isNotEmpty() }
        assertTrue(sockets.sent.all { it.address == "10.8.0.1" && it.port == 9009 && !it.broadcast })
    }

    @Test fun nothingIsSentForAMachineWhoseHardwareAddressWasNeverRead() = runBlocking<Unit> {
        stored["beta"] = machine("beta", "192.168.1.31", wake = false)
        val w = service()
        w.wake("beta")
        delay(150)
        assertTrue(sockets.sent.isEmpty())
        assertNull(w.facts("beta").value)
    }

    @Test fun readyNeedsAReadAddressAndAPathThatCanCarryIt() {
        val w = service()
        assertTrue(w.ready(alpha))
        assertFalse(w.ready(machine("gamma", "192.168.1.32", wake = false)))
        paths = listOf(lan("10.9.9.9"))
        assertFalse(w.ready(alpha), "a network the machine is not on")
        paths = emptyList()
        assertFalse(w.ready(alpha))
    }

    @Test fun becomingTheWatchedMachineClearsItsSendOnlyFactsAndForgettingDropsItsFlow() = runBlocking<Unit> {
        val w = service()
        w.wake("beta")
        w.facts("beta").next()
        watched.value = beta
        w.watching("beta")
        withTimeout(5_000) { w.facts("beta").first { it == null } }
        w.wake("alpha")
        w.facts("alpha").next()
        val before = w.facts("alpha")
        w.forget("alpha")
        assertTrue(w.facts("alpha") !== before, "a removed machine's flow is not reused")
        withTimeout(5_000) { w.facts("alpha").first { it == null } }
    }

    @Test fun aSendThatHitsAMissingPermissionSaysSo() = runBlocking<Unit> {
        permissionMissing = true
        val w = service()
        w.wake("alpha")
        until("the failure") { w.watchedFacts.value != null }
        assertEquals(WakeSendResult.Failed(WakeSendFailure.Permission), w.watchedFacts.value!!.result)
        assertTrue(sockets.sent.isEmpty())
    }
}
