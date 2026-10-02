package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-06.5 on the JVM: Esc and Ctrl+C are offered only inside a Manual input session, after a read made since it began. */
class ManualInputTest {
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 2)
    private val entered = 1_000L
    private val readAt = 1_500L

    private fun agent(status: AgentStatus = AgentStatus.Idle, ready: Boolean? = null, launching: Boolean? = null) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status, interactiveReady = ready, launchPending = launching)

    private fun row(outcome: OperationOutcome, resolved: Boolean = false, terminal: String = "term_1", kind: OperationKind = OperationKind.Esc) =
        OperationRecord(1, "h1", "paddock-test", terminal, 2, kind, 1_200, outcome, resolvedAt = if (resolved) 1_300 else null)

    private fun gate(
        a: Agent? = agent(), readAtMillis: Long? = readAt, live: Boolean = true, records: List<OperationRecord> = emptyList(), epoch: Long? = key.epoch,
    ) = ManualInputRules.gate(a, readAtMillis, entered, live, records, key, epoch)

    private fun closed(g: OperationGate) = assertIs<OperationGate.Closed>(g)

    @Test fun aReadMadeSinceTheSessionBeganOpensTheKeys() {
        assertEquals(OperationGate.Open, gate())
        assertEquals(OperationGate.Open, gate(readAtMillis = entered), "a read made at the moment of entering counts")
    }

    @Test fun theModeStartsWithAFreshRead() {
        assertEquals(SendBlock.Reading, closed(gate(readAtMillis = entered - 1)).block, "a read from before the session is not the fresh read")
        assertEquals(SendBlock.Reading, closed(gate(readAtMillis = null)).block)
        assertTrue("Reading" in closed(gate(readAtMillis = null)).sentence)
    }

    @Test fun eachConditionThatClosesTheKeysSaysWhich() {
        assertEquals(SendBlock.AgentGone, closed(gate(a = null)).block)
        assertEquals(SendBlock.NotLive, closed(gate(live = false)).block)
        val stale = closed(gate(epoch = key.epoch + 1))
        assertEquals(SendBlock.Stale, stale.block)
        assertTrue("Re-read" in stale.sentence)
        assertEquals(SendBlock.InFlight, closed(gate(records = listOf(row(OperationOutcome.Requested)))).block)
        assertEquals(SendBlock.InFlight, closed(gate(records = listOf(row(OperationOutcome.Sent)))).block)
        val unknown = closed(gate(records = listOf(row(OperationOutcome.Unknown, kind = OperationKind.Prompt))))
        assertEquals(SendBlock.NeedsReread, unknown.block, "an unknown prompt holds the terminal for a key too, as the journal refuses it")
        assertTrue("Re-read" in unknown.sentence)
        assertTrue(listOf(gate(a = null), gate(live = false), gate(epoch = key.epoch + 1), gate(readAtMillis = null)).all { closed(it).sentence.isNotBlank() })
    }

    @Test fun noAgentStatusClosesAKey() {
        // A key says nothing about readiness: Esc is for the agent that is working, Ctrl+C for the one that is stuck.
        val states = listOf(
            agent(AgentStatus.Working), agent(AgentStatus.Blocked), agent(AgentStatus.Unknown), agent(AgentStatus.Done),
            agent(ready = false), agent(launching = true),
        )
        states.forEach { assertEquals(OperationGate.Open, gate(it), it.toString()) }
    }

    @Test fun aResolvedOrFinishedRowAndAnotherTerminalsRowDoNotHoldTheKeys() {
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Unknown, resolved = true))), "re-read frees the terminal")
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Acknowledged), row(OperationOutcome.Rejected), row(OperationOutcome.NotSent))))
        assertEquals(OperationGate.Open, gate(records = listOf(row(OperationOutcome.Sent, terminal = "term_other"))))
    }

    @Test fun theKeyGateAndTheComposerGateShareTheirFirstFiveConditions() {
        // One function holds them, so a sentence cannot drift between the two screens. Compared case by case anyway.
        val cases = listOf(
            gate(a = null) to ComposerRules.gate(null, readAt, entered, true, emptyList(), key, "hello"),
            gate(live = false) to ComposerRules.gate(agent(), readAt, entered, false, emptyList(), key, "hello"),
            gate(epoch = key.epoch + 1) to ComposerRules.gate(agent(), readAt, entered, true, emptyList(), key, "hello", currentEpoch = key.epoch + 1),
            gate(readAtMillis = null) to ComposerRules.gate(agent(), null, entered, true, emptyList(), key, "hello"),
        )
        for ((keys, prompt) in cases) {
            val k = closed(keys)
            val p = assertIs<SendGate.Closed>(prompt)
            assertEquals(p.block, k.block)
            assertEquals(p.sentence, k.sentence)
        }
    }

    @Test fun theModeIsOneSessionForOneTerminal() {
        val mode = ManualInputMode()
        assertNull(mode.current.value)
        mode.enter("term_1", nowMillis = 10, epoch = 2)
        assertEquals(ManualSession("term_1", 10, 2), mode.current.value)
        mode.enter("term_1", nowMillis = 20, epoch = 3)
        assertEquals(ManualSession("term_1", 20, 3), mode.current.value, "entering again is the re-read: a new time and the new epoch")
        mode.leaveUnless("term_1")
        assertEquals(20L, mode.current.value?.enteredAtMillis, "the same terminal keeps its session")
        mode.leaveUnless("term_2")
        assertNull(mode.current.value, "another terminal never inherits it")
        mode.enter("term_1", 30, 3)
        mode.leaveUnless(null)
        assertNull(mode.current.value, "no terminal open ends it")
        mode.enter("term_1", 40, 3)
        mode.leave()
        assertNull(mode.current.value)
    }
}
