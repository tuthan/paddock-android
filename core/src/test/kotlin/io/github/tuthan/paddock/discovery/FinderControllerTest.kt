package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.JavaSockets
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.LanPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinderControllerTest {
    private val servers = mutableListOf<ServerSocket>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val bound = java.util.concurrent.CopyOnWriteArrayList<String?>()

    @AfterTest fun close() { servers.forEach { runCatching { it.close() } }; scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]?.cancel() } }

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    private fun sshServer(address: String, banner: String = "SSH-2.0-OpenSSH_9.9p1 Debian\r\n"): Int {
        val s = ServerSocket(0, 8, ip(address)).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { return@thread }
                c.getOutputStream().apply { write(banner.toByteArray(Charsets.ISO_8859_1)); flush() }
                Thread.sleep(400); runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private fun lan(prefix: Int = 24, tunnel: Boolean = false, address: String = "127.0.0.1") = LanPath("wifi", "wlan0", tunnel, listOf(Ipv4Subnet(ip(address), prefix)))

    /** Sockets whose connects take [delayMillis] first, so a scan is still running when a test acts on it. */
    private class SlowSockets(private val delayMillis: Long) : io.github.tuthan.paddock.ports.Sockets {
        private val real = JavaSockets()
        override fun udp(path: LanPath?) = real.udp(path)
        override fun tcp(): io.github.tuthan.paddock.ports.TcpConnection {
            val c = real.tcp()
            return object : io.github.tuthan.paddock.ports.TcpConnection by c {
                override fun connect(address: InetAddress, port: Int, timeoutMillis: Int) { Thread.sleep(delayMillis); c.connect(address, port, timeoutMillis) }
                override fun close() = c.close()
            }
        }
    }

    private fun controller(
        paths: List<LanPath>, grantMissing: Boolean = false, mdns: (() -> Flow<NsdService>)? = null,
        sockets: io.github.tuthan.paddock.ports.Sockets = JavaSockets(),
    ): FinderController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return FinderController(scope, LanPaths { paths }, sockets, { grantMissing }, bindProbeTo = { bound += it }, mdns = mdns, connectTimeoutMillis = 150, bannerTimeoutMillis = 300,
            // Only the port a test opened: the default 22 would find a real sshd on this machine, which answers on all of 127.0.0.0/8.
            portsFor = { typed -> listOfNotNull(typed ?: 22) }, mdnsGraceMillis = 300)
    }

    private suspend fun FinderController.finished(timeoutMillis: Long = 30_000): FinderState = withTimeout(timeoutMillis) { state.first { it.phase == FinderPhase.Done || it.phase == FinderPhase.Cancelled } }

    @Test fun beforeAnythingRunsItSaysWhatItWouldDoAndRunsNothing() {
        val c = controller(listOf(lan()))
        c.refresh(2233)
        val s = c.state.value
        // The sentence is the real rule's only where the real ports apply: checked in FinderRulesTest. Here the test's narrowed ports show.
        assertEquals(FinderPhase.Idle, s.phase)
        assertTrue(s.canStart)
        assertTrue("127.0.0.0/24" in s.sentence && "port 2233" in s.sentence, s.sentence)
        assertTrue(bound.isEmpty())
    }

    @Test fun aScanFindsTheSshServerOnTheTypedPortWithItsSoftware() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()))
        c.start(port)
        val end = c.finished()
        assertEquals(FinderPhase.Done, end.phase)
        assertTrue(end.canStart, "a finished scan can be run again")
        assertEquals(listOf(FoundHost("127.0.0.7", port, null, "OpenSSH 9.9")), end.rows)
        assertEquals(end.total, end.done)
        assertEquals(listOf("wifi", null), bound.toList(), "pinned to the scanned network while it runs and released after")
    }

    @Test fun anAnnouncedNameIsMergedIntoTheRowOfTheSameAddress() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), mdns = { flow { emit(NsdService("devbox", ip("127.0.0.7"), port)); awaitCancellation() } })
        c.start(port)
        val end = c.finished()
        assertEquals("127.0.0.7 · devbox · OpenSSH 9.9 · port $port", end.rows.single().labelWithPort)
        assertNull(end.note)
    }

    @Test fun mdnsThatCannotStartIsSaidAndTheProbeStillAnswers() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), mdns = { flow { throw SecurityException("no") } })
        c.start(port)
        val end = c.finished()
        assertEquals(1, end.rows.size)
        assertEquals(FinderController.MDNS_UNAVAILABLE, end.note)
    }

    /** A port nothing listens on, so a scan of loopback finds only what a test announces. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun anAnnouncementFromOutsideTheScannedNetworkOrOfThePhoneItselfMakesNoRow() = runBlocking {
        // A guest on the Wi-Fi can announce _ssh._tcp for any address: only a machine on this subnet, other than the phone, is a row.
        val c = controller(listOf(lan()), mdns = {
            flow {
                emit(NsdService("guest-desktop", ip("203.0.113.9"), 22))
                emit(NsdService("elsewhere", ip("127.0.1.9"), 22))
                emit(NsdService("the-phone", ip("127.0.0.1"), 22))
                emit(NsdService("the-network", ip("127.0.0.0"), 22))
                emit(NsdService("the-broadcast", ip("127.0.0.255"), 22))
                emit(NsdService("nas", ip("127.0.0.9"), 22))
                awaitCancellation()
            }
        })
        c.start(freePort())
        assertEquals(listOf(FoundHost("127.0.0.9", 22, "nas", null)), c.finished().rows)
    }

    @Test fun theListIsCappedAtTwoHundredRowsWhateverIsAnnounced() = runBlocking {
        val c = controller(listOf(lan()), mdns = { flow { for (i in 2..254) emit(NsdService("n$i", ip("127.0.0.$i"), 22)); awaitCancellation() } })
        c.start(freePort())
        assertEquals(FoundHosts.MAX_ROWS, c.finished().rows.size)
    }

    @Test fun aWorkstationNameAtPortNineJoinsTheProbedRowAtTheTypedPort() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), mdns = { flow { emit(NsdService("devbox", ip("127.0.0.7"), 9, nameOnly = true)); emit(NsdService("lonely", ip("127.0.0.8"), 9, nameOnly = true)); awaitCancellation() } })
        c.start(port)
        assertEquals(listOf(FoundHost("127.0.0.7", port, "devbox", "OpenSSH 9.9")), c.finished().rows)
    }

    private class BrokenSockets : io.github.tuthan.paddock.ports.Sockets {
        override fun udp(path: LanPath?) = throw UnsupportedOperationException()
        override fun tcp(): io.github.tuthan.paddock.ports.TcpConnection = throw IllegalStateException("a bug in the probe")
    }

    @Test fun aProbeThatFailsUnexpectedlyEndsTheScanSaysSoAndReleasesTheNetworkInsteadOfTakingTheProcessDown() = runBlocking {
        val c = controller(listOf(lan()), sockets = BrokenSockets())
        c.start(freePort())
        val end = c.finished(5_000)
        assertEquals(FinderPhase.Done, end.phase)
        assertEquals(FinderController.SCAN_FAILED, end.note)
        assertTrue(end.canStart, "it can be run again")
        assertEquals(listOf("wifi", null), bound.toList())
    }

    @Test fun mdnsThatThrowsWhenAskedToStartIsSaidAndTheProbeStillAnswers() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), mdns = { throw IllegalStateException("no NsdManager") })
        c.start(port)
        val end = c.finished(5_000)
        assertEquals(FinderPhase.Done, end.phase)
        assertEquals(1, end.rows.size)
        assertEquals(FinderController.MDNS_UNAVAILABLE, end.note)
        assertEquals(listOf("wifi", null), bound.toList())
    }

    @Test fun aSubnetWithNothingToProbeStartsNothingAndSaysSo() {
        val c = controller(listOf(lan(prefix = 32)))
        c.start(null)
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(!c.state.value.canStart)
        assertTrue("no other address" in c.state.value.sentence, c.state.value.sentence)
        assertTrue(bound.isEmpty())
    }

    @Test fun cancellingStopsTheScanKeepsWhatWasFoundAndReleasesTheNetwork() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), mdns = { flow { awaitCancellation() } }, sockets = SlowSockets(250))
        c.start(port)
        withTimeout(10_000) { c.state.first { it.rows.isNotEmpty() } }
        assertEquals(FinderPhase.Scanning, c.state.value.phase, "before cancel: ${c.state.value}")
        c.cancel()
        assertEquals(FinderPhase.Cancelled, c.state.value.phase, "after cancel: ${c.state.value}")
        assertTrue(c.state.value.canStart, "a stopped scan can be run again")
        assertEquals(1, c.state.value.rows.size, "rows: ${c.state.value}")
        assertEquals(null, bound.last(), "bound: $bound")
    }

    @Test fun closingTheScreenMidScanReleasesTheNetworkAndTheNextOpeningStartsIdle() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), sockets = SlowSockets(250))
        c.start(port)
        withTimeout(10_000) { c.state.first { it.rows.isNotEmpty() } }
        c.close()
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(c.state.value.rows.isEmpty())
        withTimeout(10_000) { while (bound.last() != null) kotlinx.coroutines.delay(20) }
        c.refresh(port)
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(c.state.value.canStart)
    }

    @Test fun aScanClosedAndStartedAgainAtOnceKeepsItsNetworkPinWhenTheOldOneUnwinds() = runBlocking {
        // close() releases the pin at once, but the cut-off scan unwinds only after its probes in flight end: it must not clear the new scan's pin.
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()), sockets = SlowSockets(250))
        c.start(port)
        withTimeout(10_000) { while (bound.size < 1) kotlinx.coroutines.delay(10) }
        c.close()
        c.start(port)
        assertEquals(FinderPhase.Done, c.finished().phase)
        kotlinx.coroutines.delay(800)
        assertEquals(listOf("wifi", null, "wifi", null), bound.toList(), "the pin is cleared once by each scan, and the second scan kept it while it ran")
    }

    @Test fun noWifiMeansNoScan() {
        val c = controller(listOf(lan(tunnel = true)))
        c.start(null)
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(!c.state.value.canStart)
        assertTrue(bound.isEmpty())
    }

    @Test fun aNetworkWiderThanASlash24IsNeverEnumerated() {
        val c = controller(listOf(lan(prefix = 16)))
        c.start(null)
        assertTrue("larger than Paddock will probe" in c.state.value.sentence)
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(bound.isEmpty())
    }

    @Test fun aMissingGrantAsksForItAndStartsNothing() {
        val c = controller(listOf(lan()), grantMissing = true)
        c.start(null)
        assertTrue(c.state.value.needsGrant)
        assertTrue(!c.state.value.canStart)
        assertEquals(FinderPhase.Idle, c.state.value.phase)
        assertTrue(bound.isEmpty())
    }

    @Test fun startingTwiceDoesNotRunTwoScans() = runBlocking {
        val port = sshServer("127.0.0.7")
        val c = controller(listOf(lan()))
        c.start(port); c.start(port)
        c.finished()
        assertEquals(listOf("wifi", null), bound.toList())
    }
}
