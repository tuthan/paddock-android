package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.herdr.AgentStatus

/**
 * What a re-read found, as facts. [resolved] are the unknown rows it freed (empty when nothing was waiting); each stays
 * Unknown, with the time it was re-read. Nothing here says whether a prompt was received.
 */
data class ReReadReport(val terminalId: String, val readAtMillis: Long, val status: AgentStatus, val resolved: List<OperationRecord>)

/** How a re-read ended for a terminal, kept until the user dismisses it or starts another operation. */
sealed interface RereadOutcome {
    data class Done(val report: ReReadReport) : RereadOutcome
    /** Nothing was re-read and every row is still waiting. [gone]: the agent is no longer there to read; otherwise the read failed. */
    data class Failed(val gone: Boolean, val detail: String) : RereadOutcome
}

/** What looking for a prompt's text in the output found. It describes the output, never the agent's behaviour. */
enum class TextCheck { NotKept, NoOutput, Found, NotFound }

object PromptTextCheck {
    private val whitespace = Regex("\\s+")
    private fun squeeze(s: String) = s.replace(whitespace, " ").trim()

    /**
     * Whether the text of [record] appears in [outputLines] (the lines the screen already holds), ignoring differences in
     * line breaks and runs of spaces, which a terminal adds when it wraps. [TextCheck.NotKept] when the journal kept only
     * a hash (the default); [TextCheck.NoOutput] when no output has been read yet.
     */
    fun check(record: OperationRecord, outputLines: List<String>?): TextCheck {
        val text = record.promptText?.let(::squeeze)?.takeIf { it.isNotEmpty() } ?: return TextCheck.NotKept
        if (outputLines == null) return TextCheck.NoOutput
        return if (squeeze(outputLines.joinToString(" ")).contains(text)) TextCheck.Found else TextCheck.NotFound
    }
}
