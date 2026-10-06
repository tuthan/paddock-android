package io.github.tuthan.paddock.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProGateTest {
    private val gated = setOf("operations.stop")

    @Test
    fun m8BundleCPlusSeveralHostsIsWhatIsGated() {
        assertEquals(
            setOf("answers.guarded", "operations.start", "operations.manage", "widgets", "hosts.switch", "hosts.merged"),
            ProGate.GATED,
        )
        assertEquals(ProGate.GATED.size, ProCapabilities.ALL.map { it.id }.toSet().size, "ids are unique")
    }

    @Test
    fun everyRealCapabilityShowsTheGateToAFreeUserWhoIsIdle() {
        for (c in ProCapabilities.ALL) {
            assertEquals(GateDecision.SHOW_GATE, ProGate.decide(c.id, hasPro = false, context = GateContext.IDLE), c.id)
            assertEquals(GateDecision.PROCEED, ProGate.decide(c.id, hasPro = true, context = GateContext.IDLE), c.id)
        }
    }

    @Test
    fun whatStaysFreeIsNotInTheGatedSet() {
        // Vault Free/Pro rules: watching, alerts, manual input, snippets and adding a machine are never Pro; nor is removing one (the user, 2026-10-06: choosing which saved machine to watch is Pro, forgetting one is not).
        for (id in listOf("alerts", "alerts.relay", "output", "input.manual", "snippets", "hosts.add", "hosts.remove", "answers.yes", "terminal")) {
            for (context in GateContext.entries) assertEquals(GateDecision.PROCEED, ProGate.decide(id, hasPro = false, context = context), id)
        }
    }

    @Test
    fun lockedIsGatedWithoutPro() {
        assertTrue(ProGate.locked("widgets", hasPro = false))
        assertEquals(false, ProGate.locked("widgets", hasPro = true))
        assertEquals(false, ProGate.locked("snippets", hasPro = false))
    }

    @Test
    fun theRealGateNeverInterruptsAPendingAnswerWhateverIsGated() {
        for (c in ProCapabilities.ALL) {
            for (context in listOf(GateContext.PENDING_ANSWER, GateContext.MANUAL_INPUT, GateContext.OPERATION_IN_FLIGHT)) {
                assertEquals(GateDecision.DEFER, ProGate.decide(c.id, hasPro = false, context = context), "${c.id} $context")
            }
        }
    }

    @Test
    fun aFreeCapabilityAlwaysProceeds() {
        for (context in GateContext.entries) {
            assertEquals(GateDecision.PROCEED, ProGate.decide("answers.yes", hasPro = false, context = context, gated = gated))
        }
    }

    @Test
    fun proHoldersProceedInEveryContext() {
        for (context in GateContext.entries) {
            assertEquals(GateDecision.PROCEED, ProGate.decide("operations.stop", hasPro = true, context = context, gated = gated))
        }
    }

    @Test
    fun theGateAppearsOnlyWhenTheUserIsIdle() {
        assertEquals(GateDecision.SHOW_GATE, ProGate.decide("operations.stop", hasPro = false, context = GateContext.IDLE, gated = gated))
    }

    @Test
    fun theGateNeverInterruptsAPendingAnswerManualInputOrAnOperationInFlight() {
        for (context in listOf(GateContext.PENDING_ANSWER, GateContext.MANUAL_INPUT, GateContext.OPERATION_IN_FLIGHT)) {
            assertEquals(GateDecision.DEFER, ProGate.decide("operations.stop", hasPro = false, context = context, gated = gated), "$context")
        }
    }
}
