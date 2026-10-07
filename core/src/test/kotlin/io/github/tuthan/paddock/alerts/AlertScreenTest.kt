package io.github.tuthan.paddock.alerts

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AlertScreenTest {
    @Test fun theScreenIsTheAlertOnlyForTheMachineItShowsAndOnlyWhileAHerdIsInFront() {
        assertTrue(AlertScreen.showsIt(herdInFront = true, watchedProfile = "laptop", alertProfile = "laptop"))
        assertFalse(AlertScreen.showsIt(herdInFront = false, watchedProfile = "laptop", alertProfile = "laptop"), "app not in front")
        // Seen on a real setup: Paddock open on one machine, another machine's agent needs you, and nothing was raised or shown.
        assertFalse(AlertScreen.showsIt(herdInFront = true, watchedProfile = "laptop", alertProfile = "server"), "another machine is not on screen")
        assertFalse(AlertScreen.showsIt(herdInFront = true, watchedProfile = null, alertProfile = "laptop"), "no machine is watched, so no herd is on screen")
    }

    @Test fun aLinkNamesTheMachineItIsForAndAnythingElseNamesNone() {
        val alert = DeepLink.build(AlertHint(io.github.tuthan.paddock.identity.TargetRef(io.github.tuthan.paddock.identity.HostProfileId("box-7"), "default", "term_1"), "w1:p1", AlertState.Blocked, 1_790_000_000, 3))
        assertEquals("box-7", DeepLink.profileOf(alert))
        assertEquals("box-9", DeepLink.profileOf(DeepLink.buildMachine(MachineHint(io.github.tuthan.paddock.identity.HostProfileId("box-9"), "abc123"))))
        for (bad in listOf(null, "", "https://example.org", "paddock://open?h=box-7", "paddock://machine?h=box-7")) assertNull(DeepLink.profileOf(bad), "$bad")
    }
}
