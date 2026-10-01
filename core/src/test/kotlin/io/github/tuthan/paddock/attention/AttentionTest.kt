package io.github.tuthan.paddock.attention

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.identity.HostProfileId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AttentionTest {
    private val host = HostProfileId("laptop")
    private fun agent(pane: String, status: AgentStatus, seq: Long? = null, title: String? = null, ready: Boolean? = null, cwd: String? = "/home/u/proj", name: String? = "claude", launching: Boolean? = null) =
        Agent(paneId = pane, terminalId = "term_$pane", workspaceId = "w1", tabId = "w1:t1", agent = name, agentStatus = status, stateChangeSeq = seq,
            terminalTitleStripped = title, interactiveReady = ready, foregroundCwd = cwd, launchPending = launching)
    private fun home(vararg a: Agent, seen: SeenLookup = SeenLookup { null }, observed: ObservedAt = ObservedAt { null }) =
        AttentionModel.home(Snapshot("0.9.1", 22, agents = a.toList()), 5_000, host, "main", 1, observed, seen)

    @Test fun sectionsFollowAttentionOrderRegardlessOfPaneOrder() {
        val h = home(agent("w1:p1", AgentStatus.Idle), agent("w1:p2", AgentStatus.Working), agent("w1:p3", AgentStatus.Done), agent("w1:p4", AgentStatus.Blocked), agent("w1:p5", AgentStatus.Unknown))
        assertEquals(listOf(Section.NeedsYou, Section.Done, Section.Working, Section.Ready, Section.Unknown), h.sections.map { it.first })
        assertEquals(listOf("w1:p4", "w1:p3", "w1:p2", "w1:p1", "w1:p5"), h.rows.map { it.paneId })
    }

    @Test fun insideASectionTheMostRecentlyChangedComesFirstWithAStableTiebreak() {
        val h = home(agent("w1:p1", AgentStatus.Blocked, seq = 3), agent("w1:p2", AgentStatus.Blocked, seq = 9), agent("w1:p3", AgentStatus.Blocked, seq = 3), agent("w1:p4", AgentStatus.Blocked))
        assertEquals(listOf("w1:p2", "w1:p1", "w1:p3", "w1:p4"), h.rows.map { it.paneId })
    }

    @Test fun readyIsIdleThatIsNotMarkedUnreadyOrStillLaunching() {
        fun state(status: AgentStatus, ready: Boolean?, launching: Boolean? = null) = home(agent("w1:p1", status, ready = ready, launching = launching)).rows.single().state
        assertEquals(StateWord.Ready, state(AgentStatus.Idle, null))
        assertEquals(StateWord.Ready, state(AgentStatus.Idle, true))
        assertEquals(StateWord.IdleNotReady, state(AgentStatus.Idle, false))
        assertEquals(StateWord.IdleNotReady, state(AgentStatus.Idle, true, launching = true), "a launch in progress is not ready")
        assertEquals(StateWord.Ready, state(AgentStatus.Idle, null, launching = false))
        // herdr could not say what the agent is doing: a readiness hint never turns that into Ready.
        assertEquals(StateWord.Unknown, state(AgentStatus.Unknown, true))
        assertEquals(StateWord.Unknown, state(AgentStatus.Unknown, null))
        assertEquals(StateWord.Unknown, state(AgentStatus.Unknown, false))
        assertEquals(StateWord.Working, state(AgentStatus.Working, true), "working is never promoted to Ready")
    }

    @Test fun titlesAndContextCannotCarryControlOrDirectionCharacters() {
        val a = agent("w1:p1", AgentStatus.Idle, title = "fix\u001b[31m the\u202E build\u200B\u0007", cwd = "/home/u/pro\u2066j")
        assertEquals("fix[31m the build", AttentionModel.title(a))
        assertEquals("proj", home(a).rows.single().context)
        assertEquals("claude · w1:p1", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "\u202E\u200B")), "a title of nothing but controls falls through")
    }

    @Test fun aSeenDoneBecomesReadyLocallyButANewerDoneComesBack() {
        val a = agent("w1:p1", AgentStatus.Done, seq = 7)
        assertEquals(Section.Done, home(a).rows.single().section)
        assertEquals(Section.Ready, home(a, seen = SeenLookup { 7 }).rows.single().section)
        assertEquals(Section.Ready, home(a, seen = SeenLookup { 9 }).rows.single().section)
        assertEquals(Section.Done, home(a.copy(stateChangeSeq = 8), seen = SeenLookup { 7 }).rows.single().section)
    }

    @Test fun titleChainUsesTheStrippedTitleElseKindAndPane() {
        assertEquals("fix the build", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "fix the build")))
        assertEquals("claude · w1:p1", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "  ")))
        assertEquals("agent · w1:p1", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, name = null)))
        assertTrue(AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "x".repeat(400))).length <= 121)
        // A presentation title an integration set comes first; a blank one falls through.
        assertEquals("Refactor auth", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "fix the build").copy(title = "Refactor auth")))
        assertEquals("fix the build", AttentionModel.title(agent("w1:p1", AgentStatus.Idle, title = "fix the build").copy(title = " ")))
    }

    @Test fun contextIsWorkspaceAndTabElseTheWorkingDirectoryBasename() {
        assertEquals("proj", home(agent("w1:p1", AgentStatus.Idle)).rows.single().context)
        assertEquals("", home(agent("w1:p1", AgentStatus.Idle, cwd = null)).rows.single().context)
        fun placed(tabLabel: String, wsLabel: String = "blindpass") = AttentionModel.home(
            Snapshot("0.9.1", 22, workspaces = listOf(io.github.tuthan.paddock.herdr.Workspace("w1", number = 2, label = wsLabel)),
                tabs = listOf(io.github.tuthan.paddock.herdr.Tab("w1:t1", "w1", number = 8, label = tabLabel)), agents = listOf(agent("w1:p1", AgentStatus.Idle))),
            5_000, host, "main", 1,
        ).rows.single().context
        assertEquals("blindpass › tab 8", placed("8"), "a tab with its default label reads by number")
        assertEquals("blindpass › Migration", placed("Migration"))
        assertEquals("blindpass › tab 8", placed(" "))
        assertEquals("workspace 2 › tab 8", placed("8", wsLabel = ""))
        assertEquals("blindpass › Mig", placed("Mig\u202E"), "labels are cleaned like titles")
    }

    @Test fun monogramsFollowTheDesignAndNeverComeOutEmpty() {
        assertEquals(listOf("cl", "cx", "oc", "gm", "sh"), listOf("claude", "Codex", "opencode", "gemini", "shell").map(Monogram::of))
        assertEquals("fa", Monogram.of("fake"))
        assertEquals("··", Monogram.of(null))
        assertEquals("··", Monogram.of("—"))
        assertEquals("claude", home(agent("w1:p1", AgentStatus.Idle)).rows.single().agentKind)
    }

    @Test fun observedAtPrefersThePhonesObservationAndFallsBackToTheReadTime() {
        val h = home(agent("w1:p1", AgentStatus.Blocked), agent("w1:p2", AgentStatus.Blocked), observed = ObservedAt { if (it == "term_w1:p1") 1_234 else null })
        assertEquals(mapOf("w1:p1" to 1_234L, "w1:p2" to 5_000L), h.rows.associate { it.paneId to it.observedAtMillis })
    }

    @Test fun summaryAndQuietState() {
        assertEquals("No agents running", home().summary); assertTrue(home().quiet)
        val busy = home(agent("w1:p1", AgentStatus.Blocked), agent("w1:p2", AgentStatus.Blocked), agent("w1:p3", AgentStatus.Working))
        assertEquals("2 need you · 1 working", busy.summary); assertFalse(busy.quiet)
        assertEquals("1 needs you", home(agent("w1:p1", AgentStatus.Blocked)).summary)
        val quiet = home(agent("w1:p1", AgentStatus.Working), agent("w1:p2", AgentStatus.Idle))
        assertEquals("Nothing needs you · 1 working · 1 ready", quiet.summary); assertTrue(quiet.quiet)
        assertFalse(home(agent("w1:p1", AgentStatus.Done)).quiet)
    }

    @Test fun ageWordingNeverClaimsDuration() {
        assertEquals("observed just now", AgeText.observed(2_000))
        assertEquals("observed 40 s ago", AgeText.observed(40_000))
        assertEquals("observed 3 min ago", AgeText.observed(185_000))
        assertEquals("observed 1 h 5 min ago", AgeText.observed(3_900_000))
        assertEquals("observed 2 d ago", AgeText.observed(2 * 86_400_000L + 5))
        assertEquals("observed just now", AgeText.observed(-50))
    }
}
