package io.github.tuthan.paddock.ops

/**
 * The text typed in the composer, held by the app above the composer screen so that it outlives the screen: Edit snippets
 * and Back leave the composer, and a reconnect replaces it for a while with "not available". It belongs to one terminal
 * (another agent never inherits it). It is the user's unsent text, not a journal field: whether the journal keeps a sent
 * prompt's text is the Settings choice and is unrelated to this.
 *
 * [acceptedThrough] is, per terminal, the newest accepted prompt row whose acceptance has already been applied here. The
 * composer sees an old accepted outcome again whenever it opens or turns, and that must not empty text typed after it. It is
 * per terminal because two sends can be in flight on different agents, and the answer for the older row may come last.
 *
 * The field stays editable while a send is in flight, so an acceptance takes the text only if the text is still what was sent
 * (the acknowledgement carries the sent prompt's SHA-256): sending A, editing the field to B and then hearing that A was
 * accepted must leave B.
 */
data class PromptDraft(val terminalId: String? = null, val text: String = "", val acceptedThrough: Map<String, Long> = emptyMap()) {
    fun textFor(id: String?): String = if (id != null && id == terminalId) text else ""

    fun typed(id: String, value: String): PromptDraft = copy(terminalId = id, text = value)

    /**
     * herdr accepted prompt row [recordId] for [id]: the text that was sent went with it. The draft is emptied only while it is
     * still that text, judged by [sentSha256] (null: the row does not say, and the text is taken as before). A row already seen
     * changes nothing; a row whose text was edited since is recorded as seen and leaves the new text alone.
     */
    fun accepted(id: String, recordId: Long, sentSha256: String? = null): PromptDraft = when {
        recordId <= (acceptedThrough[id] ?: 0L) -> this
        id == terminalId && (sentSha256 == null || OperationJournal.sha256Hex(text) == sentSha256) ->
            copy(text = "", acceptedThrough = acceptedThrough + (id to recordId))
        else -> copy(acceptedThrough = acceptedThrough + (id to recordId))
    }
}
