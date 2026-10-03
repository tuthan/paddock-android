package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.ssh.AuthorizedKeyTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-11.2 and AC-11.3: the copy command run in a real `sh` against a temporary HOME. Run twice it leaves one copy of the key,
 * modes 700 and 600, other lines untouched (also when the file had no trailing newline), and a hostile comment or another
 * key type produces no command at all.
 */
class AuthorizeCommandTest {
    private val line = AuthorizedKeyTest.p256Line()
    private val other = AuthorizedKeyTest.p256Line("laptop@home")
    private val command = assertNotNull(AuthorizeCommand.forText(line))

    private fun home(): File = Files.createTempDirectory("hm e").toFile() // a space in HOME: nothing may split on it
    private fun ssh(h: File) = File(h, ".ssh")
    private fun keys(h: File) = File(ssh(h), "authorized_keys")
    private fun mode(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    private fun run(h: File, cmd: String = command, shell: List<String> = listOf("sh", "-c")): Pair<Int, String> {
        val pb = ProcessBuilder(shell + cmd).redirectErrorStream(true)
        pb.environment().apply { put("HOME", h.path); put("PATH", "/usr/bin:/bin"); remove("ENV"); remove("BASH_ENV") }
        pb.directory(h)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the command hung")
        return p.exitValue() to out
    }

    private fun shells() = listOf(listOf("sh", "-c"), listOf("bash", "--posix", "-c"))

    @Test fun onAFreshHomeItCreatesTheDirectoryAndFileWithTheRightModesAndOneLine() {
        for (shell in shells()) {
            val h = home()
            assertEquals(0 to "", run(h, shell = shell))
            assertEquals("rwx------", mode(ssh(h)))
            assertEquals("rw-------", mode(keys(h)))
            assertEquals(line + "\n", keys(h).readText())
        }
    }

    @Test fun runTwiceItLeavesExactlyOneCopyAndTheSameBytes() {
        for (shell in shells()) {
            val h = home()
            run(h, shell = shell); val first = keys(h).readBytes()
            assertEquals(0, run(h, shell = shell).first)
            assertTrue(first.contentEquals(keys(h).readBytes()), "the second run changed the file")
            assertEquals(1, keys(h).readLines().count { it == line })
            assertEquals("rwx------", mode(ssh(h))); assertEquals("rw-------", mode(keys(h)))
        }
    }

    @Test fun otherLinesAreUntouchedWhetherOrNotTheFileEndedWithANewline() {
        val existing = listOf("# my keys", other, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl old@box")
        for (endsWithNewline in listOf(true, false)) for (shell in shells()) {
            val h = home(); ssh(h).mkdirs()
            val before = existing.joinToString("\n") + if (endsWithNewline) "\n" else ""
            keys(h).writeText(before)
            assertEquals(0 to "", run(h, shell = shell))
            assertEquals(before + (if (endsWithNewline) "" else "\n") + line + "\n", keys(h).readText(), "newline=$endsWithNewline")
            run(h, shell = shell)
            assertEquals(1, keys(h).readLines().count { it == line }, "idempotent with newline=$endsWithNewline")
            assertEquals(existing, keys(h).readLines().take(3), "the other lines are as they were")
        }
    }

    @Test fun anEmptyFileGetsNoBlankLineAndTheKeyAlreadyThereIsNotAddedAgain() {
        val h = home(); ssh(h).mkdirs(); keys(h).writeText("")
        run(h); assertEquals(line + "\n", keys(h).readText())
        val h2 = home(); ssh(h2).mkdirs(); keys(h2).writeText("$other\n$line")   // present, no trailing newline
        run(h2); assertEquals("$other\n$line", keys(h2).readText(), "already authorized: nothing is changed, not even a newline")
    }

    @Test fun anExistingLooseDirectoryAndFileAreTightened() {
        val h = home(); ssh(h).mkdirs()
        Files.setPosixFilePermissions(ssh(h).toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        keys(h).writeText("$other\n"); Files.setPosixFilePermissions(keys(h).toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))
        run(h)
        assertEquals("rwx------", mode(ssh(h))); assertEquals("rw-------", mode(keys(h)))
        assertEquals("$other\n$line\n", keys(h).readText())
    }

    @Test fun onlyAnExactLineCountsAsAlreadyThere() {
        // The same key behind an option prefix is another line: the command adds the plain one, it never edits or trusts the other.
        val restricted = "command=\"/bin/true\" $line"
        val h = home(); ssh(h).mkdirs(); keys(h).writeText("$restricted\n")
        run(h)
        assertEquals("$restricted\n$line\n", keys(h).readText())
    }

    @Test fun theCommandIsOneLineWithTheKeyOnceInsideSingleQuotesAndNothingElseTheShellCouldExpand() {
        assertEquals(false, '\n' in command)
        assertEquals(2, command.split("'$line'").size - 1, "the key line is written twice, both quoted: the check and the append")
        val outside = command.replace("'$line'", "")
        assertEquals(false, "'" in outside.replace("'%s\\n'", ""), "no other quotes")
    }

    @Test fun noCommandExistsForAHostileCommentOrAnotherKeyType() {
        val hostile = listOf(
            "$line x';touch pwned;'", "$line x;touch pwned", "$line \$(touch pwned)", "$line `touch pwned`", "$line x&touch", "$line a|b", "$line a>b", "$line a\nb",
            "command=\"touch pwned\" $line", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl x", "", "-----BEGIN OPENSSH PRIVATE KEY-----",
        )
        for (h in hostile) assertNull(AuthorizeCommand.forText(h), "no command for: ${h.take(30)}")
        assertNull(AuthorizeCommand.forText(null))
    }

    @Test fun aHomeWithAShellMetacharacterInItsNameIsStillOneWord() {
        val h = Files.createTempDirectory("hm\$x;y").toFile()
        assertEquals(0 to "", run(h)); assertEquals(line + "\n", keys(h).readText())
        assertEquals(false, File(h, "y").exists() || File(h.parentFile, "y").exists(), "nothing was split on the semicolon")
    }
}
