package io.github.tuthan.paddock.answers

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayRefused
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AnswerHostTest {
    private val hostDir = File(System.getProperty("paddock.repoRoot"), "host")
    private val decideBytes = hostDir.resolve("paddock-decide.py").readBytes()
    private val hookBytes = hostDir.resolve("paddock-claude-permission-hook.py").readBytes()
    private val decideSha = sha256Hex(decideBytes)
    private val hookSha = sha256Hex(hookBytes)
    private val pluginBytes = hostDir.resolve("paddock-opencode-permission.js").readBytes()
    private val pluginSha = sha256Hex(pluginBytes)
    private val home = "/home/u"
    private var now = 1_790_000_000_000L

    private fun session(onScript: (List<String>, ByteArray?) -> io.github.tuthan.paddock.ports.ExecResult) = FakeSession(onExec = { argv, stdin ->
        if (argv.first() == "sh" && argv[2].startsWith("printf %s")) FakeSession.result(0, home) else onScript(argv, stdin)
    })

    private fun host(s: FakeSession, label: String = "pixel", withPlugin: Boolean = false) = AnswerHost(
        s, RelayInstaller(s, decideBytes, decideSha, fileName = "paddock-decide.py"),
        RelayInstaller(s, hookBytes, hookSha, fileName = "paddock-claude-permission-hook.py"), { now }, herdrSession = "paddock-test", phoneLabel = label,
        plugin = if (withPlugin) RelayInstaller(s, pluginBytes, pluginSha, fileName = "paddock-opencode-permission.js") else null,
    )

    @Test fun aListIsOneExecThatChecksTheHashAndRunsOnlyThePinnedScript() = runBlocking<Unit> {
        val s = session { _, _ -> FakeSession.result(0, listingJson()) }
        val listing = host(s).list("w1:p1")
        assertEquals(ID_A, listing.candidate?.requestId)
        val argv = s.execs.map { it.first }.single { it.first() == "sh" && it[2].startsWith("p=") }
        assertEquals("sh", argv[0]); assertEquals("-c", argv[1])
        assertTrue(argv[2].contains("sha256sum -c --status") && argv[2].endsWith("exec python3 \"\$p\" \"\$@\""))
        assertEquals(listOf("paddock", "/home/u/.local/share/paddock/paddock-decide.py", decideSha, "list", "paddock-test", "w1:p1"), argv.drop(3))
    }

    @Test fun theWrapperTextCarriesThroughTheExecLayersQuotingRules() = runBlocking<Unit> {
        // A device run found the first wrapper refused by the real exec layer (a single quote and a backslash in its text); no fake said so.
        val s = session { _, _ -> FakeSession.result(0, listingJson()) }
        host(s).list("w1:p1")
        val argv = s.execs.map { it.first }.single { it.first() == "sh" && it[2].startsWith("p=") }
        assertFalse(argv[2].contains('\'') || argv[2].contains('\\') || argv[2].contains('\n'), "no single quote, backslash or line break in the shell text")
        assertTrue(io.github.tuthan.paddock.cli.argvToCommand(argv).startsWith("sh -c 'p=\$1;"))
    }

    @Test fun theShellTextIsFixedAndNothingFromTheHostOrTheUserIsInsideIt() = runBlocking<Unit> {
        val s = session { _, _ -> FakeSession.result(0, listingJson()) }
        val h = host(s)
        h.list("w1:p1")
        h.list("w9:p10")
        val scripts = s.execs.map { it.first }.filter { it.first() == "sh" && it[2].startsWith("p=") }.map { it[2] }.toSet()
        assertEquals(1, scripts.size, "the same text every time")
        assertFalse(scripts.single().contains("w1:p1") || scripts.single().contains("paddock-test") || scripts.single().contains(decideSha))
    }

    @Test fun aDecisionIsOneExecWithTheIdsAsWordsAndTheAnswerOnStdin() = runBlocking<Unit> {
        var written = 0
        val s = session { _, _ -> FakeSession.result(0, """{"state":"decided"}""") }
        host(s, label = "pixel 9").decide("w1:p1", "claude-1", ID_A, Behavior.Deny) { written++ }
        assertEquals(1, written, "beforeWrite once, before the exec")
        val (argv, stdin) = s.execs.single { it.first.firstOrNull() == "sh" && it.first[2].startsWith("p=") }
        assertEquals(listOf("decide", "paddock-test", "w1:p1", "claude-1", ID_A), argv.drop(6))
        val body = Json.parseToJsonElement(stdin!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("behavior", "phone", "at"), body.keys)
        assertEquals("deny", body["behavior"]!!.jsonPrimitive.content)
        assertEquals("pixel 9", body["phone"]!!.jsonPrimitive.content)
        assertEquals(java.time.Instant.ofEpochMilli(now).toString(), body["at"]!!.jsonPrimitive.content)
        assertFalse(argv.joinToString(" ").contains("deny"), "the verdict is never in the command line")
    }

    @Test fun beforeWriteRunsBeforeAnyExecOfTheDecision() = runBlocking<Unit> {
        val order = mutableListOf<String>()
        val s = session { argv, _ -> if (argv.getOrNull(2)?.startsWith("p=") == true) order += "exec"; FakeSession.result(0) }
        host(s).decide("w1:p1", "claude-1", ID_A, Behavior.Allow) { order += "before" }
        assertEquals(listOf("before", "exec"), order)
    }

    @Test fun everyExitStatusOfTheWriterIsItsOwnRefusalAndNothingElseIsMistakenForOne() = runBlocking<Unit> {
        val expect = mapOf(3 to AnswerCodes.GONE, 4 to AnswerCodes.EXPIRED, 5 to AnswerCodes.TOO_LARGE, 6 to AnswerCodes.MISMATCH, 7 to AnswerCodes.HOST_IO, 2 to AnswerCodes.USAGE)
        for ((exit, code) in expect) {
            val s = session { _, _ -> FakeSession.result(exit, err = "paddock-decide: x: y\n") }
            val e = assertFailsWith<DecisionRefused>("exit $exit") { host(s).decide("w1:p1", "claude-1", ID_A, Behavior.Allow) {} }
            assertEquals(code, e.code)
            assertNotNull(AnswerCodes.sentence(code))
        }
        val s = session { _, _ -> FakeSession.result(127, err = "python3: not found") }
        val other = assertFailsWith<IllegalStateException> { host(s).decide("w1:p1", "claude-1", ID_A, Behavior.Allow) {} }
        assertEquals(IllegalStateException::class, other::class, "an exit status the writer does not use is not a refusal: the outcome stays unknown")
    }

    @Test fun aScriptThatIsNotThePinnedOneIsRefusedAndNeverRun() = runBlocking<Unit> {
        val s = session { _, _ -> FakeSession.result(AnswerHost.NOT_PINNED) }
        assertFailsWith<RelayRefused> { host(s).list("w1:p1") }
        assertFailsWith<RelayRefused> { host(s).decide("w1:p1", "claude-1", ID_A, Behavior.Allow) {} }
    }

    @Test fun aListThatCannotBeUnderstoodIsAnErrorNotAnEmptyListing() = runBlocking<Unit> {
        val s = session { _, _ -> FakeSession.result(0, "<html>nope</html>") }
        assertFailsWith<IllegalStateException> { host(s).list("w1:p1") }
    }

    @Test fun theRoundTripOfTheListIsWhatTheRulesAllowFor() = runBlocking<Unit> {
        val s = session { _, _ -> now += 340; FakeSession.result(0, listingJson()) }
        val l = host(s).list("w1:p1")
        assertEquals(340, l.roundTripMillis)
    }

    @Test fun installingWritesBothPinnedScriptsAndChecksThem() = runBlocking<Unit> {
        val hashes = HashMap<String, String>()
        val s = FakeSession(onExec = { argv, stdin ->
            when (argv.first()) {
                "sh" -> when {
                    argv[2].startsWith("printf %s") -> FakeSession.result(0, home)
                    else -> { val name = Regex("cat > \"[^\"]*/([^/\"]+)\\.tmp\"").find(argv[2])!!.groupValues[1]; hashes["$home/.local/share/paddock/$name"] = sha256Hex(stdin!!); FakeSession.result(0) }
                }
                "sha256sum" -> hashes[argv.last()]?.let { FakeSession.result(0, "$it  ${argv.last()}\n") } ?: FakeSession.result(1)
                else -> FakeSession.result(127)
            }
        })
        val h = host(s)
        assertEquals(RelayState.Missing, h.inspect().decide)
        h.install()
        val after = h.inspect()
        assertEquals(RelayState.Current, after.decide)
        assertEquals(RelayState.Current, after.hook)
        assertEquals("/home/u/.local/share/paddock/paddock-decide.py", after.decideDestination)
        assertEquals("/home/u/.local/share/paddock/paddock-claude-permission-hook.py", after.hookDestination)
    }

    @Test fun theOpencodePluginIsInstalledWithTheOtherTwoAndNothingMoreIsWrittenWithoutIt() = runBlocking<Unit> {
        val hashes = HashMap<String, String>()
        val s = FakeSession(onExec = { argv, stdin ->
            when (argv.first()) {
                "sh" -> when {
                    argv[2].startsWith("printf %s") -> FakeSession.result(0, home)
                    else -> { val name = Regex("cat > \"[^\"]*/([^/\"]+)\\.tmp\"").find(argv[2])!!.groupValues[1]; hashes["$home/.local/share/paddock/$name"] = sha256Hex(stdin!!); FakeSession.result(0) }
                }
                "sha256sum" -> hashes[argv.last()]?.let { FakeSession.result(0, "$it  ${argv.last()}\n") } ?: FakeSession.result(1)
                else -> FakeSession.result(127)
            }
        })
        val h = host(s, withPlugin = true)
        assertEquals(pluginSha, h.opencodeSha256)
        val before = h.inspect()
        assertEquals(RelayState.Missing, before.opencode)
        assertEquals("/home/u/.local/share/paddock/paddock-opencode-permission.js", before.opencodeDestination)
        h.install()
        val after = h.inspect()
        assertEquals(listOf(RelayState.Current, RelayState.Current, RelayState.Current), listOf(after.decide, after.hook, after.opencode))
        assertEquals(setOf("paddock-decide.py", "paddock-claude-permission-hook.py", "paddock-opencode-permission.js"), hashes.keys.map { it.substringAfterLast('/') }.toSet())
        // A build without the plugin says nothing about it and writes nothing for it.
        val s2 = session { _, _ -> FakeSession.result(1) }
        val bare = host(s2).inspect()
        assertEquals(null, bare.opencode); assertEquals(null, bare.opencodeDestination); assertEquals(null, host(s2).opencodeSha256)
    }

    @Test fun noCallEverNamesHerdrAKeyOrAnAgentSend() = runBlocking<Unit> {
        val s = session { argv, _ -> FakeSession.result(0, if (argv.contains("list")) listingJson() else """{"state":"decided"}""") }
        val h = host(s)
        h.list("w1:p1")
        h.decide("w1:p1", "claude-1", ID_A, Behavior.Allow) {}
        val all = s.execs.joinToString("\n") { it.first.joinToString(" ") + " " + (it.second?.toString(Charsets.UTF_8) ?: "") }
        for (banned in listOf("herdr", "send-keys", "send_keys", "agent.prompt", "agent send", "pane send", "terminal")) assertFalse(banned in all, "$banned in a call")
    }

    // ---- the text the user pastes

    @Test fun theConfigurationCommandWritesOnlyWhereThereIsNoneAndKeepsItPrivate() {
        val c = AnswerSetupText.configCommand(45)
        assertTrue("[ -e ~/.config/paddock/hook.toml ] ||" in c)
        assertTrue("window_seconds = 45" in c)
        assertTrue("chmod 600" in c)
        // Codex has a window of its own: short by default, whatever the other agents are given.
        assertTrue("codex_window_seconds = 20" in c)
        assertTrue("codex_window_seconds = 8" in AnswerSetupText.configCommand(8))
        assertTrue("window_seconds = 120\\ncodex_window_seconds = 20\\n" in AnswerSetupText.configCommand(120))
        assertTrue("codex_window_seconds = 45" in AnswerSetupText.configCommand(60, 45))
        assertTrue("codex_window_seconds = 0" in AnswerSetupText.configCommand(60, 0))
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.configCommand(60, 301) }
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.configCommand(60, -1) }
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.configCommand(0) }
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.configCommand(301) }
    }

    @Test fun theSettingsSnippetIsValidJsonWithTheWindowPlusFiveSecondsAsTimeout() {
        val snippet = AnswerSetupText.settingsSnippet("/home/u/.local/share/paddock/paddock-claude-permission-hook.py", 60)
        val hook = Json.parseToJsonElement(snippet).jsonObject["hooks"]!!.jsonObject["PermissionRequest"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject["hooks"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
        assertEquals("command", hook["type"]!!.jsonPrimitive.content)
        assertEquals("python3 /home/u/.local/share/paddock/paddock-claude-permission-hook.py", hook["command"]!!.jsonPrimitive.content)
        assertEquals(65, hook["timeout"]!!.jsonPrimitive.content.toInt())
        for (bad in listOf("relative.py", "/a\"b.py", "/a\nb.py", "/a\\b.py")) assertFailsWith<IllegalArgumentException>(bad) { AnswerSetupText.settingsSnippet(bad, 60) }
    }

    private fun hookOf(snippet: String) = Json.parseToJsonElement(snippet).jsonObject["hooks"]!!.jsonObject["PermissionRequest"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject["hooks"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject

    @Test fun theCodexSnippetRunsTheSameHookForCodexWithItsOwnWindowPlusTenSecondsAsTimeout() {
        val path = "/home/u/.local/share/paddock/paddock-claude-permission-hook.py"
        val hook = hookOf(AnswerSetupText.codexSnippet(path, 20))
        assertEquals("command", hook["type"]!!.jsonPrimitive.content)
        assertEquals("python3 $path --agent codex", hook["command"]!!.jsonPrimitive.content)
        assertEquals(30, hook["timeout"]!!.jsonPrimitive.content.toInt())
        assertEquals(310, hookOf(AnswerSetupText.codexSnippet(path, 300))["timeout"]!!.jsonPrimitive.content.toInt())
        for (bad in listOf("relative.py", "/a\"b.py", "/a\nb.py", "/a\\b.py")) assertFailsWith<IllegalArgumentException>(bad) { AnswerSetupText.codexSnippet(bad, 20) }
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.codexSnippet(path, 0) }
        assertFailsWith<IllegalArgumentException> { AnswerSetupText.codexSnippet(path, 301) }
    }

    @Test fun theOpencodeCommandLinksThePinnedFileAndNeverCopiesOrEditsOpencodesConfig() {
        val path = "/home/u/.local/share/paddock/paddock-opencode-permission.js"
        val c = AnswerSetupText.opencodeCommand(path)
        assertTrue("mkdir -p ~/.config/opencode/plugins" in c)
        assertTrue("ln -sf '$path' ~/.config/opencode/plugins/paddock-opencode-permission.js" in c)
        for (banned in listOf("opencode.json", "cp ", "tee", ">")) assertFalse(banned in c.lines().filterNot { it.startsWith("#") }.joinToString("\n"), banned)
        assertTrue("/home/my user/p.js" in AnswerSetupText.opencodeCommand("/home/my user/p.js"), "a space is fine inside the quotes")
        for (bad in listOf("relative.js", "/a'b.js", "/a\nb.js", "/a\\b.js")) assertFailsWith<IllegalArgumentException>(bad) { AnswerSetupText.opencodeCommand(bad) }
    }

    @Test fun theAgentsTheScreensNameAreTheThreeThePhoneCanAnswerFor() {
        assertEquals(listOf("Claude Code", "Codex", "opencode"), listOf("claude", "codex", "opencode").map { AgentNames.label(it) })
        assertEquals("Codex", AgentNames.label(" Codex "))
        assertEquals(null, AgentNames.label("gemini")); assertEquals(null, AgentNames.label(null))
    }
}
