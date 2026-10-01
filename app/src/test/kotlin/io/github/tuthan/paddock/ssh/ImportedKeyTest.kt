package io.github.tuthan.paddock.ssh

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Keys are generated with the host's ssh-keygen at test time, so no private key material is committed. */
class ImportedKeyTest {
    private val dir = createTempDirectory("imported-key").toFile().also { it.deleteOnExit() }

    private fun keygen(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder("/usr/bin/ssh-keygen", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(30, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun generate(type: String, bits: String?, passphrase: String, name: String = "$type-${passphrase.length}"): File {
        assumeTrue("ssh-keygen not available", File("/usr/bin/ssh-keygen").canExecute())
        val f = File(dir, name)
        val args = mutableListOf("-q", "-t", type, "-N", passphrase, "-f", f.path, "-C", "paddock-test")
        bits?.let { args += listOf("-b", it) }
        assertEquals(0, keygen(*args.toTypedArray()).first)
        return f
    }

    private fun fingerprintOf(key: File): String = keygen("-l", "-f", key.path + ".pub").second.split(" ")[1]
    private fun pem(f: File) = f.readText().toCharArray()

    @Test
    fun unencryptedEd25519IsReadyAndMatchesSshKeygen() {
        val f = generate("ed25519", null, "")
        val r = ImportedKey.check(pem(f), null) as ImportCheck.Ready
        assertEquals("ssh-ed25519", r.keyType); assertEquals(false, r.encrypted)
        assertEquals(fingerprintOf(f), r.fingerprint)
    }

    @Test
    fun passphraseProtectedEd25519NeedsThenAcceptsThePassphrase() {
        val f = generate("ed25519", null, "correct horse")
        assertEquals(ImportCheck.NeedsPassphrase, ImportedKey.check(pem(f), null))
        assertEquals(ImportCheck.NeedsPassphrase, ImportedKey.check(pem(f), ""))
        val r = ImportedKey.check(pem(f), "correct horse") as ImportCheck.Ready
        assertTrue(r.encrypted); assertEquals(fingerprintOf(f), r.fingerprint)
    }

    @Test
    fun aWrongPassphraseIsClassifiedNotCrashed() {
        val f = generate("ed25519", null, "right")
        assertEquals(ImportCheck.WrongPassphrase, ImportedKey.check(pem(f), "wrong"))
    }

    @Test
    fun ecdsaP256AndRsaAreReadyWithMatchingFingerprints() {
        val e = generate("ecdsa", "256", "pw1")
        (ImportedKey.check(pem(e), "pw1") as ImportCheck.Ready).let { assertEquals("ecdsa-sha2-nistp256", it.keyType); assertEquals(fingerprintOf(e), it.fingerprint) }
        val r = generate("rsa", "3072", "")
        (ImportedKey.check(pem(r), null) as ImportCheck.Ready).let { assertEquals("ssh-rsa", it.keyType); assertEquals(fingerprintOf(r), it.fingerprint) }
    }

    @Test
    fun garbageTruncatedAndPublicOnlyInputsAreNotKeysAndNeverThrow() {
        val f = generate("ed25519", null, "")
        val text = f.readText()
        val cases = listOf(
            "", "hello", "-----BEGIN OPENSSH PRIVATE KEY-----\n", text.substring(0, text.length / 2), text.replace("A", "!"),
            File(f.path + ".pub").readText(), "\u0000\u0001\u0002", "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n",
        )
        cases.forEach { c ->
            val r = ImportedKey.check(c.toCharArray(), null)
            assertTrue("'${c.take(20)}' -> $r", r !is ImportCheck.Ready)
        }
    }

    @Test
    fun keyPairThrowsWithTheClassificationWhenUnusable() {
        val f = generate("ed25519", null, "pw")
        val e = runCatching { ImportedKey.keyPair(pem(f), "nope") }.exceptionOrNull() as InvalidImportedKey
        assertEquals(ImportCheck.WrongPassphrase, e.check)
        // And with the right passphrase the pair decodes and signs-capable key material is present.
        assertTrue(ImportedKey.keyPair(pem(f), "pw").private != null)
    }
}
