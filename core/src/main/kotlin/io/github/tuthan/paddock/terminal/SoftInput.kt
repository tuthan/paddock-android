package io.github.tuthan.paddock.terminal

/** One thing the soft keyboard asked for, in terminal terms. */
sealed interface SoftKey {
    data class Char(val codePoint: Int) : SoftKey
    data object Enter : SoftKey
    data object Backspace : SoftKey
}

/**
 * The soft keyboard talks to a text field, not to a terminal, so the Terminal tab keeps a hidden field and turns each edit
 * into keys. The field always holds [SENTINEL] between edits: two characters nothing can see, so a Backspace on an
 * "empty" field still deletes something and arrives as a change. Every edit is read against the sentinel, sent, and the
 * field is reset to the sentinel, so nothing the user typed is ever kept in the field.
 */
object SoftInput {
    const val SENTINEL = "​​"

    /**
     * The keys that turn [SENTINEL] into [now]: one Backspace for each sentinel character the edit removed, then the inserted
     * text, with a line break as Enter. The sentinel's own characters are never sent. Edits are found by the common prefix and
     * suffix, so an insert after the caret was moved inside the sentinel is still just the insert.
     */
    fun keys(now: String): List<SoftKey> {
        val old = SENTINEL
        var prefix = 0
        while (prefix < old.length && prefix < now.length && old[prefix] == now[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < now.length - prefix && old[old.length - 1 - suffix] == now[now.length - 1 - suffix]) suffix++
        val deleted = old.length - prefix - suffix
        val inserted = now.substring(prefix, now.length - suffix)
        val out = ArrayList<SoftKey>(deleted + inserted.length)
        repeat(deleted) { out += SoftKey.Backspace }
        var i = 0
        while (i < inserted.length) {
            val cp = inserted.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\r'.code -> { if (i < inserted.length && inserted[i] == '\n') i++; out += SoftKey.Enter }
                cp == '\n'.code -> out += SoftKey.Enter
                cp == 0x200B -> Unit
                else -> out += SoftKey.Char(cp)
            }
        }
        return out
    }

    /**
     * The bytes for [key] with the key strip's armed modifiers. They apply to one key, the first, and [Encoded.spent] says
     * whether they did, so the caller can disarm them: any key spends them, including one they mean nothing to (a digit
     * under Ctrl, Shift on Enter). Ctrl and Alt are encoded as [KeyEncoder] does for a hardware key (Alt+Backspace is ESC
     * DEL, the readline "delete word"); Shift upper-cases a letter, because the keyboard has already chosen the case of
     * anything else. A character Ctrl has no meaning for is sent as it is.
     */
    fun encode(key: SoftKey, mods: Mods): Encoded {
        val spent = !mods.none
        return when (key) {
            SoftKey.Enter -> Encoded(KeyEncoder.named(NamedKey.Enter, mods), spent)
            SoftKey.Backspace -> Encoded(KeyEncoder.named(NamedKey.Backspace, mods), spent)
            is SoftKey.Char -> {
                val cp = if (mods.shift && !mods.ctrl) Character.toUpperCase(key.codePoint) else key.codePoint
                val chord = if (mods.ctrl) KeyEncoder.text(cp, Mods(alt = mods.alt, ctrl = true)) else null
                Encoded(chord ?: KeyEncoder.text(cp, Mods(alt = mods.alt)), spent)
            }
        }
    }

    /** The bytes to send ([bytes] is null for a code point a terminal cannot take) and whether the armed modifiers were spent on it. */
    class Encoded(val bytes: ByteArray?, val spent: Boolean)
}
