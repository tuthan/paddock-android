package io.github.tuthan.paddock.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SEC-6: a file from the machine is cached under a name of Paddock's own, and the path stays inside its folder whatever the machine called it. */
class CachedNameTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = TransferCache(tmp.root)

    private fun fetched(machineName: String): File {
        val c = cache()
        val name = FileNames.cachedName(machineName)
        val dir = File(tmp.root, "transfers/m1/id1").canonicalFile
        val file = c.fetchFile("m1", "id1", name)
        assertEquals("the file is directly inside its folder: $machineName", dir, file.canonicalFile.parentFile)
        assertTrue("the name is a plain one: $name", name.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" })
        assertFalse("no leading dot: $name", name.startsWith("."))
        assertTrue("at most 100 characters", name.length <= 100)
        return file
    }

    @Test fun aParentTraversalNameStaysInside() { assertEquals("x", fetched("../x").name); assertEquals("passwd", fetched("../../../etc/passwd").name) }
    @Test fun aHiddenNameLosesItsDot() = assertEquals("hidden", fetched(".hidden").name)
    @Test fun aBidirectionalOverrideNameIsReducedToTheAllowlist() {
        val name = fetched("report‮gpj.exe").name
        assertFalse(name.any { it == '‮' })
        assertTrue(name.startsWith("report"))
    }
    @Test fun aThreeHundredCharacterNameIsCutKeepingItsExtension() {
        val name = fetched("a".repeat(300) + ".png").name
        assertTrue(name.length <= 100)
        assertTrue(name.endsWith(".png"))
    }
    @Test fun anEmptyOrAllDroppedNameBecomesFile() { assertEquals("file", fetched("").name); assertEquals("file", fetched("‮​").name) }
    @Test fun aWindowsStyleSeparatorIsNotAPathHere() = assertEquals("x.txt", fetched("a\\b\\x.txt").name)

    @Test fun theCacheRefusesAnUnreducedNameItself() {
        val c = cache()
        for (bad in listOf("", ".", "..", "../x", "a/b", "a\\b", "x\u0000y", ".hidden/..")) {
            assertThrows("name: $bad", IllegalArgumentException::class.java) { c.fetchFile("m1", "id1", bad) }
        }
    }

    @Test fun theCacheRefusesAnIdThatIsNotOurs() {
        val c = cache()
        assertThrows(IllegalArgumentException::class.java) { c.fetchFile("../m1", "id1", "f") }
        assertThrows(IllegalArgumentException::class.java) { c.fetchFile("m1", "../id1", "f") }
        assertThrows(IllegalArgumentException::class.java) { c.fetchFile("m1", "", "f") }
    }

    @Test fun aLinkStandingWhereTheFileGoesIsRefused() {
        val c = cache()
        val outside = tmp.newFile("outside.txt")
        val dir = File(tmp.root, "transfers/m1/id1").also { it.mkdirs() }
        Files.createSymbolicLink(File(dir, "report.txt").toPath(), outside.toPath())
        assertThrows("the link leads out of the folder", IllegalArgumentException::class.java) { c.fetchFile("m1", "id1", "report.txt") }
    }
}
