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

    @Test fun aNameLosesControlFormatAndSeparatorCharactersThatReorderOrHideText() {
        // C1 controls, left-to-right and right-to-left marks and overrides, isolates, the Arabic letter mark, the byte order mark and the
        // line and paragraph separators: none is a letter, and several make the text after them read as something else.
        for (c in listOf("\u0085", "\u009b", "‎", "‏", "‪", "‫", "‬", "‭", "‮", "⁦", "⁧", "⁨", "⁩", "؜", "﻿", "​", " ", " ")) {
            assertEquals("ab", FoundHosts.cleanName("a${c}b"), "U+%04X".format(c[0].code))
        }
        assertEquals("evilname", FoundHosts.cleanName("evil‮name"))
        assertEquals("my desktop 😀", FoundHosts.cleanName("my desktop 😀"), "a pair of surrogates is one character and stays")
    }

    @Test fun aLoneSurrogateIsDroppedAndNeverReachesTheProfileName() {
        assertEquals("ab", FoundHosts.cleanName("a\uD800b"))
        assertEquals("ab", FoundHosts.cleanName("a\uDC00b"))
        assertEquals("ab", FoundHosts.cleanName("a\uDE00\uD83Db"), "a low surrogate before a high one is two lone halves")
        assertNull(FoundHosts.cleanName("\uD800"))
    }

    @Test fun aLongNameIsCutByCharactersNotInsideASurrogatePair() {
        val grin = "😀"
        val cut = FoundHosts.cleanName("a".repeat(63) + grin + "b")!!
        assertEquals("a".repeat(63) + grin, cut)
        assertEquals(64, cut.codePointCount(0, cut.length))
        val many = FoundHosts.cleanName(grin.repeat(100))!!
        assertEquals(64, many.codePointCount(0, many.length))
        for (i in many.indices) if (many[i].isHighSurrogate()) assertTrue(many[i + 1].isLowSurrogate())
        assertEquals("a".repeat(63), FoundHosts.cleanName("a".repeat(63) + " ".repeat(10) + "b"), "a space left at the cut is trimmed")
    }

    @Test fun aNameThatIsOnlyBlankOrInvisibleMakesNoRow() {
        for (blank in listOf("   ", "  ", "‎‏", "‮", "   ", "﻿ ", "\u0085", "\uD800")) {
            assertNull(FoundHosts.cleanName(blank), blank.map { "U+%04X".format(it.code) }.toString())
        }
        val f = FoundHosts(); assertFalse(f.add(svc("10.0.2.2", "‎ ‮")))
        assertTrue(f.list.isEmpty())
    }

    @Test fun theListHoldsAtMostItsCapAndAnAddressAlreadyThereCanStillBeNamed() {
        val f = FoundHosts(maxRows = 3)
        for (i in 1..3) assertTrue(f.add(hit("10.0.0.$i")))
        assertFalse(f.add(hit("10.0.0.4")), "a fourth row is not made")
        assertFalse(f.add(svc("10.0.0.5", "late")))
        assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), f.list.map { it.address })
        assertTrue(f.add(svc("10.0.0.2", "second")), "a row that exists still takes its name")
        assertEquals("second", f.list[1].name)
        assertEquals(200, FoundHosts.MAX_ROWS)
    }

    @Test fun aWorkstationAnnouncementNamesTheAddressAndNeverMakesARowOfItsOwn() {
        // Avahi announces _workstation._tcp on port 9: it says whose address this is, not where SSH listens.
        val f = FoundHosts()
        assertFalse(f.add(NsdService("devbox", ip("192.168.42.86"), 9, nameOnly = true)))
        assertTrue(f.list.isEmpty(), "no row at port 9")
        assertTrue(f.add(hit("192.168.42.86", 22)), "the probe's row appears later and takes the name")
        assertEquals(listOf(FoundHost("192.168.42.86", 22, "devbox", "OpenSSH 9.9")), f.list)
    }

    @Test fun aWorkstationNameJoinsTheRowsAtThatAddressWhicheverCameFirst() {
        val f = FoundHosts()
        f.add(hit("10.0.0.7", 22)); f.add(hit("10.0.0.7", 2222)); f.add(hit("10.0.0.8", 22))
        assertTrue(f.add(NsdService("box", ip("10.0.0.7"), 9, nameOnly = true)))
        assertEquals(listOf("box", "box", null), f.list.map { it.name })
        assertFalse(f.add(NsdService("box", ip("10.0.0.7"), 9, nameOnly = true)), "the same again changes nothing")
        assertEquals(listOf(22, 2222, 22), f.list.map { it.port })
    }

    @Test fun anSshAnnouncementKeepsItsAddressAndPortAsItsIdentity() {
        val f = FoundHosts()
        f.add(NsdService("box", ip("10.0.0.7"), 9, nameOnly = true))
        f.add(svc("10.0.0.7", "box-ssh", 2222))
        assertEquals(listOf(FoundHost("10.0.0.7", 2222, "box-ssh", null)), f.list)
    }

    @Test fun rowsAddedFromManyThreadsWhileAnotherReadsAreNeitherLostNorCorrupt() {
        // The finder's probe and its mDNS listener add from different threads while the page reads the list.
        repeat(30) { round ->
            val f = FoundHosts(maxRows = 100_000)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = kotlin.concurrent.thread { try { while (!stop.get()) f.list } catch (t: Throwable) { failures += t } }
            val writers = (0 until 8).map { t ->
                kotlin.concurrent.thread {
                    try {
                        for (i in 1..250) {
                            f.add(hit("10.$t.0.$i"))
                            f.add(svc("10.$t.0.$i", "n$t-$i"))
                        }
                    } catch (e: Throwable) { failures += e }
                }
            }
            writers.forEach { it.join() }
            stop.set(true); reader.join()
            assertTrue(failures.isEmpty(), "round $round: ${failures.firstOrNull()}")
            assertEquals(2000, f.list.size, "round $round")
            assertEquals(2000, f.list.count { it.name != null && it.software != null }, "round $round")
        }
    }
}
