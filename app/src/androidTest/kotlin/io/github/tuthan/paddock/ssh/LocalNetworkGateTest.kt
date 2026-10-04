package io.github.tuthan.paddock.ssh

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.net.HostResolver
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * AC-02.9 to AC-02.11 on an Android 17 device with this target-37 build, driven by paddock-harness/run-permission-tests.sh.
 * Revoking a runtime permission kills the app's process, so the grant state is set from outside with `pm grant`
 * and `pm revoke` between instrumentation runs, and each test asserts the state it was started in. Skipped
 * below API 37, where the grant is not enforced and the gate is a no-op (that half is unit-tested in :core).
 */
class LocalNetworkGateTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host", "10.0.2.2")
    private val port = args.getString("port", "2222").toInt()
    private val user = args.getString("user", "jdoe")

    private val clock = Clock { System.currentTimeMillis() }
    private val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
    private val policy = HostKeyPolicy(FileHostKeyStore(storeFile), clock::nowMillis)
    private val phone = PhoneKey("paddock-phone-key-transport-test")
    private val gate = LocalNetworkGate(ctx)

    private fun note(msg: String) { Log.i("TRANSPORT", msg); println("TRANSPORT $msg") }
    private fun connector() = SshlibConnector(policy, clock, gate)
    private fun phoneAuth() = phone.getOrCreate().let { SshAuth.Phone(phone.privateKey(), it.publicKey) }

    @Before fun onlyOnAndroid17() { assumeTrue(Build.VERSION.SDK_INT >= 37); assertEquals(37, ctx.applicationInfo.targetSdkVersion) }
    @After fun cleanUp() { storeFile.delete() }

    @Test
    fun deniedRefusesInTheGateAndNoSocketOpens() = runBlocking<Unit> {
        assertFalse("run with the grant revoked", gate.isGranted())
        assertTrue(gate.needsRequest(host))
        // The same permission never applies to a tailnet, loopback or DNS endpoint.
        assertFalse(gate.needsRequest("100.101.102.103")); assertFalse(gate.needsRequest("127.0.0.1")); assertFalse(gate.needsRequest("box.example.com"))
        assertNotNull(gate.settingsIntent().data)

        // A listener on the device's own LAN address: any attempt to reach it would be counted.
        val accepted = AtomicInteger()
        val server = ServerSocket(0).also { s -> thread(isDaemon = true) { while (!s.isClosed) try { s.accept().close(); accepted.incrementAndGet() } catch (_: Exception) { } } }
        val lan = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        assumeTrue("no LAN address on this device", lan != null)
        try {
            val target = SshTarget("profile-gate", lan!!, server.localPort, user)
            try { connector().connect(target, phoneAuth()) { true }; fail("connected without the grant") }
            catch (e: ConnectFailure.Refused) { assertEquals(DownReason.PermissionDenied, e.reason) }
            Thread.sleep(300)
            assertEquals("a socket reached the listener", 0, accepted.get())
            note("T denied: gate refused $lan:${server.localPort}; listener accepted ${accepted.get()}")
        } finally { server.close() }

        // And the real host, which is what the recovery row is for.
        val real = SshTarget("profile-gate", host, port, user)
        assertEquals(DownReason.PermissionDenied, gate.check(real))
        try { connector().connect(real, phoneAuth()) { true }; fail("connected without the grant") }
        catch (e: ConnectFailure.Refused) { assertEquals(DownReason.PermissionDenied, e.reason) }

        // A LAN hostname: the text is a name, the address is the LAN host (mapped here by a resolver so no DNS is needed).
        val named = LocalNetworkGate(ctx, resolver = { name ->
            if (name == "nas.lan") listOf(InetAddress.getByAddress(name, InetAddress.getByName(host).address)) else HostResolver.System.resolve(name)
        })
        assertTrue("a name resolving to a LAN address needs the grant", named.needsRequest("nas.lan"))
        assertFalse("localhost resolves to loopback, which the grant does not cover", named.needsRequest("localhost"))
        // Refused before any socket: the step's sshd connection count stays 0.
        try { SshlibConnector(policy, clock, named).connect(SshTarget("profile-gate", "nas.lan", port, user), phoneAuth()) { true }; fail("connected without the grant") }
        catch (e: ConnectFailure.Refused) { assertEquals(DownReason.PermissionDenied, e.reason) }
        note("T name: nas.lan -> $host refused in the gate; localhost not local")

        // An endpoint the gate let through that then times out carries the hint, because the OS drops it silently.
        assertEquals(DownReason.LocalNetworkTimeout, gate.timeoutHint(SshTarget("profile-gate", "100.101.102.103", 22, user)))
        note("T hint: a timeout without the grant is reported as LocalNetworkTimeout")
    }

    @Test
    fun grantedConnectsInAFreshProcessWithoutAnyAppRestartLogic() = runBlocking<Unit> {
        assertTrue("run with the grant given", gate.isGranted())
        val target = SshTarget("profile-gate", host, port, user)
        assertNull(gate.check(target))
        assertNull("no hint once the grant is given", gate.timeoutHint(target))
        val s = connector().connect(target, phoneAuth()) { true }
        try { assertEquals("ok\n", String(s.exec(listOf("echo", "ok")).stdout)) } finally { s.close() }
        note("T granted: connected to $host:$port")
    }

    /** AC-02.11 evidence: what the OS does with a raw TCP connect, logged with the state it ran in. */
    @Test
    fun recordWhatTheOsDoes() {
        val state = if (gate.isGranted()) "granted" else "denied"
        fun probe(label: String, addr: String, p: Int) = try {
            Socket().use { it.connect(InetSocketAddress(addr, p), 3000) }; "$label $addr:$p connected"
        } catch (e: Exception) { "$label $addr:$p ${e.javaClass.simpleName}" }
        note("T os[$state] " + probe("lan-host", host, port))
        note("T os[$state] " + probe("loopback", "127.0.0.1", 9) + " (refused is a reachable network)")
    }
}
