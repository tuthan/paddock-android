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

/** AC-06.2 for the composer: Send is open only when everything holds, and every closed case says which condition failed. */
class ComposerRulesTest {
    private val key = TerminalKey(TargetRef(HostProfileId("h1"), "paddock-test", "term_1"), epoch = 2)
    private val opened = 1_000L
    private val readAt = 1_500L

    private fun agent(status: AgentStatus = AgentStatus.Idle, ready: Boolean? = null, launching: Boolean? = null) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status, interactiveReady = ready, launchPending = launching)

    private fun row(outcome: OperationOutcome, resolved: Boolean = false, terminal: String = "term_1") =
        OperationRecord(1, "h1", "paddock-test", terminal, 2, OperationKind.Prompt, 1_200, outcome, resolvedAt = if (resolved) 1_300 else null)

    private fun gate(
        a: Agent? = agent(), readAtMillis: Long? = readAt, live: Boolean = true, records: List<OperationRecord> = emptyList(), text: String = "hello", strict: Boolean = false,
        epoch: Long? = key.epoch, journalUnreadable: Boolean = false,
    ) = ComposerRules.gate(a, readAtMillis, opened, live, records, key, text, strict, epoch, journalUnreadable)

    private fun closed(g: SendGate) = assertIs<SendGate.Closed>(g)

    @Test fun everythingHoldingOpensSendAndSaysWhatBacksIt() {
        assertEquals(SendGate.Open(hintsUnreported = true), gate())
        assertEquals(SendGate.Open(hintsUnreported = false), gate(agent(ready = true, launching = false)))
        assertEquals(SendGate.Open(hintsUnreported = true), gate(agent(AgentStatus.Done)))
    }

    @Test fun aGoneAgentAndAnOfflineLinkAndAReadFromBeforeTheComposerOpenedEachCloseIt() {
        assertEquals(SendBlock.AgentGone, closed(gate(a = null)).block)
        assertEquals(SendBlock.NotLive, closed(gate(live = false)).block)
        assertEquals(SendBlock.Reading, closed(gate(readAtMillis = 999)).block)
        assertEquals(SendBlock.Reading, closed(gate(readAtMillis = null)).block)
        assertEquals(SendGate.Open(true), gate(readAtMillis = opened), "a read made at the moment of opening counts")
    }

    @Test fun aReconnectSinceTheScreenOpenedClosesSendUntilItReReads() {
        val g = closed(gate(epoch = key.epoch + 1))
        assertEquals(SendBlock.Stale, g.block)
        assertTrue("re-established" in g.sentence && "Re-read" in g.sentence)
        assertEquals(SendGate.Open(true), gate(epoch = null), "with no installed epoch there is nothing to compare")
    }

    @Test fun everyNotReadyConditionIsNamedBySentence() {
        for ((a, why) in listOf(
            agent(AgentStatus.Working) to NotReadyReason.Working,
            agent(AgentStatus.Blocked) to NotReadyReason.Blocked,
            agent(AgentStatus.Unknown) to NotReadyReason.StatusUnknown,
            agent(ready = false) to NotReadyReason.NotInteractive,
            agent(launching = true) to NotReadyReason.LaunchPending,
        )) {
            val g = closed(gate(a))
            assertEquals(SendBlock.NotReady, g.block, why.name)
            assertEquals(why, g.notReady, why.name)
            assertEquals(why.sentence, g.sentence, why.name)
        }
    }

    @Test fun theStrictReadingClosesSendForAnAgentThatReportsNoHints() {
        val g = closed(gate(strict = true))
        assertEquals(NotReadyReason.HintsUnreported, g.notReady)
        assertEquals(SendGate.Open(false), gate(agent(ready = true, launching = false), strict = true))
    }

    @Test fun anOperationInFlightOrAnUnresolvedUnknownHoldsTheTerminal() {
        assertEquals(SendBlock.InFlight, closed(gate(records = listOf(row(OperationOutcome.Requested)))).block)
        assertEquals(SendBlock.InFlight, closed(gate(records = listOf(row(OperationOutcome.Sent)))).block)
        val unknown = closed(gate(records = listOf(row(OperationOutcome.Unknown))))
        assertEquals(SendBlock.NeedsReread, unknown.block)
        assertTrue("Re-read" in unknown.sentence)
        assertEquals(SendGate.Open(true), gate(records = listOf(row(OperationOutcome.Unknown, resolved = true))), "re-read frees the terminal")
        assertEquals(SendGate.Open(true), gate(records = listOf(row(OperationOutcome.Acknowledged), row(OperationOutcome.Rejected), row(OperationOutcome.NotSent))))
    }

    @Test fun anUnreadableJournalClosesEveryOperationWhateverTheTerminalLooksLike() {
        val g = closed(gate(journalUnreadable = true))
        assertEquals(SendBlock.JournalUnreadable, g.block)
        assertTrue("Settings" in g.sentence && "duplicate" in g.sentence)
        // The terminal's own conditions are read first: a gone agent or a dead link is the more useful sentence.
        assertEquals(SendBlock.AgentGone, closed(gate(a = null, journalUnreadable = true)).block)
        assertEquals(SendBlock.NotLive, closed(gate(live = false, journalUnreadable = true)).block)
        // The key and focus gates share the rule.
        val agent = agent()
        assertEquals(SendBlock.JournalUnreadable, (ManualInputRules.gate(agent, readAt, opened, true, emptyList(), key, journalUnreadable = true) as OperationGate.Closed).block)
        assertEquals(SendBlock.JournalUnreadable, (FocusRules.gate(agent, readAt, true, emptyList(), key, journalUnreadable = true) as OperationGate.Closed).block)
        assertEquals(OperationGate.Open, ManualInputRules.gate(agent, readAt, opened, true, emptyList(), key))
    }

    @Test fun anotherTerminalsRowsDoNotMatter() {
        assertEquals(SendGate.Open(true), gate(records = listOf(row(OperationOutcome.Sent, terminal = "term_other"))))
    }

    @Test fun anEmptyOrOversizedPromptIsTheLastThingToCheck() {
        assertEquals(SendBlock.EmptyPrompt, closed(gate(text = "  \n")).block)
        assertEquals(SendBlock.TooLong, closed(gate(text = "x".repeat(AgentOperations.MAX_PROMPT_CHARS + 1))).block)
        assertEquals(SendGate.Open(true), gate(text = "x".repeat(AgentOperations.MAX_PROMPT_CHARS)))
        // The agent's state outranks the empty text: a blocked agent is the reason worth reading.
        assertEquals(SendBlock.NotReady, closed(gate(agent(AgentStatus.Blocked), text = "")).block)
    }

    @Test fun everyClosedCaseHasASentence() {
        val cases = listOf(gate(a = null), gate(live = false), gate(epoch = key.epoch + 1), gate(readAtMillis = null), gate(records = listOf(row(OperationOutcome.Sent))),
            gate(records = listOf(row(OperationOutcome.Unknown))), gate(journalUnreadable = true), gate(agent(AgentStatus.Working)), gate(text = ""), gate(text = "x".repeat(AgentOperations.MAX_PROMPT_CHARS + 1)))
        assertEquals(SendBlock.entries.toSet(), cases.map { closed(it).block }.toSet(), "the matrix reaches every block")
        assertTrue(cases.all { closed(it).sentence.isNotBlank() })
    }
}
