package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.SessionEntry
import io.github.tuthan.paddock.relay.FakeSession
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The saved layout of a stopped session: version 3 only, basenames only, "unavailable" for everything else. */
class SavedLayoutTest {
    private fun v3(vararg cwds: String?) = """{"version":3,"workspaces":[${cwds.joinToString(",") { """{"id":"w1","custom_name":"secret label","identity_cwd":${it?.let { c -> "\"$c\"" } ?: "null"},"tabs":[]}""" }}],"active":0}"""

    @Test fun aVersionThreeFileShowsBasenamesAndTheFileDate() {
        val r = SavedLayoutParser.parse(v3("/home/u/Projects/paddock", "/srv/www/site/"), 1_790_000_000_000)
        val layout = assertIs<SavedLayoutResult.Layout>(r).layout
        assertEquals(listOf("paddock", "site"), layout.workspaces)
        assertEquals(1_790_000_000_000, layout.savedAtMillis)
        assertEquals("paddock, site", layout.summary)
        // No path and no custom name leaves the parser.
        assertTrue(layout.workspaces.none { '/' in it || "secret" in it })
    }

    @Test fun theRealCapturedFileParses() {
        val text = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1/session-saved-v3.json").readText()
        val layout = assertIs<SavedLayoutResult.Layout>(SavedLayoutParser.parse(text, 1)).layout
        assertTrue(layout.workspaces.isNotEmpty())
    }

    @Test fun otherVersionsAndGarbageAreUnavailable() {
        for (text in listOf(
            """{"version":2,"workspaces":[]}""", """{"version":4,"workspaces":[{"identity_cwd":"/a"}]}""", """{"workspaces":[]}""", """{"version":"3","workspaces":[]}""",
            """{"version":3}""", """{"version":3,"workspaces":"x"}""", "[]", "null", "garbage", "", "{", """{"version":3,"workspaces":[""",
        )) assertIs<SavedLayoutResult.Unavailable>(SavedLayoutParser.parse(text, 1), "for <$text>")
    }

    @Test fun aWorkspaceWithoutAFolderIsShownAsSuch() {
        val layout = assertIs<SavedLayoutResult.Layout>(SavedLayoutParser.parse(v3(null), 1)).layout
        assertEquals(listOf("(no folder)"), layout.workspaces)
        assertEquals("no workspaces", SavedLayout(emptyList(), 1).summary)
    }

    @Test fun basenamesAreCleanAndBounded() {
        assertEquals("x", SavedLayoutParser.basename("/home/a/x"))
        assertEquals("x", SavedLayoutParser.basename("/home/a/x///"))
        assertEquals("/", SavedLayoutParser.basename("/"))
        assertNull(SavedLayoutParser.basename(""))
        assertNull(SavedLayoutParser.basename(null))
        assertEquals("ab", SavedLayoutParser.basename("/p/a\u0007b"))
        assertEquals(SavedLayoutParser.MAX_NAME, SavedLayoutParser.basename("/p/" + "x".repeat(500))!!.length)
    }

    @Test fun anOversizeFileOrListIsBounded() {
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutParser.parse(v3("/a") + " ".repeat(SavedLayoutParser.MAX_BYTES), 1))
        val many = assertIs<SavedLayoutResult.Layout>(SavedLayoutParser.parse(v3(*Array(200) { "/p/w$it" }), 1)).layout
        assertEquals(SavedLayoutParser.MAX_WORKSPACES, many.workspaces.size)
    }

    // ---- the reader --------------------------------------------------------------------------------------------------

    private val stopped = SessionEntry("paddock-test-2", false, false, "/home/u/.config/herdr/sessions/paddock-test-2", "/x/herdr.sock")

    @Test fun theReaderAsksForTheFixedFileInThePassedDirectoryAndDatesItByItsMtime() = runBlocking<Unit> {
        val session = FakeSession(onExec = { _, _ -> FakeSession.result(0, "1790993453\n" + v3("/tmp/paddock-test-ws")) })
        val r = SavedLayoutReader(session).read(stopped)
        val layout = assertIs<SavedLayoutResult.Layout>(r).layout
        assertEquals(1_790_993_453_000, layout.savedAtMillis)
        val argv = session.execs.single().first
        assertEquals(listOf("/bin/sh", "-c", SavedLayoutReader.SCRIPT, "paddock-layout", stopped.sessionDir), argv)
        // The directory is an argument, never part of the script text.
        assertTrue(stopped.sessionDir !in SavedLayoutReader.SCRIPT)
    }

    @Test fun aRunningSessionADirectoryThatIsNotAbsoluteAndAMissingFileAreUnavailable() = runBlocking<Unit> {
        val missing = FakeSession(onExec = { _, _ -> FakeSession.result(3) })
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(missing).read(stopped))
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(missing).read(stopped.copy(running = true)))
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(missing).read(stopped.copy(sessionDir = "relative/dir")))
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(missing).read(stopped.copy(sessionDir = "/a\nb")))
        assertEquals(1, missing.execs.size, "only the stopped, well-formed entry reached the host")
    }

    @Test fun aReadWithoutADateOrACutOffFileIsUnavailable() = runBlocking<Unit> {
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(FakeSession(onExec = { _, _ -> FakeSession.result(0, v3("/a")) })).read(stopped))
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(FakeSession(onExec = { _, _ -> FakeSession.result(0, "x\n" + v3("/a")) })).read(stopped))
        val cut = FakeSession(onExec = { _, _ -> io.github.tuthan.paddock.ports.ExecResult(0, "1\n{".toByteArray(), ByteArray(0), true, false, kotlin.time.Duration.ZERO) })
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(cut).read(stopped))
    }

    @Test fun aHostThatCannotBeAskedIsUnavailableNotAnError() = runBlocking<Unit> {
        val broken = FakeSession(onExec = { _, _ -> throw java.io.IOException("reset") })
        assertIs<SavedLayoutResult.Unavailable>(SavedLayoutReader(broken).read(stopped))
    }
}
