package io.github.tuthan.paddock.attention

/** The ten kinds that get a drawn glyph (Phase 12); the app maps each to a path, `core` only names them. */
enum class AgentGlyph { Claude, Codex, OpenCode, Gemini, Copilot, Cursor, Pi, Amp, Hermes, Shell }

/** What a row's tile shows for an agent kind: always a two-character [code], and a [glyph] for the kinds that have one. */
data class AgentMark(val code: String, val glyph: AgentGlyph? = null)

/**
 * Agent kind to mark, as data. herdr's `agent` field is a free string (the pinned schema has no enum), so the table is
 * open: [of] never throws and never returns an empty code, and a kind that is not listed shows its first two letters
 * until a row is added here. Listed kinds have fixed, unique codes (`AgentMarksTest` keeps it that way), which is what the
 * earlier first-two-letters rule did not: `cline` read as claude's `cl`, `kimi`, `kilo` and `kiro` were all `ki`, `maki`
 * and `mastracode` both `ma`.
 */
object AgentMarks {
    /** The 24 kinds `herdr agent start --kind` accepts on 0.9.1 (`agent-start-kinds.txt`), the self-reporting `crush`, and `shell`. */
    private val table: Map<String, AgentMark> = mapOf(
        "claude" to AgentMark("cl", AgentGlyph.Claude),
        "codex" to AgentMark("cx", AgentGlyph.Codex),
        "opencode" to AgentMark("oc", AgentGlyph.OpenCode),
        "gemini" to AgentMark("gm", AgentGlyph.Gemini),
        "copilot" to AgentMark("cp", AgentGlyph.Copilot),
        "cursor" to AgentMark("cu", AgentGlyph.Cursor),
        "pi" to AgentMark("pi", AgentGlyph.Pi),
        "amp" to AgentMark("am", AgentGlyph.Amp),
        "hermes" to AgentMark("hm", AgentGlyph.Hermes),
        "shell" to AgentMark("sh", AgentGlyph.Shell),
        "omp" to AgentMark("om"),
        "droid" to AgentMark("dr"),
        "devin" to AgentMark("dv"),
        "kimi" to AgentMark("km"),
        "kilo" to AgentMark("kl"),
        "kiro" to AgentMark("kr"),
        "qodercli" to AgentMark("qd"),
        "qwen" to AgentMark("qw"),
        "letta" to AgentMark("lt"),
        "mastracode" to AgentMark("mc"),
        "maki" to AgentMark("mk"),
        "muse" to AgentMark("ms"),
        "grok" to AgentMark("gk"),
        "agy" to AgentMark("ag"),
        "cline" to AgentMark("cn"),
        "crush" to AgentMark("cs"),
    )

    /**
     * Other spellings of a listed kind, written the way [normalise] leaves them. From the integration list and the
     * upstream agents page: Antigravity is `agy` to the start command and `antigravity-cli` to the integration list;
     * "Gemini CLI" and "Kiro CLI" are the detection-only names. `commandcode` and `primeagent` are not here: their
     * strings are not captured, so they show their first letters until they are.
     */
    private val aliases: Map<String, String> = mapOf(
        "antigravity" to "agy", "antigravitycli" to "agy",
        "claudecode" to "claude", "githubcopilot" to "copilot",
        "geminicli" to "gemini", "kirocli" to "kiro",
    )

    /** The listed kinds, for tests and for the reference sheet. */
    val listed: Map<String, AgentMark> get() = table

    /** Lower-cased, with everything that is not a letter or a digit dropped, so `Antigravity-CLI` and `GEMINI` match. */
    private fun normalise(kind: String?): String = kind.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    fun of(kind: String?): AgentMark {
        val k = normalise(kind)
        if (k.isEmpty()) return AgentMark("··")
        return table[aliases[k] ?: k] ?: AgentMark(k.take(2))
    }
}
