package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.cli.argvToCommand
import io.github.tuthan.paddock.integration.LocalProcessSession
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val WakeTarget.ready: WakeReadiness get() = checkNotNull(readiness)
private val RELAY = WakeRelay("192.168.1.1", 9)

/** An SSH session whose one exec answers with [answer] (which may throw), recording what it was asked to run. */
private class ScriptedSession(private val answer: () -> ExecResult) : SshSession {
    val commands = mutableListOf<List<String>>()
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult { commands += argv; return answer() }
    override suspend fun openStream(argv: List<String>): StreamChannel = error("not used")
    override suspend fun close() {}
}

private fun result(text: String, exit: Int = 0) = ExecResult(exit, text.toByteArray(), ByteArray(0), false, false, Duration.ZERO)

// Captured from the development laptop (Wi-Fi, WoWLAN on) on 2026-10-05 by running WakeCapture.COMMAND with a LAN client address.
private val WIFI = """
client=192.168.42.50
gateway=192.168.42.1
dev=wlp0s20f3
physical=yes
mac=02:00:5e:10:00:01
addr=192.168.42.86
wakeup=enabled
phy=phy0
wowlan=WoWLAN is enabled: * wake up on magic packet
end
""".trimIndent()

private val ETHERNET_G = """
client=10.0.0.5
gateway=10.0.0.1
dev=enp3s0
physical=yes
mac=3c:ec:ef:11:22:33
addr=10.0.0.20
wakeup=enabled
phy=
wol=g
end
""".trimIndent()

class WakeCaptureCommandTest {
    @Test fun itIsOneLineTheSessionWillQuote() {
        val argv = listOf("sh", "-c", WakeCapture.COMMAND)
        assertFalse('\n' in WakeCapture.COMMAND)
        assertFalse('\'' in WakeCapture.COMMAND)
        assertFalse('\\' in WakeCapture.COMMAND)
        argvToCommand(argv)
    }

    @Test fun itHasNoInterpolation() {
        // A constant: nothing the phone or the machine says can reach it.
        assertTrue("SSH_CONNECTION" in WakeCapture.COMMAND)
        assertTrue(WakeCapture.COMMAND.startsWith("PATH=\"\$PATH:/usr/sbin:/sbin\""))
    }

    private fun run(shell: String, ssh: String): Pair<Int, String> {
        val p = ProcessBuilder(shell, "-c", WakeCapture.COMMAND).also { it.environment()["SSH_CONNECTION"] = ssh }.redirectErrorStream(false).start()
        p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.errorStream.readBytes()
        return p.waitFor() to out
    }

    @Test fun itRunsUnderTheLocalShellAndNamesTheLoopbackRoute() {
        assumeTrue(System.getProperty("os.name") == "Linux" && File("/bin/sh").exists())
        val (exit, out) = run("sh", "127.0.0.1 5000 127.0.0.1 22")
        assertEquals(0, exit)
        val lines = out.lines()
        assertTrue("client=127.0.0.1" in lines, out)
        assertTrue("dev=lo" in lines, out)
        assertTrue("physical=no" in lines, out)
        assertEquals(1, lines.count { it == "dev=lo" })
        assertTrue(lines.none { "\\" in it && it.startsWith("dev=") })
    }

    @Test fun itRunsUnderDash() {
        val dash = listOf("/usr/bin/dash", "/bin/dash").firstOrNull { File(it).exists() }
        assumeTrue(dash != null)
        val (exit, out) = run(dash!!, "127.0.0.1 5000 127.0.0.1 22")
        assertEquals(0, exit)
        assertTrue("dev=lo" in out.lines(), out)
    }

    @Test fun thatLoopbackOutputIsNeverATarget() {
        assumeTrue(System.getProperty("os.name") == "Linux" && File("/bin/sh").exists())
        val (_, out) = run("sh", "127.0.0.1 5000 127.0.0.1 22")
        val t = WakeCaptureParser.parse(out, 5, null)
        // `lo` is skipped; a default-route interface on a real machine may still be chosen, and it must be a physical one with a MAC.
        if (t.available) assertNotNull(t.mac) else assertNotNull(t.reason)
        assertTrue(t.iface != "lo")
    }

    @Test fun captureRunsTheCommandAsAnArgvAndNeverThrows() = runBlocking {
        assumeTrue(System.getProperty("os.name") == "Linux" && File("/bin/sh").exists())
        val session = LocalProcessSession(mapOf("SSH_CONNECTION" to "127.0.0.1 5000 127.0.0.1 22"))
        val reading = WakeCapture.capture(session, Clock { 1234 }, RELAY)
        val t = reading.target
        assertTrue(reading.answered, "the loopback route is a real answer: the script ran and printed its blocks")
        assertEquals(listOf("sh", "-c", WakeCapture.COMMAND), session.commands.single())
        assertEquals(RELAY, t.relay)
        assertEquals(1234, t.capturedAtMillis)
    }
}

class WakeCaptureParserTest {
    private fun parse(text: String, relay: WakeRelay? = null) = WakeCaptureParser.parse(text, 99, relay)

    @Test fun aWifiLaptopIsReadyWhenWowlanHearsMagicPackets() {
        val t = parse(WIFI, RELAY)
        assertTrue(t.available)
        assertEquals("02:00:5e:10:00:01", t.mac)
        assertEquals("wlp0s20f3", t.iface)
        assertEquals("192.168.42.86", t.sourceAddress)
        assertEquals("192.168.42.1", t.gateway)
        assertEquals(RELAY, t.relay)
        assertEquals(99, t.capturedAtMillis)
        val r = assertNotNull(t.readiness)
        assertTrue(r.wifi)
        assertEquals("phy0", r.phy)
        assertEquals("enabled: magic packet", r.wowlan)
        assertEquals("enabled", r.wakeup)
        assertEquals(WakeReadiness.Verdict.Ready, r.verdict)
    }

    @Test fun wowlanDisabledIsNotReady() {
        val t = parse(WIFI.replace("WoWLAN is enabled: * wake up on magic packet", "WoWLAN is disabled."))
        assertEquals("disabled", t.ready.wowlan)
        assertEquals(WakeReadiness.Verdict.NotReady, t.ready.verdict)
    }

    @Test fun wowlanEnabledForOtherTriggersOnlyIsNotReady() {
        val t = parse(WIFI.replace("wake up on magic packet", "wake up on disconnect"))
        assertEquals("enabled", t.ready.wowlan)
        assertEquals(WakeReadiness.Verdict.NotReady, t.ready.verdict)
    }

    @Test fun aMissingIwIsUnknownNotNotReady() {
        val t = parse(WIFI.replace(Regex("wowlan=.*"), "wowlan=missing"))
        assertEquals("unknown: iw is not installed", t.ready.wowlan)
        assertEquals(WakeReadiness.Verdict.Unknown, t.ready.verdict)
    }

    @Test fun anEmptyWowlanReadIsUnknown() {
        val t = parse(WIFI.replace(Regex("wowlan=.*"), "wowlan="))
        assertEquals(WakeReadiness.Verdict.Unknown, t.ready.verdict)
    }

    @Test fun ethernetWithWakeOnGIsReady() {
        val t = parse(ETHERNET_G)
        assertTrue(t.available)
        assertFalse(t.ready.wifi)
        assertEquals("g", t.ready.ethtool)
        assertEquals(WakeReadiness.Verdict.Ready, t.ready.verdict)
    }

    @Test fun ethernetWithWakeOnDIsNotReady() {
        assertEquals(WakeReadiness.Verdict.NotReady, parse(ETHERNET_G.replace("wol=g", "wol=d")).ready.verdict)
    }

    @Test fun aMissingEthtoolIsUnknown() {
        val t = parse(ETHERNET_G.replace("wol=g", "wol=missing"))
        assertEquals("unknown: ethtool is not installed", t.ready.ethtool)
        assertEquals(WakeReadiness.Verdict.Unknown, t.ready.verdict)
    }

    @Test fun aWakeupNodeSetToDisabledBlocksReadiness() {
        assertEquals(WakeReadiness.Verdict.NotReady, parse(ETHERNET_G.replace("wakeup=enabled", "wakeup=disabled")).ready.verdict)
    }

    @Test fun aMissingWakeupNodeIsNotAFailure() {
        val t = parse(ETHERNET_G.replace("wakeup=enabled", "wakeup="))
        assertNull(t.ready.wakeup)
        assertEquals(WakeReadiness.Verdict.Ready, t.ready.verdict)
    }

    @Test fun aPhoneOnTailscaleGetsTheDefaultRouteInterfaceNeverTheTunnel() {
        val text = """
client=100.64.0.7
gateway=192.168.42.1
dev=tailscale0
physical=no
mac=
addr=100.64.0.1
wakeup=
phy=
wol=missing
end
""".trimIndent() + "\n" + WIFI.lines().drop(2).joinToString("\n")
        val t = parse(text)
        assertTrue(t.available)
        assertEquals("wlp0s20f3", t.iface)
    }

    @Test fun aTunnelWithAMacLikeAddressIsStillSkippedBecauseItIsNotPhysical() {
        val text = ETHERNET_G.replace("physical=yes", "physical=no")
        val t = parse(text)
        assertFalse(t.available)
        assertTrue("VPN or a virtual network" in t.reason!!, t.reason)
    }

    @Test fun onlyVirtualInterfacesMeanNotAvailableWithTheReasonAndTheRelayKept() {
        val text = "client=10.8.0.2\ngateway=10.8.0.1\ndev=wg0\nphysical=no\nmac=\naddr=10.8.0.1\nwakeup=\nphy=\nwol=missing\nend"
        val t = parse(text, RELAY)
        assertFalse(t.available)
        assertEquals(RELAY, t.relay)
        assertEquals("10.8.0.1", t.gateway)
        assertNull(t.mac)
    }

    @Test fun anAllZeroMacIsNotUsable() {
        val t = parse(ETHERNET_G.replace("3c:ec:ef:11:22:33", "00:00:00:00:00:00"))
        assertFalse(t.available)
        assertTrue("no usable hardware address" in t.reason!!)
    }

    @Test fun anUppercaseOrShortMacIsNotUsable() {
        assertFalse(parse(ETHERNET_G.replace("3c:ec:ef:11:22:33", "3C:EC:EF:11:22:33")).available)
        assertFalse(parse(ETHERNET_G.replace("3c:ec:ef:11:22:33", "3c:ec:ef:11:22")).available)
    }

    @Test fun nothingAtAllIsNotAvailable() {
        assertFalse(parse("").available)
        assertFalse(parse("garbage\nmore garbage").available)
        assertEquals("The machine did not say which network interface this phone reached it on.", parse("").reason)
    }

    @Test fun aBlockThatNeverEndsIsIgnored() {
        assertFalse(parse(ETHERNET_G.removeSuffix("end")).available)
    }

    @Test fun anInterfaceNameWithShellCharactersIsDropped() {
        for (bad in listOf("wlan0;reboot", "wlan0 && x", "\$(id)", "a".repeat(16), "")) {
            val t = parse(ETHERNET_G.replace("enp3s0", bad))
            assertFalse(t.available, bad)
        }
    }

    @Test fun aBadGatewayOrSourceAddressIsDroppedNotCarried() {
        val t = parse(ETHERNET_G.replace("gateway=10.0.0.1", "gateway=router.local").replace("addr=10.0.0.20", "addr=not-an-ip"))
        assertTrue(t.available)
        assertNull(t.gateway)
        assertNull(t.sourceAddress)
    }

    @Test fun aWakeupWordOutsideTheTwoKnownIsDropped() {
        assertNull(parse(ETHERNET_G.replace("wakeup=enabled", "wakeup=sometimes")).ready.wakeup)
    }

    @Test fun anOddWakeOnWordIsKeptShortAndUnknown() {
        val t = parse(ETHERNET_G.replace("wol=g", "wol=" + "x".repeat(100)))
        assertTrue(t.ready.ethtool!!.startsWith("unknown: "))
        assertEquals(WakeReadiness.Verdict.Unknown, t.ready.verdict)
    }

    @Test fun carriageReturnsAreTolerated() {
        assertTrue(parse(ETHERNET_G.replace("\n", "\r\n")).available)
    }

    @Test fun aHugeOutputIsReadOnlyToItsFirstLines() {
        val t = parse("noise\n".repeat(5000) + ETHERNET_G)
        assertFalse(t.available)
    }

    @Test fun theFirstUsablePhysicalBlockWinsAndTheRouteDeviceComesFirst() {
        val second = ETHERNET_G.lines().drop(2).joinToString("\n").replace("enp3s0", "enp9s0").replace("3c:ec:ef:11:22:33", "3c:ec:ef:aa:bb:cc")
        val t = parse(ETHERNET_G + "\n" + second)
        assertEquals("enp3s0", t.iface)
    }
}

class WakeCommandsTest {
    private fun target(readiness: WakeReadiness) = WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", readiness = readiness, capturedAtMillis = 1)

    @Test fun aReadyWifiMachineOnlyGetsThePersistCommand() {
        val cmds = WakeCommands.forTarget(target(WakeReadiness(wakeup = "enabled", wowlan = "enabled: magic packet", wifi = true, phy = "phy0")))
        assertEquals(1, cmds.size)
        assertTrue("nmcli connection modify" in cmds[0].text && "wake-on-wlan magic" in cmds[0].text)
    }

    @Test fun aWifiMachineWithWowlanOffGetsEnableAndPersist() {
        val cmds = WakeCommands.forTarget(target(WakeReadiness(wakeup = "enabled", wowlan = "disabled", wifi = true, phy = "phy0")))
        assertEquals(listOf("sudo iw phy phy0 wowlan enable magic-packet"), cmds.map { it.text }.filter { it.startsWith("sudo") })
        assertEquals(2, cmds.size)
    }

    @Test fun aDisabledWakeupNodeGetsItsOwnCommandFirst() {
class WakeReadingTest {
    private fun read(text: String) = WakeCaptureParser.read(text, 99, null)
    private suspend fun capture(answer: () -> ExecResult) = WakeCapture.capture(ScriptedSession(answer), Clock { 99 }, RELAY)

    @Test fun aFullAnswerIsAnswered() {
        assertTrue(read(WIFI).answered)
        assertTrue(read(ETHERNET_G).answered)
    }

    @Test fun anExecThatFailsIsNotAnAnswerAndKeepsTheRelay() = runBlocking {
        val r = capture { throw java.io.IOException("channel open failed (sshd MaxSessions)") }
        assertFalse(r.answered)
        assertFalse(r.target.available)
        assertEquals(RELAY, r.target.relay)
    }

    @Test fun emptyOutputIsNotAnAnswer() = runBlocking {
        assertFalse(capture { result("") }.answered)
        assertFalse(read("").answered)
    }

    @Test fun garbageAndShellErrorsAreNotAnAnswer() = runBlocking {
        assertFalse(read("garbage\nmore garbage").answered)
        assertFalse(capture { result("sh: 1: ip: not found\n", exit = 127) }.answered)
        // even when something in it looks like a block, a run that never printed its first line is not this command's
        assertFalse(read(ETHERNET_G.lines().drop(2).joinToString("\n")).answered)
    }

    @Test fun outputCutOffBeforeTheFirstBlockEndsIsNotAnAnswer() {
        assertFalse(read(ETHERNET_G.removeSuffix("end")).answered)
    }

    @Test fun aMachineWhereIpSaidNothingIsNotAnAnswer() {
        // The script ran (it printed client= and gateway=) but no interface block came: ip is missing or found no route.
        val r = read("client=10.0.0.5\ngateway=\n")
        assertFalse(r.answered)
        assertEquals("The machine did not say which network interface this phone reached it on.", r.target.reason)
    }

    @Test fun aRouteThatIsOnlyVirtualIsAGenuineNoWakeCapableInterface() {
        val r = read("client=10.8.0.2\ngateway=10.8.0.1\ndev=wg0\nphysical=no\nmac=\naddr=10.8.0.1\nwakeup=\nphy=\nwol=missing\nend")
        assertTrue(r.answered)
        assertFalse(r.target.available)
    }

    @Test fun anAllZeroMacIsAGenuineNoUsableHardwareAddress() {
        val r = read(ETHERNET_G.replace("3c:ec:ef:11:22:33", "00:00:00:00:00:00"))
        assertTrue(r.answered)
        assertFalse(r.target.available)
    }
}

class WakeAfterReadingTest {
    private val saved = WakeRelay("192.168.1.1")
    private val readiness = WakeReadiness(wakeup = "enabled", wowlan = "enabled: magic packet", wifi = true, phy = "phy0")
    private val stored = WakeTarget(
        available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", sourceAddress = "192.168.42.86",
        capturedAtMillis = 1_000, readiness = readiness, gateway = "192.168.42.1", relay = saved,
    )

    private fun answered(t: WakeTarget) = WakeReading(t, answered = true)
    private fun failed(at: Long = 5_000, relay: WakeRelay? = null) =
        WakeReading(WakeTarget.unavailable("The machine could not be asked about its network interface.", at, relay), answered = false)

    @Test fun aRunThatFailedNeverReplacesAnAvailableTarget() {
        assertNull(WakeTarget.afterReading(stored, failed()))
        assertNull(WakeTarget.afterReading(stored, failed(relay = saved)))
    }

    @Test fun aRunThatFoundNoWakeCapableInterfaceDoesReplaceItAndKeepsTheSavedRelay() {
        val genuine = answered(WakeTarget.unavailable("This phone reached the machine over a VPN or a virtual network.", 5_000))
        val next = assertNotNull(WakeTarget.afterReading(stored, genuine))
        assertFalse(next.available)
        assertEquals(saved, next.relay)
        assertEquals(5_000, next.capturedAtMillis)
    }

    @Test fun aFailedRunFillsAnEmptyProfileSoTheReasonShows() {
        assertEquals(failed().target, WakeTarget.afterReading(null, failed()))
    }

    @Test fun aFailedRunMayUpdateAnotherUnavailableTargetButKeepsItsRelay() {
        val gap = WakeTarget.unavailable("Not read yet", 1_000, saved)
        val next = assertNotNull(WakeTarget.afterReading(gap, failed()))
        assertEquals("The machine could not be asked about its network interface.", next.reason)
        assertEquals(saved, next.relay)
    }

    @Test fun theSavedRelayWinsOverTheOneTheReadingStartedWith() {
        val next = assertNotNull(WakeTarget.afterReading(stored, answered(stored.copy(relay = WakeRelay("10.0.0.1"), capturedAtMillis = 2_000, mac = "02:00:5e:10:00:02"))))
        assertEquals(saved, next.relay)
        assertEquals("02:00:5e:10:00:02", next.mac)
    }

    @Test fun aReadingThatSaysTheSameThingIsNotAWrite() {
        assertNull(WakeTarget.afterReading(stored, answered(stored.copy(capturedAtMillis = 2_000, relay = null))))
        assertNull(WakeTarget.afterReading(stored, answered(stored.copy(capturedAtMillis = 1_000 + WakeTarget.REFRESH_MILLIS - 1))))
        // and an unchanged "not available" does not rewrite either
        val gap = WakeTarget.unavailable("same reason", 1_000, saved)
        assertNull(WakeTarget.afterReading(gap, answered(WakeTarget.unavailable("same reason", 9_000))))
    }

    @Test fun aReadingThatSaysTheSameThingRefreshesTheTimeOnceItIsADayOld() {
        val next = assertNotNull(WakeTarget.afterReading(stored, answered(stored.copy(capturedAtMillis = 1_000 + WakeTarget.REFRESH_MILLIS, relay = null))))
        assertEquals(1_000 + WakeTarget.REFRESH_MILLIS, next.capturedAtMillis)
        assertEquals(saved, next.relay)
    }

    @Test fun aChangedReadingIsStoredWithItsTime() {
        val off = readiness.copy(wowlan = "disabled")
        val next = assertNotNull(WakeTarget.afterReading(stored, answered(stored.copy(readiness = off, capturedAtMillis = 2_000, relay = null))))
        assertEquals(off, next.readiness)
        assertEquals(2_000, next.capturedAtMillis)
        assertEquals(saved, next.relay)
    }
}

        val cmds = WakeCommands.forTarget(target(WakeReadiness(wakeup = "disabled", wowlan = "disabled", wifi = true, phy = "phy0")))
        assertEquals("echo enabled | sudo tee /sys/class/net/wlp0s20f3/device/power/wakeup", cmds.first().text)
    }

    @Test fun ethernetGetsEthtoolAndNmcli() {
        val t = WakeTarget(available = true, mac = "3c:ec:ef:11:22:33", iface = "enp3s0", capturedAtMillis = 1, readiness = WakeReadiness(wakeup = "enabled", ethtool = "d"))
        val cmds = WakeCommands.forTarget(t).map { it.text }
        assertTrue("sudo ethtool -s enp3s0 wol g" in cmds)
        assertTrue(cmds.any { "802-3-ethernet.wake-on-lan magic" in it })
    }

    @Test fun nothingIsShownForAnUnavailableTarget() {
        assertEquals(emptyList(), WakeCommands.forTarget(WakeTarget.unavailable("VPN", 1)))
    }

    @Test fun theStatusSaysReadyOrWhyNotWithTheMacAndTheTime() {
        val ready = WakeCommands.status(target(WakeReadiness(wakeup = "enabled", wowlan = "enabled: magic packet", wifi = true, phy = "phy0")), "today 10:15")
        assertEquals("Ready · 02:00:5e:10:00:01 on wlp0s20f3 · wakes from sleep, not from shutdown · as of today 10:15", ready)
        val off = WakeCommands.status(target(WakeReadiness(wakeup = "enabled", wowlan = "disabled", wifi = true, phy = "phy0")), "x")
        assertTrue(off.startsWith("Not ready: Wi-Fi wake (WoWLAN) is disabled"), off)
        val unknown = WakeCommands.status(target(WakeReadiness(wifi = true, phy = "phy0", wowlan = "unknown: iw is not installed")), "x")
        assertTrue(unknown.startsWith("Not known: iw is not installed"), unknown)
        assertEquals("Not available: VPN", WakeCommands.status(WakeTarget.unavailable("VPN", 1), "x"))
    }
}
