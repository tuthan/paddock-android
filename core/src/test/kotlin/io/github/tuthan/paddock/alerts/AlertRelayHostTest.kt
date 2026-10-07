package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.RelayState
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AlertRelayHostTest {
    private val host = File(System.getProperty("paddock.repoRoot"), "host")
    private val script = host.resolve("paddock-alert-relay.py").readBytes()
    private val pin = sha256Hex(script)
    private val home = "/home/u"
    private val path = "$home/.local/share/paddock/paddock-alert-relay.py"

    private fun session(onHost: String?, checkExit: Int = 0, checkErr: String = "", service: String = "active") = FakeSession(onExec = { argv, _ ->
        when {
            argv.first() == "sh" && argv[2].startsWith("printf") -> FakeSession.result(0, home)
            argv.first() == "sha256sum" -> if (onHost == null) FakeSession.result(1) else FakeSession.result(0, "$onHost  ${argv.last()}\n")
            argv.first() == "python3" -> FakeSession.result(checkExit, err = checkErr)
            argv.first() == "systemctl" -> FakeSession.result(if (service == "active") 0 else 3, out = service + "\n")
            else -> FakeSession.result(127)
        }
    })
    private fun relay(s: FakeSession) = AlertRelayHost(s, RelayInstaller(s, script, pin, fileName = "paddock-alert-relay.py"))

    @Test fun aMissingScriptIsNotRunAndTheServiceIsStillAsked() = runBlocking<Unit> {
        val s = session(null, service = "inactive")
        val status = relay(s).inspect()
        assertEquals(RelayState.Missing, status.script)
        assertNull(status.check)
        assertEquals(ServiceState.Inactive, status.service)
        assertEquals(path, status.destination)
        assertTrue(s.execs.none { it.first.first() == "python3" }, "an unpinned file is never run")
    }

    @Test fun aDifferentFileOnTheHostIsNeverRun() = runBlocking<Unit> {
        val s = session("0".repeat(64))
        val status = relay(s).inspect()
        assertEquals(RelayState.Mismatch("0".repeat(64)), status.script)
        assertNull(status.check)
        assertTrue(s.execs.none { it.first.first() == "python3" })
    }

    @Test fun theCurrentScriptIsCheckedWithItsOwnCheckAndOnlyItsReasonLinesAreKept() = runBlocking<Unit> {
        val s = session(pin, 0, "paddock-alert-relay: config ok; herdr reachable (session default, delivery ntfy)\nsomething else with tk_secret\n")
        val status = relay(s).inspect()
        assertEquals(RelayState.Current, status.script)
        val check = assertNotNull(status.check)
        assertTrue(check.ok)
        assertEquals(listOf("config ok; herdr reachable (session default, delivery ntfy)"), check.lines)
        assertEquals(ServiceState.Active, status.service)
        assertEquals(listOf("python3", path, "--check"), s.execs.first { it.first.first() == "python3" }.first)
    }

    @Test fun aConfigProblemAndAnUnreachableHerdrAreTheScriptsOwnExitCodes() = runBlocking<Unit> {
        assertEquals(2, relay(session(pin, 2, "paddock-alert-relay: config: profile must be lowercase\n")).inspect().check!!.exit)
        assertEquals(3, relay(session(pin, 3, "paddock-alert-relay: config ok; herdr not reachable (socket_missing)\n")).inspect().check!!.exit)
    }

    @Test fun theServiceStatesAreNamedAndAnythingElseIsUnknown() = runBlocking<Unit> {
        for ((said, want) in listOf("active" to ServiceState.Active, "inactive" to ServiceState.Inactive, "failed" to ServiceState.Failed, "unknown" to ServiceState.NotInstalled, "Failed to connect to bus" to ServiceState.Unknown, "" to ServiceState.Unknown))
            assertEquals(want, relay(session(pin, service = said)).inspect().service, said)
    }

    @Test fun installingWritesThePinnedScriptOnlyAndVerifiesIt() = runBlocking<Unit> {
        var installed = false
        val s = FakeSession(onExec = { argv, stdin ->
            when {
                argv.first() == "sh" && argv[2].startsWith("umask") -> { installed = true; assertTrue(stdin.contentEquals(script)); assertContains(argv[2], "paddock-alert-relay.py"); FakeSession.result(0) }
                argv.first() == "sha256sum" -> if (installed) FakeSession.result(0, "$pin  x\n") else FakeSession.result(1)
                else -> FakeSession.result(0, home)
            }
        })
        relay(s).install()
        assertTrue(installed)
    }

    private fun pushSession(testExit: Int = 0, writeExit: Int = 0) = FakeSession(onExec = { argv, _ ->
        when {
            argv.first() == "sh" && argv[2].startsWith("test -s") -> FakeSession.result(testExit)
            argv.first() == "sh" && argv[2].startsWith("umask") -> FakeSession.result(writeExit)
            argv.first() == "sh" && argv[2].startsWith("rm -f") -> FakeSession.result(0)
            else -> FakeSession.result(127)
        }
    })

    @Test fun theAddressGoesOnStdinWithMode600AndNeverInTheCommandLine() = runBlocking<Unit> {
        val s = pushSession()
        relay(s).writePushEndpoint("https://ntfy.example.org/upSecretSecret123")
        val (argv, stdin) = s.execs.single()
        assertEquals("""{"endpoint":"https://ntfy.example.org/upSecretSecret123"}""", stdin!!.toString(Charsets.UTF_8))
        assertFalse(argv.any { "upSecretSecret123" in it }, "the address is not in argv")
        val cmd = argv[2]
        assertTrue(cmd.startsWith("umask 077 && "), cmd)
        assertContains(cmd, "\$HOME/.config/paddock/push-endpoint.json.tmp")
        assertTrue(cmd.indexOf("cat >") < cmd.indexOf("mv -f"), "written, then moved into place")
    }

    @Test fun anAddressTheRelayWouldRefuseIsNotWritten() = runBlocking<Unit> {
        for (bad in listOf("http://ntfy.example.org/up", "https://user:pw@ntfy.example.org/up", "ftp://x/y", "https://x.example/up\n{\"x\":1}", ""))
            assertFailsWith<IllegalArgumentException>(bad) { relay(pushSession()).writePushEndpoint(bad) }
        val s = pushSession()
        assertFailsWith<IllegalArgumentException> { relay(s).writePushEndpoint("javascript:alert(1)") }
        assertTrue(s.execs.isEmpty())
    }

    @Test fun aFailedWriteSaysSoAndTheFileIsAskedAboutWithoutReadingIt() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { relay(pushSession(writeExit = 1)).writePushEndpoint("https://ntfy.example.org/up1") }
        val s = pushSession(testExit = 0)
        assertTrue(relay(s).hasPushEndpoint())
        assertFalse(relay(pushSession(testExit = 1)).hasPushEndpoint())
        assertTrue(s.execs.single().first[2].startsWith("test -s"), "existence only: nothing is read back")
        val r = pushSession(); relay(r).removePushEndpoint()
        assertContains(r.execs.single().first[2], "rm -f")
    }

    // ---- a Mac ----------------------------------------------------------------------------------------------------------------------

    private val macHome = "/Users/u"
    private val macPath = "$macHome/.local/share/paddock/paddock-alert-relay.py"

    /** A Mac as an SSH command sees it: `uname` says Darwin, there is no `sha256sum` (only `shasum`), no `systemctl`, and every script goes in on stdin. */
    private fun mac(onHost: String?, checkExit: Int = 0, checkErr: String = "", state: String = "active", testOut: String = "paddock-test: ok 200\n", uname: () -> Int = { 0 }) =
        FakeSession(onExec = { argv, stdin ->
            val text = stdin?.toString(Charsets.UTF_8).orEmpty()
            when {
                argv.first() == "sh" && argv.getOrNull(1) == "-c" && argv[2].startsWith("uname") -> if (uname() == 0) FakeSession.result(0, "Darwin\n") else FakeSession.result(1)
                argv.first() == "sh" && argv.getOrNull(1) == "-c" && argv[2].startsWith("printf") -> FakeSession.result(0, macHome)
                argv.first() == "sha256sum" -> FakeSession.result(127, err = "sha256sum: command not found")
                argv.first() == "shasum" -> if (onHost == null) FakeSession.result(1) else FakeSession.result(0, "$onHost  ${argv.last()}\n")
                argv == listOf("sh", "-s") && "--check" in text -> FakeSession.result(checkExit, out = if (checkExit == 21) "paddock-setup: no-python\n" else "", err = checkErr)
                argv == listOf("sh", "-s") && "launchctl print" in text -> FakeSession.result(0, state + "\n")
                argv == listOf("sh", "-s") && "PADDOCK_TEST" in text -> FakeSession.result(if ("ok" in testOut) 0 else 1, testOut)
                argv == listOf("sh", "-s") -> FakeSession.result(0, "paddock-setup: off\n")
                else -> FakeSession.result(127)
            }
        })

    @Test fun aMacIsReadThroughShasumAndTheLaunchAgentAndALookedForPython() = runBlocking<Unit> {
        val s = mac(pin, 0, "paddock-alert-relay: config ok; herdr reachable (session default, delivery unifiedpush)\n")
        val r = relay(s); val status = r.inspect()
        assertEquals(ServicePlatform.Launchd, r.platform())
        assertEquals(RelayState.Current, status.script, "the hash came from shasum: a Mac has no sha256sum")
        assertEquals(macPath, status.destination)
        assertTrue(assertNotNull(status.check).ok)
        assertEquals(ServiceState.Active, status.service)
        assertTrue(s.execs.none { it.first.first() in listOf("systemctl", "python3") }, "no systemd and no bare python3 on a Mac")
        val check = s.execs.map { it.second?.toString(Charsets.UTF_8).orEmpty() }.single { "--check" in it }
        assertContains(check, AlertSetupScript.FIND_PYTHON); assertContains(check, "\"$macPath\" --check </dev/null")
    }

    @Test fun theLaunchAgentsStatesAreNamedLikeSystemds() = runBlocking<Unit> {
        for ((said, want) in listOf("active" to ServiceState.Active, "inactive" to ServiceState.Inactive, "unknown" to ServiceState.NotInstalled, "???" to ServiceState.Unknown))
            assertEquals(want, relay(mac(pin, state = said)).inspect().service, said)
    }

    @Test fun aMacWithoutAnyPythonThreeElevenSaysSoInsteadOfAReadingFailure() = runBlocking<Unit> {
        val check = assertNotNull(relay(mac(pin, checkExit = 21)).inspect().check)
        assertEquals(21, check.exit); assertFalse(check.ok); assertEquals(listOf(AlertSetupCopy.NO_PYTHON_MAC), check.lines)
    }

    @Test fun theTestAlertOnAMacRunsThePythonItFoundAndSaysWhenThereIsNone() = runBlocking<Unit> {
        val s = mac(pin)
        assertEquals(AlertSetupScript.TestOutcome.Sent, relay(s).sendTest())
        val (argv, stdin) = s.execs.last()
        assertEquals(listOf("sh", "-s"), argv, "over SSH an argument cannot hold quotes or line breaks, so the script is on stdin")
        val text = stdin!!.toString(Charsets.UTF_8)
        assertContains(text, AlertSetupScript.FIND_PYTHON); assertContains(text, "exec \"\$PY\" - <<'PADDOCK_TEST'"); assertContains(text, "urllib.request")
        assertEquals(AlertSetupScript.TestOutcome.Failed("no Python 3.11 or newer on the machine"), relay(mac(pin, testOut = "paddock-setup: no-python\n")).sendTest())
    }

    @Test fun turningOffOnAMacSendsTheLaunchdScriptNotTheSystemdOne() = runBlocking<Unit> {
        val s = mac(pin); relay(s).turnOff()
        val text = s.execs.last().second!!.toString(Charsets.UTF_8)
        assertContains(text, "launchctl bootout"); assertContains(text, "launchctl disable"); assertFalse("systemctl" in text)
    }

    @Test fun installingOnAMacVerifiesTheFileWithShasum() = runBlocking<Unit> {
        var installed = false
        val s = FakeSession(onExec = { argv, stdin ->
            when {
                argv.first() == "sh" && argv[2].startsWith("umask") -> { installed = true; assertTrue(stdin.contentEquals(script)); FakeSession.result(0) }
                argv.first() == "sha256sum" -> FakeSession.result(127)
                argv.first() == "shasum" -> if (installed) FakeSession.result(0, "$pin  x\n") else FakeSession.result(1)
                else -> FakeSession.result(0, macHome)
            }
        })
        relay(s).install()
        assertTrue(installed)
    }

    @Test fun aMachineThatWillNotSayWhatItIsIsTakenForLinuxAndAskedAgainNextTime() = runBlocking<Unit> {
        var answers = false
        val r = relay(mac(pin, uname = { if (answers) 0 else 1 }))
        assertEquals(ServicePlatform.Systemd, r.platform())
        answers = true
        assertEquals(ServicePlatform.Launchd, r.platform(), "a slow answer is not remembered as a wrong one")
    }
}
