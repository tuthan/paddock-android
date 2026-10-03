package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Pane
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AlertResolverTest {
    private val host = HostProfileId("workstation")
    private fun agent(terminal: String, status: AgentStatus, pane: String = "w1:p1") =
        Agent(paneId = pane, terminalId = terminal, workspaceId = "w1", tabId = "w1:t1", agentStatus = status)
    private fun snapshot(vararg agents: Agent) = Snapshot("0.9.1", 22, agents = agents.toList())
    private fun hint(state: AlertState = AlertState.Blocked, terminal: String = "term_a", session: String = "default", host: String = "workstation", pane: String = "w1:p1") =
        AlertHint(TargetRef(HostProfileId(host), session, terminal), pane, state, 1_790_000_000, 7)
    private fun resolve(h: AlertHint, s: Snapshot) = AlertResolver.resolve(h, host, "default", s)

    @Test fun aLiveBlockerStillBlockedOpensTheAgent() {
        assertEquals(AlertOutcome.Current("term_a", AlertState.Blocked), resolve(hint(), snapshot(agent("term_a", AgentStatus.Blocked))))
    }

    @Test fun aDoneStillDoneOpensTheAgent() {
        assertEquals(AlertOutcome.Current("term_a", AlertState.Done), resolve(hint(AlertState.Done), snapshot(agent("term_a", AgentStatus.Done))))
    }

    @Test fun anAgentThatMovedOnIsReportedWithItsStateNow() {
        for (now in listOf(AgentStatus.Working, AgentStatus.Idle, AgentStatus.Unknown, AgentStatus.Done))
            assertEquals(AlertOutcome.Changed("term_a", now), resolve(hint(), snapshot(agent("term_a", now))))
        assertEquals(AlertOutcome.Changed("term_a", AgentStatus.Blocked), resolve(hint(AlertState.Done), snapshot(agent("term_a", AgentStatus.Blocked))))
    }

    @Test fun aTerminalThatIsGoneOrNoLongerAnAgentIsNoLongerObserved() {
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.Absent), resolve(hint(), snapshot()))
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.Absent), resolve(hint(), snapshot(agent("term_other", AgentStatus.Blocked))))
        // a pane with that terminal id that is no longer an agent is not an agent to open
        val paneOnly = Snapshot("0.9.1", 22, panes = listOf(Pane("w1:p1", "term_a", "w1", "w1:t1")))
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.Absent), resolve(hint(), paneOnly))
    }

    @Test fun theTerminalIsFoundByItsIdNotItsPaneId() {
        // the pane was renumbered since the alert, and another terminal now holds the old pane id
        val s = snapshot(agent("term_a", AgentStatus.Blocked, pane = "w1:p9"), agent("term_b", AgentStatus.Blocked, pane = "w1:p1"))
        assertEquals(AlertOutcome.Current("term_a", AlertState.Blocked), resolve(hint(pane = "w1:p1"), s))
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.Absent), resolve(hint(terminal = "term_gone", pane = "w1:p1"), s))
    }

    @Test fun anotherSessionOrAnotherMachineNeverResolvesAgainstThisSnapshot() {
        val s = snapshot(agent("term_a", AgentStatus.Blocked))
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.OtherSession), resolve(hint(session = "paddock-test"), s))
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine), resolve(hint(host = "laptop"), s))
    }

    @Test fun noOutcomeCarriesAnActionOrTheLinksOwnText() {
        // The outcomes are data about what to show; none of them names a pane, a prompt or a key.
        val all = listOf(
            resolve(hint(), snapshot(agent("term_a", AgentStatus.Blocked))), resolve(hint(), snapshot(agent("term_a", AgentStatus.Idle))),
            resolve(hint(), snapshot()),
        )
        for (o in all) assertTrue(o.toString().none { it == '\n' })
    }

    @Test fun theAgeLineUsesTheRelaysTimeAndIgnoresAClockThatIsFarAhead() {
        val now = 1_790_000_600_000L
        assertEquals("10 min ago", AlertCopy.age(now, 1_790_000_000))
        assertEquals("just now", AlertCopy.age(now, 1_790_000_600))
        assertEquals("just now", AlertCopy.age(now, 1_790_000_660))      // a minute ahead: clock skew, still "just now"
        assertNull(AlertCopy.age(now, 1_790_001_200))                    // ten minutes ahead: no number
    }

    @Test fun theNoticeNamesTheOutcomeInWordsOfOurOwn() {
        val now = 1_790_000_600_000L
        val h = hint()
        assertEquals("Opened from an alert. This agent is still blocked. The alert was sent 10 min ago.", AlertCopy.notice(AlertOutcome.Current("t", AlertState.Blocked), h, now, "laptop"))
        assertEquals("State changed since the alert: this agent is working now. The alert was sent 10 min ago.", AlertCopy.notice(AlertOutcome.Changed("t", AgentStatus.Working), h, now, "laptop"))
        assertEquals("State changed since the alert: this agent is ready now. The alert was sent 10 min ago.", AlertCopy.notice(AlertOutcome.Changed("t", AgentStatus.Idle), h, now, "laptop"))
        assertEquals("No longer observed: that agent is not on laptop now. The alert was sent 10 min ago.", AlertCopy.notice(AlertOutcome.NoLongerObserved(Unobserved.Absent), h, now, "laptop"))
        assertTrue(AlertCopy.notice(AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine), h, now, "laptop").startsWith("No longer observed"))
        assertTrue(AlertCopy.notice(AlertOutcome.NoLongerObserved(Unobserved.OtherSession), h, now, "laptop").startsWith("No longer observed"))
        // a skewed clock drops the age sentence instead of printing nonsense
        assertEquals("Opened from an alert. This agent is still done.", AlertCopy.notice(AlertOutcome.Current("t", AlertState.Done), h.copy(atSeconds = 1_790_009_999), now, "laptop"))
    }

    @Test fun aPushForAMachineCountsWhatTheFreshReadShows() {
        val s = snapshot(agent("a", AgentStatus.Blocked), agent("b", AgentStatus.Blocked, pane = "w1:p2"), agent("c", AgentStatus.Done, pane = "w1:p3"), agent("d", AgentStatus.Working, pane = "w1:p4"))
        assertEquals(MachineOutcome(blocked = 2, done = 1), AlertResolver.resolveMachine(s))
        assertEquals(MachineOutcome(0, 0), AlertResolver.resolveMachine(snapshot()))
    }

    @Test fun theMachineNoticeSaysWhatWasFoundAndNothingTheyPushSaid() {
        assertEquals("Opened from an alert: 1 agent needs you on laptop.", AlertCopy.machineNotice(MachineOutcome(1, 0), "laptop"))
        assertEquals("Opened from an alert: 3 agents need you on laptop.", AlertCopy.machineNotice(MachineOutcome(3, 0), "laptop"))
        assertEquals("Opened from an alert: 2 agents need you on laptop, and 1 is done.", AlertCopy.machineNotice(MachineOutcome(2, 1), "laptop"))
        assertEquals("Opened from an alert: 1 agent is done on laptop.", AlertCopy.machineNotice(MachineOutcome(0, 1), "laptop"))
        assertEquals("Opened from an alert: 2 agents are done on laptop.", AlertCopy.machineNotice(MachineOutcome(0, 2), "laptop"))
        assertEquals("No longer observed: no agent on laptop needs you now.", AlertCopy.machineNotice(MachineOutcome(0, 0), "laptop"))
    }
}
