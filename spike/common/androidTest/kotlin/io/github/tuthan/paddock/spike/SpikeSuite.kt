package io.github.tuthan.paddock.spike

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** S1 to S6 from the Phase 02 spike matrix. Subclassed once per library; results go to logcat tag SPIKE. */
abstract class SpikeSuite {
    abstract fun newClient(): SpikeClient

    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host", "10.0.2.2")
    private val port = args.getString("port", "2222").toInt()
    private val proxyPort = args.getString("proxyPort", "2223").toInt()
    private val controlPort = args.getString("controlPort", "2224").toInt()
    private val user = args.getString("user", "jdoe")
    private val hostFp = args.getString("hostFp")
    private val allow: (String, String) -> Boolean = { _, _ -> true }

    private fun note(msg: String) { Log.i("SPIKE", msg); println("SPIKE $msg") }

    private fun keystoreAuth(): SpikeAuth.Keystore { val (priv, pub) = SpikeKeys.keystoreKeyPair(); return SpikeAuth.Keystore(priv, pub) }

    /** Step 0: write the public keys the host must authorize. Not a pass/fail check. */
    @Test
    fun s0_exportKeys() {
        val (_, pub) = SpikeKeys.keystoreKeyPair()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "keystore.pub").writeText(SpikeKeys.openSshPublicLine(pub, "paddock-spike-keystore") + "\n")
        note("S0 exported ${File(dir, "keystore.pub")}")
    }

    @Test
    fun s1_keystoreP256Signing() {
        newClient().use { c ->
            c.connect(host, port, user, keystoreAuth(), allow)
            val r = c.exec("echo s1")
            assertEquals(0, r.exit); assertEquals("s1\n", String(r.stdout))
        }
        note("S1 PASS keystore P-256 key authenticated")
    }

    @Test
    fun s2_importedEd25519WithPassphrase() {
        val key = File("/data/local/tmp/spike_imported")
        assumeTrue("imported key not pushed", key.canRead())
        newClient().use { c ->
            c.connect(host, port, user, SpikeAuth.Pem(key.readText(), "spikepass"), allow)
            assertEquals("s2\n", String(c.exec("echo s2").stdout))
        }
        note("S2 PASS imported passphrase-protected Ed25519 key authenticated")
    }

    @Test
    fun s3a_hostKeyCallbackSeesAlgorithmAndFingerprintBeforeAuth() {
        var seen: Pair<String, String>? = null
        newClient().use { c ->
            c.connect(host, port, user, keystoreAuth(), { a, f -> seen = a to f; true })
        }
        assertNotNull(seen)
        note("S3a host key callback: ${seen!!.first} ${seen!!.second}")
        if (hostFp != null) assertEquals(hostFp, seen!!.second)
        assertEquals("ssh-ed25519", seen!!.first)
    }

    @Test
    fun s3b_changedHostKeyAbortsBeforeAuth() {
        var calls = 0
        try {
            newClient().use { c -> c.connect(host, port, user, keystoreAuth(), { _, _ -> calls++; false }) }
            fail("connect must not succeed when the host key is rejected")
        } catch (e: HostKeyRejected) {
            assertEquals(1, calls)
            note("S3b PASS rejected ${e.algorithm} ${e.fingerprint}; host log must show no authentication attempt")
        }
    }

    @Test
    fun s4_execKeepsExitStdoutAndStderrApart() {
        newClient().use { c ->
            c.connect(host, port, user, keystoreAuth(), allow)
            val r = c.exec("echo out; echo err 1>&2; exit 3")
            assertEquals(3, r.exit)
            assertEquals("out\n", String(r.stdout)); assertEquals("err\n", String(r.stderr))
            val big = c.exec("head -c 300000 /dev/zero | tr '\\0' x")
            assertEquals(300000, big.stdout.size)
        }
        note("S4 PASS exit 3, separate streams, 300 kB stdout intact")
    }

    @Test
    fun s5_threeConcurrentExecChannels() {
        newClient().use { c ->
            c.connect(host, port, user, keystoreAuth(), allow)
            val pool = Executors.newFixedThreadPool(3)
            val t0 = System.nanoTime()
            val futures = (1..3).map { n -> pool.submit(Callable { String(c.exec("sleep 2; echo n$n").stdout) }) }
            val out = futures.map { it.get(30, TimeUnit.SECONDS) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            pool.shutdown()
            assertEquals(listOf("n1\n", "n2\n", "n3\n"), out)
            assertTrue("three 2 s channels took $ms ms; they did not overlap", ms < 5_000)
            note("S5 PASS three concurrent channels in $ms ms")
        }
    }

    @Test
    fun s6_deadLinkDetectedWithin30Seconds() {
        newClient().use { c ->
            c.connect(host, proxyPort, user, keystoreAuth(), allow, keepaliveSeconds = 15)
            assertEquals("up\n", String(c.exec("echo up").stdout))
            Socket(host, controlPort).use { it.getOutputStream().write("freeze\n".toByteArray()); it.getInputStream().read(ByteArray(64)) }
            val ms = c.awaitDead(60_000)
            Socket(host, controlPort).use { it.getOutputStream().write("thaw\n".toByteArray()); it.getInputStream().read(ByteArray(64)) }
            note("S6 dead link detected after $ms ms (limit 30000)")
            assertTrue("not detected within 60 s", ms >= 0)
            assertTrue("detected after $ms ms, over the 30 s limit", ms <= 30_000)
        }
    }
}
