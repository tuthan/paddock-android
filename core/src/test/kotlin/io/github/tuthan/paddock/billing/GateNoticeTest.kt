package io.github.tuthan.paddock.billing

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A deferred tap on a Pro control is never silent (review F6): what the graph does with a gate decision besides opening the sheet, with the
 * graph's scope shape and a short wait so the clock can be watched.
 */
class GateNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun notice(showMillis: Long = GateNotice.SHOW_MILLIS) = GateNotice(scope, showMillis)

    private fun waitUntil(what: String, limitMillis: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + limitMillis
        while (!condition()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    @Test
    fun everyDeferralOfEveryCapabilityShowsItsContextsSentence() {
        for (c in ProCapabilities.ALL) for (context in listOf(GateContext.PENDING_ANSWER, GateContext.MANUAL_INPUT, GateContext.OPERATION_IN_FLIGHT)) {
            val n = notice()
            val decision = ProGate.decide(c.id, hasPro = false, context = context)
            assertEquals(GateDecision.DEFER, decision)
            n.decided(decision, context)
            assertNotNull(n.text.value, "${c.id} in $context: a deferral that says nothing is the silent no-op")
            assertEquals(ProGate.deferNotice(context), n.text.value, "${c.id} in $context")
        }
    }

    @Test
    fun aTapThatOpensTheSheetOrProceedsShowsNoSentenceAndTheSheetClearsAnOldOne() {
        val n = notice()
        n.decided(ProGate.decide("operations.start", hasPro = true, context = GateContext.IDLE), GateContext.IDLE)
        assertNull(n.text.value, "a Pro holder's tap proceeds without a word")
        n.decided(ProGate.decide("operations.start", hasPro = false, context = GateContext.IDLE), GateContext.IDLE)
        assertNull(n.text.value, "the sheet is the answer, not a sentence")
        n.decided(GateDecision.DEFER, GateContext.OPERATION_IN_FLIGHT)
        assertEquals("Pro is offered once the operation in progress finishes.", n.text.value)
        n.decided(GateDecision.PROCEED, GateContext.IDLE)
        assertEquals("Pro is offered once the operation in progress finishes.", n.text.value, "a capability that runs does not erase what an earlier tap said")
        n.decided(GateDecision.SHOW_GATE, GateContext.IDLE)
        assertNull(n.text.value, "the sheet replaces the sentence")
    }

    @Test
    fun aDeferralInAnIdleContextHasNothingToSayAndSaysNothing() {
        val n = notice()
        n.decided(GateDecision.DEFER, GateContext.IDLE)
        assertNull(n.text.value)
    }

    @Test
    fun theSentenceGoesByItselfAfterItsWait() {
        val n = notice(showMillis = 100)
        n.decided(GateDecision.DEFER, GateContext.MANUAL_INPUT)
        assertEquals("Pro is offered once Manual input is closed.", n.text.value)
        waitUntil("the sentence to clear") { n.text.value == null }
    }

    @Test
    fun aSecondDeferralRestartsTheWaitInsteadOfCuttingTheSentenceShort() {
        val n = notice(showMillis = 600)
        n.decided(GateDecision.DEFER, GateContext.PENDING_ANSWER)
        Thread.sleep(350)
        n.decided(GateDecision.DEFER, GateContext.PENDING_ANSWER)
        Thread.sleep(350) // past the first wait (600 ms), inside the second
        assertEquals("Pro is offered once the request on screen is answered.", n.text.value)
        waitUntil("the sentence to clear") { n.text.value == null }
    }

    @Test
    fun dismissClearsNowAndAnEarlierClockCannotWipeALaterSentence() {
        val n = notice(showMillis = 400)
        n.decided(GateDecision.DEFER, GateContext.PENDING_ANSWER)
        n.dismiss()
        assertNull(n.text.value)
        Thread.sleep(250)
        n.decided(GateDecision.DEFER, GateContext.OPERATION_IN_FLIGHT)
        Thread.sleep(250) // past the dismissed sentence's wait, inside the new one's
        assertEquals("Pro is offered once the operation in progress finishes.", n.text.value)
        n.dismiss()
        assertNull(n.text.value)
    }

    @AfterTest fun end() { scope.cancel() }
}
