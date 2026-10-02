package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

/** AC-06.2's matrix: every case that disables Send names its failing condition. Times are on a fake clock. */
class ReadinessTest {
    private val readAt = 1_000_000L

    private fun agent(status: AgentStatus = AgentStatus.Idle, ready: Boolean? = true, launching: Boolean? = false) =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agentStatus = status, interactiveReady = ready, launchPending = launching)

    private fun check(a: Agent, age: Long = 500, strict: Boolean = false) = isReady(a, readAt, readAt + age, strict)
    private fun notReady(r: Readiness) = (r as Readiness.NotReady).reasons

    @Test fun idleAndDoneWithBothHintsGoodAreReady() {
        assertEquals(Readiness.Ready(hintsUnreported = false), check(agent(AgentStatus.Idle)))
        assertEquals(Readiness.Ready(hintsUnreported = false), check(agent(AgentStatus.Done)))
    }

    @Test fun anAgentHerdrSendsNoHintsForIsReadyAndSaysSo() {
        // The case of every agent seen on herdr 0.9.1, real claude and codex included.
        assertEquals(Readiness.Ready(hintsUnreported = true), check(agent(ready = null, launching = null)))
        assertEquals(Readiness.Ready(hintsUnreported = false), check(agent(ready = true, launching = null)))
    }

    @Test fun theReadMustBeWithinTwoSecondsAndTwoSecondsExactlyStillCounts() {
        assertEquals(Readiness.Ready(false), check(agent(), age = 2_000))
        assertEquals(listOf(NotReadyReason.StaleRead), notReady(check(agent(), age = 2_001)))
    }

    @Test fun aClockThatMovedBackIsNotStale() {
        assertEquals(Readiness.Ready(false), isReady(agent(), readAt, readAt - 5_000))
    }

    @Test fun workingBlockedAndUnknownEachNameThemselves() {
        assertEquals(listOf(NotReadyReason.Working), notReady(check(agent(AgentStatus.Working))))
        assertEquals(listOf(NotReadyReason.Blocked), notReady(check(agent(AgentStatus.Blocked))))
        assertEquals(listOf(NotReadyReason.StatusUnknown), notReady(check(agent(AgentStatus.Unknown))))
    }

    @Test fun anExplicitFalseOrTrueHintFails() {
        assertEquals(listOf(NotReadyReason.NotInteractive), notReady(check(agent(ready = false))))
        assertEquals(listOf(NotReadyReason.LaunchPending), notReady(check(agent(launching = true))))
        assertEquals(listOf(NotReadyReason.NotInteractive, NotReadyReason.LaunchPending), notReady(check(agent(ready = false, launching = true))))
    }

    @Test fun everyFailingConditionIsListedMostBasicFirst() {
        val r = check(agent(AgentStatus.Working, ready = false, launching = true), age = 9_000)
        assertEquals(listOf(NotReadyReason.StaleRead, NotReadyReason.Working, NotReadyReason.NotInteractive, NotReadyReason.LaunchPending), notReady(r))
        assertEquals(NotReadyReason.StaleRead, (r as Readiness.NotReady).primary)
    }

    @Test fun anIdleAgentWithAFailingHintIsNotReadyWhateverItsStatusSays() {
        assertTrue(check(agent(AgentStatus.Done, launching = true)) is Readiness.NotReady)
    }

    @Test fun theStrictReadingNeedsBothHintsReported() {
        assertEquals(listOf(NotReadyReason.HintsUnreported), notReady(check(agent(ready = null, launching = null), strict = true)))
        assertEquals(listOf(NotReadyReason.HintsUnreported), notReady(check(agent(ready = true, launching = null), strict = true)))
        assertEquals(listOf(NotReadyReason.HintsUnreported), notReady(check(agent(ready = null, launching = false), strict = true)))
        assertEquals(Readiness.Ready(false), check(agent(ready = true, launching = false), strict = true))
    }

    @Test fun everyReasonHasItsOwnCodeAndAnExplanation() {
        val codes = NotReadyReason.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(NotReadyReason.entries.all { it.sentence.isNotBlank() })
        // The phone's own codes never collide with herdr's, so a journal row says whose refusal it was.
        assertTrue(codes.none { it == "agent_blocked" || it == "agent_not_ready" || it == "agent_not_found" })
        assertNotEquals(NotReadyReason.Blocked.code, "agent_blocked")
    }
}
