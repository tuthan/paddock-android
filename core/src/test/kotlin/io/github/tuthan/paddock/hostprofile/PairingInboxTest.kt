package io.github.tuthan.paddock.hostprofile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingInboxTest {
    private val fp = "SHA256:" + "A".repeat(43)
    private val good = "paddock://pair?v=1&host=box&port=22&user=jdoe&fp=$fp"

    @Test fun aValidLinkWaitsForTheScreenAndIsConsumedOnce() {
        val inbox = PairingInbox()
        inbox.offer(good)
        val e = assertIs<PairingEvent.Link>(inbox.event.value)
        assertEquals("box", e.link.host)
        inbox.consume(e); assertNull(inbox.event.value)
    }

    @Test fun anInvalidLinkLeavesOnlyItsReason() {
        val inbox = PairingInbox()
        inbox.offer("paddock://pair?v=1&host=box&port=0&user=jdoe&fp=$fp")
        assertEquals(PairingRejection.BadField, assertIs<PairingEvent.Invalid>(inbox.event.value).reason)
        inbox.offer(null); assertEquals(PairingRejection.NotAPairingLink, assertIs<PairingEvent.Invalid>(inbox.event.value).reason)
    }

    @Test fun aNewerLinkReplacesOneStillWaitingAndAStaleConsumeKeepsIt() {
        val inbox = PairingInbox()
        inbox.offer(good); val first = inbox.event.value!!
        inbox.offer(good.replace("host=box", "host=nas")); val second = inbox.event.value!!
        inbox.consume(first)
        assertEquals(second, inbox.event.value, "consuming the old one leaves the newer")
    }

    @Test fun theNoticesNeverRepeatTheLink() {
        for (r in PairingRejection.entries) assertTrue(!PairingCopy.invalid(r).contains("paddock://") && PairingCopy.invalid(r).isNotBlank())
    }
}
