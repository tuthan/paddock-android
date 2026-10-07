package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.ops.Begin
import io.github.tuthan.paddock.ops.InMemoryJournalStore
import io.github.tuthan.paddock.ops.ManualInputMode
import io.github.tuthan.paddock.ops.OperationJournal
import io.github.tuthan.paddock.ops.OperationKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The gate the graph used to hold itself (what the user is in the middle of, the sheet, the one-sentence notice), against the real rule
 * ([ProGate]) and a real journal and manual-input mode: the same [ProService] the app graph builds, with no store.
 */
class ProServiceTest {
    private var now = 10_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val manual = ManualInputMode()
    private val journal = OperationJournal(InMemoryJournalStore()) { now }

    private fun service(unlocked: Boolean = false) =
        ProService(Entitlements(NoBilling, InMemoryEntitlementStore(), { now }, unlocked), scope, { now }, manual, journal, unlocked)

    @AfterTest fun end() { scope.cancel() }

    private val gated = ProCapabilities.START_AGENT
    private val free = "snippets"

    @Test fun anIdleTapOnAProControlWithoutProAsksForTheSheetAndSaysNothingElse() {
        val s = service()
        assertTrue(s.locked(gated))
        assertFalse(s.requestCapability(gated.id, pendingAnswerOnScreen = false))
        assertEquals(gated.id, s.gateRequest.value)
        assertNull(s.gateNotice.value)
        s.dismissGate()
        assertNull(s.gateRequest.value)
    }

    @Test fun whatIsFreeRunsAndNeverExplainsItself() {
        val s = service()
        for (id in listOf(free, "alerts", "hosts.add", "input.manual")) assertTrue(s.requestCapability(id, pendingAnswerOnScreen = true), id)
        assertNull(s.gateRequest.value)
        assertNull(s.gateNotice.value)
    }

    @Test fun anUnlockedBuildLocksNothingAndRunsEverything() {
        val s = service(unlocked = true)
        for (c in ProCapabilities.ALL) {
            assertFalse(s.locked(c), c.id)
            assertTrue(s.requestCapability(c.id, pendingAnswerOnScreen = false), c.id)
        }
        assertNull(s.gateRequest.value)
    }

    @Test fun aPendingAnswerOnScreenDefersAndTheNoticeSaysWhy() {
        val s = service()
        assertFalse(s.requestCapability(ProCapabilities.GUARDED_ANSWERS.id, pendingAnswerOnScreen = true))
        assertNull(s.gateRequest.value)
        assertEquals("Pro is offered once the request on screen is answered.", s.gateNotice.value)
        assertEquals(GateContext.PENDING_ANSWER, s.gateContext(pendingAnswerOnScreen = true))
    }

    @Test fun manualInputDefersUntilItIsClosed() {
        val s = service()
        manual.enter("term_1", now, epoch = 1)
        assertFalse(s.requestCapability(gated.id, pendingAnswerOnScreen = false))
        assertNull(s.gateRequest.value)
        assertEquals("Pro is offered once Manual input is closed.", s.gateNotice.value)
        manual.leave()
        assertFalse(s.requestCapability(gated.id, pendingAnswerOnScreen = false))
        assertEquals(gated.id, s.gateRequest.value)
        assertNull(s.gateNotice.value, "the sheet replaces the sentence")
    }

    @Test fun anOperationInFlightDefersAndAnEndedOneDoesNot() {
        val s = service()
        val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "t1"), 1)
        val row = (journal.begin(key, OperationKind.Prompt, "x") as Begin.Started).record
        assertEquals(GateContext.OPERATION_IN_FLIGHT, s.gateContext(false))
        assertFalse(s.requestCapability(ProCapabilities.MANAGE_SESSIONS.id, pendingAnswerOnScreen = false))
        assertEquals("Pro is offered once the operation in progress finishes.", s.gateNotice.value)
        journal.markSent(row.id, "w1:p1"); journal.acknowledged(row.id)
        assertEquals(GateContext.IDLE, s.gateContext(false))
    }

    @Test fun theOverviewOpensTheSheetWhenIdleAndDefersLikeACapabilityWhenBusy() {
        val s = service()
        s.openProOverview()
        assertEquals(ProCopy.OVERVIEW_ID, s.gateRequest.value)
        assertNull(s.gateNotice.value)
        s.dismissGate()
        manual.enter("term_1", now, epoch = 1)
        s.openProOverview()
        assertNull(s.gateRequest.value)
        assertEquals("Pro is offered once Manual input is closed.", s.gateNotice.value)
        s.dismissGateNotice()
        assertNull(s.gateNotice.value)
    }

    /** A build that sells Pro, with nothing behind it: only the answer to "does the account hold Pro" matters here. */
    private object Selling : Billing by NoBilling {
        override val sellsPro = true
    }

    @Test fun lockedFollowsTheViewAScreenCollectedNotOnlyTheCurrentOne() {
        val s = ProService(Entitlements(Selling, InMemoryEntitlementStore(), { now }, false), scope, { now }, manual, journal, false)
        val bought = ProView(state = EntitlementState(status = ProStatus.PRO), sellsPro = true)
        assertTrue(s.locked(gated), "the current view holds nothing")
        assertFalse(s.locked(gated, bought), "the screen's own view says Pro")
        assertTrue(s.hasPro(bought))
        assertFalse(s.hasPro())
    }
}
