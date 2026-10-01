package io.github.tuthan.paddock.live

import io.github.tuthan.paddock.output.AnsiLine

/** What the expanded first blocked row shows under its title. */
sealed interface PreviewState {
    data object Loading : PreviewState

    /** The last lines of the pane's detection text, already stripped by the ANSI rules. Untrusted display data, never stored. */
    data class Showing(val lines: List<AnsiLine>, val readAtMillis: Long) : PreviewState

    /** The read failed or the pane is gone. The row says so and offers the output screen, which reads again on open. */
    data object Unavailable : PreviewState
}

/**
 * The preview for one blocked agent. [stateChangeSeq] is part of the identity: when the same terminal becomes blocked
 * again, or herdr reports a new state change, the old text is not reused for the new question.
 */
data class BlockedPreview(val terminalId: String, val stateChangeSeq: Long?, val state: PreviewState)

object PreviewText {
    const val MAX_LINES = 8

    /** Drops blank lines at both ends and keeps the last [MAX_LINES]: the prompt is at the bottom of the detection text. */
    fun trim(lines: List<AnsiLine>): List<AnsiLine> {
        val body = lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        return body.takeLast(MAX_LINES)
    }
}
