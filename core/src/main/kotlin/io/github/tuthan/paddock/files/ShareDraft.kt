package io.github.tuthan.paddock.files

import io.github.tuthan.paddock.ops.AgentOperations

/**
 * How a text shared to Paddock joins what is already typed for the agent it is sent to (Phase 15, slice 11). The shared text has been cleaned and checked
 * against the prompt limit by itself ([io.github.tuthan.paddock.output.SharedTexts]); this decides what the draft becomes.
 *
 * A draft with nothing in it becomes the shared text. A draft with words keeps them: the shared text goes after a blank line, so nothing the person wrote is lost
 * or overwritten by a share. When the two together would pass the limit the share is not added and the caller says so; the share stays where it was, and the draft is
 * unchanged.
 */
object ShareDraft {
    const val TOO_LONG = "That text is too long to add to what you already wrote. Send or clear your words first."

    /** The draft's text after [shared] joins [existing], or null when the two together are over [max]. */
    fun merge(existing: String, shared: String, max: Int = AgentOperations.MAX_PROMPT_CHARS): String? {
        val joined = if (existing.isBlank()) shared else existing.trimEnd() + "\n\n" + shared
        return joined.takeIf { it.length <= max }
    }
}
