package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.net.Ipv4Subnet
import io.github.tuthan.paddock.ui.screens.RelayEntry
import io.github.tuthan.paddock.ui.screens.relayEntry
import io.github.tuthan.paddock.wake.WakeFacts
import io.github.tuthan.paddock.wake.WakeReadiness
import io.github.tuthan.paddock.wake.WakeRelay
import io.github.tuthan.paddock.wake.WakeSendResult
import io.github.tuthan.paddock.wake.WakeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordsMapperTest {
    private val label = { m: Long -> "t$m" }
    private fun profile(wake: WakeTarget?) = HostProfile("laptop", "Laptop", "192.168.42.86", 22, "jdoe", wake = wake)
    private val ready = WakeTarget(
        available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 5, gateway = "192.168.42.1",
        readiness = WakeReadiness(wakeup = "enabled", wowlan = "enabled: magic packet", wifi = true, phy = "phy0"),
    )
    private fun words(p: HostProfile, facts: WakeFacts? = null, now: Long = 100_000, ready: Boolean = true, phone: WakeRelay? = null) =
        WakeWordsMapper.words(p, facts, now, ready, phone, label)

    @Test fun aMachineNotReadYetSaysSoAndHasNothingToCopy() {
        val w = words(profile(null), ready = false)
        assertEquals(WakeWordsMapper.NOT_READ, w.status)
        assertTrue(w.commands.isEmpty())
        assertFalse(w.canWake)
    }

    @Test fun aReadMachineShowsItsWordsAndTheCommandToKeepItAfterAReboot() {
        val w = words(profile(ready))
        assertTrue(w.status, w.status.startsWith("Ready · 02:00:5e:10:00:01 on wlp0s20f3"))
        assertTrue(w.commands.any { "wake-on-wlan magic" in it.text })
        assertTrue(w.canWake)
    }

    @Test fun theMachinesGatewayAndThePhonesGatewayAreSuggestedAndNeverSaved() {
        val w = words(profile(ready), phone = WakeRelay("10.0.0.1"))
        assertEquals(listOf("192.168.42.1", "10.0.0.1"), w.suggestions)
        assertNull(w.relay)
    }

    @Test fun aSavedRelayIsShownAndNotSuggestedAgain() {
        val w = words(profile(ready.copy(relay = WakeRelay("192.168.42.1", 9009))), phone = WakeRelay("192.168.42.1", 9009))
        assertEquals("192.168.42.1:9009", w.relay)
        assertEquals(listOf("192.168.42.1"), w.suggestions)
    }

    @Test fun theSameSuggestionFromBothSourcesAppearsOnce() {
        assertEquals(listOf("192.168.42.1"), words(profile(ready), phone = WakeRelay("192.168.42.1")).suggestions)
    }

    @Test fun wakeIsWithheldInsideTheGuardAndTheLastTapIsShown() {
        val facts = WakeFacts(90_000, WakeSendResult.Sent(listOf(Ipv4Subnet.literal("192.168.42.255")!!)))
        val w = words(profile(ready), facts, now = 100_000)
        assertFalse(w.canWake)
        assertEquals(3, w.lines.size)
        assertTrue(words(profile(ready), facts, now = 130_000).canWake)
    }

    @Test fun wakeIsWithheldWhenNothingCanSendIt() {
        assertFalse(words(profile(ready), ready = false).canWake)
    }

    @Test fun anUnavailableTargetSaysWhy() {
        val w = words(profile(WakeTarget.unavailable("This phone reached the machine over a VPN or a virtual network.", 5)), ready = false)
        assertTrue(w.status, w.status.startsWith("Not available: This phone reached the machine over a VPN"))
        assertTrue(w.commands.isEmpty())
    }

    @Test fun theRelayFieldSavesClearsOrRefuses() {
        assertEquals(RelayEntry.Save(WakeRelay("192.168.1.1", 9)), relayEntry(" 192.168.1.1 "))
        assertEquals(RelayEntry.Save(WakeRelay("192.168.1.1", 9009)), relayEntry("192.168.1.1:9009"))
        assertEquals(RelayEntry.Clear, relayEntry("  "))
        for (bad in listOf("router.local", "0.0.0.0", "127.0.0.1", "255.255.255.255", "224.0.0.1", "192.168.1.1:0", "192.168.1.1:99999", "fe80::1"))
            assertTrue(bad, relayEntry(bad) is RelayEntry.Invalid)
    }
}
