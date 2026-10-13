package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.files.FileTransfers

/**
 * The file waiting on the composer (Phase 15), as the draft keeps it: the intake's snapshot id, never its bytes, so the attachment survives
 * what the draft survives (a rotation, a process restore) without the sender's grant (SEC-2). [name] is the sanitized name the chip shows.
 */
data class DraftAttachment(val snapshotId: String, val name: String, val size: Long, val mime: String)

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
 * accepted must leave B. With an attachment the hash that is compared is the user's own text (X12, [OperationRecord.userTextSha256]), because
 * what herdr was sent also holds the phrase line; an acceptance takes the attachment with the text when the row named that file.
 */
data class PromptDraft(
    val terminalId: String? = null,
    val text: String = "",
    val acceptedThrough: Map<String, Long> = emptyMap(),
    val attachment: DraftAttachment? = null,
) {
    fun textFor(id: String?): String = if (id != null && id == terminalId) text else ""

    fun attachmentFor(id: String?): DraftAttachment? = if (id != null && id == terminalId) attachment else null

    /** Typing for another terminal replaces the draft, so the other terminal's attachment goes with its text. */
    fun typed(id: String, value: String): PromptDraft = copy(terminalId = id, text = value, attachment = attachmentFor(id))

    /** [a] is the file waiting for [id]'s prompt. The text stays when it is [id]'s own, and another terminal's draft is replaced, as typing does. */
    fun attached(id: String, a: DraftAttachment): PromptDraft = copy(terminalId = id, text = textFor(id), attachment = a)

    /** The user removed the attachment of [id]'s draft; another terminal's draft is not touched. */
    fun detached(id: String): PromptDraft = if (id == terminalId) copy(attachment = null) else this

    /**
     * herdr accepted prompt row [recordId] for [id]: the text that was sent went with it. The draft is emptied only while it is
     * still that text, judged by [sentSha256] (null: the row does not say, and the text is taken as before). A row already seen
     * changes nothing; a row whose text was edited since is recorded as seen and leaves the new text, and its attachment, alone.
     * Knowing only the row's id, this takes the attachment with the text whichever file it is; the form with the row checks which file went.
     */
    fun accepted(id: String, recordId: Long, sentSha256: String? = null): PromptDraft = accept(id, recordId, sentSha256, takesFile = true)

    /**
     * [accepted] for the journal row herdr accepted: compares the user's own text, which is the payload's hash when the row has no attachment. The
     * attachment goes with the text only when the row named it ([named]): a file attached while a plain prompt waited for its answer stays.
     */
    fun accepted(id: String, record: OperationRecord): PromptDraft =
        accept(id, record.id, record.userTextSha256, takesFile = attachment?.let { named(record, it) } ?: false)

    private fun accept(id: String, recordId: Long, sentSha256: String?, takesFile: Boolean): PromptDraft = when {
        recordId <= (acceptedThrough[id] ?: 0L) -> this
        id == terminalId && (sentSha256 == null || OperationJournal.sha256Hex(text) == sentSha256) ->
            copy(text = "", attachment = if (takesFile) null else attachment, acceptedThrough = acceptedThrough + (id to recordId))
        else -> copy(acceptedThrough = acceptedThrough + (id to recordId))
    }

    /**
     * Whether prompt row [record] named the file [a]: its path ends in [a]'s name or a name the inbox gave instead ([FileTransfers.inboxNames]), at
     * [a]'s size. A row with no path names no file, unless it has the user's own text hash: a prompt with a file journaled before rows kept its path.
     */
    private fun named(record: OperationRecord, a: DraftAttachment): Boolean {
        val path = record.path ?: return record.textSha256 != null
        return path.substringAfterLast('/') in FileTransfers.inboxNames(a.name) && (record.size == null || record.size == a.size)
    }

    /**
     * The draft as the plain list a saved-state saver stores (strings and longs only): the terminal id and text, the attachment (a snapshot id, name,
     * size and type; empty strings when there is none), then each terminal's accepted row. [restore] reads it back.
     */
    fun saved(): List<Any> = listOf<Any>(
        terminalId.orEmpty(), text,
        attachment?.snapshotId.orEmpty(), attachment?.name.orEmpty(), attachment?.size ?: 0L, attachment?.mime.orEmpty(),
    ) + acceptedThrough.flatMap { (id, row) -> listOf(id, row) }

    companion object {
        /** The draft [saved] wrote; a list that is not one is an empty draft, never a crash on a process restore. */
        fun restore(saved: List<Any>): PromptDraft = try {
            val snapshotId = saved[2] as String
            PromptDraft(
                terminalId = (saved[0] as String).ifEmpty { null }, text = saved[1] as String,
                acceptedThrough = saved.drop(6).chunked(2).associate { (id, row) -> id as String to row as Long },
                attachment = snapshotId.takeIf { it.isNotEmpty() }?.let { DraftAttachment(it, saved[3] as String, saved[4] as Long, saved[5] as String) },
            )
        } catch (_: RuntimeException) { PromptDraft() }
    }
}
