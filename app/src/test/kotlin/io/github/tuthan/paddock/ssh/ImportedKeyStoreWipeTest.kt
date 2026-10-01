package io.github.tuthan.paddock.ssh

import io.github.tuthan.paddock.ports.SecretStore
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The key text goes to and from the secret store without a String copy, and every buffer handed over is wiped. */
class ImportedKeyStoreWipeTest {
    /** Keeps a copy for get(), and the very arrays it was handed and returned so the test can look at them afterwards. */
    private class Recording : SecretStore {
        val stored = mutableMapOf<String, ByteArray>()
        val handed = mutableMapOf<String, ByteArray>()
        val returned = mutableListOf<ByteArray>()
        override suspend fun put(name: String, secret: ByteArray) { handed[name] = secret; stored[name] = secret.copyOf() }
        override suspend fun get(name: String): ByteArray? = stored[name]?.copyOf()?.also { returned += it }
        override suspend fun delete(name: String) { stored.remove(name) }
        override suspend fun names(): Set<String> = stored.keys
    }

    private fun ed25519(passphrase: String): CharArray {
        assumeTrue("ssh-keygen not available", File("/usr/bin/ssh-keygen").canExecute())
        val f = File(createTempDirectory("ikwipe").toFile().also { it.deleteOnExit() }, "k")
        val p = ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", passphrase, "-f", f.path, "-C", "paddock-test")
            .redirectErrorStream(true).start()
        p.waitFor(30, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue())
        return f.readText().toCharArray()
    }

    @Test
    fun theKeyTextRoundTripsExactlyAndNoBufferIsLeftHoldingIt() = runBlocking<Unit> {
        val pem = ed25519("wipepass")
        val secrets = Recording()
        val store = ImportedKeyStore(secrets)
        assertTrue(store.import("k1", pem.copyOf(), "wipepass", rememberPassphrase = true) is ImportCheck.Ready)
        assertTrue("the bytes handed to put() are wiped", secrets.handed.getValue("imported-k1").all { it == 0.toByte() })
        assertArrayEquals("stored as UTF-8", String(pem).toByteArray(Charsets.UTF_8), secrets.stored.getValue("imported-k1"))

        val auth = store.load("k1")!!
        assertArrayEquals(pem, auth.pem)
        assertEquals("wipepass", auth.passphrase)
        assertTrue("the decrypted bytes load() read are wiped", secrets.returned.all { b -> b.all { it == 0.toByte() } })
    }
}
