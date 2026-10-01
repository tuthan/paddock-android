package io.github.tuthan.paddock.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PosixTest {
    /** Everything a login shell might treat specially, minus what is rejected outright (`'`, `\`, NUL, CR, LF). */
    private val hostile = listOf(
        "plain", "with space", "\$(reboot)", "`reboot`", "a;b", "a && b", "a | b", "*", "?", "[a-z]", "~", "~root", "\$HOME",
        "\${IFS}", "\"", "a\"b", "#comment", "!history", "-n", "--", "=", "=ls", "a=b", "{a,b}", "%self", "%s", "^caret", "a&b",
        "(sub)", "<in", ">out", "@at", "+plus", "w1:p1", "a,b", "tab\there", "é日本語", "😀", "", " ", "  leading", "trailing  ",
    )

    /** A field separator that no hostile argument contains, so printf's format needs no backslash. */
    private val sep = '\u001f'

    @Test
    fun bareWordsAreOnlyLettersDigitsAndUnderscoreDotSlashDash() {
        assertEquals("''", posixQuote(""))
        assertEquals("herdr", posixQuote("herdr"))
        assertEquals("/home/jdoe/.local/bin/herdr", posixQuote("/home/jdoe/.local/bin/herdr"))
        assertEquals("-n", posixQuote("-n"))
        assertEquals("a_b.c-d", posixQuote("a_b.c-d"))
        // Formerly bare, now quoted: each is special to some login shell.
        assertEquals("'w1:p1'", posixQuote("w1:p1"))
        assertEquals("'=ls'", posixQuote("=ls"))
        assertEquals("'a=b'", posixQuote("a=b"))
        assertEquals("'a@b'", posixQuote("a@b"))
        assertEquals("'%s'", posixQuote("%s"))
        assertEquals("'a+b,c'", posixQuote("a+b,c"))
        assertEquals("'a b'", posixQuote("a b"))
    }

    @Test
    fun rejectsQuotesAndBackslashesThatNoSingleQuotingFormCarriesEverywhere() {
        listOf("it's", "'", "''", "a'b'c", "\\", "\\'", "a\\b", "-e\\x41", "%s\\n").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { posixQuote(bad) }
            assertFailsWith<IllegalArgumentException> { argvToCommand(listOf("herdr", bad)) }
        }
    }

    @Test
    fun rejectsNulAndLineBreaks() {
        listOf("a\u0000b", "a\nb", "a\rb", "\n").forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad.replace("\u0000", "NUL")) { posixQuote(bad) }
            assertFailsWith<IllegalArgumentException> { argvToCommand(listOf("herdr", bad)) }
        }
    }

    @Test
    fun rejectsEmptyArgv() {
        assertFailsWith<IllegalArgumentException> { argvToCommand(emptyList()) }
    }

    /** The relay installer's real `sh -c` scripts and paths go through the quoting rule (an argument it rejects would throw). */
    @Test
    fun theRelayInstallersScriptsAreQuotable() = kotlinx.coroutines.runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val session = object : io.github.tuthan.paddock.ports.SshSession {
            override val link = kotlinx.coroutines.flow.MutableStateFlow<io.github.tuthan.paddock.ports.LinkState>(io.github.tuthan.paddock.ports.LinkState.Up(0))
            override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: io.github.tuthan.paddock.ports.ExecLimits): io.github.tuthan.paddock.ports.ExecResult {
                sent += argvToCommand(argv)
                val out = if (argv.first() == "sha256sum") "${io.github.tuthan.paddock.relay.sha256Hex(SCRIPT)}  x" else "/home/jdoe"
                return io.github.tuthan.paddock.ports.ExecResult(0, out.toByteArray(), ByteArray(0), false, false, kotlin.time.Duration.ZERO)
            }
            override suspend fun openStream(argv: List<String>) = error("unused")
            override suspend fun close() = Unit
        }
        val installer = io.github.tuthan.paddock.relay.RelayInstaller(session, SCRIPT, io.github.tuthan.paddock.relay.sha256Hex(SCRIPT))
        installer.install(installer.homeDirectory())
        assertEquals(3, sent.size, sent.joinToString("\n")) // HOME, the install script, the re-hash
        assertTrue(sent.all { it.startsWith("sh -c '") || it.startsWith("sha256sum -- /home/jdoe/") }, sent.joinToString("\n"))
    }

    private companion object { val SCRIPT = "print('relay')\n".toByteArray() }

    /**
     * The strongest check: every login shell installed here (sshd runs `$SHELL -c <line>`) must hand each hostile argument
     * back unchanged. sh and bash at least; dash, zsh, fish and tcsh when present.
     */
    @Test
    fun everyInstalledLoginShellRoundTripsEveryHostileArgument() {
        val shells = listOf("/bin/sh", "/usr/bin/bash", "/bin/bash", "/usr/bin/dash", "/usr/bin/zsh", "/bin/zsh", "/usr/bin/fish", "/usr/bin/tcsh", "/bin/tcsh")
            .map(::File).filter { it.canExecute() }.distinctBy { it.canonicalPath }
        assertTrue(shells.isNotEmpty(), "no shell to test with")
        val command = argvToCommand(listOf("printf", "%s$sep") + hostile)
        for (shell in shells) {
            val proc = ProcessBuilder(shell.path, "-c", command).redirectErrorStream(false).start()
            val out = proc.inputStream.readBytes()
            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(proc.waitFor(10, TimeUnit.SECONDS), "${shell.path} hung")
            assertEquals(0, proc.exitValue(), "${shell.path} exit: $err")
            assertEquals(hostile, out.toString(Charsets.UTF_8).split(sep).dropLast(1), shell.path)
        }
    }
}
