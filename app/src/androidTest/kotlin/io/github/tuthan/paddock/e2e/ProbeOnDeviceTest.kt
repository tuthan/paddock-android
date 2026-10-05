package io.github.tuthan.paddock.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.discovery.ProbeEvent
import io.github.tuthan.paddock.discovery.SshProbe
import io.github.tuthan.paddock.net.AndroidLanPaths
import io.github.tuthan.paddock.net.AndroidSockets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Phase 14 slice 4: the probe over the app's real sockets, with and without the finder's network pin. It needs an SSH server on the
 * host that the emulator reaches at 10.0.2.2 (the harness's `check-discovery.py` starts tools/test-sshd.sh on 2233; this test skips
 * itself when nothing listens there). The result lines go to logcat tag E2E.
 */
class ProbeOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = InetAddress.getByName("10.0.2.2") as Inet4Address

    private fun probe(pin: Boolean): List<String> {
        val paths = AndroidLanPaths(context)
        val sockets = AndroidSockets(paths)
        val lan = paths.paths().firstOrNull { !it.tunnel && it.hasIpv4 }
        Log.i("E2E", "PROBE paths=${paths.paths().map { "${it.id}:${it.interfaceName}:${it.subnets}" }}")
        if (pin) sockets.tcpPathId = lan?.id
        val events = runBlocking { SshProbe(sockets, 1_000, 1_500).scan(listOf(host), listOf(22, 2233)).toList() }
        val hits = events.filterIsInstance<ProbeEvent.Hit>().map { "${it.hit.address.hostAddress}:${it.hit.port} ${it.hit.banner}" }
        Log.i("E2E", "PROBE pin=$pin lan=${lan?.id} hits=$hits")
        return hits
    }

    private fun sshdUp() = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 2233), 1_500); true } } catch (_: Exception) { false }

    @Test fun theProbeFindsTheHostsSshdOnAnUnpinnedSocket() {
        org.junit.Assume.assumeTrue("no sshd at 10.0.2.2:2233", sshdUp())
        assertTrue(probe(pin = false).any { it.startsWith("10.0.2.2:2233 SSH-") })
    }

    @Test fun theProbeFindsTheHostsSshdOnASocketPinnedToTheWifiNetwork() {
        org.junit.Assume.assumeTrue("no sshd at 10.0.2.2:2233", sshdUp())
        assertTrue(probe(pin = true).any { it.startsWith("10.0.2.2:2233 SSH-") })
    }

    @Test fun aWholeSubnetScanWithTheProbesDefaultsFindsTheHostsSshdInUnderFifteenSeconds() {
        org.junit.Assume.assumeTrue("no sshd at 10.0.2.2:2233", sshdUp())
        val paths = AndroidLanPaths(context)
        val sockets = AndroidSockets(paths)
        val lan = paths.paths().first { !it.tunnel && it.hasIpv4 }
        // The API 26 emulator's Wi-Fi is a /21, which the finder refuses to enumerate (checked by check-discovery.py); nothing to scan here.
        org.junit.Assume.assumeTrue("the Wi-Fi network is wider than /24", lan.subnets.first().prefix >= 24)
        sockets.tcpPathId = lan.id
        val addresses = lan.subnets.first().let { it.hosts(setOf(it.address)) }
        val started = System.nanoTime()
        val events = runBlocking { SshProbe(sockets).scan(addresses, listOf(22, 2233)).toList() }
        val millis = (System.nanoTime() - started) / 1_000_000
        val hits = events.filterIsInstance<ProbeEvent.Hit>().map { "${it.hit.address.hostAddress}:${it.hit.port}" }
        Log.i("E2E", "PROBE scan defaults targets=${addresses.size * 2} $millis ms hits=$hits")
        assertTrue("hits: $hits", hits.contains("10.0.2.2:2233"))
        assertTrue("$millis ms", millis < 15_000)
    }
}
