package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.wake.WakeTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which edits to a saved machine need a new connection (what the graph decided in `watch`). */
class HostProfileSameConnectionTest {
    private val base = HostProfile("alpha", "Alpha", "10.0.0.2", 22, "jdoe")

    @Test fun whatWasReadAboutWakingAndTheIconNeverReconnect() {
        val read = base.copy(wake = WakeTarget(available = true, mac = "02:00:5e:10:00:01", iface = "wlp0s20f3", capturedAtMillis = 1), os = HostOs.Linux, detectedOs = HostOs.Mac)
        assertTrue(base.sameConnectionAs(read))
        assertTrue(read.sameConnectionAs(base))
        assertTrue(base.sameConnectionAs(base))
    }

    @Test fun theNetworkSearchSwitchIsASettingNotAConnection() {
        assertTrue(base.sameConnectionAs(base.copy(searchNetwork = true)))
        assertTrue(base.copy(searchNetwork = true).sameConnectionAs(base))
    }

    @Test fun theDropFolderIsASettingNotAConnection() {
        assertTrue(base.sameConnectionAs(base.copy(dropFolder = "~/Inbox")))
        assertTrue(base.copy(dropFolder = "/srv/drop").sameConnectionAs(base))
    }

    @Test fun aNameThatPaddockChoseFollowsTheMachineWithoutANewConnection() {
        val byAddress = base.copy(name = "10.0.0.2", host = "10.0.0.2")
        val byHostname = byAddress.copy(name = "devbox", detectedName = "devbox")
        assertTrue(byAddress.nameIsDefault)
        assertTrue(byHostname.nameIsDefault)
        assertTrue(byAddress.sameConnectionAs(byHostname), "the hostname was adopted")
        assertTrue(byHostname.sameConnectionAs(byAddress.copy(name = "devbox-2", detectedName = "devbox-2")), "and followed")
        assertTrue(byHostname.sameConnectionAs(byAddress))
    }

    @Test fun aNameTheUserTypedStaysPartOfTheConnection() {
        val byHostname = base.copy(name = "devbox", detectedName = "devbox")
        val typed = byHostname.copy(name = "Build server")
        assertFalse(typed.nameIsDefault)
        assertFalse(byHostname.sameConnectionAs(typed))
        assertFalse(typed.sameConnectionAs(byHostname))
        assertFalse(typed.sameConnectionAs(typed.copy(name = "Build server 2")))
        assertFalse(base.copy(name = "devbox").nameIsDefault, "a name that matches nothing the machine said is the user's")
        assertTrue(base.copy(name = "DEVBOX", detectedName = "devbox").nameIsDefault, "case does not matter")
    }

    @Test fun everythingThatShapesTheConnectionOrItsLabelDoes() {
        for (changed in listOf(
            base.copy(name = "Alpha 2"), base.copy(host = "10.0.0.3"), base.copy(port = 2222), base.copy(user = "other"),
            base.copy(session = "work"), base.copy(key = KeyKind.Imported, importedKeyId = "imported"),
        )) assertFalse(base.sameConnectionAs(changed), changed.toString())
        assertFalse(base.sameConnectionAs(base.copy(id = "beta")))
    }
}
