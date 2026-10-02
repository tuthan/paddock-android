package io.github.tuthan.paddock.ui.components

import android.view.KeyEvent as AndroidKey
import io.github.tuthan.paddock.terminal.KeyEncoder
import io.github.tuthan.paddock.terminal.Mods
import io.github.tuthan.paddock.terminal.NamedKey

/**
 * A hardware key press as the bytes the terminal should receive, or null for a key that sends nothing (a bare modifier, a
 * volume key, the system's back key). Character keys use [unicode] (the key event's code point); chords with Ctrl are
 * read from the key code instead, because the unicode of Ctrl+C varies with the keyboard layout and Android version.
 */
object HardwareKeys {
    fun encode(keyCode: Int, unicode: Int, shift: Boolean, alt: Boolean, ctrl: Boolean): ByteArray? {
        val mods = Mods(shift, alt, ctrl)
        NAMED[keyCode]?.let { return KeyEncoder.named(it, mods) }
        if (ctrl) {
            val cp = ctrlCodePoint(keyCode) ?: return null
            return KeyEncoder.text(cp, mods.copy(shift = false))
        }
        if (unicode < 0x20 || unicode == 0x7F) return null
        return KeyEncoder.text(unicode, mods.copy(shift = false, ctrl = false))
    }

    /**
     * Whether a key event is a named key or a chord (Alt or Ctrl held) rather than a plain printable key. The soft keyboard's
     * hidden field encodes these itself and leaves the plain ones to the field, which turns them into text edits.
     */
    fun isRaw(keyCode: Int, alt: Boolean, ctrl: Boolean): Boolean = keyCode in NAMED || alt || ctrl

    private fun ctrlCodePoint(keyCode: Int): Int? = when (keyCode) {
        in AndroidKey.KEYCODE_A..AndroidKey.KEYCODE_Z -> 'a'.code + (keyCode - AndroidKey.KEYCODE_A)
        AndroidKey.KEYCODE_SPACE -> ' '.code
        AndroidKey.KEYCODE_LEFT_BRACKET -> '['.code
        AndroidKey.KEYCODE_RIGHT_BRACKET -> ']'.code
        AndroidKey.KEYCODE_BACKSLASH -> '\\'.code
        else -> null
    }

    private val NAMED: Map<Int, NamedKey> = buildMap {
        put(AndroidKey.KEYCODE_ENTER, NamedKey.Enter); put(AndroidKey.KEYCODE_NUMPAD_ENTER, NamedKey.Enter)
        put(AndroidKey.KEYCODE_ESCAPE, NamedKey.Escape); put(AndroidKey.KEYCODE_TAB, NamedKey.Tab)
        put(AndroidKey.KEYCODE_DEL, NamedKey.Backspace); put(AndroidKey.KEYCODE_FORWARD_DEL, NamedKey.Delete)
        put(AndroidKey.KEYCODE_INSERT, NamedKey.Insert)
        put(AndroidKey.KEYCODE_DPAD_UP, NamedKey.Up); put(AndroidKey.KEYCODE_DPAD_DOWN, NamedKey.Down)
        put(AndroidKey.KEYCODE_DPAD_LEFT, NamedKey.Left); put(AndroidKey.KEYCODE_DPAD_RIGHT, NamedKey.Right)
        put(AndroidKey.KEYCODE_MOVE_HOME, NamedKey.Home); put(AndroidKey.KEYCODE_MOVE_END, NamedKey.End)
        put(AndroidKey.KEYCODE_PAGE_UP, NamedKey.PageUp); put(AndroidKey.KEYCODE_PAGE_DOWN, NamedKey.PageDown)
        val f = listOf(NamedKey.F1, NamedKey.F2, NamedKey.F3, NamedKey.F4, NamedKey.F5, NamedKey.F6, NamedKey.F7, NamedKey.F8, NamedKey.F9, NamedKey.F10, NamedKey.F11, NamedKey.F12)
        f.forEachIndexed { i, k -> put(AndroidKey.KEYCODE_F1 + i, k) }
    }
}
