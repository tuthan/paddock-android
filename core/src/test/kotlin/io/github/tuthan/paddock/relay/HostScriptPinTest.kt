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
        assertEquals(listOf("alert-relay.example.toml", "paddock-alert-relay.py", "paddock-alert-relay.service", "paddock-claude-permission-hook.py", "paddock-control.py", "paddock-decide.py", "paddock-relay.py"), scripts)
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

    @Test fun theAlertRelayUsesTheStandardLibraryAsksHerdrForTwoThingsAndRunsNoShell() {
        val text = host.resolve("paddock-alert-relay.py").readText()
        val modules = text.lines().mapNotNull { Regex("^\\s*(?:import|from)\\s+([a-zA-Z_.]+)").find(it)?.groupValues?.get(1) }.toSet()
        val stdlib = setOf("asyncio", "json", "os", "random", "re", "secrets", "signal", "socket", "stat", "sys", "time", "tomllib", "urllib.error", "urllib.parse", "urllib.request", "collections", "dataclasses")
        assertEquals(emptySet(), modules - stdlib, "the alert relay imports only the standard library")
        for (banned in listOf("shell=True", "os.system", "eval(", "exec(", "os.popen", "subprocess", "pickle", "marshal")) assertFalse(banned in text, "$banned in paddock-alert-relay.py")
        // It never reads pane text: the only requests it makes are a snapshot and the two kinds of subscription.
        assertEquals(setOf("session.snapshot", "events.subscribe"), Regex("_request\\(\"([a-z._]+)\"").findAll(text).map { it.groupValues[1] }.toSet())
        for (banned in listOf("pane.read", "agent.read", "agent.get", "pane.get", "send_keys", "agent.prompt", "\"terminal.")) assertFalse(banned in text, "$banned in paddock-alert-relay.py")
        assertTrue(text.startsWith("#!/usr/bin/env python3"))
        val version = Regex("\"alert_relay_version\"\\s*:\\s*(\\d+)").find(source)!!.groupValues[1]
        assertEquals(version, Regex("(?m)^VERSION = (\\d+)$").find(text)!!.groupValues[1], "the script's VERSION and host/SOURCE.json disagree")
    }

    /** The script without its module docstring, which says in words what the code must never do. */
    private fun codeOf(text: String) = text.substringAfter("\"\"\"").substringAfter("\"\"\"")

    private fun modulesOf(text: String) = text.lines().mapNotNull { Regex("^\\s*(?:import|from)\\s+([a-zA-Z_.]+)").find(it)?.groupValues?.get(1) }.toSet()

    @Test fun thePermissionHookUsesTheStandardLibraryOnlyAsksHerdrForOneStatusAndRunsNoShellNorHerdrBinary() {
        val text = codeOf(host.resolve("paddock-claude-permission-hook.py").readText())
        val stdlib = setOf("json", "os", "sys", "time", "re", "socket", "signal", "stat", "tomllib")
        assertEquals(emptySet(), modulesOf(text) - stdlib, "the hook imports only the standard library")
        for (banned in listOf("shell=True", "os.system", "eval(", "exec(", "os.popen", "subprocess", "pickle", "marshal", "urllib", "http")) assertFalse(banned in text, "$banned in the hook")
        // The only thing it asks herdr is one agent's status; it never reports a state, reads pane text or sends a key.
        assertEquals(setOf("agent.get"), Regex("\"method\": \"([a-z._]+)\"").findAll(text).map { it.groupValues[1] }.toSet())
        for (banned in listOf("pane.read", "agent.read", "send_keys", "agent.prompt", "report_agent", "report-agent", "\"terminal.", "updatedInput", "updatedPermissions")) assertFalse(banned in text, "$banned in the hook")
        assertTrue(host.resolve("paddock-claude-permission-hook.py").readText().startsWith("#!/usr/bin/env python3"))
        assertTrue("os._exit(0)" in text && "sys.exit(2)" !in text, "it never ends with Claude Code's blocking status")
        val version = Regex("\"permission_hook_version\"\\s*:\\s*(\\d+)").find(source)!!.groupValues[1]
        assertEquals(version, Regex("(?m)^VERSION = (\\d+)$").find(text)!!.groupValues[1], "the script's VERSION and host/SOURCE.json disagree")
    }

    @Test fun theDecisionWriterUsesTheStandardLibraryOnlyAndTouchesNothingButRequestFiles() {
        val text = codeOf(host.resolve("paddock-decide.py").readText())
        val stdlib = setOf("json", "os", "re", "stat", "sys", "time")
        assertEquals(emptySet(), modulesOf(text) - stdlib, "the writer imports only the standard library")
        for (banned in listOf("shell=True", "os.system", "eval(", "exec(", "os.popen", "subprocess", "socket", "pickle", "marshal", "urllib", "send_keys")) assertFalse(banned in text, "$banned in paddock-decide.py")
        assertTrue(host.resolve("paddock-decide.py").readText().startsWith("#!/usr/bin/env python3"))
        val version = Regex("\"decide_version\"\\s*:\\s*(\\d+)").find(source)!!.groupValues[1]
        assertEquals(version, Regex("(?m)^VERSION = (\\d+)$").find(text)!!.groupValues[1], "the script's VERSION and host/SOURCE.json disagree")
        // The hook and the writer agree on the file layout: the same name pattern, the same states.
        val hook = codeOf(host.resolve("paddock-claude-permission-hook.py").readText())
        for (state in listOf("pending", "claimed", "decision", "consumed", "expired")) { assertTrue(state in text && state in hook, state) }
        val namePattern = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
        assertTrue(namePattern in text && namePattern in hook)
    }

    @Test fun theInstallerRefusesAScriptThatIsNotItsPin() {
        val pin = pinOf("paddock-control.py")!!
        RelayInstaller(FakeSession(), control, pin, fileName = "paddock-control.py")
        assertFailsWith<IllegalArgumentException> { RelayInstaller(FakeSession(), control + "#".toByteArray(), pin, fileName = "paddock-control.py") }
        for (bad in listOf("../x.py", "paddock-control.sh", "paddock-.py", "paddock--x.py", "paddock-x-.py", "other.py", "paddock-control.py ", "paddock-Control.py"))
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
