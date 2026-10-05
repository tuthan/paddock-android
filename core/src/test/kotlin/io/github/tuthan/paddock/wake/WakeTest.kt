package io.github.tuthan.paddock.wake

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath
import io.github.tuthan.paddock.ports.Sockets
import io.github.tuthan.paddock.ports.TcpConnection
import io.github.tuthan.paddock.ports.UdpSender
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ip(s: String) = Ipv4Subnet.literal(s)!!
private fun lan(id: String = "wifi", address: String = "192.168.1.20", prefix: Int = 24, gateways: List<String> = listOf("192.168.1.1")) =
    LanPath(id, id, tunnel = false, subnets = listOf(Ipv4Subnet(ip(address), prefix)), gateways = gateways.map(::ip))
private fun tunnel(id: String = "wg") = LanPath(id, id, tunnel = true, subnets = listOf(Ipv4Subnet(ip("10.8.0.2"), 24)), gateways = listOf(ip("10.8.0.1")))
private val TARGET = WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 1)

class MagicPacketTest {
    @Test fun itIsSixFFThenTheMacSixteenTimes() {
        val b = MagicPacket.bytes("02:00:5e:10:00:01")
        assertEquals(102, b.size)
        assertTrue(b.take(6).all { it == 0xff.toByte() })
        val mac = byteArrayOf(0x02, 0x00, 0x5e, 0x10, 0x00, 0x01)
        for (i in 0 until 16) assertEquals(mac.toList(), b.slice(6 + i * 6 until 12 + i * 6))
    }

    @Test fun aBadMacDoesNotBuild() {
        for (bad in listOf("", "0200.5e10.0001", "02:00:5E:10:00:01", "02:00:5e:10:00", "02:00:5e:10:00:zz", "0200-5e10-0001"))
            assertFailsWith<IllegalArgumentException>(bad) { MagicPacket.bytes(bad) }
    }
}

class WakeRelayTest {
    @Test fun anIpv4LiteralWithAnOptionalPort() {
        assertEquals(WakeRelay("192.168.1.1", 9), WakeRelay.parse("192.168.1.1"))
        assertEquals(WakeRelay("10.8.0.1", 9009), WakeRelay.parse(" 10.8.0.1:9009 "))
        assertEquals("10.8.0.1:9009", WakeRelay.parse("10.8.0.1:9009")!!.format())
        assertEquals("10.8.0.1", WakeRelay.parse("10.8.0.1:9")!!.format())
    }

    @Test fun namesIpv6WildcardsLoopbackMulticastBroadcastAndBadPortsAreRefused() {
        for (bad in listOf("relay.example.net", "::1", "0.0.0.0", "127.0.0.1", "224.0.0.1", "239.1.1.1", "255.255.255.255", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:", "1.2.3.4:x", "1.2.3", ""))
            assertNull(WakeRelay.parse(bad), bad)
        assertFailsWith<IllegalArgumentException> { WakeRelay("relay.example.net") }
    }

    @Test fun theSuggestionIsTheLanGatewayAndNeverATunnelsOrAnUnusableOne() {
        assertEquals(WakeRelay("192.168.1.1"), WakeRelay.suggestionFrom(listOf(tunnel(), lan())))
        assertNull(WakeRelay.suggestionFrom(listOf(tunnel())))
        assertNull(WakeRelay.suggestionFrom(listOf(lan(gateways = emptyList()))))
    }
}

class WakePathsTest {
    @Test fun theNetworkHoldingTheMachineComesFirstThenTunnels() {
        val other = lan("eth", "10.0.0.5")
        val c = WakePaths.candidates(listOf(tunnel(), other, lan()), "192.168.1.50", null)
        assertEquals(listOf("wifi", "wg"), c.map { it.id })
    }

    @Test fun aMachineOnNoneOfThemStillTriesATunnelOnly() {
        assertEquals(listOf("wg"), WakePaths.candidates(listOf(lan(), tunnel()), "100.64.0.9", null).map { it.id })
    }

    @Test fun aNameTriesEveryLanInThePhonesOrderWhenNoneIsNamedLikeTheMachinesInterface() {
        // The machine's interface (wlp0s20f3) and the phone's (eth0, wlan0) are two devices' names: in practice they differ.
        val c = WakePaths.candidates(listOf(lan("eth0", "10.0.0.5"), lan("wlan0")), "box.example.net", "wlp0s20f3")
        assertEquals(listOf("eth0", "wlan0"), c.map { it.id })
    }

    @Test fun aNetworkNamedLikeTheMachinesInterfaceIsOnlyATiebreak() {
        // A Raspberry Pi and a phone both say wlan0: with two LANs and only a name to go on, that one is tried first.
        val c = WakePaths.candidates(listOf(lan("eth0", "10.0.0.5"), lan("wlan0")), "pi.local", "wlan0")
        assertEquals(listOf("wlan0", "eth0"), c.map { it.id })
    }

    @Test fun aNameWithAnAlwaysOnVpnStillTriesTheLanBeforeTheTunnel() {
        val c = WakePaths.candidates(listOf(tunnel(), lan("wlan0")), "desktop.local", "wlp0s20f3")
        assertEquals(listOf("wlan0", "wg"), c.map { it.id })
    }

    @Test fun aNameWithAVpnUpIsBroadcastOnTheLanWithoutARelay() = runBlocking {
        val s = RecordingUdp()
        val c = WakePaths.candidates(listOf(tunnel(), lan("wlan0")), "desktop.local", "wlp0s20f3")
        val r = WakeSender(s, packets = 1, gapMillis = 0).send(TARGET, c, null)
        assertTrue(r is WakeSendResult.Sent && !r.viaRelay, r.toString())
        assertTrue(s.sent.isNotEmpty() && s.sent.all { it == "wlan0" })
    }

    @Test fun aNameWithOnlyATunnelTriesTheTunnel() {
        assertEquals(listOf("wg"), WakePaths.candidates(listOf(tunnel()), "desktop.local", null).map { it.id })
    }

    @Test fun anIpv4AddressOnNoneOfTheNetworksIsNeverBroadcastToAnUnrelatedLan() {
        // The machine's own 192.168.1.x while the phone is on a hotel's 10.20.0.x: a broadcast there cannot reach it.
        assertEquals(emptyList(), WakePaths.candidates(listOf(lan("wlan0", "10.20.0.5")), "192.168.1.50", null))
        assertEquals(listOf("wg"), WakePaths.candidates(listOf(lan("wlan0", "10.20.0.5"), tunnel()), "192.168.1.50", null).map { it.id })
    }

    private val relay = WakeRelay("10.8.0.1")

    @Test fun wakeIsOfferedOnALanHoldingTheMachineWithOrWithoutARelay() {
        val c = WakePaths.candidates(listOf(lan("wlan0")), "192.168.1.50", null)
        assertTrue(WakePaths.canSend(c, null))
        assertTrue(WakePaths.canSend(c, relay))
    }

    @Test fun aSavedRelayAloneOffersNothingWhenNoTunnelCanCarryIt() {
        // On cellular (no path at all) or a non-VPN network away from the machine's LAN the relay is never used.
        assertFalse(WakePaths.canSend(WakePaths.candidates(emptyList(), "192.168.1.50", null), relay))
        assertFalse(WakePaths.canSend(WakePaths.candidates(listOf(lan("wlan0", "10.20.0.5")), "192.168.1.50", null), relay))
    }

    @Test fun aTunnelOffersWakeOnlyWithARelaySaved() {
        val c = WakePaths.candidates(listOf(lan("wlan0", "10.20.0.5"), tunnel()), "192.168.1.50", null)
        assertFalse(WakePaths.canSend(c, null))
        assertTrue(WakePaths.canSend(c, relay))
    }

    @Test fun aLanWithoutAnIpv4AddressCannotSend() {
        val v6 = LanPath("wlan0", "wlan0", tunnel = false, subnets = emptyList())
        assertFalse(WakePaths.canSend(WakePaths.candidates(listOf(v6), "desktop.local", null), relay))
    }

    @Test fun aNameOnTheLanIsOfferedWakeEvenWithAnAlwaysOnVpnAndNoRelay() {
        assertTrue(WakePaths.canSend(WakePaths.candidates(listOf(tunnel(), lan("wlan0")), "desktop.local", null), null))
    }

    @Test fun anIpv4AddressOnNoneOfTheNetworksSendsNothingAndSaysSo() = runBlocking {
        val s = RecordingUdp()
        val c = WakePaths.candidates(listOf(lan("wlan0", "10.20.0.5")), "192.168.1.50", null)
        assertEquals(WakeSendResult.Failed(WakeSendFailure.NetworkMissing), WakeSender(s, packets = 1, gapMillis = 0).send(TARGET, c, null))
        assertEquals(0, s.opened)
    }
}

/** Records the path id of each datagram, for the path-selection tests. */
private class RecordingUdp : Sockets {
    val sent = mutableListOf<String?>()
    var opened = 0
    override fun tcp(): TcpConnection = error("not used")
    override fun udp(path: LanPath?): UdpSender {
        opened++
        return object : UdpSender {
            override fun send(payload: ByteArray, address: InetAddress, port: Int, broadcast: Boolean) { sent += path?.id }
            override fun close() {}
        }
    }
}

class WakeTargetTest {
    @Test fun anAvailableTargetNeedsAMacAndAnInterface() {
        assertFailsWith<IllegalArgumentException> { WakeTarget(available = true, capturedAtMillis = 1) }
        assertFailsWith<IllegalArgumentException> { WakeTarget(available = true, mac = "02:00:5e:10:00:01", capturedAtMillis = 1) }
        assertNotNull(TARGET)
        assertEquals("no physical interface", WakeTarget.unavailable("no physical interface", 5).reason)
    }

    @Test fun badValuesFailToLoadInsteadOfHalfLoading() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val good = json.encodeToString(WakeTarget.serializer(), TARGET)
        assertEquals(TARGET, json.decodeFromString(WakeTarget.serializer(), good))
        for ((from, to) in listOf("02:00:5e:10:00:01" to "02:00", "wlp0s20f3" to "has space", "\"relay\":null" to "\"relay\":{\"address\":\"nope\"}"))
            assertFailsWith<IllegalArgumentException>(to) { json.decodeFromString(WakeTarget.serializer(), good.replace(from, to)) }
    }

    @Test fun readinessIsReadFromWordsNotAssumed() {
        assertEquals(WakeReadiness.Verdict.Ready, WakeReadiness(wakeup = "enabled", wowlan = "enabled: magic packet", wifi = true).verdict)
        assertEquals(WakeReadiness.Verdict.NotReady, WakeReadiness(wakeup = "enabled", wowlan = "disabled", wifi = true).verdict)
        assertEquals(WakeReadiness.Verdict.NotReady, WakeReadiness(wakeup = "disabled", wowlan = "enabled: magic packet", wifi = true).verdict)
        assertEquals(WakeReadiness.Verdict.Unknown, WakeReadiness(wakeup = "enabled", wifi = true).verdict)
        assertEquals(WakeReadiness.Verdict.Ready, WakeReadiness(wakeup = "enabled", ethtool = "g").verdict)
        assertEquals(WakeReadiness.Verdict.NotReady, WakeReadiness(wakeup = "enabled", ethtool = "d").verdict)
        assertEquals(WakeReadiness.Verdict.Unknown, WakeReadiness(ethtool = "unknown: ethtool is not installed").verdict)
        assertEquals(WakeReadiness.Verdict.Unknown, WakeReadiness().verdict)
    }
}

class WakeSenderTest {
    private class Sent(val payload: ByteArray, val address: InetAddress, val port: Int, val broadcast: Boolean, val pathId: String?)

    private class FakeSockets(val failOn: Set<String> = emptySet(), val failOpen: Boolean = false, val openError: String = "no socket") : Sockets {
        val sent = mutableListOf<Sent>()
        var opened = 0
        override fun tcp(): TcpConnection = error("not used")
        override fun udp(path: LanPath?): UdpSender {
            opened++
            if (failOpen) throw IOException(openError)
            return object : UdpSender {
                override fun send(payload: ByteArray, address: InetAddress, port: Int, broadcast: Boolean) {
                    if (address.hostAddress in failOn) throw IOException("sendto failed: EPERM")
                    sent += Sent(payload, address, port, broadcast, path?.id)
                }
                override fun close() {}
            }
        }
    }

    private fun sender(s: Sockets, permissionMissing: () -> Boolean = { false }) = WakeSender(s, permissionMissing, packets = 3, gapMillis = 0)

    @Test fun aLanSendGoesToTheDirectedAndTheLimitedBroadcastThreeTimesOnPort9() = runBlocking {
        val s = FakeSockets()
        val r = sender(s).send(TARGET, listOf(lan()), null)
        assertTrue(r is WakeSendResult.Sent && !r.viaRelay)
        assertEquals(setOf("192.168.1.255", "255.255.255.255"), r.destinations.map { it.hostAddress }.toSet())
        assertEquals(6, s.sent.size)
        assertTrue(s.sent.all { it.port == 9 && it.broadcast && it.payload.contentEquals(MagicPacket.bytes(TARGET.mac!!)) && it.pathId == "wifi" })
    }

    @Test fun aTunnelWithoutARelaySendsNothingAndSaysTheRelayIsTheFix() = runBlocking {
        val s = FakeSockets()
        assertEquals(WakeSendResult.Failed(WakeSendFailure.RelayRequired), sender(s).send(TARGET, listOf(tunnel()), null))
        assertEquals(0, s.opened)
    }

    @Test fun aTunnelWithARelaySendsOnlyUnicastToTheRelayOnItsPort() = runBlocking {
        val s = FakeSockets()
        val r = sender(s).send(TARGET, listOf(tunnel()), WakeRelay("10.8.0.1", 9009))
        assertTrue(r is WakeSendResult.Sent && r.viaRelay)
        assertEquals(3, s.sent.size)
        assertTrue(s.sent.all { it.address.hostAddress == "10.8.0.1" && it.port == 9009 && !it.broadcast })
    }

    @Test fun aLanPathIgnoresTheRelayAndStillBroadcasts() = runBlocking {
        val s = FakeSockets()
        sender(s).send(TARGET, listOf(lan()), WakeRelay("10.8.0.1"))
        assertTrue(s.sent.all { it.broadcast && it.address.hostAddress != "10.8.0.1" })
    }

    @Test fun aRelayNeedIsReportedAheadOfALanFailure() = runBlocking {
        val s = FakeSockets(failOpen = true)
        assertEquals(WakeSendResult.Failed(WakeSendFailure.RelayRequired), sender(s).send(TARGET, listOf(lan(), tunnel()), null))
    }

    @Test fun aPathThatCannotBeUsedDoesNotHideOneThatWorks() = runBlocking {
        val s = object : Sockets {
            val real = FakeSockets()
            override fun tcp() = error("no")
            override fun udp(path: LanPath?): UdpSender = if (path?.id == "bad") throw IOException("refused") else real.udp(path)
        }
        val r = sender(s).send(TARGET, listOf(lan("bad"), lan("good")), null)
        assertTrue(r is WakeSendResult.Sent)
        assertTrue(s.real.sent.all { it.pathId == "good" })
    }

    @Test fun aMissingGrantStopsBeforeAnySocketIsOpened() = runBlocking {
        val s = FakeSockets()
        assertEquals(WakeSendResult.Failed(WakeSendFailure.Permission), sender(s) { true }.send(TARGET, listOf(lan()), null))
        assertEquals(0, s.opened)
    }

    @Test fun oneRefusedDestinationDoesNotStopTheOtherAndIsReportedAsPartial() = runBlocking {
        val s = FakeSockets(failOn = setOf("255.255.255.255"))
        val r = sender(s).send(TARGET, listOf(lan()), null)
        assertTrue(r is WakeSendResult.Partial && r.reason == WakeSendFailure.SendFailed, r.toString())
        assertEquals(3, s.sent.size)
        assertTrue(s.sent.all { it.address.hostAddress == "192.168.1.255" })
    }

    @Test fun everySendRefusedWithEpermIsTheLocalNetworkGrantBeingOffNotAGenericFailure() = runBlocking {
        // Spike S3 (API 37): with the grant off every send throws IOException "sendto failed: EPERM" (not a SecurityException), unicast and broadcast alike.
        val s = FakeSockets(failOn = setOf("192.168.1.255", "255.255.255.255"))
        val r = sender(s).send(TARGET, listOf(lan()), null)
        assertTrue(r is WakeSendResult.Failed && r.reason == WakeSendFailure.Permission, r.toString())
    }

    @Test fun aSocketThatCannotBeOpenedWithEpermIsTheGrantToo() = runBlocking {
        val r = sender(FakeSockets(failOpen = true, openError = "socket failed: EPERM (Operation not permitted)")).send(TARGET, listOf(lan()), null)
        assertTrue(r is WakeSendResult.Failed && r.reason == WakeSendFailure.Permission, r.toString())
        // Any other open failure stays a send failure.
        val other = sender(FakeSockets(failOpen = true)).send(TARGET, listOf(lan()), null)
        assertTrue(other is WakeSendResult.Failed && other.reason == WakeSendFailure.SendFailed, other.toString())
    }

    @Test fun aTargetThatIsNotAvailableSendsNothing() = runBlocking {
        val s = FakeSockets()
        assertEquals(WakeSendResult.Failed(WakeSendFailure.TargetMissing), sender(s).send(WakeTarget.unavailable("none", 1), listOf(lan()), null))
        assertEquals(0, s.opened)
    }

    @Test fun anIpv6OnlyLanHasNowhereToBroadcast() = runBlocking {
        val v6 = LanPath("wifi", "wifi", tunnel = false, subnets = emptyList())
        assertEquals(WakeSendResult.Failed(WakeSendFailure.NetworkMissing), sender(FakeSockets()).send(TARGET, listOf(v6), null))
    }
}
