package io.github.tuthan.paddock.wake

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLIENT = "192.168.42.50"

private val ROUTE_TO_PHONE = """
   route to: $CLIENT
destination: $CLIENT
  interface: en0
      flags: <UP,HOST,DONE,LLINFO,WASCLONED,IFSCOPE,IFREF>
 recvpipe  sendpipe  ssthresh  rtt,msec    rttvar  hopcount      mtu     expire
       0         0         0         0         0         0      1500      1199
""".trimIndent() + "\n"

private val ROUTE_DEFAULT = """
   route to: default
destination: default
       mask: default
    gateway: 192.168.42.1
  interface: en0
      flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
 recvpipe  sendpipe  ssthresh  rtt,msec    rttvar  hopcount      mtu     expire
       0         0         0         0         0         0      1500         0
""".trimIndent() + "\n"

private fun ifconfigEn0(ether: String = "02:00:5e:10:00:01") = (
    "en0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST> mtu 1500\n" +
        "\toptions=6460<TSO4,TSO6,CHANNEL_IO,PARTIAL_CSUM,ZEROINVERT_CSUM>\n" +
        "\tether $ether\n" +
        "\tinet6 fe80::1c2f:aaaa:bbbb:cccc%en0 prefixlen 64 secured scopeid 0xb\n" +
        "\tinet 192.168.42.23 netmask 0xffffff00 broadcast 192.168.42.255\n" +
        "\tnd6 options=201<PERFORMNUD,DAD>\n" +
        "\tmedia: autoselect\n" +
        "\tstatus: active\n"
    )

private fun pmset(womp: String?) = (
    "System-wide power settings:\nCurrently in use:\n standby              1\n Sleep On Power Button 1\n hibernatefile        /var/vm/sleepimage\n" +
        " powernap             1\n networkoversleep     0\n disksleep            10\n sleep                1 (sleep prevented by powerd, sharingd)\n" +
        " hibernatemode        3\n ttyskeepawake        1\n displaysleep         2\n tcpkeepalive         1\n lowpowermode         0\n" +
        (if (womp != null) " womp                 $womp\n" else "")
    )

/**
 * A macOS host as far as the capture command can tell: stand-ins for `uname`, `route`, `ifconfig` and `pmset` that print what those programs print
 * (not captured from a Mac: none was at hand), first on the PATH, and an `ip` that records that it was called, which it must not be.
 */
private class FakeMac(
    routeToPhone: String = ROUTE_TO_PHONE, routeDefault: String = ROUTE_DEFAULT, ifconfig: String = ifconfigEn0(), pmset: String = pmset("1"),
    private val uname: String = "Darwin",
) {
    private val dir: File = Files.createTempDirectory("fake-mac").toFile().also { it.deleteOnExit() }
    private val ipCalled = File(dir, "ip-called")
    private val routeCalled = File(dir, "route-called")

    init {
        script("uname", "echo $uname")
        script("route", "touch '${routeCalled.path}'\ncase \"\$*\" in\n\"-n get default\") cat <<'EOF'\n$routeDefault" + "EOF\n;;\n\"-n get $CLIENT\") cat <<'EOF'\n$routeToPhone" + "EOF\n;;\n*) exit 1;;\nesac")
        script("ifconfig", "case \"\$1\" in\nen0) cat <<'EOF'\n$ifconfig" + "EOF\n;;\n*) printf '%s: flags=8051<UP>\\n' \"\$1\";;\nesac")
        script("pmset", "[ \"\$1\" = -g ] && cat <<'EOF'\n$pmset" + "EOF")
        script("ip", "touch '${ipCalled.path}'\nexit 1")
    }

    private fun script(name: String, body: String) {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n"); f.setExecutable(true)
    }

    val ipWasCalled get() = ipCalled.exists()
    val routeWasCalled get() = routeCalled.exists()

    fun run(shell: List<String>): String {
        val pb = ProcessBuilder(shell + listOf("-c", WakeCapture.COMMAND))
        pb.environment().clear()
        pb.environment()["PATH"] = "${dir.path}:/usr/bin:/bin"
        pb.environment()["SSH_CONNECTION"] = "$CLIENT 50000 192.168.42.23 22"
        val p = pb.start()
        p.outputStream.close()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.errorStream.readBytes()
        check(p.waitFor() == 0) { "the command exited non-zero: $out" }
        return out
    }
}

private val SHELLS = listOf(listOf("sh"), listOf("/usr/bin/dash"), listOf("bash", "--posix")).filter { cmd ->
    runCatching { ProcessBuilder(cmd + listOf("-c", "exit 0")).start().waitFor() == 0 }.getOrDefault(false)
}

class WakeMacosCommandTest {
    private fun capture(mac: FakeMac = FakeMac(), shell: List<String> = listOf("sh")): WakeReading {
        assumeTrue("needs a POSIX shell to run the command", SHELLS.isNotEmpty())
        return WakeCaptureParser.read(mac.run(shell), 7, null)
    }

    @Test fun aMacOnWifiWithWakeForNetworkAccessOnIsReadyOnItsHardwareAddress() {
        val mac = FakeMac()
        val r = capture(mac)
        assertTrue(r.answered)
        val t = r.target
        assertTrue(t.available, t.reason)
        assertEquals("02:00:5e:10:00:01", t.mac)
        assertEquals("en0", t.iface)
        assertEquals("192.168.42.23", t.sourceAddress)
        assertEquals("192.168.42.1", t.gateway)
        assertEquals("enabled", t.readiness?.womp)
        assertEquals(WakeReadiness.Verdict.Ready, t.readiness?.verdict)
        assertTrue(mac.routeWasCalled, "the macOS reader ran")
        assertFalse(mac.ipWasCalled, "`ip` is a Linux program and is never reached on macOS")
        assertTrue(WakeCommands.forTarget(t).isEmpty(), "nothing to change when it is on")
        assertTrue(WakeCommands.status(t, "18:04").startsWith("Ready · 02:00:5e:10:00:01 on en0"), WakeCommands.status(t, "18:04"))
    }

    @Test fun itReadsTheSameUnderEveryPosixShell() {
        assumeTrue(SHELLS.isNotEmpty())
        for (shell in SHELLS) {
            val t = capture(shell = shell).target
            assertTrue(t.available && t.mac == "02:00:5e:10:00:01" && t.readiness?.womp == "enabled", "$shell: $t")
        }
    }

    @Test fun wakeForNetworkAccessOffIsNotReadyAndGetsPmsetAsItsCommand() {
        val t = capture(FakeMac(pmset = pmset("0"))).target
        assertEquals(WakeReadiness.Verdict.NotReady, t.readiness?.verdict)
        assertEquals("disabled", t.readiness?.womp)
        assertEquals(listOf("sudo pmset -a womp 1"), WakeCommands.forTarget(t).map { it.text })
        assertTrue(WakeCommands.status(t, "18:04").startsWith("Not ready: Wake for network access is off"), WakeCommands.status(t, "18:04"))
    }

    @Test fun aSettingThatCannotBeReadIsUnknownAndStillGetsItsCommand() {
        val t = capture(FakeMac(pmset = pmset(null))).target
        assertTrue(t.available)
        assertEquals(WakeReadiness.Verdict.Unknown, t.readiness?.verdict)
        assertTrue(t.readiness?.womp?.startsWith("unknown") == true, t.readiness?.womp)
        assertEquals(listOf("sudo pmset -a womp 1"), WakeCommands.forTarget(t).map { it.text })
        assertTrue(WakeCommands.status(t, "18:04").startsWith("Not known: Wake for network access could not be read"), WakeCommands.status(t, "18:04"))
    }

    @Test fun aPhoneOnATunnelGetsTheDefaultRoutesPhysicalInterface() {
        val viaTunnel = ROUTE_TO_PHONE.replace("en0", "utun3")
        val t = capture(FakeMac(routeToPhone = viaTunnel)).target
        assertTrue(t.available, t.reason)
        assertEquals("en0", t.iface, "a tunnel is never the interface to wake")
    }

    @Test fun aRouteThatIsOnlyVirtualIsAGenuineNoWakeCapableInterface() {
        val tunnel = ROUTE_DEFAULT.replace("en0", "utun3")
        val r = capture(FakeMac(routeToPhone = ROUTE_TO_PHONE.replace("en0", "utun3"), routeDefault = tunnel, ifconfig = ifconfigEn0().replace("en0", "utun3")))
        assertTrue(r.answered)
        assertFalse(r.target.available)
        assertTrue("VPN or a virtual network" in r.target.reason.orEmpty(), r.target.reason)
    }

    @Test fun anInterfaceWithoutAHardwareAddressIsNotUsable() {
        val r = capture(FakeMac(ifconfig = "en0: flags=8863<UP> mtu 1500\n\tinet 192.168.42.23 netmask 0xffffff00\n"))
        assertFalse(r.target.available)
        assertEquals("The network interface has no usable hardware address.", r.target.reason)
    }

    @Test fun aMacWithNothingToReadIsNotAnAnswerAndNeverReplacesWhatWasRead() {
        // route prints nothing: the run says nothing about the machine.
        val r = capture(FakeMac(routeToPhone = "", routeDefault = ""))
        assertFalse(r.answered)
        assertFalse(r.target.available)
    }

    @Test fun onLinuxTheMacProgramsAreNeverReached() {
        assumeTrue(SHELLS.isNotEmpty())
        val mac = FakeMac(uname = "Linux")
        mac.run(listOf("sh"))
        assertFalse(mac.routeWasCalled, "uname said Linux")
        assertTrue(mac.ipWasCalled, "the Linux reader ran")
    }
}

class WakeMacosReadinessTest {
    private fun target(womp: String?) = WakeTarget(
        available = true, mac = "02:00:5e:10:00:01", iface = "en0", capturedAtMillis = 1, readiness = WakeReadiness(womp = womp),
    )

    @Test fun aWifiOrEthernetMacHasOneSettingAndOneCommand() {
        assertEquals(listOf("sudo pmset -a womp 1"), WakeCommands.forTarget(target("disabled")).map { it.text })
        assertEquals(listOf("sudo pmset -a womp 1"), WakeCommands.forTarget(target("unknown: Wake for network access could not be read")).map { it.text })
        assertEquals(emptyList(), WakeCommands.forTarget(target("enabled")))
    }

    @Test fun noLinuxCommandIsEverShownForAMac() {
        for (womp in listOf("enabled", "disabled", "unknown: x")) {
            val text = WakeCommands.forTarget(target(womp)).joinToString { it.text }
            for (linuxOnly in listOf("sysfs", "/sys/", "iw phy", "ethtool", "nmcli")) assertFalse(linuxOnly in text, "$womp: $text")
        }
    }

    @Test fun anOddWompWordIsRefused() {
        assertFailsWith("a made-up value") { WakeReadiness(womp = "maybe") }
        assertFailsWith("too long") { WakeReadiness(womp = "unknown: " + "x".repeat(200)) }
    }

    @Test fun aTargetStoredBeforeMacSupportStillLoadsAndOneWithWompRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"available":true,"mac":"02:00:5e:10:00:01","iface":"wlp0s20f3","capturedAtMillis":5,"readiness":{"wakeup":"enabled","wowlan":"enabled: magic packet","wifi":true,"phy":"phy0"}}"""
        val loaded = json.decodeFromString(WakeTarget.serializer(), old)
        assertNull(loaded.readiness?.womp)
        val mac = target("disabled")
        assertEquals(mac, json.decodeFromString(WakeTarget.serializer(), json.encodeToString(WakeTarget.serializer(), mac)))
        assertNotNull(loaded.readiness)
    }

    private inline fun assertFailsWith(why: String, block: () -> Unit) {
        val failed = try { block(); false } catch (_: IllegalArgumentException) { true }
        assertTrue(failed, why)
    }
}
