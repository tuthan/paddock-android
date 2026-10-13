package io.github.tuthan.paddock.demo

import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.ports.Clock
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The demo machine's herdr session: four agents in two workspaces whose states follow a script on [clock], and the text each one has on screen.
 * Everything is in memory and made again from nothing when the demo starts. What a real herdr would answer is built here as the same JSON the app
 * decodes from a real one (`fixtures/herdr-0.9.1/`), so the demo goes through the app's own parsing and not around it.
 *
 * The script (milliseconds after the start): `refactor` works and finishes at [FINISH_AT]; `docs` is idle and needs an answer from [BLOCK_AT]; `tests` is
 * idle; `migrate` is done. A prompt sent to an agent that accepts one (idle or done) makes it work for [PROMPT_WORK] and then finish.
 */
class DemoHerd(private val clock: Clock) {
    private val start = clock.nowMillis()
    private val lock = Any()

    private class Step(val atMillis: Long, val status: AgentStatus)

    /** [answers] are lines that appear on the agent's screen from a time on: what it says once a prompt it was given is done. */
    private class Track(val spec: Spec, val steps: MutableList<Step>, val transcript: MutableList<String>, val answers: MutableList<Pair<Long, List<String>>> = mutableListOf())

    private class Spec(
        val paneId: String, val terminalId: String, val workspaceId: String, val tabId: String,
        val kind: String, val name: String, val cwd: String, val title: String,
    )

    private val tracks: List<Track> = listOf(
        Track(Spec("w1:p1", "term_demo0001", "w1", "w1:t1", "claude", "refactor", "$HOME_WORK/api", "Refactor order validation"),
            mutableListOf(Step(0, AgentStatus.Working), Step(FINISH_AT, AgentStatus.Done)), REFACTOR.toMutableList()),
        Track(Spec("w1:p2", "term_demo0002", "w1", "w1:t2", "claude", "migrate", "$HOME_WORK/api", "Invoice status migration"),
            mutableListOf(Step(0, AgentStatus.Done)), MIGRATE.toMutableList()),
        Track(Spec("w2:p1", "term_demo0003", "w2", "w2:t1", "codex", "docs", "$HOME_WORK/site", "Write the install guide"),
            mutableListOf(Step(0, AgentStatus.Idle), Step(BLOCK_AT, AgentStatus.Blocked)), DOCS.toMutableList()),
        Track(Spec("w2:p2", "term_demo0004", "w2", "w2:t2", "opencode", "tests", "$HOME_WORK/site", "Keep the test suite green"),
            mutableListOf(Step(0, AgentStatus.Idle)), TESTS.toMutableList()),
    )

    private val workspaces = listOf("w1" to "api", "w2" to "site")

    private fun elapsed() = clock.nowMillis() - start

    private fun statusOf(t: Track, at: Long): AgentStatus = t.steps.lastOrNull { it.atMillis <= at }?.status ?: t.steps.first().status

    /** herdr's own count of this agent's state changes: the script's and the prompts' that have happened so far. */
    private fun seqOf(t: Track, at: Long): Long = 100L + (t.steps.count { it.atMillis <= at } - 1)

    /** The process id each pane's shell has, for the terminal size probe: fixed numbers, one per pane. */
    fun shellPids(): Map<String, Int> = tracks.mapIndexed { i, t -> t.spec.paneId to 4200 + i }.toMap()

    /** The pane ids of the agents, in the order the herd lists them. */
    val paneIds: List<String> get() = tracks.map { it.spec.paneId }

    /** What every agent's status is now, by pane id: the relay's event stream compares it with the last one it told. */
    fun statuses(): Map<String, AgentStatus> = synchronized(lock) { val at = elapsed(); tracks.associate { it.spec.paneId to statusOf(it, at) } }

    fun statusOf(paneId: String): AgentStatus? = synchronized(lock) { track(paneId)?.let { statusOf(it, elapsed()) } }

    private fun track(paneId: String) = tracks.firstOrNull { it.spec.paneId == paneId }

    /** The agent's `name` and kind as the relay's status event carries them. */
    fun eventFor(paneId: String, status: AgentStatus): JsonObject? = synchronized(lock) {
        val t = track(paneId) ?: return null
        buildJsonObject {
            put("event", "pane.agent_status_changed")
            put("data", buildJsonObject { put("agent", t.spec.kind); put("agent_status", status.wire); put("pane_id", t.spec.paneId); put("workspace_id", t.spec.workspaceId) })
        }
    }

    /**
     * A prompt sent to [paneId]: the agent works for [PROMPT_WORK] and finishes. The text is kept only in memory, as a line on the agent's screen.
     * False when there is no such agent.
     */
    fun prompt(paneId: String, text: String): Boolean = synchronized(lock) {
        val t = track(paneId) ?: return false
        val at = elapsed()
        t.steps += Step(at, AgentStatus.Working)
        t.steps += Step(at + PROMPT_WORK, AgentStatus.Done)
        t.transcript += listOf("", "> " + text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(120))
        t.answers += (at + PROMPT_WORK) to listOf("", "● This is a demo, so nothing ran. With your own machine the agent would get this prompt.")
        true
    }

    // ---- what herdr answers ----

    private fun agentObject(t: Track, at: Long): JsonObject = buildJsonObject {
        put("agent", t.spec.kind)
        put("agent_status", statusOf(t, at).wire)
        put("cwd", t.spec.cwd)
        put("focused", t.spec.paneId == "w1:p1")
        put("foreground_cwd", t.spec.cwd)
        put("name", t.spec.name)
        put("pane_id", t.spec.paneId)
        put("revision", 1)
        put("state_change_seq", seqOf(t, at))
        put("tab_id", t.spec.tabId)
        put("terminal_id", t.spec.terminalId)
        put("terminal_title", title(t))
        put("terminal_title_stripped", title(t))
        put("title", t.spec.title)
        put("workspace_id", t.spec.workspaceId)
    }

    private fun title(t: Track) = "${Demo.USER}@${Demo.HOSTNAME}:~/work/${t.spec.cwd.substringAfterLast('/')}"

    fun agent(paneId: String): JsonObject? = synchronized(lock) { track(paneId)?.let { agentObject(it, elapsed()) } }

    /** `session_snapshot`'s `snapshot`: the herd as it is now. */
    fun snapshot(): JsonObject = synchronized(lock) {
        val at = elapsed()
        fun worst(of: List<Track>): AgentStatus = of.map { statusOf(it, at) }.minByOrNull { RANK.indexOf(it) } ?: AgentStatus.Unknown
        buildJsonObject {
            put("agents", buildJsonArray { tracks.forEach { add(agentObject(it, at)) } })
            put("focused_pane_id", "w1:p1")
            put("focused_tab_id", "w1:t1")
            put("focused_workspace_id", "w1")
            put("layouts", buildJsonArray {
                tracks.forEach { t ->
                    add(buildJsonObject {
                        put("area", rect())
                        put("focused_pane_id", t.spec.paneId)
                        put("panes", buildJsonArray { add(buildJsonObject { put("focused", true); put("pane_id", t.spec.paneId); put("rect", rect()) }) })
                        put("splits", JsonArray(emptyList()))
                        put("tab_id", t.spec.tabId)
                        put("workspace_id", t.spec.workspaceId)
                        put("zoomed", false)
                    })
                }
            })
            put("panes", buildJsonArray {
                tracks.forEach { t ->
                    add(buildJsonObject {
                        put("agent", t.spec.kind)
                        put("agent_status", statusOf(t, at).wire)
                        put("cwd", t.spec.cwd)
                        put("focused", t.spec.paneId == "w1:p1")
                        put("foreground_cwd", t.spec.cwd)
                        put("pane_id", t.spec.paneId)
                        put("revision", 1)
                        put("scroll", buildJsonObject { put("max_offset_from_bottom", 0); put("offset_from_bottom", 0); put("viewport_rows", ROWS) })
                        put("tab_id", t.spec.tabId)
                        put("terminal_id", t.spec.terminalId)
                        put("terminal_title", title(t))
                        put("terminal_title_stripped", title(t))
                        put("title", t.spec.title)
                        put("workspace_id", t.spec.workspaceId)
                    })
                }
            })
            put("protocol", Demo.PROTOCOL)
            put("tabs", buildJsonArray {
                tracks.forEach { t ->
                    add(buildJsonObject {
                        put("agent_status", statusOf(t, at).wire)
                        put("focused", t.spec.tabId == "w1:t1")
                        put("label", t.spec.name)
                        put("number", t.spec.tabId.substringAfter(":t").toInt())
                        put("pane_count", 1)
                        put("tab_id", t.spec.tabId)
                        put("workspace_id", t.spec.workspaceId)
                    })
                }
            })
            put("version", Demo.HERDR_VERSION)
            put("workspaces", buildJsonArray {
                workspaces.forEachIndexed { i, (id, label) ->
                    val mine = tracks.filter { it.spec.workspaceId == id }
                    add(buildJsonObject {
                        put("active_tab_id", mine.first().spec.tabId)
                        put("agent_status", worst(mine).wire)
                        put("focused", id == "w1")
                        put("label", label)
                        put("number", i + 1)
                        put("pane_count", mine.size)
                        put("tab_count", mine.size)
                        put("workspace_id", id)
                    })
                }
            })
        }
    }

    private fun rect() = buildJsonObject { put("height", ROWS); put("width", COLS); put("x", 0); put("y", 0) }

    /**
     * `agent read`: the last [lines] lines of the agent's screen. [detection] is the part herdr's detector looks at (the prompt of a blocked agent, the
     * last lines of any other), [ansi] adds the colours a real one would. Null when there is no such agent.
     */
    fun read(paneId: String, detection: Boolean, lines: Int, ansi: Boolean): String? = synchronized(lock) {
        val t = track(paneId) ?: return null
        val all = screenOf(t, elapsed())
        val picked = all.takeLast(if (detection) minOf(lines, DETECTION_LINES) else lines)
        val text = if (ansi) picked.joinToString("\n") { colour(it) } else picked.joinToString("\n")
        text + "\n"
    }

    /** A full repaint of the agent's screen for a viewport of [cols] x [rows], as `terminal session observe` sends its first frame. */
    fun frame(paneId: String, cols: Int, rows: Int): JsonObject? = synchronized(lock) {
        val t = track(paneId) ?: return null
        val screen = screenOf(t, elapsed()).takeLast(rows)
        val sb = StringBuilder("\u001b[?2026h\u001b[?25l\u001b[2J\u001b[H")
        screen.forEachIndexed { i, line -> sb.append("\u001b[${i + 1};1H").append(colour(line.take(cols))) }
        sb.append("\u001b[${screen.size};1H\u001b[?25h\u001b[?2026l")
        buildJsonObject {
            put("bytes", Base64.getEncoder().encodeToString(sb.toString().toByteArray(Charsets.UTF_8)))
            put("encoding", "ansi")
            put("full", true)
            put("height", rows)
            put("seq", 1)
            put("type", "terminal.frame")
            put("width", cols)
        }
    }

    /** What the agent shows now: its transcript, and under it what its state says (a footer while it works, the question while it is blocked). */
    private fun screenOf(t: Track, at: Long): List<String> {
        val tail = when (statusOf(t, at)) {
            AgentStatus.Working -> listOf("", "✻ Working… (esc to interrupt)")
            AgentStatus.Blocked -> BLOCKED_TAIL
            AgentStatus.Idle -> listOf("", "Ready for the next task.")
            AgentStatus.Done -> if (t.spec.name == "refactor" && t.transcript.size == REFACTOR.size) listOf("", "● Done. 3 files changed, 14 tests pass.") else emptyList()
            AgentStatus.Unknown -> emptyList()
        }
        return t.transcript + t.answers.filter { it.first <= at }.flatMap { it.second } + tail
    }

    private fun colour(line: String): String = when {
        line.startsWith("● ") -> "\u001b[32m●\u001b[0m" + line.drop(1)
        line.startsWith("✻ ") -> "\u001b[33m✻\u001b[0m" + line.drop(1)
        line.startsWith("> ") -> "\u001b[2m$line\u001b[0m"
        else -> line
    }

    companion object {
        /** When `refactor` finishes: two minutes in. */
        const val FINISH_AT = 120_000L
        /** When `docs` stops to ask: twenty seconds in. */
        const val BLOCK_AT = 20_000L
        /** How long an agent that was given a prompt works before it is done. */
        const val PROMPT_WORK = 8_000L
        const val DETECTION_LINES = 12

        /** The size of every pane: narrow enough that its text is readable on a phone. */
        const val COLS = 80
        const val ROWS = 36
        private const val HOME_WORK = "${Demo.HOME}/work"

        /** What a workspace or a tab says when its panes differ: the one that needs the user first. */
        private val RANK = listOf(AgentStatus.Blocked, AgentStatus.Working, AgentStatus.Done, AgentStatus.Idle, AgentStatus.Unknown)

        private val REFACTOR = listOf(
            "● Reading src/routes/orders.ts",
            "● Updated 3 files: orders.ts, orders.test.ts, errors.ts",
            "  ⎿ Moved the checks into validateOrder()",
            "",
            "> Run the order tests again after the rename.",
            "",
            "● Bash(npm test -- orders)",
            "  ⎿ PASS src/routes/orders.test.ts",
            "     ✓ rejects an empty basket (12 ms)",
            "     ✓ prices a basket with two items (9 ms)",
            "     ✓ keeps the shipping rule for oversize parcels (11 ms)",
            "  ⎿ Tests: 14 passed, 14 total",
        )

        private val MIGRATE = listOf(
            "● Wrote migrations/0042_add_invoice_status.sql",
            "● Bash(npm run migrate)",
            "  ⎿ applied 0042_add_invoice_status",
            "",
            "● Done. The dev database has the new column and the app builds.",
        )

        private val DOCS = listOf(
            "• Added docs/install.md and linked it from README.md",
            "• Rewrote the quick start so it needs three commands",
        )

        /** What `docs` asks once it is blocked; its detection text is these lines. */
        private val BLOCKED_TAIL = listOf(
            "",
            "• Running the link checker",
            "",
            "Allow Codex to run `npm run docs:check`?",
            "",
            " › 1. Yes, proceed",
            "   2. Yes, and don't ask again for `npm run docs:check` commands in this folder",
            "   3. No, and tell Codex what to do differently",
        )

        private val TESTS = listOf(
            "● All 31 tests pass in 4.2 s.",
        )
    }
}
