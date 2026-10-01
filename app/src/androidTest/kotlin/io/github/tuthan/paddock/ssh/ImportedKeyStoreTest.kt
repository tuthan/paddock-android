package io.github.tuthan.paddock.ssh

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.hostkey.FileHostKeyStore
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.ports.Clock
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** AC-02.8: an imported, passphrase-protected Ed25519 key is stored wrapped, never in clear, and still connects. */
class ImportedKeyStoreTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(ctx.filesDir, "ikstest-${UUID.randomUUID()}")
    private val store = ImportedKeyStore(KeystoreSecretStore(dir, "paddock-ikstest-${UUID.randomUUID()}"))
    private val keyFile = File("/data/local/tmp/spike_imported")

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test
    fun storedKeyIsNotReadableOnDiskAndStillAuthenticates() = runBlocking<Unit> {
        assumeTrue("imported key not pushed", keyFile.canRead())
        val pem = keyFile.readText()
        val result = store.import("laptop-key", pem.toCharArray(), "spikepass", rememberPassphrase = true)
        assertTrue(result.toString(), result is ImportCheck.Ready)

        // Nothing under the secret directory, and nothing anywhere in app data, holds the key text or its body.
        val body = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val needles = listOf("BEGIN OPENSSH PRIVATE KEY", body.take(40), "spikepass")
        val files = ctx.dataDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.path.startsWith(dir.path) })
        for (f in files) { val text = f.readBytes().toString(Charsets.ISO_8859_1); for (n in needles) assertFalse("${f.name} contains plaintext", text.contains(n)) }

        val info = store.info("laptop-key")!!
        assertEquals("ssh-ed25519", info.keyType); assertTrue(info.encrypted); assertTrue(info.passphraseRemembered)

        val clock = Clock { System.currentTimeMillis() }
        val storeFile = File(ctx.cacheDir, "pins-${UUID.randomUUID()}.json")
        try {
            val auth = store.load("laptop-key")!!
            val target = SshTarget("profile-ik", args.getString("host", "10.0.2.2"), args.getString("port", "2222").toInt(), args.getString("user", "jdoe"))
            val s = SshlibConnector(HostKeyPolicy(FileHostKeyStore(storeFile), clock::nowMillis), clock).connect(target, auth) { true }
            try { assertEquals("ok\n", String(s.exec(listOf("echo", "ok")).stdout)) } finally { s.close() }
        } finally { storeFile.delete() }

        store.delete("laptop-key")
        assertNull(store.info("laptop-key")); assertNull(store.load("laptop-key"))
    }

    @Test
    fun anUnusableKeyIsReturnedAndNothingIsStored() = runBlocking<Unit> {
        assumeTrue("imported key not pushed", keyFile.canRead())
        assertEquals(ImportCheck.NeedsPassphrase, store.import("k1", keyFile.readText().toCharArray(), null, false))
        assertEquals(ImportCheck.WrongPassphrase, store.import("k1", keyFile.readText().toCharArray(), "nope", false))
        assertEquals(ImportCheck.NotAKey, store.import("k1", "hello".toCharArray(), null, false))
        assertNull(store.info("k1")); assertNull(store.load("k1"))
    }

    /** A damaged stored copy is a key problem for the user, not a JSON or network error, and nothing is half-loaded. */
    @Test
    fun aCorruptStoredKeyIsKeyUnavailable() = runBlocking<Unit> {
        assumeTrue("imported key not pushed", keyFile.canRead())
        assertTrue(store.import("k3", keyFile.readText().toCharArray(), "spikepass", rememberPassphrase = true) is ImportCheck.Ready)
        File(dir, "imported-k3").writeBytes(byteArrayOf(1, 12) + ByteArray(40) { 7 })
        try { store.load("k3"); fail("a corrupt key loaded") }
        catch (e: ConnectFailure.KeyUnavailable) { assertEquals(io.github.tuthan.paddock.ports.DownReason.KeyUnavailable, e.reason) }
    }

    @Test
    fun withoutRememberingTheCallerSuppliesThePassphrase() = runBlocking<Unit> {
        assumeTrue("imported key not pushed", keyFile.canRead())
        store.import("k2", keyFile.readText().toCharArray(), "spikepass", rememberPassphrase = false)
        assertFalse(store.info("k2")!!.passphraseRemembered)
        assertNull(store.load("k2")!!.passphrase)
        assertEquals("spikepass", store.load("k2", "spikepass")!!.passphrase)
    }
}
