package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.integration.LocalProcessSession
import io.github.tuthan.paddock.relay.RelayClient
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The script `RelaySagaHost.resolveFolder` sends, run by a real `/bin/sh` against real folders: it has to agree with herdr about what a path is,
 * because herdr itself accepts nothing and refuses nothing (a missing folder, a file and a relative path all open in its default folder).
 */
class ResolveFolderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun host(home: File): RelaySagaHost {
        val session = LocalProcessSession(mapOf("HOME" to home.absolutePath))
        return RelaySagaHost(RelayClient(session, "/none", "/none.sock"), session, HerdrCli("/usr/bin/herdr", "s"))
    }

    private fun ask(h: RelaySagaHost, path: String) = runBlocking { h.resolveFolder(path) }

    @Test fun `a folder resolves to its real path and anything else to null`() {
        val home = tmp.newFolder("home").canonicalFile
        val work = File(home, "work/a b").also { it.mkdirs() }
        val odd = File(home, "it's \\ here").also { it.mkdirs() }
        File(home, "file.txt").writeText("x")
        val link = File(tmp.root, "link").also { Files.createSymbolicLink(it.toPath(), work.toPath()) }
        val h = host(home)

        assertEquals(work.path, ask(h, work.path))
        assertEquals(work.path, ask(h, work.path + "/"))
        assertEquals(work.path, ask(h, "${work.parent}/../work/a b"))
        assertEquals(work.path, ask(h, link.path), "a symlink is followed, as herdr reports the pane's real folder")
        // Free text goes on stdin, so the characters an argument could not carry are fine in a folder's name.
        assertEquals(odd.path, ask(h, odd.path))
        assertEquals(home.path, ask(h, "~"))
        assertEquals(File(home, "work").path, ask(h, "~/work"))
        assertEquals(work.path, ask(h, "~/work/a b"))

        assertNull(ask(h, File(home, "missing").path))
        assertNull(ask(h, File(home, "file.txt").path), "a file is not a folder")
        assertNull(ask(h, "work"), "a relative path means nothing over SSH")
        assertNull(ask(h, "~/missing"))
        assertNull(ask(h, "~nobody/x"))
    }
}
