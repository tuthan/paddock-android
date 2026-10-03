package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AlertNotificationTest {
    private val target = TargetRef(HostProfileId("workstation"), "default", "term_a")
    private fun alert(state: AlertState = AlertState.Blocked, title: String? = "Approve the migration of the users table", seq: Long = 5) =
        LocalAlert(target, "w1:p1", state, seq, 1_790_000_000, "laptop", title)
    private fun agent(status: AgentStatus, seq: Long? = 5) = Agent(paneId = "w1:p1", terminalId = "term_a", workspaceId = "w1", tabId = "w1:t1", agentStatus = status, stateChangeSeq = seq)

    @Test fun theLockScreenTextIsGenericAndTheAgentTitleIsOnlyInThePrivateVersion() {
        val c = AlertContent.of(alert(), hideOnLockScreen = true)
        assertEquals("Paddock: attention on laptop", c.publicTitle)
        assertEquals("An agent needs you.", c.publicText)
        assertTrue(c.hideOnLockScreen)
        assertEquals("Approve the migration of the users table", c.title)
        assertEquals("Needs you · laptop", c.text)
        // nothing the agent said reaches the public text
        for (word in listOf("Approve", "migration", "users")) assertFalse(word in c.publicTitle + c.publicText, word)
    }

    @Test fun withoutATitleThePrivateVersionIsTheGenericOne() {
        for (t in listOf(null, "", "   ")) assertEquals("Paddock: attention on laptop", AlertContent.of(alert(title = t), true).title)
    }

    @Test fun aLongTitleIsCutAndTheRedactionSettingTravelsWithTheContent() {
        assertEquals(AlertContent.MAX_TITLE, AlertContent.of(alert(title = "x".repeat(500)), false).title.length)
        assertFalse(AlertContent.of(alert(), hideOnLockScreen = false).hideOnLockScreen)
    }

    @Test fun blockedGoesToNeedsYouAndDoneToDoneGroupedPerMachine() {
        val b = AlertContent.of(alert(), true)
        val d = AlertContent.of(alert(AlertState.Done), true)
        assertEquals(AlertChannel.NeedsYou, b.channel); assertNull(b.group)
        assertEquals(AlertChannel.Done, d.channel); assertEquals("paddock-done-workstation", d.group)
        assertEquals("An agent finished.", d.publicText)
    }

    @Test fun theOnlyActionsAreOpenAndReviewAndTheyOnlyOpenTheLink() {
        val c = AlertContent.of(alert(), true)
        assertEquals(listOf(AlertAction.Open, AlertAction.Review), c.actions)
        assertEquals(setOf("Open", "Review"), AlertAction.entries.map { it.label }.toSet())
        val hint = assertIs<DeepLinkResult.Valid>(DeepLink.parse(c.link)).hint
        assertEquals(target, hint.target); assertEquals(AlertState.Blocked, hint.state); assertEquals(5L, hint.sequence)
    }

    @Test fun oneNotificationPerTerminalSoANewStateReplacesTheOldOne() {
        assertEquals(AlertContent.idFor(target), AlertContent.of(alert(), true).id)
        assertEquals(AlertContent.of(alert(), true).id, AlertContent.of(alert(AlertState.Done), true).id)
        assertNotEquals(AlertContent.idFor(target), AlertContent.idFor(target.copy(terminalId = "term_b")))
        assertTrue(AlertContent.idFor(target) >= 0)
    }

    @Test fun theChannelsAreTheFourTheDesignNames() {
        assertEquals(listOf("needs_you", "done", "machines", "watching"), AlertChannel.entries.map { it.id })
        assertEquals(listOf("Needs you", "Done", "Machines", "Watching"), AlertChannel.entries.map { it.title })
    }

    private fun raise(rules: LocalAlertRules, to: AgentStatus, a: Agent? = agent(to), enabled: Boolean = true, interactive: Boolean = false) =
        rules.alertFor(to, a, target, "laptop", 1_790_000_000, enabled, interactive, "A title")

    @Test fun onlyANewBlockedOrDoneRaisesAnAlert() {
        val rules = LocalAlertRules()
        for (s in listOf(AgentStatus.Idle, AgentStatus.Working, AgentStatus.Unknown)) assertNull(raise(rules, s), s.name)
        assertEquals(AlertState.Blocked, raise(rules, AgentStatus.Blocked)?.state)
        assertEquals(AlertState.Done, raise(LocalAlertRules(), AgentStatus.Done)?.state)
    }

    @Test fun nothingIsRaisedWhileOffOrWhileTheAppIsInFront() {
        assertNull(raise(LocalAlertRules(), AgentStatus.Blocked, enabled = false))
        assertNull(raise(LocalAlertRules(), AgentStatus.Blocked, interactive = true))
        // and the refusal does not use up the state: once the user leaves, the same agent can still raise it
        val rules = LocalAlertRules()
        assertNull(raise(rules, AgentStatus.Blocked, interactive = true))
        assertEquals(AlertState.Blocked, raise(rules, AgentStatus.Blocked)?.state)
    }

    @Test fun theSameStateOfATerminalRaisesOnceAndANewSequenceRaisesAgain() {
        val rules = LocalAlertRules()
        assertTrue(raise(rules, AgentStatus.Blocked, agent(AgentStatus.Blocked, seq = 5)) != null)
        assertNull(raise(rules, AgentStatus.Blocked, agent(AgentStatus.Blocked, seq = 5)))
        assertTrue(raise(rules, AgentStatus.Blocked, agent(AgentStatus.Blocked, seq = 9)) != null)
    }

    @Test fun anObservationTheFreshReadNoLongerBacksRaisesNothing() {
        assertNull(raise(LocalAlertRules(), AgentStatus.Blocked, agent(AgentStatus.Working)))
        assertNull(raise(LocalAlertRules(), AgentStatus.Blocked, a = null))
    }

    @Test fun theMemoryIsBounded() {
        val rules = LocalAlertRules(capacity = 3)
        val first = alert(seq = 1)
        assertTrue(rules.firstTime(first))
        for (n in 2L..5L) rules.firstTime(alert(seq = n))
        assertTrue(rules.firstTime(first), "the oldest was forgotten")
    }

    @Test fun aPushNotificationIsGenericEverywhereAndLinksToTheMachine() {
        val hint = MachineHint(HostProfileId("workstation"), "abc123")
        val c = AlertContent.ofPush("laptop", hint, 1_790_000_000_000L, hideOnLockScreen = true)
        assertEquals("Paddock: attention on laptop", c.publicTitle)
        assertEquals("An agent needs your attention.", c.publicText)
        assertEquals(c.publicTitle, c.title)
        assertEquals(c.publicText, c.text)
        assertEquals(AlertChannel.NeedsYou, c.channel)
        assertEquals("paddock://machine?h=workstation&n=abc123", c.link)
        assertEquals(listOf(AlertAction.Open, AlertAction.Review), c.actions)
        assertNull(c.group)
    }

    @Test fun aBurstOfPushesForOneMachineReplacesOneNotification() {
        val a = AlertContent.ofPush("laptop", MachineHint(HostProfileId("workstation"), "n1"), 1, true)
        val b = AlertContent.ofPush("laptop", MachineHint(HostProfileId("workstation"), "n2"), 2, true)
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, AlertContent.ofPush("laptop", MachineHint(HostProfileId("other"), "n1"), 1, true).id)
        assertNotEquals(a.id, AlertContent.idFor(target))
    }
}
