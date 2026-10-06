package io.github.tuthan.paddock.cli

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.FakeSession.Companion.result
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The locator's two scripts run for real under `sh` against a temporary HOME, and `find` runs against a scripted session. */
class HerdrLocatorTest {
    private val tmp: File = Files.createTempDirectory("herdr-locator").toFile().also { it.deleteOnExit() }

    private fun exe(path: File, body: String = "#!/bin/sh\nexit 0\n", executable: Boolean = true): File {
        path.parentFile.mkdirs(); path.writeText(body); path.setExecutable(executable)
        return path
    }

    private fun sh(script: String, env: Map<String, String>): Pair<Int, String> {
        val pb = ProcessBuilder("sh", "-c", script)
        pb.environment().clear(); pb.environment().putAll(mapOf("PATH" to "/usr/bin:/bin") + env)
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return p.exitValue() to out
    }

    // ---- the absolute candidates -----------------------------------------------------------------------------------------------

    @Test fun theFirstExecutableCandidateWinsAndOneThatIsNotExecutableIsSkipped() {
        val script = HerdrLocator.script(listOf("\$HOME/a/herdr", "\$HOME/b/herdr", "\$HOME/c/herdr"))
        val env = mapOf("HOME" to tmp.path)
        assertEquals(1 to "", sh(script, env), "none installed")
        exe(File(tmp, "c/herdr")); exe(File(tmp, "b/herdr"))
        assertEquals(0 to "${tmp.path}/b/herdr", sh(script, env), "earlier in the list wins")
        exe(File(tmp, "a/herdr"), executable = false)
        assertEquals(0 to "${tmp.path}/b/herdr", sh(script, env), "a file that is not executable is not herdr")
        exe(File(tmp, "a/herdr"))
        assertEquals(0 to "${tmp.path}/a/herdr", sh(script, env))
    }

    @Test fun aHomeWithASpaceIsStillOneWord() {
        val home = File(tmp, "my home").also { it.mkdirs() }
        exe(File(home, ".local/bin/herdr"))
        assertEquals(0 to "${home.path}/.local/bin/herdr", sh(HerdrLocator.script(listOf("\$HOME/.local/bin/herdr")), mapOf("HOME" to home.path)))
    }

    @Test fun theDefaultListCoversHerdrsOwnInstallerAndHomebrewOnAppleSiliconIntelAndLinux() {
        val c = HerdrLocator.CANDIDATES
        for (want in listOf("\$HOME/.local/bin/herdr", "\$HOME/.cargo/bin/herdr", "/opt/homebrew/bin/herdr", "/usr/local/bin/herdr", "/home/linuxbrew/.linuxbrew/bin/herdr", "/usr/bin/herdr")) {
            assertTrue(want in c, "$want is looked for")
        }
        assertTrue(c.indexOf("/opt/homebrew/bin/herdr") < c.indexOf("/usr/bin/herdr"))
        assertEquals(HerdrLocator.script(c), HerdrLocator.SCRIPT)
    }

    @Test fun theNotFoundMessageNamesEveryFolderTheScriptLooksInAndTheLoginShell() {
        val message = HerdrLocator.notFoundMessage()
        for (dir in HerdrLocator.CANDIDATES.map { it.substringBeforeLast('/').replace("\$HOME", "~") }) assertTrue(dir in message, "$dir is named")
        assertTrue("/opt/homebrew/bin" in message)
        assertTrue("login shell" in message)
        assertFalse("\$HOME" in message, "a path the user can read, not a shell variable")
    }

    // ---- the login shell -------------------------------------------------------------------------------------------------------

    private fun loginShell(name: String, prints: String): File = exe(File(tmp, "shells/$name"), "#!/bin/sh\n$prints")

    @Test fun theLoginShellsAnswerIsUsedWhenItIsTheLastLineAndAnExecutablePath() {
        val herdr = exe(File(tmp, "brew/bin/herdr"))
        val zsh = loginShell("zsh", "echo 'Welcome back'\necho '${herdr.path}'\n")
        assertEquals(0 to herdr.path, sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to zsh.path)), "a banner before the answer is ignored")
        for (name in listOf("bash", "fish", "sh", "dash", "ksh")) {
            assertEquals(0 to herdr.path, sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to loginShell(name, "echo '${herdr.path}'\n").path)), name)
        }
    }

    @Test fun theLoginShellIsAskedForLoginAndCommandOnly() {
        val args = File(tmp, "args")
        val zsh = loginShell("zsh", "echo \"\$@\" > '${args.path}'\necho /nonexistent\n")
        sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to zsh.path))
        assertEquals("-lc command -v herdr", args.readText().trim())
    }

    @Test fun anAliasAFunctionARelativePathOrAFileThatIsNotExecutableIsNotAnAnswer() {
        val plain = exe(File(tmp, "brew/bin/herdr-plain"), executable = false)
        for ((why, prints) in listOf(
            "an alias" to "echo 'herdr: aliased to /opt/x/herdr'\n",
            "a function" to "echo herdr\n",
            "a relative path" to "echo bin/herdr\n",
            "a path that is not executable" to "echo '${plain.path}'\n",
            "nothing" to "true\n",
            "a failing shell" to "exit 3\n",
        )) {
            assertEquals(1 to "", sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to loginShell("zsh", prints).path)), why)
        }
    }

    @Test fun onlyShellsWithStandardLoginAndCommandVAreRunAndTheShellMustBeAnAbsolutePath() {
        val marker = File(tmp, "nu-ran")
        val nu = loginShell("nu", "touch '${marker.path}'\necho /usr/bin/true\n")
        assertEquals(1 to "", sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to nu.path)))
        assertFalse(marker.exists(), "a shell outside the list is never started")
        assertEquals(1 to "", sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to "zsh")), "a relative SHELL")
        assertEquals(1 to "", sh(HerdrLocator.LOGIN_SHELL_SCRIPT, mapOf("SHELL" to "")), "an empty SHELL")
    }

    // ---- find ------------------------------------------------------------------------------------------------------------------

    @Test fun aHomebrewHerdrIsFoundByTheLoginShellWhenNoFixedFolderHasIt() = runBlocking {
        val s = FakeSession(onExec = { argv, _ -> if (argv[2] == HerdrLocator.SCRIPT) result(1) else result(0, "/opt/homebrew/bin/herdr") })
        assertEquals("/opt/homebrew/bin/herdr", HerdrLocator.find(s))
        assertEquals(listOf(HerdrLocator.SCRIPT, HerdrLocator.LOGIN_SHELL_SCRIPT), s.execs.map { it.first[2] }, "the fixed folders first, then the login shell")
    }

    @Test fun aFixedFolderAnswerNeverStartsTheLoginShell() = runBlocking {
        val s = FakeSession(onExec = { _, _ -> result(0, "/usr/bin/herdr") })
        assertEquals("/usr/bin/herdr", HerdrLocator.find(s))
        assertEquals(1, s.execs.size)
    }

    @Test fun nothingAnywhereIsNullAndOddAnswersAreNotPaths() = runBlocking {
        assertNull(HerdrLocator.find(FakeSession(onExec = { _, _ -> result(1) })))
        for (odd in listOf("herdr", "bin/herdr", "/a/herdr\n/b/herdr", "")) {
            assertNull(HerdrLocator.find(FakeSession(onExec = { _, _ -> result(0, odd) })), "'$odd'")
        }
    }

    @Test fun aLoginShellThatFailsToRunIsNotFoundAndACancellationStillCancels() = runBlocking {
        val broken = FakeSession(onExec = { argv, _ -> if (argv[2] == HerdrLocator.SCRIPT) result(1) else throw IOException("link dropped") })
        assertNull(HerdrLocator.find(broken))
        val cancelled = FakeSession(onExec = { argv, _ -> if (argv[2] == HerdrLocator.SCRIPT) result(1) else throw CancellationException("stopped") })
        assertFailsWith<CancellationException> { HerdrLocator.find(cancelled) }
        Unit
    }

    @Test fun aFailureOfTheFixedFolderCheckItselfIsStillTheCallersToHandle() {
        val s = FakeSession(onExec = { _, _ -> throw IOException("link dropped") })
        assertFailsWith<IOException> { runBlocking { HerdrLocator.find(s) } }
    }
}
