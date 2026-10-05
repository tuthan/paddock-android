package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ports.LanPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinderRulesTest {
    private fun path(id: String, prefix: Int = 24, tunnel: Boolean = false, ipv4: Boolean = true) =
        LanPath(id, id, tunnel, if (ipv4) listOf(Ipv4Subnet(Ipv4Subnet.literal("192.168.1.20")!!, prefix)) else emptyList())

    @Test fun aWifiNetworkIsReady() {
        val d = FinderRules.decide(listOf(path("wifi")), grantMissing = false)
        assertTrue(d is FinderDecision.Ready && d.subnet.prefix == 24)
    }

    @Test fun aVpnAloneOrNothingIsNoLan() {
        assertEquals(FinderDecision.NoLan, FinderRules.decide(emptyList(), false))
        assertEquals(FinderDecision.NoLan, FinderRules.decide(listOf(path("wg", tunnel = true)), false))
    }

    @Test fun aLanWithoutAnIpv4AddressIsIpv6Only() {
        assertEquals(FinderDecision.Ipv6Only, FinderRules.decide(listOf(path("wifi", ipv4 = false)), false))
    }

    @Test fun aWideNetworkIsRefusedWithItsPrefix() {
        assertEquals(FinderDecision.TooWide(16), FinderRules.decide(listOf(path("wifi", prefix = 16)), false))
    }

    @Test fun aNetworkWithNothingToProbeSaysSoInsteadOfStartingAnEmptyScan() {
        assertEquals(FinderDecision.TooSmall(31), FinderRules.decide(listOf(path("wifi", prefix = 31)), false))
        assertEquals(FinderDecision.TooSmall(32), FinderRules.decide(listOf(path("wifi", prefix = 32)), false))
        // Before the grant too: asking for access that would then find nothing to probe is no help.
        assertEquals(FinderDecision.TooSmall(32), FinderRules.decide(listOf(path("wifi", prefix = 32)), grantMissing = true))
        assertTrue(FinderRules.decide(listOf(path("wifi", prefix = 30)), false) is FinderDecision.Ready, "a /30 holds one other address")
        val sentence = FinderRules.sentence(FinderDecision.TooSmall(32))
        assertTrue("no other address" in sentence && "/32" in sentence, sentence)
    }

    @Test fun aMissingGrantAsksBeforeAnythingRuns() {
        assertEquals(FinderDecision.NeedsGrant, FinderRules.decide(listOf(path("wifi")), grantMissing = true))
    }

    @Test fun aTunnelDoesNotHideTheLanBesideIt() {
        val d = FinderRules.decide(listOf(path("wg", tunnel = true), path("wifi")), false)
        assertTrue(d is FinderDecision.Ready && d.path.id == "wifi")
    }

    @Test fun thePortsAreTheSshDefaultAndTheTypedOne() {
        assertEquals(listOf(22), FinderRules.ports(null))
        assertEquals(listOf(22), FinderRules.ports(22))
        assertEquals(listOf(22, 2233), FinderRules.ports(2233))
        assertEquals(listOf(22), FinderRules.ports(70000))
    }

    @Test fun theSentenceBeforeTheScanNamesTheNetworkThePortsAndWhatItNeverDoes() {
        val s = FinderRules.beforeScan(Ipv4Subnet(Ipv4Subnet.literal("192.168.42.86")!!, 24), listOf(22, 2233))
        assertTrue("192.168.42.0/24" in s && "ports 22 and 2233" in s && "trusts and stores nothing" in s && "intrusion-detection" in s, s)
        assertTrue("port 22" in FinderRules.beforeScan(Ipv4Subnet(Ipv4Subnet.literal("10.0.0.2")!!, 24), listOf(22)))
    }
}
