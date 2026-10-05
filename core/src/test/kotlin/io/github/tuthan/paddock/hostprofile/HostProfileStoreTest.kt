package io.github.tuthan.paddock.hostprofile

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // ---- update: one critical section, so a read-modify-write cannot be overwritten by a stale copy ----

    @Test fun updateAppliesTheTransformToTheStoredProfileAndReturnsWhatItWrote() = runBlocking<Unit> {
        for (s in listOf<HostProfileStore>(FileHostProfileStore(File(tmp.root, "u.json")), InMemoryHostProfileStore())) {
            s.put(profile())
            val written = s.update("laptop") { it.copy(name = "Renamed") }
            assertEquals("Renamed", written?.name)
            assertEquals("Renamed", s.get("laptop")?.name)
        }
    }

    @Test fun updateOfAMissingProfileDoesNotCallTheTransformOrCreateOne() = runBlocking<Unit> {
        for (s in listOf<HostProfileStore>(FileHostProfileStore(File(tmp.root, "m.json")), InMemoryHostProfileStore())) {
            var called = false
            assertNull(s.update("laptop") { called = true; it })
            assertFalse(called)
            assertNull(s.get("laptop"))
        }
    }

    @Test fun updateThatChangesNothingWritesNothing() = runBlocking<Unit> {
        val f = File(tmp.root, "n.json")
        val s = FileHostProfileStore(f)
        s.put(profile())
        // The same profile in a different layout: a rewrite would pretty-print it again.
        val compact = """{"version":1,"profiles":[{"id":"laptop","name":"Laptop","host":"10.0.0.2","port":22,"user":"jdoe"}]}"""
        f.writeText(compact)
        assertNull(s.update("laptop") { it.copy() })
        assertNull(s.update("laptop") { null })
        assertEquals(compact, f.readText())
    }

    @Test fun updateCannotRenameAProfileIntoAnotherId() = runBlocking<Unit> {
        for (s in listOf<HostProfileStore>(FileHostProfileStore(File(tmp.root, "r.json")), InMemoryHostProfileStore())) {
            s.put(profile())
            assertFailsWith<IllegalArgumentException> { s.update("laptop") { it.copy(id = "other") } }
            assertEquals(listOf("laptop"), s.list().map { it.id })
        }
    }

    @Test fun concurrentUpdatesAreNeverLostToAStaleCopy() = runBlocking<Unit> {
        for (s in listOf<HostProfileStore>(FileHostProfileStore(File(tmp.root, "c.json")), InMemoryHostProfileStore())) {
            s.put(profile().copy(port = 1))
            kotlinx.coroutines.coroutineScope {
                repeat(40) { launch(Dispatchers.Default) { s.update("laptop") { it.copy(port = it.port + 1) } } }
            }
            assertEquals(41, s.get("laptop")?.port, s::class.simpleName)
        }
    }

    @Test fun anUpdateAndAPutRacingEachOtherKeepEachOthersFields() = runBlocking<Unit> {
        val s = FileHostProfileStore(File(tmp.root, "x.json"))
        s.put(profile())
        // The wake read adds a relay while the user edits the name: whichever lands second must not undo the first.
        val relay = io.github.tuthan.paddock.wake.WakeTarget.unavailable("x", 1, io.github.tuthan.paddock.wake.WakeRelay("192.168.1.1"))
        kotlinx.coroutines.coroutineScope {
            launch(Dispatchers.Default) { s.update("laptop") { it.copy(wake = relay) } }
            launch(Dispatchers.Default) { s.update("laptop") { it.copy(name = "Edited") } }
        }
        assertEquals("Edited", s.get("laptop")?.name)
        assertEquals(relay, s.get("laptop")?.wake)
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
