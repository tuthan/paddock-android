package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.ports.Clock
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

class AlertInboxTest {
    private var now = 1_000_000L
    private val inbox = AlertInbox(Clock { now })
    private fun link(n: Long, host: String = "workstation") = "paddock://open?h=$host&s=default&t=term_a&p=w1%3Ap1&st=blocked&at=1790000000&n=$n"

    @Test fun aValidLinkArrivesWithThePhonesClockNotTheRelays() {
        inbox.offer(link(5))
        val e = assertIs<AlertEvent.Arrived>(inbox.event.value)
        assertEquals(1_000_000L, e.arrivedAtMillis)
        assertEquals(5L, e.hint.sequence)
        assertEquals(1_790_000_000L, e.hint.atSeconds)
    }

    @Test fun anInvalidLinkIsOneInvalidEventAndKeepsNothing() {
        inbox.offer("paddock://open?h=workstation&s=%00")
        assertIs<AlertEvent.Invalid>(inbox.event.value)
        inbox.offer(null)
        assertIs<AlertEvent.Invalid>(inbox.event.value)
    }

    @Test fun theSameMessageTwiceInQuickSuccessionIsOneArrival() {
        inbox.offer(link(5))
        val first = inbox.event.value
        inbox.consume(first!!)
        now += 2_000
        inbox.offer(link(5))
        assertNull(inbox.event.value)
        now += 4_000
        inbox.offer(link(5))                       // far enough apart: the user tapped it again on purpose
        assertIs<AlertEvent.Arrived>(inbox.event.value)
    }

    @Test fun anotherSequenceOrAnotherMachineIsNotADuplicate() {
        inbox.offer(link(5)); inbox.consume(inbox.event.value!!)
        inbox.offer(link(6))
        assertEquals(6L, (inbox.event.value as AlertEvent.Arrived).hint.sequence)
        inbox.consume(inbox.event.value!!)
        inbox.offer(link(6, host = "laptop"))
        assertEquals("laptop", (inbox.event.value as AlertEvent.Arrived).hint.target.host.value)
    }

    @Test fun aNewerArrivalReplacesOneStillWaitingAndConsumingAnOldOneKeepsTheNewer() {
        inbox.offer(link(1))
        val old = inbox.event.value!!
        inbox.offer(link(2))
        inbox.consume(old)
        assertEquals(2L, (inbox.event.value as AlertEvent.Arrived).hint.sequence)
    }

    private fun machine(nonce: String, host: String = "workstation") = "paddock://machine?h=$host&n=$nonce"

    @Test fun aPushLinkArrivesAsAMachineWakeWithThePhonesClock() {
        inbox.offer(machine("abc123"))
        val e = assertIs<AlertEvent.MachineWoke>(inbox.event.value)
        assertEquals(1_000_000L, e.arrivedAtMillis)
        assertEquals("abc123", e.hint.nonce)
    }

    @Test fun thePushNonceIsDedupedLikeASequenceAndTheTwoKindsDoNotCollide() {
        inbox.offer(machine("42"))
        inbox.consume(inbox.event.value!!)
        now += 2_000
        inbox.offer(machine("42"))
        assertNull(inbox.event.value)
        now += 100
        inbox.offer(link(42))                     // an alert link with sequence 42 for the same machine is a different message
        assertIs<AlertEvent.Arrived>(inbox.event.value)
    }
}
