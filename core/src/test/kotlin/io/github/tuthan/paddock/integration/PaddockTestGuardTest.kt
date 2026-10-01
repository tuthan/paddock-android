package io.github.tuthan.paddock.integration

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The integration-test guard on temporary directories and symlinks; no herdr involved. */
class PaddockTestGuardTest {
    private val root: File = createTempDirectory("paddock-guard").toFile()

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    private fun file(path: String): File = File(root, path).apply { parentFile.mkdirs(); writeText("") }
    private fun link(path: String, target: File): File =
        File(root, path).also { it.parentFile.mkdirs(); Files.createSymbolicLink(it.toPath(), target.toPath()) }

    @Test
    fun aRealDisposableSessionSocketPasses() {
        assertEquals("paddock-test", PaddockTest.guard(file("sessions/paddock-test/herdr.sock").path))
        assertEquals("paddock-test-2b", PaddockTest.guard(file("sessions/paddock-test-2b/herdr.sock").path))
    }

    @Test
    fun aSymlinkAboveTheSessionsDirectoryStillPasses() {
        file("real/sessions/paddock-test/herdr.sock")
        link("config", File(root, "real"))
        assertEquals("paddock-test", PaddockTest.guard(File(root, "config/sessions/paddock-test/herdr.sock").path))
    }

    @Test
    fun aPaddockTestPathLinkedToTheDefaultSocketIsRefused() {
        val default = file("herdr/herdr.sock")
        val socket = link("x/sessions/paddock-test/herdr.sock", default)
        assertFailsWith<IllegalArgumentException> { PaddockTest.guard(socket.path) }
    }

    @Test
    fun aPaddockTestPathLinkedToAnotherNamedSessionIsRefused() {
        val other = file("herdr/sessions/default/herdr.sock")
        val socket = link("x/sessions/paddock-test/herdr.sock", other)
        assertFailsWith<IllegalArgumentException> { PaddockTest.guard(socket.path) }
    }

    @Test
    fun aLinkedSessionDirectoryIsRefused() {
        file("herdr/herdr.sock")
        link("x/sessions/paddock-test", File(root, "herdr"))
        assertFailsWith<IllegalArgumentException> { PaddockTest.guard(File(root, "x/sessions/paddock-test/herdr.sock").path) }
    }

    @Test
    fun aLinkToADifferentDisposableSessionIsRefused() {
        val other = file("sessions/paddock-test-other/herdr.sock")
        val socket = link("y/sessions/paddock-test/herdr.sock", other)
        assertFailsWith<IllegalArgumentException> { PaddockTest.guard(socket.path) }
    }

    @Test
    fun lexicallyForeignOrMissingPathsAreRefused() {
        listOf(
            file("sessions/default/herdr.sock").path,
            file("sessions/paddock-test-Upper/herdr.sock").path,
            file("sessions/paddock-test-a.b/herdr.sock").path,
            file("herdr.sock").path,
            File(root, "sessions/paddock-test-missing/herdr.sock").path,
        ).forEach { assertFailsWith<IllegalArgumentException>(it) { PaddockTest.guard(it) } }
    }
}
