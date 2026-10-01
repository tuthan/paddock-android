package io.github.tuthan.paddock.ssh

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ports.DownReason
import java.io.File
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneKeyTest {
    // One alias per test: Keystore2 can fail a delete that immediately follows another test's delete and create on
    // the same alias ("Trying to unbind the key"), which is test interference, not behaviour under test.
    private val key = PhoneKey("paddock-phone-key-test-${java.util.UUID.randomUUID()}")

    @After
    fun clean() { runCatching { key.delete() } }

    @Test
    fun createsAP256KeyAndReportsAKnownBacking() {
        assertFalse(key.exists())
        val info = key.getOrCreate()
        assertTrue(key.exists())
        assertEquals(256, info.publicKey.params.curve.field.fieldSize)
        Log.i("PHONEKEY", "api=${Build.VERSION.SDK_INT} backing=${info.backing}")
        // The platform tells us; the emulator is Software or Tee, a phone may be StrongBox. Never "assumed".
        assertNotEquals("backing could not be read", KeyBacking.Unknown, info.backing)
    }

    /** After app-data loss the alias is gone: connecting must report a key problem, not crash or claim the network is down. */
    @Test
    fun aMissingKeyIsKeyUnavailableAndIsNeverRegeneratedSilently() {
        assertFalse(key.exists())
        try { key.privateKey(); fail("a missing key returned a handle") } catch (e: ConnectFailure.KeyUnavailable) { assertEquals(DownReason.KeyUnavailable, e.reason) }
        try { key.info(); fail("a missing key returned info") } catch (_: ConnectFailure.KeyUnavailable) { }
        assertFalse("privateKey() must not create a new identity", key.exists())
    }

    @Test
    fun secondCallReturnsTheSameKeyNotANewOne() {
        val a = key.getOrCreate().publicKey
        val b = key.getOrCreate().publicKey
        assertEquals(a.w, b.w)
    }

    @Test
    fun theHandleSignsAndThePublicKeyVerifies() {
        val info = key.getOrCreate()
        val message = "paddock".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key.privateKey()); update(message) }.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(info.publicKey); update(message) }.verify(sig))
    }

    @Test
    fun thePrivateKeyCannotBeExported() {
        key.getOrCreate()
        assertEquals(null, key.privateKey().encoded)
    }

    @Test
    fun deleteRemovesTheKeyAndANewOneDiffers() {
        val first = key.getOrCreate().publicKey.w
        key.delete()
        assertFalse(key.exists())
        // Generated under a second alias so the check does not race the delete just issued.
        val other = PhoneKey("paddock-phone-key-test-${java.util.UUID.randomUUID()}")
        try { assertNotEquals(first, other.getOrCreate().publicKey.w) } finally { runCatching { other.delete() } }
    }

    /** Written for the host-side check: `ssh-keygen -l -f` must accept the exported line (AC for slice 5). */
    @Test
    fun exportsAnAuthorizedKeysLine() {
        val line = key.publicLine("paddock-instrumentation")
        assertTrue(line, line.startsWith("ecdsa-sha2-nistp256 AAAA"))
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "phonekey.pub").writeText(line + "\n")
    }
}
