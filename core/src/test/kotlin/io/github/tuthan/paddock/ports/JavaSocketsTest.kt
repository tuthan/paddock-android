package io.github.tuthan.paddock.ports

import java.io.IOException
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaSocketsTest {
    @Test fun aBinderThatThrowsDoesNotLeaveTheSocketOpen() {
        // A scan makes hundreds of sockets: one whose bind fails must give its descriptor back.
        val seen = mutableListOf<Socket>()
        val sockets = JavaSockets(bindTcp = { seen += it; throw IOException("the network is gone") })
        repeat(3) { assertFailsWith<IOException> { sockets.tcp() } }
        assertEquals(3, seen.size)
        assertTrue(seen.all { it.isClosed }, "every socket whose bind failed is closed")
    }

    @Test fun aBinderThatWorksLeavesTheSocketForTheCaller() {
        var seen: Socket? = null
        JavaSockets(bindTcp = { seen = it }).tcp().use { assertTrue(!seen!!.isClosed) }
        assertTrue(seen!!.isClosed)
    }

    @Test fun aPinWithoutANetworkFailsInsteadOfFollowingTheDefaultRoute() {
        var bound: String? = null
        assertFailsWith<IOException> { NetworkPin.apply("wifi", { null }) { n: String -> bound = n } }
        assertEquals(null, bound, "nothing was bound")
        NetworkPin.apply("wifi", { "net-for-$it" }) { n: String -> bound = n }
        assertEquals("net-for-wifi", bound)
    }

    @Test fun noPinMeansTheLookupIsNeverAskedAndNothingIsBound() {
        var asked = false
        NetworkPin.apply(null, { asked = true; "n" }) { _: String -> throw AssertionError("bound without a pin") }
        assertTrue(!asked)
    }

    @Test fun aPinThatFailsOnTcpClosesTheSocketToo() {
        val seen = mutableListOf<Socket>()
        val sockets = JavaSockets(bindTcp = { seen += it; NetworkPin.apply("wifi", { _: String -> null }) { _: String -> } })
        assertFailsWith<IOException> { sockets.tcp() }
        assertTrue(seen.single().isClosed)
    }

    // What Android throws when the VPN does not select the app (seen on the real phone through WireGuard, in the SteamOS companion app, 2026-09-30).
    private fun refused(): Nothing = throw java.net.SocketException("Binding socket to network 196: EPERM (Operation not permitted)")

    @Test fun aRefusedBindToATunnelLeavesTheUdpSocketUnboundSoTheWakePacketCanFollowTheDefaultNetwork() {
        var seen: java.net.DatagramSocket? = null
        val sockets = JavaSockets(bindUdp = { s, p -> seen = s; NetworkPin.apply(p?.id, { "net" }, tolerateRefusal = p?.tunnel == true) { _: String -> refused() } })
        sockets.udp(LanPath("wg", "tun0", true, emptyList())).use { assertTrue(seen!!.isBound && !seen!!.isClosed, "a usable, unbound-to-a-network socket") }
    }

    @Test fun aRefusedBindToALanPathStillFailsBecauseFollowingTheDefaultNetworkCouldLeaveThroughAVpn() {
        var seen: java.net.DatagramSocket? = null
        val sockets = JavaSockets(bindUdp = { s, p -> seen = s; NetworkPin.apply(p?.id, { "net" }, tolerateRefusal = p?.tunnel == true) { _: String -> refused() } })
        assertFailsWith<IOException> { sockets.udp(LanPath("wifi", "wlan0", false, emptyList())) }
        assertTrue(seen!!.isClosed)
    }

    @Test fun aTunnelThatHasGoneStillFailsEvenWhenARefusalIsTolerated() {
        assertFailsWith<IOException> { NetworkPin.apply("wg", { _: String -> null }, tolerateRefusal = true) { _: String -> } }
    }

    @Test fun aPinThatFailsOnUdpClosesTheSocketAndThrows() {
        var seen: java.net.DatagramSocket? = null
        val sockets = JavaSockets(bindUdp = { s, _ -> seen = s; NetworkPin.apply("wifi", { _: String -> null }) { _: String -> } })
        assertFailsWith<IOException> { sockets.udp(LanPath("wifi", "wlan0", false, emptyList())) }
        assertTrue(seen!!.isClosed)
    }
}
