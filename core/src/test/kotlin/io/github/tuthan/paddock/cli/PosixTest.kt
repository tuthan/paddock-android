package io.github.tuthan.paddock.cli

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PosixTest {
    private val hostile = listOf(
        "plain", "with space", "it's", "''", "'", "a'b'c", "\$(reboot)", "`reboot`", "a;b", "a && b", "a | b",
        "*", "?", "[a-z]", "~", "\$HOME", "\${IFS}", "\\", "\\'", "\"", "a\"b", "#comment", "!history", "-n", "--", "=",
        "tab\there", "é日本語", "😀", "", " ", "  leading", "trailing  ", "%s", "-e\\x41",
    )

    @Test
    fun emptyAndSimpleArguments() {
        assertEquals("''", posixQuote(""))
        assertEquals("herdr", posixQuote("herdr"))
        assertEquals("w1:p1", posixQuote("w1:p1"))
        assertEquals("'a b'", posixQuote("a b"))
        assertEquals("'it'\\''s'", posixQuote("it's"))
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

    /** The strongest check: a real POSIX shell must hand each hostile argument back unchanged. */
    @Test
    fun aRealShellRoundTripsEveryHostileArgument() {
        val sh = java.io.File("/bin/sh")
        if (!sh.canExecute()) return
        val command = argvToCommand(listOf("printf", "%s\\0") + hostile)
        val proc = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(false).start()
        val out = proc.inputStream.readBytes()
        assertTrue(proc.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, proc.exitValue(), "shell exit")
        val back = out.toString(Charsets.UTF_8).split('\u0000').dropLast(1)
        assertEquals(hostile, back)
    }
}
