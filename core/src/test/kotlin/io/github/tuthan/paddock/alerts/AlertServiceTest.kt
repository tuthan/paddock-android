package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import io.github.tuthan.paddock.live.MonitoredHost
import io.github.tuthan.paddock.reconcile.Observation
import io.github.tuthan.paddock.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * What the graph used to decide about an alert (Phase 07): which machine an arrival switches to and how it ends when no fresh read comes, and when the
 * open app raises a notification of its own. The read-and-resolve step itself ([AlertResolver], [AlertArrival]) is tested beside this.
 */
class AlertServiceTest {
    private val host = HostProfileId("laptop")
    private val target = TargetRef(host, "main", "term_1")
    private val key = TerminalKey(target, 1)
    private val laptop = HostProfile("laptop", "Laptop", "laptop.lan", user = "u")

    private var settings = AppSettings(localAlerts = true)
    private var interactive = false
    private val shown = mutableListOf<NotificationContent>()
    private val switched = mutableListOf<String>()
    private val monitored = MutableStateFlow<MonitoredHost?>(null)
    private var known: HostProfile? = laptop
    @Volatile private var now = 1_000_000L

    private fun service(timeoutMillis: Long = 100) = AlertService(
        clock = { now }, settings = { settings }, interactive = { interactive }, show = { shown += it },
        switchTo = { id -> switched += id; known?.takeIf { it.id == id } }, monitored = monitored, arrivalTimeoutMillis = timeoutMillis,
    )

    private fun agent(status: AgentStatus, seq: Long = 5, title: String? = "approve edit") =
        Agent(paneId = "w1:p1", terminalId = "term_1", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = status, stateChangeSeq = seq, terminalTitleStripped = title)

    private suspend fun AlertService.sees(to: AgentStatus, agent: Agent?, from: AgentStatus = AgentStatus.Working, machine: String = "Laptop") =
        raise(flowOf(Observation.StatusChanged(now, key, from, to)), machine) { agent?.let { Snapshot("0.9.1", 22, agents = listOf(it)) } }

    // ---- the arrival rule ---------------------------------------------------------------------------------------------------

    private fun arrived(machine: String = "laptop") =
        AlertEvent.Arrived(AlertHint(TargetRef(HostProfileId(machine), "main", "term_1"), "w1:p1", AlertState.Blocked, 1_000, 5), arrivedAtMillis = now)

    @Test fun anAlertForAMachineThePhoneDoesNotHaveSwitchesNothingAndSaysSo() = runBlocking {
        val r = service().resolveAlert(arrived("elsewhere"))
        assertEquals(listOf("elsewhere"), switched, "the switch was asked for, by the id the alert names")
        assertEquals(AlertOutcome.NoLongerObserved(Unobserved.UnknownMachine), r.outcome)
        assertEquals("this phone", r.machine)
        assertNull(r.row); assertNull(r.host)
    }

    @Test fun aPushForAMachineThePhoneDoesNotHaveIsUnknown() = runBlocking {
        val r = service().resolveMachine(AlertEvent.MachineWoke(MachineHint(HostProfileId("elsewhere"), "n1"), now))
        assertTrue(r.unknownMachine)
        assertNull(r.outcome)
    }

    @Test fun whenNoFreshReadComesTheOutcomeIsNullAndTheMachineIsNamed() = runBlocking {
        val r = service().resolveAlert(arrived())
        assertEquals(listOf("laptop"), switched)
        assertNull(r.outcome)
        assertEquals("Laptop", r.machine)
        assertNull(r.host)
        val m = service().resolveMachine(AlertEvent.MachineWoke(MachineHint(host, "n1"), now))
        assertNull(m.outcome); assertEquals("Laptop", m.machine); assertEquals(false, m.unknownMachine)
    }

    // ---- watching mode ------------------------------------------------------------------------------------------------------

    @Test fun aTerminalThatBecameBlockedRaisesOneNotificationWithItsTitleBehindTheLockScreenRule() = runBlocking {
        service().sees(AgentStatus.Blocked, agent(AgentStatus.Blocked))
        val n = shown.single()
        assertEquals(AlertChannel.NeedsYou, n.channel)
        assertEquals("approve edit", n.title)
        assertEquals("Needs you · Laptop", n.text)
        assertTrue(n.hideOnLockScreen, "the default hides the title on the lock screen")
        settings = settings.copy(hidePromptOnLockScreen = false)
        service().sees(AgentStatus.Blocked, agent(AgentStatus.Blocked, seq = 6))
        assertEquals(false, shown.last().hideOnLockScreen)
    }

    @Test fun aDoneAgentRaisesADoneNotificationAndTheSameChangeIsRaisedOnce() = runBlocking {
        val s = service()
        s.sees(AgentStatus.Done, agent(AgentStatus.Done))
        s.sees(AgentStatus.Done, agent(AgentStatus.Done))
        assertEquals(listOf(AlertChannel.Done), shown.map { it.channel })
    }

    @Test fun nothingIsRaisedWithAlertsOffOrWithTheHerdInFrontOrForAnythingButBlockedAndDone() = runBlocking {
        settings = AppSettings(localAlerts = false)
        service().sees(AgentStatus.Blocked, agent(AgentStatus.Blocked))
        settings = AppSettings(localAlerts = true)
        interactive = true
        service().sees(AgentStatus.Blocked, agent(AgentStatus.Blocked, seq = 7))
        interactive = false
        service().sees(AgentStatus.Working, agent(AgentStatus.Working), from = AgentStatus.Idle)
        assertTrue(shown.isEmpty(), "$shown")
    }

    @Test fun aChangeTheFreshReadNoLongerShowsRaisesNothingAndOtherObservationsAreIgnored() = runBlocking {
        service().sees(AgentStatus.Blocked, agent = null)
        service().sees(AgentStatus.Blocked, agent(AgentStatus.Idle))
        service().raise(flowOf(Observation.PaneVanished(now, key, "w1:p1")), "Laptop") { Snapshot("0.9.1", 22, agents = listOf(agent(AgentStatus.Blocked))) }
        assertTrue(shown.isEmpty())
    }

    @Test fun aNotifierThatThrowsDoesNotEndTheWatch() = runBlocking {
        val throwing = AlertService(
            clock = { now }, settings = { settings }, interactive = { false }, show = { error("the notification manager said no") },
            switchTo = { null }, monitored = monitored,
        )
        throwing.raise(flowOf(Observation.StatusChanged(now, key, AgentStatus.Working, AgentStatus.Blocked), Observation.StatusChanged(now, key, AgentStatus.Working, AgentStatus.Done)), "Laptop") {
            Snapshot("0.9.1", 22, agents = listOf(agent(AgentStatus.Blocked)))
        }
    }
}
