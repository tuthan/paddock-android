package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.relay.FakeSession
import io.github.tuthan.paddock.relay.RelayInstaller
import io.github.tuthan.paddock.relay.sha256Hex
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The steps run in order against a scripted machine, each reported, the first failure stops the rest, and nothing the user did not agree to is written. */
class AlertSetupRunnerTest {
    private val host = File(System.getProperty("paddock.repoRoot"), "host")
    private val script = host.resolve("paddock-alert-relay.py").readBytes()
    private val pin = sha256Hex(script)
    private val unit = host.resolve("paddock-alert-relay.service").readText()
    private val home = "/home/u"
    private val socket = "$home/.config/herdr/herdr.sock"
    private val ntfy = AlertSetup("workstation", "Workstation", DeliveryMode.NtfyApp, socket, "https://ntfy.example.org", "T0pic_T0pic_T0pic_T0pic_T0pic_00")
    private val push = AlertSetup("workstation", "Workstation", DeliveryMode.Push, socket)

    /** A machine whose relay script is [installed] or not, and whose setup script answers [setupOut] (stdout), [setupErr] and [setupExit]. */
    private class Machine(installed: Boolean, val setupOut: String, val setupErr: String = "", val setupExit: Int = 0) {
        var scriptOnHost = installed
        val log = mutableListOf<String>()
        val written = mutableListOf<String>()
    }

    private fun session(m: Machine, script: ByteArray, pin: String) = FakeSession(onExec = { argv, stdin ->
        when {
            argv == listOf("sh", "-s") -> { m.log += "setup"; m.written += stdin!!.toString(Charsets.UTF_8); FakeSession.result(m.setupExit, m.setupOut, m.setupErr) }
            argv.first() == "sh" && argv[2].startsWith("printf") -> FakeSession.result(0, home)
            argv.first() == "sha256sum" -> if (m.scriptOnHost) FakeSession.result(0, "$pin  x\n") else FakeSession.result(1)
            argv.first() == "sh" && argv[2].startsWith("umask") && stdin.contentEquals(script) -> { m.log += "install"; m.scriptOnHost = true; FakeSession.result(0) }
            argv.first() == "sh" && argv[2].startsWith("umask") -> { m.log += "address"; m.written += stdin!!.toString(Charsets.UTF_8); FakeSession.result(0) }
            else -> FakeSession.result(127)
        }
    })

    /** The same machine as a Mac sees it: no `sha256sum` (the shell says exit 127), `shasum` instead. */
    private fun macSession(m: Machine, script: ByteArray, pin: String) = FakeSession(onExec = { argv, stdin ->
        when {
            argv == listOf("sh", "-s") -> { m.log += "setup"; m.written += stdin!!.toString(Charsets.UTF_8); FakeSession.result(m.setupExit, m.setupOut, m.setupErr) }
            argv.first() == "sh" && argv[2].startsWith("printf") -> FakeSession.result(0, "/Users/u")
            argv.first() == "sha256sum" -> FakeSession.result(127)
            argv.first() == "shasum" -> if (m.scriptOnHost) FakeSession.result(0, "$pin  x\n") else FakeSession.result(1)
            argv.first() == "sh" && argv[2].startsWith("umask") && stdin.contentEquals(script) -> { m.log += "install"; m.scriptOnHost = true; FakeSession.result(0) }
            else -> FakeSession.result(127)
        }
    })

    private fun runner(s: FakeSession) = AlertSetupRunner(AlertRelayHost(s, RelayInstaller(s, script, pin, fileName = "paddock-alert-relay.py")), unit, pin)

    private class Reports { val seen = mutableListOf<Pair<SetupStep, StepState>>(); val fn: (SetupStep, StepState) -> Unit = { s, st -> seen += s to st } }

    private fun done() = "paddock-setup: done\n"

    @Test fun theNtfyAppModeInstallsThenConfiguresAndReportsEachStepOnce() = runBlocking<Unit> {
        val m = Machine(installed = false, setupOut = done()); val r = Reports()
        val out = runner(session(m, script, pin)).run(ntfy, { error("no endpoint is asked for in this mode") }, r.fn)
        assertEquals(SetupRun.Done(), out)
        assertEquals(listOf("install", "setup"), m.log)
        assertEquals(
            listOf(SetupStep.Install to StepState.Running, SetupStep.Install to StepState.Done, SetupStep.Configure to StepState.Running, SetupStep.Configure to StepState.Done), r.seen,
            "the push-only steps are not reported for this mode",
        )
        assertContains(m.written.single(), "delivery = \"ntfy\""); assertContains(m.written.single(), "topic = \"T0pic_T0pic_T0pic_T0pic_T0pic_00\"")
    }

    private val macSetup = ntfy.copy(platform = ServicePlatform.Launchd, socketPath = "/Users/u/.config/herdr/herdr.sock")

    @Test fun aMacInstallsWithShasumAndRunsTheLaunchdScriptAndIsToldItRunsInTheLoginSession() = runBlocking<Unit> {
        val m = Machine(installed = false, setupOut = "paddock-setup: login-only\n" + done())
        val out = runner(macSession(m, script, pin)).run(macSetup, { error("not asked for") }, Reports().fn)
        assertEquals(SetupRun.Done(AlertSetupScript.MAC_LOGIN_NOTE), out)
        assertEquals(listOf("install", "setup"), m.log, "the install was verified without sha256sum")
        assertContains(m.written.single(), "launchctl bootstrap"); assertFalse("systemctl" in m.written.single())
    }

    @Test fun aMacWithoutPythonOrALoginSessionGetsTheMacWordsNotTheLinuxOnes() = runBlocking<Unit> {
        val py = runner(macSession(Machine(true, "paddock-setup: no-python\n", setupExit = 21), script, pin)).run(macSetup, { null }, Reports().fn)
        assertEquals(SetupRun.Stopped(SetupStep.Configure, AlertSetupCopy.NO_PYTHON_MAC), py)
        val login = runner(macSession(Machine(true, "paddock-setup: no-login-session\n", setupExit = 26), script, pin)).run(macSetup, { null }, Reports().fn)
        assertEquals(SetupRun.Stopped(SetupStep.Configure, AlertSetupCopy.NO_LOGIN_SESSION), login)
        val linux = runner(session(Machine(true, "paddock-setup: no-python\n", setupExit = 21), script, pin)).run(ntfy, { null }, Reports().fn)
        assertEquals(SetupRun.Stopped(SetupStep.Configure, AlertSetupCopy.NO_PYTHON), linux, "a Linux machine keeps its own wording")
    }

    @Test fun anAlreadyPinnedRelayIsNotInstalledAgain() = runBlocking<Unit> {
        val m = Machine(installed = true, setupOut = done())
        runner(session(m, script, pin)).run(ntfy, { null }, Reports().fn)
        assertEquals(listOf("setup"), m.log)
    }

    @Test fun thePushModeRegistersInstallsSendsTheAddressThenConfiguresInThatOrder() = runBlocking<Unit> {
        val m = Machine(installed = false, setupOut = done()); val r = Reports(); var asked = 0
        val out = runner(session(m, script, pin)).run(push, { asked++; "https://ntfy.example.org/upAbC123" }, r.fn)
        assertEquals(SetupRun.Done(), out); assertEquals(1, asked)
        assertEquals(listOf("install", "address", "setup"), m.log)
        assertEquals(SetupStep.entries, r.seen.map { it.first }.distinct(), "every step, in order")
        assertTrue(r.seen.all { it.second != StepState.Failed })
        assertEquals("""{"endpoint":"https://ntfy.example.org/upAbC123"}""", m.written.first())
        assertContains(m.written.last(), "delivery = \"unifiedpush\"")
    }

    @Test fun aDistributorThatGivesNoAddressStopsEverythingBeforeAnythingIsWritten() = runBlocking<Unit> {
        val m = Machine(installed = false, setupOut = done()); val r = Reports()
        val out = runner(session(m, script, pin)).run(push, { null }, r.fn)
        assertEquals(SetupRun.Stopped(SetupStep.Register, AlertSetupCopy.NO_ENDPOINT), out)
        assertEquals(emptyList(), m.log, "nothing was installed or written on the machine")
        assertEquals(listOf(SetupStep.Register to StepState.Running, SetupStep.Register to StepState.Failed), r.seen)
    }

    @Test fun anAddressTheRelayWouldRefuseIsNotWritten() = runBlocking<Unit> {
        val m = Machine(installed = true, setupOut = done())
        val out = runner(session(m, script, pin)).run(push, { "http://ntfy.example.org/up" }, Reports().fn)
        assertEquals(SetupRun.Stopped(SetupStep.SendAddress, AlertSetupCopy.ENDPOINT_REFUSED), out)
        assertFalse("address" in m.log); assertFalse("setup" in m.log)
    }

    @Test fun aRefusedConfigurationEndsTheRunWithTheRelaysReasonAndNeverReportsDone() = runBlocking<Unit> {
        val m = Machine(installed = true, setupOut = "paddock-setup: check-failed\n", setupErr = "paddock-alert-relay: config: socket must be an absolute path\n", setupExit = 22)
        val r = Reports()
        val out = runner(session(m, script, pin)).run(ntfy, { null }, r.fn) as SetupRun.Stopped
        assertEquals(SetupStep.Configure, out.step); assertContains(out.message, "socket must be an absolute path")
        assertEquals(SetupStep.Configure to StepState.Failed, r.seen.last())
    }

    @Test fun aMachineWithoutSystemdIsToldSoInPlainWords() = runBlocking<Unit> {
        val m = Machine(installed = true, setupOut = "paddock-setup: no-systemd\n", setupExit = 20)
        assertEquals(SetupRun.Stopped(SetupStep.Configure, AlertSetupCopy.NO_SYSTEMD), runner(session(m, script, pin)).run(ntfy, { null }, Reports().fn))
    }

    @Test fun theLingerNoteTravelsWithASuccessfulRun() = runBlocking<Unit> {
        val m = Machine(installed = true, setupOut = "paddock-setup: linger-failed\npaddock-setup: done\n")
        assertEquals(SetupRun.Done(AlertSetupScript.LINGER_NOTE), runner(session(m, script, pin)).run(ntfy, { null }, Reports().fn))
    }

    @Test fun aRelayThatDoesNotMatchAfterInstallingStopsTheRunBeforeAnythingIsConfigured() = runBlocking<Unit> {
        // the install "succeeds" but the host's file still hashes to something else
        val s = FakeSession(onExec = { argv, _ ->
            when {
                argv.first() == "sh" && argv[2].startsWith("printf") -> FakeSession.result(0, home)
                argv.first() == "sha256sum" -> FakeSession.result(0, "${"0".repeat(64)}  x\n")
                argv.first() == "sh" && argv[2].startsWith("umask") -> FakeSession.result(0)
                else -> FakeSession.result(127)
            }
        })
        val out = runner(s).run(ntfy, { null }, Reports().fn)
        assertEquals(SetupRun.Stopped(SetupStep.Install, AlertSetupCopy.RELAY_DID_NOT_MATCH), out)
        assertTrue(s.execs.none { it.first == listOf("sh", "-s") }, "the setup script never ran")
    }

    @Test fun aFailureOfTheConnectionIsReportedAsTheStepThatWasRunning() = runBlocking<Unit> {
        val s = FakeSession(onExec = { _, _ -> throw java.io.IOException("connection closed") })
        val out = runner(s).run(ntfy, { null }, Reports().fn) as SetupRun.Stopped
        assertEquals(SetupStep.Install, out.step); assertContains(out.message, "connection closed")
    }
}

class AlertStatusAndFormTest {
    private val saved = SavedAlertSetup(DeliveryMode.NtfyApp, "https://ntfy.sh", "T")
    private val savedPush = SavedAlertSetup(DeliveryMode.Push)

    @Test fun theStatusIsOffOnOnByHandOrNeedsAttention() {
        assertEquals(AlertStatus.Off, AlertStatus.of(running = false, saved = null, addressOnHost = null))
        assertEquals(AlertStatus.On, AlertStatus.of(true, saved, null))
        assertEquals(AlertStatus.OnByHand, AlertStatus.of(true, null, null), "running, but not from this phone")
        assertEquals(AlertStatus.NeedsAttention, AlertStatus.of(false, saved, null), "set up here, not running now")
        assertEquals(AlertStatus.NeedsAttention, AlertStatus.of(true, savedPush, addressOnHost = false), "the address file is gone")
        assertEquals(AlertStatus.On, AlertStatus.of(true, savedPush, addressOnHost = true))
        assertEquals(AlertStatus.On, AlertStatus.of(true, savedPush, addressOnHost = null), "not being able to ask is not a problem")
    }

    @Test fun thePublicServerNeedsNothingTypedAndAnOwnServerNeedsAnAddress() {
        val pub = AlertSetupForm.check(DeliveryMode.NtfyApp, ownServer = false, ownUrl = "ignored", token = "ignored", distributors = 0)
        assertTrue(pub.canStart); assertEquals(NtfyServer.PUBLIC, pub.serverUrl)
        val none = AlertSetupForm.check(DeliveryMode.NtfyApp, true, "", "", 0)
        assertFalse(none.canStart, "an empty own-server field cannot start"); assertEquals(null, none.urlError, "and says nothing until something was typed")
        val bad = AlertSetupForm.check(DeliveryMode.NtfyApp, true, "http://ntfy.example.org", "", 0)
        assertFalse(bad.canStart); assertTrue(bad.urlError!!.contains("https"))
        val ok = AlertSetupForm.check(DeliveryMode.NtfyApp, true, "ntfy.example.org", "tk_AbC", 0)
        assertTrue(ok.canStart); assertEquals("https://ntfy.example.org", ok.serverUrl)
        assertFalse(AlertSetupForm.check(DeliveryMode.NtfyApp, true, "ntfy.example.org", "tk bad", 0).canStart)
    }

    @Test fun thePaddockShowsThemModeNeedsADistributorAndNothingTyped() {
        assertFalse(AlertSetupForm.check(DeliveryMode.Push, false, "", "", distributors = 0).canStart)
        assertTrue(AlertSetupForm.check(DeliveryMode.Push, false, "", "", distributors = 1).canStart)
    }

    @Test fun serverWordsSayPublicOrYours() {
        assertEquals("the public ntfy.sh server", AlertSetupForm.serverWords("https://ntfy.sh/upAbC"))
        assertEquals("your server, ntfy.example.org", AlertSetupForm.serverWords("https://ntfy.example.org/upAbC"))
    }

    @Test fun theSavedSetupSurvivesARestartAndRemoveForgetsIt() = runBlocking<Unit> {
        val f = java.io.File.createTempFile("alert-setup", ".json").also { it.deleteOnExit(); it.delete() }
        val s1 = FileAlertSetupStore(f)
        s1.put("a", SavedAlertSetup(DeliveryMode.NtfyApp, "https://ntfy.example.org", "Tp", "tk_x", 5))
        assertEquals(SavedAlertSetup(DeliveryMode.NtfyApp, "https://ntfy.example.org", "Tp", "tk_x", 5), FileAlertSetupStore(f).get("a"))
        assertEquals(null, FileAlertSetupStore(f).get("b"))
        FileAlertSetupStore(f).remove("a"); assertEquals(null, FileAlertSetupStore(f).get("a"))
        f.writeText("{ nope"); assertEquals(null, FileAlertSetupStore(f).get("a"), "an unreadable file is not set up")
    }
}
