package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The desktop-focus button's gate: the shared conditions only, each named, and nothing about what the agent is doing. */
class FocusRulesTest {
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 2)

    private fun agent(status: AgentStatus = AgentStatus.Idle) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status)

    private fun row(outcome: OperationOutcome, resolved: Boolean = false, terminal: String = "term_1") =
        OperationRecord(1, "h1", "paddock-test", terminal, 2, OperationKind.Focus, 1_200, outcome, resolvedAt = if (resolved) 1_300 else null)

    private fun gate(
        a: Agent? = agent(), readAt: Long? = 1_500, live: Boolean = true, records: List<OperationRecord> = emptyList(), epoch: Long? = key.epoch,
    ) = FocusRules.gate(a, readAt, live, records, key, epoch)

    private fun closed(g: OperationGate) = assertIs<OperationGate.Closed>(g)

    @Test fun aLiveLinkAndAnAgentInTheSessionOpenIt() {
        assertEquals(OperationGate.Open, gate())
    }

    @Test fun noAgentStateClosesIt() {
        // Focus moves the desktop's cursor; it is no use only for agents that are ready, so no status is a reason.
        for (status in AgentStatus.entries) assertEquals(OperationGate.Open, gate(agent(status)), status.name)
    }

    @Test fun eachConditionThatClosesItIsNamed() {
        assertEquals(SendBlock.AgentGone, closed(gate(a = null)).block)
        assertEquals(SendBlock.NotLive, closed(gate(live = false)).block)
        assertEquals(SendBlock.Stale, closed(gate(epoch = key.epoch + 1)).block)
        assertEquals(SendBlock.Reading, closed(gate(readAt = null)).block)
        assertEquals(SendBlock.InFlight, closed(gate(records = listOf(row(OperationOutcome.Sent)))).block)
        val unknown = closed(gate(records = listOf(row(OperationOutcome.Unknown))))
        assertEquals(SendBlock.NeedsReread, unknown.block)
        assertTrue("Re-read" in unknown.sentence)
        val all = listOf(gate(a = null), gate(live = false), gate(epoch = key.epoch + 1), gate(readAt = null), gate(records = listOf(row(OperationOutcome.Sent))), gate(records = listOf(row(OperationOutcome.Unknown))))
        assertTrue(all.all { closed(it).sentence.isNotBlank() })
    }

    @Test fun aFinishedOrResolvedRowAndAnotherTerminalsRowDoNotHoldIt() {
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Acknowledged), row(OperationOutcome.Rejected), row(OperationOutcome.NotSent))))
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Unknown, resolved = true))))
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Sent, terminal = "term_other"))))
    }
}
