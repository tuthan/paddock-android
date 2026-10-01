package io.github.tuthan.paddock.ssh

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.ports.SecretCorrupt
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeystoreSecretStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "secrets-test-${UUID.randomUUID()}")
    private val store = KeystoreSecretStore(dir, "paddock-secret-wrap-test-${UUID.randomUUID()}")

    @After
    fun clean() { dir.deleteRecursively(); runCatching { store.destroyKey() } }

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun roundTripsSecretsIncludingEmptyAndLarge() = runBlocking<Unit> {
        val large = ByteArray(64 * 1024) { (it * 31).toByte() }
        store.put("a", bytes("hello")); store.put("empty", ByteArray(0)); store.put("large", large)
        assertArrayEquals(bytes("hello"), store.get("a"))
        assertArrayEquals(ByteArray(0), store.get("empty"))
        assertArrayEquals(large, store.get("large"))
        assertEquals(setOf("a", "empty", "large"), store.names())
    }

    @Test
    fun plaintextNeverReachesDisk() = runBlocking<Unit> {
        val marker = "-----BEGIN OPENSSH PRIVATE KEY----- SECRET-MARKER-9f3a"
        store.put("key", bytes(marker))
        val all = dir.listFiles()!!.map { it.readBytes() }
        assertTrue(all.isNotEmpty())
        all.forEach { f ->
            assertFalse("plaintext on disk", String(f, Charsets.ISO_8859_1).contains("SECRET-MARKER"))
            assertFalse("plaintext on disk", String(f, Charsets.ISO_8859_1).contains("BEGIN OPENSSH"))
        }
        assertFalse("temp file left behind", dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun everyWriteUsesAFreshIv() = runBlocking<Unit> {
        store.put("a", bytes("same")); val first = File(dir, "a").readBytes()
        store.put("a", bytes("same")); val second = File(dir, "a").readBytes()
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun aFlippedBitIsDetectedNotReturned() = runBlocking<Unit> {
        store.put("a", bytes("value"))
        val f = File(dir, "a"); val d = f.readBytes()
        for (index in listOf(0, 1, 2, 14, d.size - 1)) {
            val bad = d.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            f.writeBytes(bad)
            try { store.get("a"); fail("tampered byte $index went unnoticed") } catch (_: SecretCorrupt) { }
        }
    }

    @Test
    fun aFileMovedUnderAnotherNameFailsAuthentication() = runBlocking<Unit> {
        store.put("one", bytes("1")); store.put("two", bytes("2"))
        File(dir, "one").copyTo(File(dir, "two"), overwrite = true)
        try { store.get("two"); fail("swapped file accepted") } catch (_: SecretCorrupt) { }
    }

    @Test
    fun truncatedFilesAreCorruptNotMissing() = runBlocking<Unit> {
        store.put("a", bytes("value"))
        File(dir, "a").writeBytes(byteArrayOf(1, 12, 0))
        try { store.get("a"); fail("short file accepted") } catch (_: SecretCorrupt) { }
    }

    @Test
    fun missingIsNullAndDeleteIsIdempotent() = runBlocking<Unit> {
        assertNull(store.get("nothing"))
        store.put("a", bytes("x")); store.delete("a"); store.delete("a")
        assertNull(store.get("a")); assertEquals(emptySet<String>(), store.names())
    }

    @Test
    fun hostileNamesAreRejectedBeforeTouchingTheFilesystem() = runBlocking<Unit> {
        listOf("", "../x", "a/b", "A", "a b", "x".repeat(65), "..", ".", ".hidden", "a.tmp", "a\u0000b", "naïve").forEach { name ->
            try { store.put(name, bytes("x")); fail("accepted '$name'") } catch (_: IllegalArgumentException) { }
            try { store.get(name); fail("accepted '$name'") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(dir.exists() && dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun destroyingTheKeyMakesStoredSecretsUnreadable() = runBlocking<Unit> {
        store.put("a", bytes("x")); store.destroyKey()
        // A new wrapping key is generated on demand, so the old ciphertext no longer authenticates.
        try { store.get("a"); fail("old ciphertext still readable") } catch (_: SecretCorrupt) { }
    }
}
