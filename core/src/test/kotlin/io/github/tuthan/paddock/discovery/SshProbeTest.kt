package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.ports.JavaSockets
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.Sockets
import io.github.tuthan.paddock.ports.TcpConnection
import io.github.tuthan.paddock.ports.UdpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshProbeTest {
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest fun close() { servers.forEach { runCatching { it.close() } } }

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    /** A server on [address] that sends [greeting] (nothing when null) and then holds the connection a moment. */
    private fun server(address: String, greeting: String?, holdMillis: Long = 600): Int {
        val s = ServerSocket(0, 8, ip(address)).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { return@thread }
                if (greeting != null) c.getOutputStream().apply { write(greeting.toByteArray(Charsets.ISO_8859_1)); flush() }
                Thread.sleep(holdMillis)
                runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private fun scan(addresses: List<String>, ports: List<Int>, bannerMs: Int = 300) = runBlocking {
        SshProbe(JavaSockets(), connectTimeoutMillis = 200, bannerTimeoutMillis = bannerMs).scan(addresses.map(::ip), ports).toList()
    }

    private fun hits(events: List<ProbeEvent>) = events.filterIsInstance<ProbeEvent.Hit>().map { it.hit }

    @Test fun anSshServerIsAHitWithItsBannerAndTheOthersAreNot() {
        val port = server("127.0.0.5", "SSH-2.0-OpenSSH_9.9p1 Debian-1\r\n")
        val events = scan((1..10).map { "127.0.0.$it" }, listOf(port))
        val h = hits(events)
        assertEquals(1, h.size)
        assertEquals("127.0.0.5", h[0].address.hostAddress)
        assertEquals(port, h[0].port)
        assertEquals("SSH-2.0-OpenSSH_9.9p1 Debian-1", h[0].banner)
        assertEquals("OpenSSH 9.9", h[0].software)
    }

    @Test fun progressCountsEveryAddressAndPortOnceAndEndsAtTheTotal() {
        val events = scan((1..6).map { "127.0.0.$it" }, listOf(1, 2))
        val progress = events.filterIsInstance<ProbeEvent.Progress>()
        assertEquals(12, progress.last().total)
        assertEquals(12, progress.maxOf { it.done })
        assertEquals(0, progress.first().done)
    }

    @Test fun aSilentServerAndAServerThatSendsJunkAreNotHits() {
        val silent = server("127.0.0.6", null, holdMillis = 800)
        val junk = server("127.0.0.7", "HTTP/1.1 400 Bad Request\r\n")
        assertTrue(hits(scan(listOf("127.0.0.6"), listOf(silent), bannerMs = 150)).isEmpty())
        assertTrue(hits(scan(listOf("127.0.0.7"), listOf(junk))).isEmpty())
    }

    @Test fun aRefusedPortIsNotAHit() {
        assertTrue(hits(scan(listOf("127.0.0.8"), listOf(1))).isEmpty())
    }

    /** Sockets whose connect blocks (as a host that never answers does) until [release] opens, counting how many are blocked at once. */
    private class BlockingSockets(val release: CountDownLatch, val blocked: AtomicInteger) : Sockets {
        override fun udp(path: LanPath?): UdpSender = throw UnsupportedOperationException()
        override fun tcp(): TcpConnection = object : TcpConnection {
            override fun connect(address: InetAddress, port: Int, timeoutMillis: Int) {
                blocked.incrementAndGet()
                release.await(15, TimeUnit.SECONDS)
                throw IOException("no answer")
            }
            override fun write(bytes: ByteArray) = Unit
            override fun readLine(max: Int, timeoutMillis: Int): String? = null
            override fun close() = Unit
        }
    }

    // With assertions on, coroutine debugging appends " @coroutine#N" to the name of a thread while it runs a coroutine.
    private fun probeThreads() = Thread.getAllStackTraces().keys.count { it.name.startsWith("paddock-probe") && it.isAlive }

    @Test fun aScanOfSilentHostsDoesNotTakeTheSharedIoPoolFromTheRestOfTheApp() = runBlocking {
        // The probe blocks one thread per address in flight. Dispatchers.IO (64 threads) is also where the watched machine's SSH, ledger and
        // profile work runs, so 64 or more blocked probes must be on a pool of their own.
        val release = CountDownLatch(1)
        val blocked = AtomicInteger()
        val probe = SshProbe(BlockingSockets(release, blocked), 600, 800, parallelism = 200)
        val scanning = launch(Dispatchers.Default) { probe.scan((1..200).map { ip("10.0.0.$it") }, listOf(22)).collect { } }
        try {
            withTimeout(10_000) { while (blocked.get() < 64) delay(10) }
            val answered = withTimeoutOrNull(1_000) { withContext(Dispatchers.IO) { "free" } }
            assertEquals("free", answered, "an unrelated IO task did not run while ${blocked.get()} probes were blocked")
        } finally {
            release.countDown()
            scanning.cancelAndJoin()
        }
    }

    @Test fun theProbesOwnThreadsEndWithTheScanWhetherItFinishesOrIsCancelled() = runBlocking {
        scan(listOf("127.0.0.8", "127.0.0.9"), listOf(1))
        withTimeout(5_000) { while (probeThreads() > 0) delay(20) }

        val release = CountDownLatch(1)
        val probe = SshProbe(BlockingSockets(release, AtomicInteger()), 600, 800, parallelism = 8)
        val scanning = launch(Dispatchers.Default) { probe.scan((1..8).map { ip("10.0.0.$it") }, listOf(22)).collect { } }
        withTimeout(5_000) { while (probeThreads() == 0) delay(10) }
        release.countDown()
        scanning.cancelAndJoin()
        withTimeout(5_000) { while (probeThreads() > 0) delay(20) }
    }

    @Test fun theBannerRuleKeepsOnlyPrintableSshIdentificationLines() {
        assertEquals("SSH-2.0-dropbear_2022.83", BannerReader.accept("SSH-2.0-dropbear_2022.83"))
        assertNull(BannerReader.accept("SSH"))
        assertNull(BannerReader.accept(null))
        assertNull(BannerReader.accept("SSH-2.0-x\u0000y"))
        assertNull(BannerReader.accept("SSH-" + "a".repeat(260)))
        assertEquals("dropbear 2022.83", BannerReader.software("SSH-2.0-dropbear_2022.83"))
        assertEquals("libssh", BannerReader.software("SSH-2.0-libssh"))
    }
}
