package io.github.tuthan.paddock.discovery

import io.github.tuthan.paddock.net.Ipv4Subnet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundHostsTest {
    private fun ip(s: String) = Ipv4Subnet.literal(s)!!
    private fun hit(a: String, port: Int = 22, banner: String = "SSH-2.0-OpenSSH_9.9p1 Debian") = ProbeHit(ip(a), port, banner)
    private fun svc(a: String, name: String, port: Int = 22) = NsdService(name, ip(a), port)

    @Test fun aProbedAddressIsARowWithItsSoftware() {
        val f = FoundHosts(); f.add(hit("192.168.42.86"))
        assertEquals(listOf(FoundHost("192.168.42.86", 22, null, "OpenSSH 9.9")), f.list)
        assertEquals("192.168.42.86 · OpenSSH 9.9", f.list.single().label)
    }

    @Test fun anAnnouncedNameJoinsTheRowOfTheSameAddressAndPort() {
        val f = FoundHosts(); f.add(hit("192.168.42.86")); f.add(svc("192.168.42.86", "devbox"))
        assertEquals("192.168.42.86 · devbox · OpenSSH 9.9", f.list.single().label)
    }

    @Test fun theOrderIsTheOrderOfFirstAppearanceWhicheverSourceCameSecond() {
        val f = FoundHosts()
        f.add(svc("192.168.42.20", "nas")); f.add(hit("192.168.42.86")); f.add(hit("192.168.42.20"))
        assertEquals(listOf("192.168.42.20", "192.168.42.86"), f.list.map { it.address })
        assertEquals("OpenSSH 9.9", f.list.first().software)
    }

    @Test fun anAnnouncementAloneIsAHostWithoutSoftware() {
        val f = FoundHosts(); f.add(svc("192.168.42.20", "nas", 2222))
        assertEquals(FoundHost("192.168.42.20", 2222, "nas", null), f.list.single())
        assertEquals("192.168.42.20 · nas · port 2222", f.list.single().labelWithPort)
    }

    @Test fun twoPortsOnOneAddressAreTwoRows() {
        val f = FoundHosts(); f.add(hit("10.0.2.2", 22)); f.add(hit("10.0.2.2", 2233))
        assertEquals(listOf(22, 2233), f.list.map { it.port })
    }

    @Test fun addingTheSameThingAgainChangesNothing() {
        val f = FoundHosts()
        assertTrue(f.add(hit("10.0.2.2")))
        assertFalse(f.add(hit("10.0.2.2")))
        assertTrue(f.add(svc("10.0.2.2", "box")))
        assertFalse(f.add(svc("10.0.2.2", "box")))
        assertEquals(1, f.list.size)
    }

    @Test fun theFirstNameStaysWhenAnotherArrives() {
        val f = FoundHosts(); f.add(svc("10.0.2.2", "first")); f.add(svc("10.0.2.2", "second"))
        assertEquals("first", f.list.single().name)
    }

    @Test fun aNameFromTheNetworkIsCleanedAndAnEmptyOneMakesNoRow() {
        assertEquals("evil name", FoundHosts.cleanName("evil\u0000\u001b name\n"))
        assertEquals(64, FoundHosts.cleanName("n".repeat(200))!!.length)
        assertNull(FoundHosts.cleanName("\u0001\u0002 "))
        val f = FoundHosts(); assertFalse(f.add(svc("10.0.2.2", "\u0001")))
        assertTrue(f.list.isEmpty())
    }
}
