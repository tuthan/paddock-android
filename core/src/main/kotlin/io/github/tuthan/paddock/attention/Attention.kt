package io.github.tuthan.paddock.attention

import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.herdr.boundedForUi
import io.github.tuthan.paddock.output.SafeText
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey

/** The home list's sections, in attention order. */
enum class Section(val heading: String) {
    NeedsYou("Needs you"), Done("Done"), Working("Working"), Ready("Ready"), Unknown("Unknown")
}

/**
 * What the row says about the state, as a word. Colour never carries it alone, and [Unknown] has its own shape (a
 * ring) so it is distinguishable without colour. [IdleNotReady] is an idle agent that reports it is not yet taking input.
 */
enum class StateWord(val word: String) {
    Blocked("Blocked"), Done("Done"), Working("Working"), Ready("Ready"), IdleNotReady("Idle · starting"), Unknown("Unknown")
}

/** What the phone knows about when it saw a state. Null when it only knows the read time. */
fun interface ObservedAt { fun of(terminalId: String): Long? }

/** Which Done terminals the user already acknowledged on this phone: terminal id to the `state_change_seq` they saw. */
fun interface SeenLookup { fun seenSeq(terminalId: String): Long? }

data class AgentRowModel(
    val key: TerminalKey,
    val paneId: String,
    val title: String,
    /** Where the agent is: "workspace › tab", else the working directory's name, else empty. */
    val context: String,
    val state: StateWord,
    val section: Section,
    /** When the phone observed this state, or the time of the read that first showed it; never "blocked for". */
    val observedAtMillis: Long,
    val stateChangeSeq: Long?,
    /** herdr's agent kind (`claude`, `codex`, …), for the monogram; null when herdr does not say. */
    val agentKind: String? = null,
)

/** Two lowercase letters for an agent kind, as the design's monograms: known kinds by name, others by their first letters. */
object Monogram {
    private val known = mapOf(
        "claude" to "cl", "codex" to "cx", "opencode" to "oc", "gemini" to "gm", "copilot" to "cp",
        "pi" to "pi", "amp" to "am", "hermes" to "hm", "shell" to "sh",
    )

    fun of(kind: String?): String {
        val k = kind?.trim()?.lowercase().orEmpty()
        known[k]?.let { return it }
        val letters = k.filter { it in 'a'..'z' || it in '0'..'9' }
        return if (letters.isEmpty()) "··" else letters.take(2)
    }
}

data class HomeModel(
    val sections: List<Pair<Section, List<AgentRowModel>>>,
    val summary: String,
    /** True when nothing needs the user: no Blocked and no unseen Done. */
    val quiet: Boolean,
) {
    val rows: List<AgentRowModel> get() = sections.flatMap { it.second }
}

object AttentionModel {
    /**
     * Builds the home list from an installed snapshot. herdr's own `agent list` is in pane order, not attention
     * order (observed on 0.9.1), so the order is ours: sections as declared, and inside a section the most recently
     * changed state first (`state_change_seq` descending, then pane id for a stable tiebreak).
     */
    fun home(
        snapshot: Snapshot,
        readAtMillis: Long,
        host: HostProfileId,
        session: String,
        epoch: Long,
        observedAt: ObservedAt = ObservedAt { null },
        seen: SeenLookup = SeenLookup { null },
    ): HomeModel {
        val rows = snapshot.agents.map { a -> row(a, snapshot, readAtMillis, host, session, epoch, observedAt, seen) }
        val grouped = Section.entries.map { s -> s to rows.filter { it.section == s }.sortedWith(compareByDescending<AgentRowModel> { it.stateChangeSeq ?: -1L }.thenBy { it.paneId }) }
            .filter { it.second.isNotEmpty() }
        val needs = rows.count { it.section == Section.NeedsYou }
        val done = rows.count { it.section == Section.Done }
        return HomeModel(grouped, summary(rows), quiet = needs == 0 && done == 0)
    }

    fun section(a: Agent, seen: SeenLookup): Pair<StateWord, Section> = when (a.agentStatus) {
        AgentStatus.Blocked -> StateWord.Blocked to Section.NeedsYou
        AgentStatus.Done ->
            // A Done the user already acknowledged at this state_change_seq is Ready locally, without calling herdr.
            if (seen.seenSeq(a.terminalId)?.let { it >= (a.stateChangeSeq ?: 0) } == true) StateWord.Ready to Section.Ready
            else StateWord.Done to Section.Done
        AgentStatus.Working -> StateWord.Working to Section.Working
        // Ready is an idle agent herdr does not say is still starting: `interactive_ready` false or `launch_pending` true
        // makes it "Idle · starting". An unknown status is Unknown whatever the hints say. (Phase 06's send rule adds a
        // fresh read and requires interactive_ready true; a Ready row is not by itself permission to type.)
        AgentStatus.Idle -> if (a.interactiveReady == false || a.launchPending == true) StateWord.IdleNotReady to Section.Ready else StateWord.Ready to Section.Ready
        AgentStatus.Unknown -> StateWord.Unknown to Section.Unknown
    }

    /** The title chain: the presentation title an integration set, then the terminal's own title with its prompt stripped, else the agent kind and pane id. */
    fun title(a: Agent): String =
        (a.title.shown() ?: a.terminalTitleStripped.shown() ?: "${a.displayAgent.shown() ?: a.agent.shown() ?: "agent"} · ${a.paneId}").boundedForUi(120)

    /**
     * "workspace › tab" from the snapshot, as herdr labels them; a tab still carrying its default label (its number)
     * reads "tab N". Without a known workspace, the working directory's name.
     */
    private fun context(a: Agent, s: Snapshot): String {
        val workspace = s.workspaces.firstOrNull { it.workspaceId == a.workspaceId }
        if (workspace != null) {
            val ws = workspace.label.shown() ?: "workspace ${workspace.number}"
            val tab = s.tabs.firstOrNull { it.tabId == a.tabId }
            val tabText = tab?.let { t -> t.label.shown()?.takeIf { it != t.number.toString() } ?: "tab ${t.number}" }
            return if (tabText == null) ws else "$ws › $tabText"
        }
        return (a.foregroundCwd ?: a.cwd)?.let { it.trimEnd('/').substringAfterLast('/').ifEmpty { it } }?.let(SafeText::clean) ?: ""
    }

    /** Titles come from terminals and integrations: untrusted text, cleaned of controls and reordering marks before display. */
    private fun String?.shown(): String? = this?.let(SafeText::clean)?.takeIf { it.isNotBlank() }

    private fun row(a: Agent, s: Snapshot, readAt: Long, host: HostProfileId, session: String, epoch: Long, observedAt: ObservedAt, seen: SeenLookup): AgentRowModel {
        val (word, section) = section(a, seen)
        return AgentRowModel(
            key = TerminalKey(TargetRef(host, session, a.terminalId), epoch), paneId = a.paneId, title = title(a), context = context(a, s).boundedForUi(80),
            state = word, section = section, observedAtMillis = observedAt.of(a.terminalId) ?: readAt, stateChangeSeq = a.stateChangeSeq,
            agentKind = a.agent.shown(),
        )
    }

    fun summary(rows: List<AgentRowModel>): String {
        if (rows.isEmpty()) return "No agents running"
        val n = { s: Section -> rows.count { it.section == s } }
        val parts = buildList {
            if (n(Section.NeedsYou) > 0) add("${n(Section.NeedsYou)} ${if (n(Section.NeedsYou) == 1) "needs" else "need"} you")
            if (n(Section.Done) > 0) add("${n(Section.Done)} done")
            if (n(Section.Working) > 0) add("${n(Section.Working)} working")
            if (n(Section.Ready) > 0) add("${n(Section.Ready)} ready")
            if (n(Section.Unknown) > 0) add("${n(Section.Unknown)} unknown")
        }
        return if (n(Section.NeedsYou) == 0 && n(Section.Done) == 0) "Nothing needs you · " + parts.joinToString(" · ") else parts.joinToString(" · ")
    }
}

/** "observed 40 s ago": the phone saw the state then; it never claims how long the state has lasted. */
object AgeText {
    fun observed(ageMillis: Long): String = "observed " + span(ageMillis)

    fun span(ageMillis: Long): String {
        val s = (ageMillis.coerceAtLeast(0)) / 1000
        return when {
            s < 5 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
            else -> "${s / 86_400} d ago"
        }
    }
}
