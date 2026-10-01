package io.github.tuthan.paddock.terminal

/** Keys that are not text. */
enum class NamedKey {
    Escape, Enter, Tab, Backspace, Delete, Insert, Up, Down, Left, Right, Home, End, PageUp, PageDown,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

/** Modifiers held with a key. */
data class Mods(val shift: Boolean = false, val alt: Boolean = false, val ctrl: Boolean = false) {
    val none: Boolean get() = !shift && !alt && !ctrl
    /** xterm's modifier parameter: 1 plus shift 1, alt 2, ctrl 4. */
    val param: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    companion object { val None = Mods() }
}

/**
 * The bytes a terminal receives for a key, the way xterm sends them. The frames Paddock draws from carry no application
 * mode (herdr sends cell patches only), so whether the application asked for application cursor keys or bracketed paste is
 * unknown here: arrows and Home/End are always the CSI form, which readline and the common TUI libraries read in either
 * mode, and text is never wrapped for paste. Everything is bytes for `terminal.input`; nothing is interpreted on the way.
 */
object KeyEncoder {
    private const val ESC = 0x1B

    fun named(key: NamedKey, mods: Mods = Mods.None): ByteArray {
        val m = mods.param
        val body: ByteArray = when (key) {
            NamedKey.Escape -> byteArrayOf(ESC.toByte())
            NamedKey.Enter -> byteArrayOf(if (mods.ctrl) 0x0A else 0x0D)
            NamedKey.Tab -> if (mods.shift) csi("Z") else byteArrayOf(0x09)
            NamedKey.Backspace -> byteArrayOf(if (mods.ctrl) 0x08 else 0x7F)
            NamedKey.Up -> cursor('A', m); NamedKey.Down -> cursor('B', m); NamedKey.Right -> cursor('C', m); NamedKey.Left -> cursor('D', m)
            NamedKey.Home -> cursor('H', m); NamedKey.End -> cursor('F', m)
            NamedKey.Insert -> tilde(2, m); NamedKey.Delete -> tilde(3, m); NamedKey.PageUp -> tilde(5, m); NamedKey.PageDown -> tilde(6, m)
            NamedKey.F1 -> ss3('P', m); NamedKey.F2 -> ss3('Q', m); NamedKey.F3 -> ss3('R', m); NamedKey.F4 -> ss3('S', m)
            NamedKey.F5 -> tilde(15, m); NamedKey.F6 -> tilde(17, m); NamedKey.F7 -> tilde(18, m); NamedKey.F8 -> tilde(19, m)
            NamedKey.F9 -> tilde(20, m); NamedKey.F10 -> tilde(21, m); NamedKey.F11 -> tilde(23, m); NamedKey.F12 -> tilde(24, m)
        }
        // Alt on a key that has no modifier parameter in its sequence is an ESC prefix.
        val carriesParam = key in PARAMETERISED
        return if (mods.alt && !carriesParam) byteArrayOf(ESC.toByte()) + body else body
    }

    /**
     * A character key. Ctrl turns A to Z (either case) and `@ [ \ ] ^ _ ?` and space into the control byte; any other ctrl
     * combination sends nothing, and the result is null. Alt prefixes ESC. [codePoint] must be a printable code point.
     */
    fun text(codePoint: Int, mods: Mods = Mods.None): ByteArray? {
        if (codePoint < 0x20 || codePoint == 0x7F || codePoint > Character.MAX_CODE_POINT || codePoint in 0xD800..0xDFFF) return null
        val bytes: ByteArray = if (mods.ctrl) {
            val c = ctrlByte(codePoint) ?: return null
            byteArrayOf(c)
        } else String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        return if (mods.alt) byteArrayOf(ESC.toByte()) + bytes else bytes
    }

    /** Ctrl+C, the key strip's one chord. */
    val interrupt: ByteArray get() = byteArrayOf(0x03)

    private fun ctrlByte(cp: Int): Byte? = when (cp) {
        in 'a'.code..'z'.code -> (cp - 'a'.code + 1).toByte()
        in 'A'.code..'Z'.code -> (cp - 'A'.code + 1).toByte()
        '@'.code, ' '.code -> 0
        '['.code -> 0x1B; '\\'.code -> 0x1C; ']'.code -> 0x1D; '^'.code -> 0x1E; '_'.code -> 0x1F; '?'.code -> 0x7F
        else -> null
    }

    private val PARAMETERISED = setOf(
        NamedKey.Up, NamedKey.Down, NamedKey.Left, NamedKey.Right, NamedKey.Home, NamedKey.End, NamedKey.Insert, NamedKey.Delete,
        NamedKey.PageUp, NamedKey.PageDown, NamedKey.F1, NamedKey.F2, NamedKey.F3, NamedKey.F4, NamedKey.F5, NamedKey.F6, NamedKey.F7,
        NamedKey.F8, NamedKey.F9, NamedKey.F10, NamedKey.F11, NamedKey.F12,
    )

    private fun csi(rest: String) = "\u001B[$rest".toByteArray(Charsets.US_ASCII)
    private fun cursor(final: Char, m: Int) = if (m == 1) csi("$final") else csi("1;$m$final")
    private fun ss3(final: Char, m: Int) = if (m == 1) "\u001BO$final".toByteArray(Charsets.US_ASCII) else csi("1;$m$final")
    private fun tilde(n: Int, m: Int) = if (m == 1) csi("$n~") else csi("$n;$m~")
}
