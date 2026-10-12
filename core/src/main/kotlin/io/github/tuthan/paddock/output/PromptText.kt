package io.github.tuthan.paddock.output

import io.github.tuthan.paddock.ops.AgentOperations

/** What [cleanPrompt] made of a text: the text, and how many code points it dropped. */
data class Cleaned(val text: String, val removed: Int)

/**
 * Text from another app that is about to enter a prompt (SEC-3), or a path that is about to be copied into one. It is untrusted twice over: a person
 * cannot see what a model reads (tag characters and bidirectional controls carry instructions OWASP's LLM01 describes as "not human-visible"), and an
 * escape sequence such as `ESC [ 201 ~` would end herdr's bracketed paste early and type the rest into the agent's terminal.
 *
 * Drops everything [SafeText.isUnsafe] drops, with two exceptions that are the point of a prompt: `\n` and `\t` stay. A CRLF becomes a LF (the CR is
 * not counted); a CR on its own is dropped like any other control character. Unpaired surrogates go too, in the same pass, so dropping a tag
 * character between two of them cannot leave a new, valid pair behind (the bypass AWS describes for Java). Every character that is kept comes
 * through whole, and cleaning the result again changes nothing.
 *
 * The cost: the England, Scotland and Wales flags are tag sequences, and show as a black flag.
 */
fun SafeText.cleanPrompt(s: String): Cleaned {
    val out = StringBuilder(s.length)
    var removed = 0
    var i = 0
    while (i < s.length) {
        // codePointAt returns an unpaired surrogate as itself, which isUnsafe drops.
        val cp = s.codePointAt(i)
        when {
            cp == '\n'.code || cp == '\t'.code -> out.append(cp.toChar())
            cp == '\r'.code && i + 1 < s.length && s[i + 1] == '\n' -> Unit // the LF that follows is kept; this CR is the line ending's, not a drop
            SafeText.isUnsafe(cp) -> removed++
            else -> out.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return Cleaned(out.toString(), removed)
}

/** What a shared text came to. */
sealed interface SharedText {
    /** [text] is cleaned; [removed] is how many code points the cleaning dropped, for the draft to say. */
    data class Ok(val text: String, val removed: Int) : SharedText

    /** Longer than allowed before any work was done on it. */
    data object TooLong : SharedText

    /** Nothing to share: no text, or only blanks and what cleaning drops. */
    data object Empty : SharedText
}

object SharedTexts {
    /**
     * The one rule for a text that arrives from outside, such as `EXTRA_TEXT` of a share. Its length is checked first, on the raw
     * characters, so a huge text is refused without being copied or scanned; then [cleanPrompt] runs.
     */
    fun of(raw: CharSequence?, max: Int = AgentOperations.MAX_PROMPT_CHARS): SharedText {
        if (raw == null) return SharedText.Empty
        if (raw.length > max) return SharedText.TooLong
        val cleaned = SafeText.cleanPrompt(raw.toString())
        return if (cleaned.text.isBlank()) SharedText.Empty else SharedText.Ok(cleaned.text, cleaned.removed)
    }
}
