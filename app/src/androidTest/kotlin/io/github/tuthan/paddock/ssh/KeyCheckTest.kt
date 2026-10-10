package io.github.tuthan.paddock.ssh

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.discovery.KeyCheck
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostkey.PinnedHostKey
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relocator's key check (`SshlibConnector.checkPinnedKey`) against the throwaway sshd from tools/test-sshd.sh, run by
 * paddock-harness/run-transport-tests.sh with this class as the filter. The pin is made by a first contact as a user sshd refuses, so the run's
 * sshd log holds no accepted sign-in at all: one there would mean the check signed in.
 */
class KeyCheckTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val host = args.getString("host", "10.0.2.2")
    private val port = args.getString("port", "2222").toInt()
    private val user = args.getString("user", "jdoe")

    private val clock = Clock { System.currentTimeMillis() }
    private val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
    private val store = FileHostKeyStore(storeFile)
    private val policy = HostKeyPolicy(store, clock::nowMillis)
    private val phone = PhoneKey("paddock-phone-key-transport-test")
    private fun connector(gate: ConnectGate = ConnectGate.Open) = SshlibConnector(policy, clock, gate)

    @After fun cleanUp() { storeFile.delete() }

    /** First contact as a user sshd does not allow: the key exchange completes and the key is pinned, the sign-in is refused. */
    private suspend fun pinTheServersKey(profile: String) {
        val auth = phone.getOrCreate().let { SshAuth.Phone(phone.privateKey(), it.publicKey) }
        val r = runCatching { connector().connect(SshTarget(profile, host, port, "paddock-no-such-user"), auth) { true } }
        assertTrue("the sign-in must be refused: ${r.exceptionOrNull()}", r.exceptionOrNull() is ConnectFailure.AuthFailed)
        assertNotNull(store.find(profile))
    }

    @Test fun thePinnedKeyIsPresentedWhoeverTheUserIsAndThePinIsNotTouched() = runBlocking<Unit> {
        pinTheServersKey("kc-1")
        val before = store.find("kc-1")!!.lastSeenMillis
        Thread.sleep(5)
        assertEquals(KeyCheck.Matches, connector().checkPinnedKey(SshTarget("kc-1", host, port, user)))
        // The check never reaches authentication, so a user the server would refuse makes no difference.
        assertEquals(KeyCheck.Matches, connector().checkPinnedKey(SshTarget("kc-1", host, port, "paddock-no-such-user")))
        assertEquals("a key seen at a candidate address is not a sighting at the saved one", before, store.find("kc-1")!!.lastSeenMillis)
    }

    @Test fun anotherKeyIsNotTheMachine() = runBlocking<Unit> {
        val blob = ByteArray(51).also { java.util.Random(7).nextBytes(it) }
        store.save(PinnedHostKey("kc-2", "$host:$port", "ssh-ed25519", blob, "SHA256:other", 1L, 1L))
        assertEquals("another machine's key is said to be another key, so it is remembered as not the machine", KeyCheck.OtherKey, connector().checkPinnedKey(SshTarget("kc-2", host, port, user)))
        assertEquals("SHA256:other", store.find("kc-2")!!.fingerprint)
    }

    @Test fun withoutAPinNothingIsTheMachineAndNothingIsWritten() = runBlocking<Unit> {
        assertEquals(KeyCheck.NoAnswer, connector().checkPinnedKey(SshTarget("kc-3", host, port, user)))
        assertNull(store.find("kc-3"))
    }

    @Test fun nothingListeningIsNotTheMachineAndEndsQuickly() = runBlocking<Unit> {
        pinTheServersKey("kc-4")
        val t0 = System.nanoTime()
        assertEquals("nothing answered, so it says nothing about who is there", KeyCheck.NoAnswer, connector().checkPinnedKey(SshTarget("kc-4", host, 2299, user)))
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 6_000_000_000L)
    }

    @Test fun aGateThatRefusesIsNotTheMachine() = runBlocking<Unit> {
        pinTheServersKey("kc-5")
        assertEquals(KeyCheck.NoAnswer, connector(ConnectGate { DownReason.PermissionDenied }).checkPinnedKey(SshTarget("kc-5", host, port, user)))
    }
}
