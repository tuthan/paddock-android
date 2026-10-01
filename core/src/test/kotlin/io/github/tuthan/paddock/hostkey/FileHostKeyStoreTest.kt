package io.github.tuthan.paddock.hostkey

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class FileHostKeyStoreTest {
    private val dir = createTempDirectory("pins").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "nested/host-keys.json")
    private val key = PresentedHostKey("ssh-ed25519", byteArrayOf(1, 2, 3, 0, 127, -128, -1))

    private fun pin(id: String, now: Long = 10) = PinnedHostKey(id, "h:22", key.algorithm, key.blob, key.fingerprint, now, now)

    @Test
    fun missingFileMeansNoPins() = runBlocking<Unit> { assertNull(FileHostKeyStore(file).find("p")) }

    @Test
    fun savedPinsSurviveANewInstanceBytesIntact() = runBlocking<Unit> {
        FileHostKeyStore(file).save(pin("p"))
        val back = FileHostKeyStore(file).find("p")!!
        assertContentEquals(key.blob, back.blob)
        assertEquals(key.fingerprint, back.fingerprint)
        assertEquals("h:22", back.endpoint)
    }

    @Test
    fun touchUpdatesOnlyLastSeen() = runBlocking<Unit> {
        val s = FileHostKeyStore(file); s.save(pin("p", 10)); s.touch("p", 99)
        val p = s.find("p")!!
        assertEquals(10, p.firstSeenMillis); assertEquals(99, p.lastSeenMillis)
        s.touch("absent", 5)
        assertNull(s.find("absent"))
    }

    @Test
    fun removeDeletesOnePin() = runBlocking<Unit> {
        val s = FileHostKeyStore(file); s.save(pin("a")); s.save(pin("b")); s.remove("a")
        assertNull(s.find("a")); assertEquals("b", s.find("b")!!.profileId)
    }

    @Test
    fun aCorruptFileIsAnErrorNeverEmpty() = runBlocking<Unit> {
        val s = FileHostKeyStore(file); s.save(pin("p"))
        file.writeText("{not json")
        assertFailsWith<HostKeyStoreCorrupt> { s.find("p") }
        file.writeText("""{"version":1,"pins":[{"profileId":"p","endpoint":"e","algorithm":"a","blob":"!!!notbase64","fingerprint":"f","firstSeenMillis":1,"lastSeenMillis":1}]}""")
        assertFailsWith<HostKeyStoreCorrupt> { s.find("p") }
        // and a save must not paper over it
        assertFailsWith<HostKeyStoreCorrupt> { s.save(pin("q")) }
    }

    @Test
    fun noTempFileRemainsAndConcurrentWritersLoseNothing() = runBlocking<Unit> {
        val s = FileHostKeyStore(file)
        (1..25).map { i -> async { s.save(pin("p$i")) } }.awaitAll()
        (1..25).forEach { assertEquals("p$it", s.find("p$it")?.profileId) }
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun worksUnderThePolicyEndToEnd() = runBlocking<Unit> {
        val policy = HostKeyPolicy(FileHostKeyStore(file)) { 1 }
        assertIs<HostKeyState.Unknown>(policy.evaluate("p", "e", key))
        policy.acceptUnknown("p", "e", key)
        assertIs<HostKeyState.Pinned>(HostKeyPolicy(FileHostKeyStore(file)) { 2 }.evaluate("p", "e", key))
        assertIs<HostKeyState.Changed>(policy.evaluate("p", "e", PresentedHostKey("ssh-ed25519", byteArrayOf(9))))
    }
}
