package io.github.tuthan.paddock.answers

/** What the phone calls the agents whose permission prompts it can answer, by the kind herdr reports for the pane. */
object AgentNames {
    /** "Claude Code", "Codex" or "opencode" for those kinds; null for any other (the caller says "the agent"). */
    fun label(kind: String?): String? = when (kind?.trim()?.lowercase()) {
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        "opencode" -> "opencode"
        else -> null
    }

    /** Codex, by the kind herdr reports. herdr's status for its pane is not a reliable sign of a waiting request (see [AnswerRules.readsAtFullPace]). */
    fun isCodex(kind: String?): Boolean = kind?.trim()?.lowercase() == "codex"
}
