package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The machine's hostname as Paddock keeps it (one DNS label, from `uname -n`), how it is read, and that it never costs a reconnect. */
class HostNameTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theFirstLabelIsKeptAndAnythingElseIsNotGuessed() {
        assertEquals("labbox", MachineHostName.label("labbox\n"))
        assertEquals("Jdoes-MacBook-Pro", MachineHostName.label("Jdoes-MacBook-Pro.local\n"), "a Mac says its .local name")
        assertEquals("box", MachineHostName.label("box.example.org"))
        assertEquals("DESKTOP-7Q2R", MachineHostName.label("DESKTOP-7Q2R\r\n"))
        assertEquals("devbox", MachineHostName.label("Welcome to the box\ndevbox\n"), "a banner before the answer does not hide it")
        assertEquals("a".repeat(63), MachineHostName.label("a".repeat(63)))
        for (w in listOf("", "  ", "localhost", "LOCALHOST.localdomain", "my box", "-box", "box-", "under_score", "a".repeat(64), ".hidden", "box‮")) {
            assertNull(MachineHostName.label(w), "'$w'")
        }
    }

    @Test fun theProbeAsksOneConstantCommandAndReadsItsAnswer() = runBlocking<Unit> {
        val s = FakeSession(onExec = { _, _ -> result(0, "labbox\n") })
        assertEquals("labbox", HostNameProbe.read(s))
        assertEquals(listOf("sh", "-c", HostNameProbe.COMMAND), s.execs.single().first)
    }

    @Test fun aMachineThatCannotAnswerIsNotKnownNeverAFailure() = runBlocking<Unit> {
        assertNull(HostNameProbe.read(FakeSession(onExec = { _, _ -> result(127, "", "uname: not found") })), "exit status")
        assertNull(HostNameProbe.read(FakeSession(onExec = { _, _ -> result(0, "") })), "silence")
        assertNull(HostNameProbe.read(FakeSession(onExec = { _, _ -> throw java.io.IOException("channel closed") })), "a channel that fails")
    }

    @Test fun aProfileHoldsOnlyALabelAndTheHostnameIsNoReasonToReconnect() {
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "u", detectedName = "two words") }
        assertFailsWith<IllegalArgumentException> { HostProfile("a", "A", "h", user = "u", detectedName = "box.example.org") }
        val p = HostProfile("a", "A", "192.168.42.86", user = "u")
        assertTrue(p.sameConnectionAs(p.copy(detectedName = "labbox")))
    }

    @Test fun anOldProfilesFileLoadsWithoutAHostnameAndANewOneKeepsIt() = runBlocking<Unit> {
        val f = File(tmp.root, "profiles.json")
        f.writeText("""{"version":1,"profiles":[{"id":"a","name":"A","host":"h","user":"u"}]}""")
        val store = FileHostProfileStore(f)
        assertNull(store.get("a")?.detectedName)
        assertFalse(store.get("a")!!.searchNetwork, "an old file has the network search off")
        store.update("a") { it.copy(detectedName = "labbox", searchNetwork = true) }
        assertEquals("labbox", FileHostProfileStore(f).get("a")?.detectedName)
        assertTrue(FileHostProfileStore(f).get("a")!!.searchNetwork)
    }

    @Test fun anOldProfilesFileLoadsWithTheDefaultDropFolderAndANewOneKeepsTheChoice() = runBlocking<Unit> {
        val f = File(tmp.root, "profiles-drop.json")
        f.writeText("""{"version":1,"profiles":[{"id":"a","name":"A","host":"h","user":"u"}]}""")
        val store = FileHostProfileStore(f)
        assertNull(store.get("a")!!.dropFolder)
        assertEquals("~/Paddock", store.get("a")!!.dropFolderValue)
        store.update("a") { it.copy(dropFolder = "~/Incoming") }
        assertEquals("~/Incoming", FileHostProfileStore(f).get("a")!!.dropFolder)
    }

    @Test fun aProfileHoldsOnlyAFolderTheMachineMayBeAskedAbout() {
        for (ok in listOf("~", "~/Paddock", "/srv/drop", "/home/jdoe/My Files")) HostProfile("a", "A", "h", user = "u", dropFolder = ok)
        for (bad in listOf("", "Paddock", "~jdoe/x", "../x", "/tmp/a\nb", "/tmp/a\u0000b", "/" + "x".repeat(HostProfile.MAX_DROP_FOLDER))) {
            assertFailsWith<IllegalArgumentException>(bad) { HostProfile("a", "A", "h", user = "u", dropFolder = bad) }
        }
    }
}
