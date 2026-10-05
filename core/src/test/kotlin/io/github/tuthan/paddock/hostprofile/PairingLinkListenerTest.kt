package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.wake.WakeRelay
import io.github.tuthan.paddock.wake.WakeTarget
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingLinkListenerTest {
    private val fp = "SHA256:7P9Ky1rZCc/vtp/cdHHe8SLbijox5h43vGMFvUE0y/g"
    private val base = "paddock://pair?v=1&host=box&port=22&user=jdoe&fp=$fp"
    private val sid = "abcdefghijklmnopqrstuv"

    private fun valid(text: String) = (PairingLinks.parse(text) as PairingResult.Valid).link
    private fun rejected(text: String) = PairingLinks.parse(text) as PairingResult.Rejected

    @Test fun aLinkWithoutTheListenerParametersIsUnchanged() {
        val l = valid(base)
        assertNull(l.pairPort); assertNull(l.sid)
    }

    @Test fun theListenerPortAndHandleAreReadTogether() {
        val l = valid("$base&pair=40123&sid=$sid")
        assertEquals(40123, l.pairPort)
        assertEquals(sid, l.sid)
        assertEquals("$base&pair=40123&sid=$sid", PairingLinks.build(l))
    }

    @Test fun oneWithoutTheOtherIsRefusedNamingTheMissingOne() {
        assertEquals(PairingRejection.MissingField to "sid", rejected("$base&pair=40123").let { it.reason to it.field })
        assertEquals(PairingRejection.MissingField to "pair", rejected("$base&sid=$sid").let { it.reason to it.field })
    }

    @Test fun badValuesAreRefusedPerField() {
        for (port in listOf("0", "65536", "abc", "-1", "", "123456")) assertEquals("pair", rejected("$base&pair=$port&sid=$sid").field, port)
        for (bad in listOf("short", "a".repeat(23), "abcdefghijklmnopqrstu!", "abcdefghijklmnopqrstu%20")) assertEquals("sid", rejected("$base&pair=40123&sid=$bad").field, bad)
        assertEquals(PairingRejection.DuplicateField, rejected("$base&pair=1&pair=2&sid=$sid").reason)
    }

    @Test fun anOlderShapeOfLinkStillFitsTheLengthAndParameterBounds() {
        val four = (1..4).joinToString(",") { "SHA256:" + "A".repeat(42) + it }
        assertTrue(PairingLinks.parse("paddock://pair?v=1&host=box&port=22&user=jdoe&fp=$four&session=work&pair=40123&sid=$sid") is PairingResult.Valid)
    }
}

class HostProfileWakeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun aProfileWithoutWakeLoadsAndOneWithItRoundTrips() {
        val old = """{"id":"box","name":"box","host":"box","port":22,"user":"jdoe"}"""
        assertNull(json.decodeFromString(HostProfile.serializer(), old).wake)
        val withWake = HostProfile("box", "box", "box", 22, "jdoe", wake = WakeTarget(true, "02:00:5e:10:00:01", "wlp0s20f3", "192.168.1.86", 5, relay = WakeRelay("192.168.1.1")))
        assertEquals(withWake, json.decodeFromString(HostProfile.serializer(), json.encodeToString(HostProfile.serializer(), withWake)))
    }

    @Test fun aBadWakeValueFailsTheWholeProfileToLoad() {
        val bad = """{"id":"box","name":"box","host":"box","port":22,"user":"jdoe","wake":{"available":true,"mac":"nope","iface":"x"}}"""
        val failed = runCatching { json.decodeFromString(HostProfile.serializer(), bad) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException, failed.toString())
    }
}
