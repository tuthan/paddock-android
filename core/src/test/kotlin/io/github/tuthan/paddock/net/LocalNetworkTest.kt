package io.github.tuthan.paddock.net

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

class LocalNetworkTest {
    private fun assertClass(expected: EndpointClass, vararg hosts: String) =
        hosts.forEach { assertEquals(expected, classifyEndpoint(it), it) }

    @Test
    fun rfc1918AndLinkLocalV4AreLocal() = assertClass(
        EndpointClass.Local,
        "10.0.0.1", "10.255.255.254", "10.0.2.2", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.42.86", "169.254.1.1",
    )

    @Test
    fun justOutsideTheRangesIsNotLocal() = assertClass(
        EndpointClass.NotLocal,
        "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "169.253.0.1", "169.255.0.1",
    )

    @Test
    fun tailnetAndOtherVpnRangesAreNotLocal() = assertClass(
        EndpointClass.NotLocal, "100.64.0.1", "100.127.255.255", "100.101.102.103", "11.0.0.1",
    )

    @Test
    fun loopbackAndPublicAreNotLocal() = assertClass(EndpointClass.NotLocal, "127.0.0.1", "::1", "8.8.8.8", "1.1.1.1", "0.0.0.0")

    @Test
    fun ipv6LinkLocalAndUniqueLocalAreLocal() = assertClass(
        EndpointClass.Local, "fe80::1", "fe80::9b0c:5722:fef:68a4", "febf::1", "fc00::1", "fd12:3456:789a::1", "[fe80::1]", "fe80::1%wlan0",
    )

    @Test
    fun ipv6OutsideThoseRangesIsNotLocal() = assertClass(EndpointClass.NotLocal, "fec0::1", "2001:db8::1", "2606:4700::1111", "fb00::1")

    @Test
    fun ipv4MappedFollowsTheEmbeddedAddress() {
        assertClass(EndpointClass.Local, "::ffff:192.168.1.5", "::ffff:10.0.0.1")
        assertClass(EndpointClass.NotLocal, "::ffff:8.8.8.8", "::ffff:100.64.0.1")
    }

    @Test
    fun dotLocalNamesAreLocalAndOtherNamesAreNot() {
        assertClass(EndpointClass.Local, "devbox.local", "Printer.LOCAL", "a.b.local")
        assertClass(EndpointClass.NotLocal, "local", ".local", "example.com", "devbox", "my.local.example.com", "localhost")
    }

    @Test
    fun malformedInputIsNotLocalAndNeverResolves() = assertClass(
        EndpointClass.NotLocal, "", " ", "10.0.0", "10.0.0.256", "10.0.0.1.2", "999.1.1.1", "1:2:3", "fe80:::1", "not an address",
    )

    private fun resolver(vararg map: Pair<String, String>) = HostResolver { name ->
        map.filter { it.first == name }.map { InetAddress.getByName(it.second) }.ifEmpty { throw UnknownHostException(name) }
    }

    @Test
    fun aNameThatResolvesToALanAddressIsLocal() = runBlocking<Unit> {
        val r = resolver("nas.lan" to "192.168.1.20", "devbox" to "10.0.0.5", "x.home.arpa" to "fd00::1", "router" to "169.254.3.4")
        for (name in listOf("nas.lan", "devbox", "x.home.arpa", "router", "NAS.lan")) assertEquals(EndpointClass.Local, classifyResolved(name, r), name)
    }

    @Test
    fun aNameIsLocalWhenAnyOfItsAddressesIsLocal() = runBlocking<Unit> {
        val r = resolver("both" to "100.64.0.7", "both" to "192.168.0.2", "vpn-only" to "100.64.0.7", "public" to "93.184.216.34")
        assertEquals(EndpointClass.Local, classifyResolved("both", r))
        assertEquals(EndpointClass.NotLocal, classifyResolved("vpn-only", r))
        assertEquals(EndpointClass.NotLocal, classifyResolved("public", r))
    }

    @Test
    fun loopbackNamesAndUnresolvableNamesAreNotLocal() = runBlocking<Unit> {
        val r = resolver("localhost" to "127.0.0.1", "ip6-localhost" to "::1")
        assertEquals(EndpointClass.NotLocal, classifyResolved("localhost", r))
        assertEquals(EndpointClass.NotLocal, classifyResolved("ip6-localhost", r))
        assertEquals(EndpointClass.NotLocal, classifyResolved("nowhere.invalid", r), "a failed lookup")
        assertEquals(EndpointClass.NotLocal, classifyResolved("", r))
    }

    @Test
    fun literalsAndDotLocalNamesNeverTouchTheResolver() = runBlocking<Unit> {
        val refuses = HostResolver { fail("resolved $it") }
        assertEquals(EndpointClass.Local, classifyResolved("10.0.0.1", refuses))
        assertEquals(EndpointClass.Local, classifyResolved("printer.local", refuses))
        assertEquals(EndpointClass.Local, classifyResolved("[fe80::1]", refuses))
        assertEquals(EndpointClass.NotLocal, classifyResolved("8.8.8.8", refuses))
        assertEquals(EndpointClass.NotLocal, classifyResolved("100.64.0.1", refuses))
        assertEquals(EndpointClass.NotLocal, classifyResolved("2001:db8::1", refuses))
    }

    @Test
    fun aSlowLookupIsBoundedAndCountsAsNotLocal() = runBlocking<Unit> {
        val stuck = HostResolver { Thread.sleep(5_000); listOf(InetAddress.getByName("192.168.1.1")) }
        val t0 = System.nanoTime()
        assertEquals(EndpointClass.NotLocal, classifyResolved("slow.lan", stuck, timeout = 200.milliseconds))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_500, "took $ms ms")
    }

    @Test
    fun theTimeoutHintAppliesOnlyWhereTheGrantIsEnforcedAndMissing() {
        assertTrue(LocalNetworkPolicy.timeoutHint(37, 37, granted = false))
        assertFalse(LocalNetworkPolicy.timeoutHint(37, 37, granted = true))
        assertFalse(LocalNetworkPolicy.timeoutHint(36, 37, granted = false))
        assertFalse(LocalNetworkPolicy.timeoutHint(37, 36, granted = false))
        assertTrue(LocalNetworkPolicy.enforced(38, 37)); assertFalse(LocalNetworkPolicy.enforced(37, 26))
    }

    @Test
    fun policyRequiresTheGrantOnlyForLocalOnAndroid17WithTargetSdk37() {
        val local = EndpointClass.Local
        assertEquals(GateDecision.NeedsGrant, LocalNetworkPolicy.decide(37, 37, local, granted = false))
        assertEquals(GateDecision.Granted, LocalNetworkPolicy.decide(37, 37, local, granted = true))
        assertEquals(GateDecision.NotRequired, LocalNetworkPolicy.decide(36, 37, local, granted = false))
        assertEquals(GateDecision.NotRequired, LocalNetworkPolicy.decide(37, 36, local, granted = false))
        assertEquals(GateDecision.NotRequired, LocalNetworkPolicy.decide(37, 37, EndpointClass.NotLocal, granted = false))
        assertEquals(GateDecision.NeedsGrant, LocalNetworkPolicy.decide(38, 38, local, granted = false))
    }
}
