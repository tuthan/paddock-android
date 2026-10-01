package io.github.tuthan.paddock.hostprofile

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HostProfileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun profile(id: String = "laptop", name: String = "Laptop", host: String = "10.0.0.2") =
        HostProfile(id, name, host, 22, "jdoe")

    @Test fun roundTripsThroughTheFileAndSortsByName() = runBlocking<Unit> {
        val file = File(tmp.root, "profiles/profiles.json")
        val a = FileHostProfileStore(file)
        a.put(profile("zed", "Zed box"))
        a.put(profile("alpha", "alpha box").copy(key = KeyKind.Imported, importedKeyId = "alpha"))
        val b = FileHostProfileStore(file)
        assertEquals(listOf("alpha", "zed"), b.list().map { it.id })
        assertEquals(KeyKind.Imported, b.get("alpha")?.key)
        assertEquals("alpha", b.get("alpha")?.importedKeyId)
        assertFalse(File(file.absolutePath + ".tmp").exists())
    }

    @Test fun putReplacesById() = runBlocking<Unit> {
        val s = FileHostProfileStore(File(tmp.root, "p.json"))
        s.put(profile(host = "10.0.0.2")); s.put(profile(host = "10.0.0.9"))
        assertEquals(listOf("10.0.0.9"), s.list().map { it.host })
    }

    @Test fun removeTouchesOnlyTheNamedProfile() = runBlocking<Unit> {
        val s = FileHostProfileStore(File(tmp.root, "p.json"))
        s.put(profile("a", "A")); s.put(profile("b", "B"))
        s.remove("a"); s.remove("missing")
        assertEquals(listOf("b"), s.list().map { it.id })
        assertNull(s.get("a"))
    }

    @Test fun anUnreadableFileThrowsInsteadOfLoadingEmpty() = runBlocking<Unit> {
        val f = File(tmp.root, "p.json").apply { writeText("{ nope") }
        assertFailsWith<HostProfilesCorrupt> { FileHostProfileStore(f).list() }
        // and a write must not paper over it: the bad file is still there for the user to look at
        assertFailsWith<HostProfilesCorrupt> { FileHostProfileStore(f).put(profile()) }
        assertEquals("{ nope", f.readText())
    }

    @Test fun anInvalidEntryOnDiskIsCorruptNotSkipped() = runBlocking<Unit> {
        val f = File(tmp.root, "p.json").apply {
            writeText("""{"version":1,"profiles":[{"id":"Bad ID","name":"x","host":"h","user":"u"}]}""")
        }
        assertFailsWith<HostProfilesCorrupt> { FileHostProfileStore(f).list() }
    }

    @Test fun validationRejectsWhatCouldNotBeAKeyOrATarget() {
        assertFailsWith<IllegalArgumentException> { profile(id = "Has Space") }
        assertFailsWith<IllegalArgumentException> { profile(id = "-lead") }
        assertFailsWith<IllegalArgumentException> { profile(name = "  ") }
        assertFailsWith<IllegalArgumentException> { profile(host = "a b") }
        assertFailsWith<IllegalArgumentException> { profile(host = "-oProxyCommand=x y") }
        assertFailsWith<IllegalArgumentException> { profile(host = "-oFoo") }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", port = 0, user = "u") }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", port = 70000, user = "u") }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "bad user") }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "u", key = KeyKind.Imported) }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "u", importedKeyId = "k") }
    }

    @Test fun theTargetCarriesTheProfileIdSoPinsAndProfilesAgree() {
        val t = profile("laptop", host = "fe80::1%wlan0").toTarget()
        assertEquals("laptop", t.profileId)
        assertEquals("fe80::1%wlan0:22", t.endpoint)
    }

    @Test fun theInMemoryStoreBehavesTheSame() = runBlocking<Unit> {
        val s = InMemoryHostProfileStore()
        s.put(profile("b", "B")); s.put(profile("a", "A")); s.remove("b")
        assertEquals(listOf("a"), s.list().map { it.id })
    }

    @Test fun aSessionNameSurvivesTheFileAndABadOneNeverConstructs() = runBlocking<Unit> {
        val f = java.nio.file.Files.createTempDirectory("hp").toFile().resolve("p.json")
        FileHostProfileStore(f).put(HostProfile("a", "A", "h", user = "u", session = "paddock-test"))
        assertEquals("paddock-test", FileHostProfileStore(f).get("a")?.session)
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "u", session = "x y") }
        // A file written before sessions existed has no field and loads as the default.
        f.writeText("""{"version":1,"profiles":[{"id":"a","name":"A","host":"h","user":"u"}]}""")
        assertEquals(null, FileHostProfileStore(f).get("a")?.session)
    }
}
