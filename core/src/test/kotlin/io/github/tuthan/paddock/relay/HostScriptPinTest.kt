package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.terminal.SshControlHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** host/SOURCE.json pins every script the app may install on a host; these tests are what keeps it honest. */
class HostScriptPinTest {
    private val host = File(System.getProperty("paddock.repoRoot"), "host")
    private val source = host.resolve("SOURCE.json").readText()
    private fun pinOf(name: String) = Regex("\"host/${Regex.escape(name)}\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").find(source)?.groupValues?.get(1)
    private val control = host.resolve("paddock-control.py").readBytes()
    private val home = "/home/u"
    private val destination = "$home/.local/share/paddock/paddock-control.py"

    @Test fun everyScriptInHostIsPinnedAndNothingElseIs() {
        val scripts = host.listFiles { f -> f.isFile && f.name != "SOURCE.json" }!!.map { it.name }.sorted()
        assertEquals(listOf("paddock-control.py", "paddock-relay.py"), scripts)
        for (name in scripts) assertEquals(pinOf(name), sha256Hex(host.resolve(name).readBytes()), "$name differs from its pin in host/SOURCE.json")
        assertEquals(scripts.size, Regex("\"host/[^\"]+\"\\s*:").findAll(source).count(), "a pin for a file that is not there")
    }

    @Test fun theControlScriptImportsOnlyWhatItNeedsAndRunsNoShell() {
        val text = control.toString(Charsets.UTF_8)
        val imports = text.lines().filter { it.startsWith("import ") }.flatMap { it.removePrefix("import ").split(",").map(String::trim) }.sorted()
        assertEquals(listOf("json", "os", "re", "select", "signal", "subprocess", "sys", "time"), imports)
        for (banned in listOf("shell=True", "os.system", "eval(", "exec(", "os.popen", "socket")) assertFalse(banned in text, "$banned in paddock-control.py")
        assertTrue(text.startsWith("#!/usr/bin/env python3"))
        assertEquals(1, Regex("subprocess\\.Popen\\(").findAll(text).count(), "the script starts exactly one process")
    }

    @Test fun theInstallerRefusesAScriptThatIsNotItsPin() {
        val pin = pinOf("paddock-control.py")!!
        RelayInstaller(FakeSession(), control, pin, fileName = "paddock-control.py")
        assertFailsWith<IllegalArgumentException> { RelayInstaller(FakeSession(), control + "#".toByteArray(), pin, fileName = "paddock-control.py") }
        for (bad in listOf("../x.py", "paddock-control.sh", "paddock-.py", "other.py", "paddock-control.py ", "paddock-Control.py"))
            assertFailsWith<IllegalArgumentException>(bad) { RelayInstaller(FakeSession(), control, pin, fileName = bad) }
    }

    private fun sha256sumSession(hashOnHost: String?) = FakeSession(onExec = { argv, _ ->
        when (argv.first()) {
            "sha256sum" -> if (hashOnHost == null) FakeSession.result(1, err = "No such file") else FakeSession.result(0, "$hashOnHost  ${argv.last()}\n")
            "sh" -> if (argv[2].startsWith("printf")) FakeSession.result(0, home) else FakeSession.result(0)
            else -> FakeSession.result(127)
        }
    })
    private fun helper(session: FakeSession) = SshControlHelper(RelayInstaller(session, control, pinOf("paddock-control.py")!!, fileName = "paddock-control.py"))

    @Test fun theHelperPathIsGivenOnlyForTheCurrentFile() = runBlocking<Unit> {
        assertEquals(destination, helper(sha256sumSession(pinOf("paddock-control.py"))).verifiedPath())
        assertNull(helper(sha256sumSession(null)).verifiedPath())
        assertNull(helper(sha256sumSession("0".repeat(64))).verifiedPath())
    }

    @Test fun theHelperSaysWhetherInstallingWouldReplaceSomethingTheUserHas() = runBlocking<Unit> {
        assertFalse(helper(sha256sumSession(null)).isReplacing())
        assertTrue(helper(sha256sumSession("0".repeat(64))).isReplacing())
        assertEquals(destination, helper(sha256sumSession(null)).destination())
    }

    @Test fun installingSendsTheControlScriptOnStdinToItsOwnFile() = runBlocking<Unit> {
        var installed = false
        val session = FakeSession(onExec = { argv, stdin ->
            when {
                argv.first() == "sh" && argv[2].startsWith("umask") -> {
                    installed = true; assertContentEquals(control, stdin); assertTrue("paddock-control.py" in argv[2]); assertFalse("paddock-relay.py" in argv[2]); FakeSession.result(0)
                }
                argv.first() == "sha256sum" -> if (installed) FakeSession.result(0, "${pinOf("paddock-control.py")}  x\n") else FakeSession.result(1)
                else -> FakeSession.result(0, home)
            }
        })
        val h = helper(session); h.install()
        assertTrue(installed); assertNotNull(h.verifiedPath())
    }
}
