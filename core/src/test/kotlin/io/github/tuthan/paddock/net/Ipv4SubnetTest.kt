package io.github.tuthan.paddock.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ipv4SubnetTest {
    private fun ip(s: String) = Ipv4Subnet.literal(s)!!
    private fun subnet(address: String, prefix: Int) = Ipv4Subnet(ip(address), prefix)

    @Test fun aSlash24HoldsTheOtherTwoHundredAndFiftyThreeAddresses() {
        val hosts = subnet("192.168.1.20", 24).hosts()
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first().hostAddress)
        assertEquals("192.168.1.254", hosts.last().hostAddress)
        assertFalse(ip("192.168.1.20") in hosts, "the phone's own address is left out")
        assertFalse(ip("192.168.1.0") in hosts)
        assertFalse(ip("192.168.1.255") in hosts)
    }

    @Test fun aSmallerNetworkHoldsFewerAndASlash30HoldsOneOther() {
        assertEquals(125, subnet("10.0.0.5", 25).hosts().size)
        assertEquals(1, subnet("10.0.0.1", 30).hosts().size)
        assertEquals(0, subnet("10.0.0.1", 31).hosts().size)
        assertEquals(0, subnet("10.0.0.1", 32).hosts().size)
    }

    @Test fun aNetworkWiderThanASlash24IsNeverEnumerated() {
        assertFailsWith<IllegalArgumentException> { subnet("10.1.2.3", 23).hosts() }
        assertFailsWith<IllegalArgumentException> { subnet("10.1.2.3", 16).hosts() }
        assertFalse(subnet("10.1.2.3", 16).probeable)
        assertTrue(subnet("10.1.2.3", 24).probeable)
    }

    @Test fun networkAndDirectedBroadcastFollowThePrefix() {
        val s = subnet("192.168.42.86", 24)
        assertEquals("192.168.42.0", s.network.hostAddress)
        assertEquals("192.168.42.255", s.directedBroadcast.hostAddress)
        assertEquals("10.0.3.255", subnet("10.0.2.9", 23).directedBroadcast.hostAddress)
        assertEquals("192.168.1.127", subnet("192.168.1.100", 25).directedBroadcast.hostAddress)
    }

    @Test fun containsIsDecidedByThePrefixOnly() {
        val s = subnet("192.168.1.20", 24)
        assertTrue(s.contains(ip("192.168.1.200")))
        assertFalse(s.contains(ip("192.168.2.1")))
        assertFalse(s.contains(java.net.InetAddress.getByName("::1")))
    }

    @Test fun literalAcceptsOnlyFourDecimalOctets() {
        assertNotNull(Ipv4Subnet.literal("0.0.0.0"))
        for (bad in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "a.b.c.d", "1..2.3", "1.2.3.4 ", "01234.1.1.1", "box.example.net", "::1", "1.2.3.-4")) {
            assertNull(Ipv4Subnet.literal(bad), bad)
        }
    }

    @Test fun anInvalidPrefixDoesNotConstruct() {
        assertFailsWith<IllegalArgumentException> { subnet("1.2.3.4", 33) }
        assertFailsWith<IllegalArgumentException> { subnet("1.2.3.4", -1) }
        assertEquals("192.168.1.0/24", subnet("192.168.1.20", 24).toString())
        assertEquals("255.255.255.255", Ipv4Subnet.fromValue(0xffff_ffffL).hostAddress)
    }
}
