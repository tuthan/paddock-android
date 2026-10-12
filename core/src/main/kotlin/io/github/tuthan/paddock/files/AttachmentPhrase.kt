package io.github.tuthan.paddock.files

/**
 * The line a prompt with an attachment starts with, naming the file by its path under the agent's folder, on a line of its own before the
 * user's text. PROVISIONAL: spike S3 confirms (or changes) the wording against a real Claude Code, opencode and Codex; until then every user of the
 * phrase goes through [line], so a change is made here only.
 *
 * It holds the sanitized path and nothing the user or the sender wrote (SEC-3): a name that cannot be written into an instruction cannot carry one.
 */
object AttachmentPhrase {
    private const val PREFIX = "Attached file: "
    private val SAFE_PATH = Regex("[A-Za-z0-9._/-]{1,300}")

    /**
     * Room the phrase line and its line break take out of a prompt's length: the prefix, the `.paddock/inbox/` folder and a name of at most 100
     * characters (the script's limit), with some to spare. The composer's gate leaves this much of `MAX_PROMPT_CHARS` for the phrase.
     */
    const val RESERVED_CHARS = 160

    /** The phrase for [relPath], a path like `.paddock/inbox/2026-10-11-143205-screenshot.png`; anything outside `[A-Za-z0-9._/-]` is refused. */
    fun line(relPath: String): String {
        require(SAFE_PATH.matches(relPath)) { "an attachment is named by its sanitized path only" }
        return PREFIX + relPath
    }
}
