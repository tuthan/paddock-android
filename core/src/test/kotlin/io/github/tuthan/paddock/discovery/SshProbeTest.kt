package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.ports.JavaSockets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Inet4Address
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
