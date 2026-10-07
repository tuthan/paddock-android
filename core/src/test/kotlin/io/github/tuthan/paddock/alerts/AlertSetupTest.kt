package io.github.tuthan.paddock.alerts

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The one-step alert setup, proved against the real things: the relay's own configuration parser runs on every configuration the app writes, and the setup
 * script runs under a real `sh` in a throwaway HOME with stand-ins for systemd, so the order of its steps and what it refuses are observed, not assumed.
 */
class AlertSetupTest {
    private val repo = File(System.getProperty("paddock.repoRoot"))
    private val unit = repo.resolve("host/paddock-alert-relay.service").readText()
    private val socket = "/home/u/.config/herdr/herdr.sock"

    private fun ntfy(url: String = "https://ntfy.example.org", topic: String = "abcDEF123_-xyz0123456789abcdefgh", token: String = "") =
        AlertSetup("workstation", "Workstation", DeliveryMode.NtfyApp, socket, url, topic, token)

    private val push = AlertSetup("workstation", "Workstation", DeliveryMode.Push, socket)

    // ---- the server and the topic ---------------------------------------------------------------------------------------------

    @Test fun aServerAddressIsHttpsOnlyAndTidied() {
        assertEquals(NtfyServer.Result.Ok("https://ntfy.sh"), NtfyServer.normalize("ntfy.sh"))
        assertEquals(NtfyServer.Result.Ok("https://ntfy.example.org"), NtfyServer.normalize("  https://NTFY.example.org/  "))
        assertEquals(NtfyServer.Result.Ok("https://example.org:8443/ntfy"), NtfyServer.normalize("example.org:8443/ntfy/"))
        for (bad in listOf("", "   ", "http://ntfy.example.org", "ftp://x.example", "https://user:pw@ntfy.example.org", "https://ntfy.example.org/a?b=1", "https://ntfy.example.org/#x",
            "https://exa mple.org", "https://example.org:99999", "https://", "https://-bad.example", "https://ntfy.example.org/a b"))
            assertTrue(NtfyServer.normalize(bad) is NtfyServer.Result.Bad, "'$bad'")
    }

    @Test fun thePublicServerIsToldFromAnyOtherByItsHost() {
        assertTrue(NtfyServer.isPublic("https://ntfy.sh")); assertTrue(NtfyServer.isPublic("https://NTFY.SH/up123?up=1"))
        assertFalse(NtfyServer.isPublic("https://ntfy.sh.evil.example")); assertFalse(NtfyServer.isPublic("https://my-ntfy.sh"))
        assertEquals("ntfy.example.org:8443", NtfyServer.hostOf("https://ntfy.example.org:8443/upAbC?up=1"))
    }

    @Test fun aTopicIsMadeFromRandomBytesAndTheRelayAcceptsIt() {
        val a = NtfyServer.newTopic(ByteArray(24) { it.toByte() }); val b = NtfyServer.newTopic(ByteArray(24) { (it + 1).toByte() })
        assertEquals(32, a.length); assertTrue(NtfyServer.validTopic(a)); assertTrue(a != b)
        assertTrue(a.all { it.isLetterOrDigit() || it == '_' || it == '-' }, a)
        assertFailsWith<IllegalArgumentException> { NtfyServer.newTopic(ByteArray(8)) }
        assertFalse(NtfyServer.validTopic("has space")); assertFalse(NtfyServer.validTopic("")); assertFalse(NtfyServer.validTopic("a".repeat(65)))
    }

    @Test fun theSubscribeLinkOpensTheNtfyAppAndTheWebLinkIsTheSameTopic() {
        assertEquals("ntfy://ntfy.sh/Zx_9", NtfyServer.subscribeLink("https://ntfy.sh", "Zx_9"))
        assertEquals("ntfy://ntfy.example.org:8443/ntfy/Zx_9", NtfyServer.subscribeLink("https://ntfy.example.org:8443/ntfy", "Zx_9"))
        assertEquals("https://ntfy.sh/Zx_9", NtfyServer.webLink("https://ntfy.sh", "Zx_9"))
    }

    // ---- the configuration, judged by the relay's own parser --------------------------------------------------------------------

    private fun python(program: String, stdin: String? = null, env: Map<String, String> = emptyMap(), dir: File? = null): Triple<Int, String, String> {
        val pb = ProcessBuilder("python3", "-c", program).directory(dir)
        pb.environment().putAll(env)
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8); val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); return Triple(p.exitValue(), out, err)
    }

    /** Runs the relay's real `parse_config` on [toml] as if the file had permission bits [mode]; the answer is its verdict, or its refusal. */
    private fun relayVerdict(toml: String, mode: Int = 0x180): String {
        val relay = repo.resolve("host/paddock-alert-relay.py").absolutePath
        val (exit, out, err) = python(
            "import importlib.util,sys,tomllib\n" +
                "spec=importlib.util.spec_from_file_location('relay','$relay'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n" +
                "try:\n c=m.parse_config(tomllib.loads(sys.stdin.read()), mode=$mode); print('ok', c.delivery, c.profile, c.label, c.ntfy_url, c.ntfy_topic, bool(c.ntfy_token), c.endpoint_file)\n" +
                "except Exception as e:\n print('refused', e)\n",
            stdin = toml,
        )
        assertEquals(0, exit, err)
        return out.trim()
    }

    @Test fun everyConfigurationTheAppWritesIsOneTheRelayAccepts() {
        assertEquals("ok ntfy workstation Workstation https://ntfy.example.org abcDEF123_-xyz0123456789abcdefgh False", relayVerdict(AlertSetupScript.config(ntfy())))
        assertTrue(relayVerdict(AlertSetupScript.config(push)).startsWith("ok unifiedpush workstation Workstation   False /"), relayVerdict(AlertSetupScript.config(push)))
        assertTrue(relayVerdict(AlertSetupScript.config(ntfy(token = "tk_AbC123"))).contains("True"), "a token is carried")
        assertTrue(relayVerdict(AlertSetupScript.config(ntfy(url = "https://ntfy.sh"))).contains("https://ntfy.sh"))
    }

    @Test fun aTokenInAReadableFileIsRefusedByTheRelayWhichIsWhyTheScriptWritesModeSixHundred() {
        val withToken = AlertSetupScript.config(ntfy(token = "tk_AbC123"))
        assertTrue(relayVerdict(withToken, mode = 0x1a4).startsWith("refused"), "0644 with a token")
        assertTrue(relayVerdict(withToken, mode = 0x180).startsWith("ok"))
    }

    @Test fun aNameWithAQuoteOrABackslashOrALineBreakCannotBreakTheConfiguration() {
        for (label in listOf("My \"box\"", "back\\slash", "two\nlines", "tab\there", "'single'")) {
            val verdict = relayVerdict(AlertSetupScript.config(ntfy().copy(label = label)))
            assertTrue(verdict.startsWith("ok "), "$label -> $verdict")
        }
        assertTrue(relayVerdict(AlertSetupScript.config(ntfy().copy(label = "x".repeat(100)))).contains(" " + "x".repeat(40) + " "), "cut to the relay's 40")
        assertTrue(relayVerdict(AlertSetupScript.config(ntfy().copy(label = "   "))).contains(" workstation "), "a blank name falls back to the id")
    }

    @Test fun whatTheConfigurationCouldBeBrokenWithIsRefusedBeforeItIsWritten() {
        for (bad in listOf(
            ntfy().copy(profileId = "Bad ID"), ntfy().copy(socketPath = "relative/herdr.sock"), ntfy().copy(socketPath = "/a\"b/herdr.sock"), ntfy().copy(socketPath = "/a/other.sock"),
            ntfy().copy(ntfyUrl = "http://ntfy.example.org"), ntfy().copy(ntfyTopic = "has space"), ntfy().copy(ntfyTopic = ""), ntfy().copy(ntfyToken = "tk bad\n"),
        )) assertFailsWith<IllegalArgumentException>(bad.toString()) { AlertSetupScript.config(bad) }
        assertFailsWith<IllegalArgumentException> { AlertSetupScript.build(unit + "\nPADDOCK_UNIT\n", ntfy(), "x") }
    }

    // ---- the script, run for real -------------------------------------------------------------------------------------------------

    private class Box(val home: File, val bin: File, val calls: File)

    /**
     * [mac] makes the stand-ins a Mac's: `uname` says [uname], there is no systemctl or loginctl, `launchctl` answers [bootstrapExit] to `bootstrap` and [printExit] to
     * `print` (1: the agent is not loaded), and the only Python is a `python3.13` whose version probe exits [probeExit] (0: it is 3.11 or newer).
     */
    private fun box(
        withSystemctl: Boolean = true, checkExit: Int = 0, checkErr: String = "", lingerExit: Int = 0,
        mac: Boolean = false, uname: String = "Darwin", probeExit: Int = 0, bootstrapExit: Int = 0, printExit: Int = 1, rootPrefix: String = "alert-setup",
    ): Box {
        val root = Files.createTempDirectory(rootPrefix).toFile().also { it.deleteOnExit() }
        val home = File(root, "home").also { it.mkdirs() }; val bin = File(root, "bin").also { it.mkdirs() }; val calls = File(root, "calls.log")
        for (tool in listOf("sh", "cat", "mkdir", "chmod", "cmp", "cp", "mv", "id", "echo", "rm", "true", "false", "test", "env", "dirname")) {
            val real = listOf("/usr/bin/$tool", "/bin/$tool").map(::File).firstOrNull { it.exists() } ?: continue
            Files.createSymbolicLink(File(bin, tool).toPath(), real.toPath())
        }
        if (mac) {
            File(bin, "uname").apply { writeText("#!/bin/sh\necho $uname\n"); setExecutable(true) }
            // the Python a Homebrew install leaves: found by name, its version probe (`-c`) is not a call of the relay's
            File(bin, "python3.13").apply { writeText("#!/bin/sh\nif [ \"\$1\" = -c ]; then exit $probeExit; fi\necho \"python3.13 \$*\" >> '${calls.path}'\nprintf '%s' '$checkErr' >&2\nexit $checkExit\n"); setExecutable(true) }
            File(bin, "launchctl").apply {
                writeText("#!/bin/sh\necho \"launchctl \$*\" >> '${calls.path}'\ncase \"\$1\" in bootstrap) exit $bootstrapExit;; print) exit $printExit;; esac\nexit 0\n"); setExecutable(true)
            }
            return Box(home, bin, calls)
        }
        // a stand-in for python3 that is only the relay's check: the script runs `python3 ~/.local/share/paddock/paddock-alert-relay.py --check`
        File(bin, "python3").apply { writeText("#!/bin/sh\necho \"python3 \$*\" >> '${calls.path}'\nprintf '%s' '$checkErr' >&2\nexit $checkExit\n"); setExecutable(true) }
        if (withSystemctl) File(bin, "systemctl").apply { writeText("#!/bin/sh\necho \"systemctl \$*\" >> '${calls.path}'\nexit 0\n"); setExecutable(true) }
        File(bin, "loginctl").apply { writeText("#!/bin/sh\necho \"loginctl \$*\" >> '${calls.path}'\nexit $lingerExit\n"); setExecutable(true) }
        return Box(home, bin, calls)
    }

    private data class Ran(val exit: Int, val out: String, val err: String)

    private fun run(box: Box, script: String): Ran {
        val pb = ProcessBuilder("sh", "-s")
        pb.environment().clear(); pb.environment().putAll(mapOf("PATH" to box.bin.path, "HOME" to box.home.path, "USER" to "u", "XDG_RUNTIME_DIR" to "/tmp/none"))
        val p = pb.start()
        p.outputStream.use { it.write(script.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8); val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); return Ran(p.exitValue(), out, err)
    }

    private fun calls(box: Box) = if (box.calls.exists()) box.calls.readLines() else emptyList()

    @Test fun theScriptWritesTheUnitAndTheConfigurationChecksThenEnablesAndRestarts() {
        val b = box(); val script = AlertSetupScript.build(unit, ntfy(token = "tk_AbC123"), "a".repeat(64))
        val r = run(b, script)
        assertEquals(0, r.exit, r.err + r.out); assertEquals(SetupOutcome.Done(), AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertEquals(unit.trimEnd('\n'), File(b.home, ".config/systemd/user/paddock-alert-relay.service").readText().trimEnd('\n'))
        val conf = File(b.home, ".config/paddock/alert-relay.toml")
        assertEquals(AlertSetupScript.config(ntfy(token = "tk_AbC123")).trimEnd('\n'), conf.readText().trimEnd('\n'))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(conf.toPath())), "owner only: it may hold a token")
        assertFalse(File(b.home, ".config/paddock/alert-relay.toml.new").exists(), "no partial file left")
        assertEquals(listOf(
            "python3 ${b.home}/.local/share/paddock/paddock-alert-relay.py --check", "systemctl --user daemon-reload", "systemctl --user enable paddock-alert-relay.service",
            "systemctl --user restart paddock-alert-relay.service", "loginctl enable-linger u",
        ), calls(b), "the check comes before anything is enabled or started")
    }

    @Test fun aReplacedConfigurationIsKeptAsBackupAndAnUnchangedOneIsNot() {
        val b = box(); val a = AlertSetupScript.build(unit, ntfy(), "x"); val other = AlertSetupScript.build(unit, ntfy(url = "https://ntfy.sh"), "x")
        run(b, a)
        val conf = File(b.home, ".config/paddock/alert-relay.toml"); val bak = File(b.home, ".config/paddock/alert-relay.toml.bak")
        run(b, a); assertFalse(bak.exists(), "same setup again: nothing to back up")
        val first = conf.readText()
        run(b, other); assertTrue(bak.exists()); assertEquals(first, bak.readText(), "the earlier configuration is what was kept")
        assertContains(conf.readText(), "https://ntfy.sh")
    }

    @Test fun aConfigurationTheRelayRefusesStartsNothingAndSaysWhy() {
        val b = box(checkExit = 2, checkErr = "paddock-alert-relay: config: profile must be lowercase\nnoise with tk_secret\n")
        val r = run(b, AlertSetupScript.build(unit, ntfy(), "x"))
        assertEquals(22, r.exit)
        assertEquals(SetupOutcome.CheckFailed(listOf("config: profile must be lowercase")), AlertSetupScript.outcome(r.exit, r.out, r.err), "only the relay's reason lines, never the noise")
        assertEquals(listOf("python3 ${b.home}/.local/share/paddock/paddock-alert-relay.py --check"), calls(b), "nothing was enabled, started or restarted")
    }

    @Test fun aMachineWithoutSystemdOrPythonIsToldBeforeAnythingIsWritten() {
        val b = box(withSystemctl = false)
        val r = run(b, AlertSetupScript.build(unit, ntfy(), "x"))
        assertEquals(SetupOutcome.NoSystemd, AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertFalse(File(b.home, ".config").exists(), "nothing was written")
        assertEquals(SetupOutcome.NoPython, AlertSetupScript.outcome(21, "paddock-setup: no-python\n", ""))
    }

    @Test fun aMachineThatWillNotKeepTheRelayAfterLogoutStillGetsAWorkingRelayAndTheReason() {
        val b = box(lingerExit = 1)
        val r = run(b, AlertSetupScript.build(unit, push, "x"))
        assertEquals(0, r.exit)
        assertEquals(SetupOutcome.Done(AlertSetupScript.LINGER_NOTE), AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertContains(calls(b), "systemctl --user restart paddock-alert-relay.service")
    }

    @Test fun anythingElseThatStopsTheScriptIsReportedInItsOwnWordsNotAsSuccess() {
        assertEquals(SetupOutcome.Failed("systemctl: not found"), AlertSetupScript.outcome(127, "", "line\nsystemctl: not found\n"))
        assertTrue(AlertSetupScript.outcome(1, "", "") is SetupOutcome.Failed)
        assertTrue(AlertSetupScript.outcome(0, "", "") is SetupOutcome.Failed, "exit 0 without the final word is not done")
    }

    @Test fun theScriptTheUserReadsIsTheOneThatRunsAndNamesTheRelayItIsFor() {
        val text = AlertSetupScript.build(unit, push, "b".repeat(64))
        assertContains(text, "relay script sha256 ${"b".repeat(64)}")
        assertTrue(text.indexOf("--check") < text.indexOf("enable paddock-alert-relay"), "check, then enable")
        assertTrue(text.indexOf("chmod 600") < text.indexOf("mv -f"), "owner-only before it is in place")
        assertFalse("tk_" in AlertSetupScript.build(unit, ntfy(), "x"))
    }

    @Test fun turningOffStopsTheUnitRemovesTheAddressAndKeepsTheConfiguration() {
        val b = box(); run(b, AlertSetupScript.build(unit, push, "x"))
        File(b.home, ".config/paddock/push-endpoint.json").writeText("""{"endpoint":"https://x.example/up1"}""")
        File(b.bin, "systemctl").writeText("#!/bin/sh\necho \"systemctl \$*\" >> '${b.calls.path}'\nexit 3\n"); File(b.bin, "systemctl").setExecutable(true) // is-active: not active
        val r = run(b, AlertSetupScript.turnOff())
        assertEquals(0, r.exit, r.err); assertEquals(SetupOutcome.Done(), AlertSetupScript.turnOffOutcome(r.exit, r.out))
        assertFalse(File(b.home, ".config/paddock/push-endpoint.json").exists(), "the address is the capability: it goes")
        assertTrue(File(b.home, ".config/paddock/alert-relay.toml").exists(), "the topic survives")
        assertContains(calls(b), "systemctl --user disable --now paddock-alert-relay.service")
    }

    @Test fun aRelayThatIsStillRunningAfterTurnOffIsNotReportedOff() {
        val b = box() // the stand-in systemctl exits 0 for everything, so is-active says "active"
        val r = run(b, AlertSetupScript.turnOff())
        assertEquals(23, r.exit)
        assertTrue(AlertSetupScript.turnOffOutcome(r.exit, r.out) is SetupOutcome.Failed)
        assertEquals(SetupOutcome.NoSystemd, AlertSetupScript.turnOffOutcome(20, "paddock-setup: no-systemd\n"))
    }

    // ---- the same setup on a Mac -----------------------------------------------------------------------------------------------------

    private val mac = push.copy(platform = ServicePlatform.Launchd)
    private val macNtfy = ntfy(token = "tk_AbC123").copy(platform = ServicePlatform.Launchd, socketPath = "/Users/u/.config/herdr/herdr.sock")
    private val agentFile = "Library/LaunchAgents/${AlertSetupScript.LAUNCH_LABEL}.plist"

    private fun plistOf(file: File): Map<String, Any?> {
        val (exit, out, err) = python("import plistlib,sys,json; print(json.dumps(plistlib.loads(sys.stdin.buffer.read())))", stdin = file.readText())
        assertEquals(0, exit, "the LaunchAgent is not a valid property list: $err")
        @Suppress("UNCHECKED_CAST") return kotlinx.serialization.json.Json.parseToJsonElement(out).let { e -> (e as kotlinx.serialization.json.JsonObject).mapValues { (_, v) -> v.toString() } } as Map<String, Any?>
    }

    @Test fun aMacGetsALaunchAgentWithTheAbsolutePythonItFoundAndNoSystemd() {
        val b = box(mac = true); val script = AlertSetupScript.build(unit, macNtfy, "c".repeat(64))
        val r = run(b, script)
        assertEquals(0, r.exit, r.err + r.out)
        assertEquals(SetupOutcome.Done(AlertSetupScript.MAC_LOGIN_NOTE), AlertSetupScript.outcome(r.exit, r.out, r.err), "a Mac says it runs in the login session")
        assertFalse("systemctl" in script || "loginctl" in script || "systemd" in script, "no systemd anywhere in the Mac script")
        val plist = File(b.home, agentFile)
        assertTrue(plist.exists(), "the agent file is where launchd looks for a user's agents")
        val p = plistOf(plist)
        assertEquals("\"${AlertSetupScript.LAUNCH_LABEL}\"", p["Label"])
        val py = File(b.bin, "python3.13").path
        assertEquals("[\"$py\",\"${b.home}/.local/share/paddock/paddock-alert-relay.py\",\"--config\",\"${b.home}/.config/paddock/alert-relay.toml\"]", p["ProgramArguments"], "absolute paths: launchd expands neither ~ nor \$HOME")
        assertEquals("true", p["RunAtLoad"]); assertEquals("true", p["KeepAlive"])
        assertTrue(p["StandardErrorPath"]!!.toString().contains("Library/Logs/paddock-alert-relay.log"))
        val conf = File(b.home, ".config/paddock/alert-relay.toml")
        assertEquals(AlertSetupScript.config(macNtfy).trimEnd('\n'), conf.readText().trimEnd('\n'))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(conf.toPath())))
        val seen = calls(b)
        assertEquals("python3.13 ${b.home}/.local/share/paddock/paddock-alert-relay.py --check", seen.first(), "the relay's check comes before anything is loaded")
        assertEquals(listOf("bootout", "enable", "bootstrap", "kickstart"), seen.drop(1).map { it.split(" ")[1] })
        assertTrue(seen.drop(1).all { it.contains("gui/") || it.contains("LaunchAgents") }, "the user's own login domain")
        assertTrue(seen.single { it.startsWith("launchctl bootstrap") }.endsWith("${b.home}/$agentFile"))
    }

    @Test fun aMacWithOnlyTheSystemPythonOrAnOldOneIsToldBeforeAnythingIsWritten() {
        val b = box(mac = true, probeExit = 1)
        val r = run(b, AlertSetupScript.build(unit, mac, "x"))
        assertEquals(21, r.exit); assertEquals(SetupOutcome.NoPython, AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertFalse(File(b.home, ".config").exists() || File(b.home, "Library").exists(), "nothing was written")
        assertContains(AlertSetupCopy.noPython(ServicePlatform.Launchd), "brew install python")
        assertTrue(calls(b).isEmpty(), "an old Python is never asked to run the relay")
    }

    @Test fun theMacScriptRefusesAMachineThatIsNotAMacBeforeWritingAnything() {
        val b = box(mac = true, uname = "Linux")
        val r = run(b, AlertSetupScript.build(unit, mac, "x"))
        assertEquals(24, r.exit)
        val outcome = AlertSetupScript.outcome(r.exit, r.out, r.err)
        assertTrue(outcome is SetupOutcome.Failed && "not a Mac" in outcome.message, outcome.toString())
        assertFalse(File(b.home, ".config").exists() || File(b.home, "Library").exists())
    }

    @Test fun aRefusedConfigurationOnAMacLoadsAndStartsNothing() {
        val b = box(mac = true, checkExit = 2, checkErr = "paddock-alert-relay: config: profile must be lowercase\n")
        val r = run(b, AlertSetupScript.build(unit, mac, "x"))
        assertEquals(22, r.exit)
        assertEquals(SetupOutcome.CheckFailed(listOf("config: profile must be lowercase")), AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertFalse(File(b.home, agentFile).exists(), "no agent file before the check passes")
        assertEquals(1, calls(b).size, "only the relay's check ran")
    }

    @Test fun aMacWithNobodyLoggedInKeepsItsConfigurationAndSaysWhatToDo() {
        val b = box(mac = true, bootstrapExit = 5)
        val r = run(b, AlertSetupScript.build(unit, mac, "x"))
        assertEquals(26, r.exit); assertEquals(SetupOutcome.NoLoginSession, AlertSetupScript.outcome(r.exit, r.out, r.err))
        assertTrue(File(b.home, ".config/paddock/alert-relay.toml").exists(), "the configuration is written, so trying again after a login is one step")
        assertTrue(calls(b).none { it.startsWith("launchctl kickstart") }, "nothing was started")
        assertContains(AlertSetupCopy.NO_LOGIN_SESSION, "automatic login")
    }

    @Test fun aFolderNameThatCouldBreakThePropertyListIsRefusedNotWritten() {
        val b = box(mac = true, rootPrefix = "a&b-")
        val r = run(b, AlertSetupScript.build(unit, mac, "x"))
        assertEquals(25, r.exit); assertTrue(AlertSetupScript.outcome(r.exit, r.out, r.err) is SetupOutcome.Failed)
        assertFalse(File(b.home, agentFile).exists())
    }

    @Test fun turningOffOnAMacUnloadsAndDisablesTheAgentAndRemovesTheAddress() {
        val b = box(mac = true); File(b.home, ".config/paddock").mkdirs(); File(b.home, ".config/paddock/push-endpoint.json").writeText("{}")
        val r = run(b, AlertSetupScript.turnOff(ServicePlatform.Launchd))
        assertEquals(0, r.exit, r.err); assertEquals(SetupOutcome.Done(), AlertSetupScript.turnOffOutcome(r.exit, r.out))
        assertFalse(File(b.home, ".config/paddock/push-endpoint.json").exists())
        assertEquals(listOf("bootout", "disable", "print"), calls(b).map { it.split(" ")[1] }, "disable is what keeps it from coming back at the next login")
    }

    @Test fun anAgentStillLoadedAfterTurnOffIsNotReportedOffOnAMac() {
        val b = box(mac = true, printExit = 0)
        val r = run(b, AlertSetupScript.turnOff(ServicePlatform.Launchd))
        assertEquals(23, r.exit); assertTrue(AlertSetupScript.turnOffOutcome(r.exit, r.out) is SetupOutcome.Failed)
    }

    @Test fun thePythonFinderLooksWhereHomebrewPutsItAndNeverRunsTheSystemPython() {
        val f = AlertSetupScript.FIND_PYTHON
        for (dir in listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "\$HOME/.local/bin")) assertContains(f, dir)
        assertContains(f, "[ \"\$p\" = /usr/bin/python3 ] && continue")
        assertContains(f, "sys.version_info >= (3, 11)")
        assertTrue(f.indexOf("python3.14") < f.indexOf(" python3;"), "the versioned names come before the bare one")
    }

    @Test fun thePlatformIsAMacOnlyForAMac() {
        assertEquals(ServicePlatform.Launchd, ServicePlatform.of(io.github.tuthan.paddock.hostprofile.HostOs.Mac))
        for (os in listOf(io.github.tuthan.paddock.hostprofile.HostOs.Linux, io.github.tuthan.paddock.hostprofile.HostOs.Windows, null)) assertEquals(ServicePlatform.Systemd, ServicePlatform.of(os))
        assertEquals(ServicePlatform.Systemd, ntfy().platform, "a setup that says nothing is the systemd one, as before")
    }

    @Test fun theMacConfigurationIsOneTheRelayAccepts() {
        assertTrue(relayVerdict(AlertSetupScript.config(macNtfy)).startsWith("ok ntfy workstation"), relayVerdict(AlertSetupScript.config(macNtfy)))
    }

    // ---- the test alert --------------------------------------------------------------------------------------------------------

    private class Seen { @Volatile var path = ""; @Volatile var body = ""; @Volatile var auth: String? = null; @Volatile var type: String? = null }

    private fun withServer(status: Int = 200, block: (Int, Seen) -> Unit) {
        val seen = Seen(); val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen.path = ex.requestURI.path; seen.body = ex.requestBody.readBytes().toString(Charsets.UTF_8); seen.auth = ex.requestHeaders.getFirst("Authorization"); seen.type = ex.requestHeaders.getFirst("Content-Type")
            ex.sendResponseHeaders(status, -1); ex.close()
        }
        server.start()
        try { block(server.address.port, seen) } finally { server.stop(0) }
    }

    private fun runTest(home: File): Ran {
        val pb = ProcessBuilder("python3", "-"); pb.environment()["HOME"] = home.path
        val p = pb.start(); p.outputStream.use { it.write(AlertSetupScript.TEST_PY.toByteArray()) }
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8); val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); return Ran(p.exitValue(), out, err)
    }

    private fun homeWith(config: String, endpoint: String? = null): File {
        val home = Files.createTempDirectory("alert-test").toFile().also { it.deleteOnExit() }
        File(home, ".config/paddock").mkdirs(); File(home, ".config/paddock/alert-relay.toml").writeText(config)
        endpoint?.let { File(home, ".config/paddock/push-endpoint.json").writeText("""{"endpoint": "$it"}""") }
        return home
    }

    @Test fun theTestAlertPostsToTheConfiguredNtfyTopicWithTheTokenAndNothingAboutAnAgent() = withServer { port, seen ->
        val cfg = "socket = \"/s/herdr.sock\"\nprofile = \"w\"\nlabel = \"Box\"\ndelivery = \"ntfy\"\n[ntfy]\nurl = \"http://127.0.0.1:$port\"\ntopic = \"T0pic\"\ntoken = \"tk_x\"\n"
        val r = runTest(homeWith(cfg))
        assertEquals(0, r.exit, r.err); assertEquals(AlertSetupScript.TestOutcome.Sent, AlertSetupScript.testOutcome(r.exit, r.out))
        assertEquals("Bearer tk_x", seen.auth); assertEquals("application/json", seen.type)
        assertContains(seen.body, "\"topic\": \"T0pic\""); assertContains(seen.body, "test on Box")
        assertFalse("paddock://" in seen.body, "a test names no machine, session or terminal")
    }

    @Test fun theTestAlertPostsTheSameSmallHintToAUnifiedPushEndpoint() = withServer { port, seen ->
        val cfg = "socket = \"/s/herdr.sock\"\nprofile = \"w\"\ndelivery = \"unifiedpush\"\n[unifiedpush]\nendpoint_file = \"~/.config/paddock/push-endpoint.json\"\n"
        val r = runTest(homeWith(cfg, "http://127.0.0.1:$port/upAbC"))
        assertEquals(0, r.exit, r.err)
        assertEquals("/upAbC", seen.path); assertContains(seen.body, "\"v\": 1"); assertContains(seen.body, "\"h\": \"w\""); assertContains(seen.body, "\"n\": \"test")
    }

    @Test fun aServerThatRefusesOrANetworkThatIsDownIsFailedNotSent() = withServer(status = 403) { port, _ ->
        val cfg = "socket = \"/s/herdr.sock\"\nprofile = \"w\"\ndelivery = \"ntfy\"\n[ntfy]\nurl = \"http://127.0.0.1:$port\"\ntopic = \"T\"\n"
        val r = runTest(homeWith(cfg))
        assertEquals(1, r.exit)
        assertEquals(AlertSetupScript.TestOutcome.Failed("HTTPError"), AlertSetupScript.testOutcome(r.exit, r.out))
        assertEquals(AlertSetupScript.TestOutcome.Failed("the machine could not send it"), AlertSetupScript.testOutcome(1, ""))
    }
}
