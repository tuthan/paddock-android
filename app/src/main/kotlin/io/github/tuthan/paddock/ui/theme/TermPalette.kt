package io.github.tuthan.paddock.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.tuthan.paddock.output.AnsiColor
import io.github.tuthan.paddock.terminal.TermColor

/**
 * A terminal cell's colour as the theme draws it. The sixteen named colours are the output slab's ([AnsiPalette]), so a
 * line reads the same in Output and in Terminal; 16 to 231 are xterm's 6x6x6 cube, 232 to 255 its grey ramp, and a 24-bit
 * colour is drawn as asked. The default colour is whatever the caller passes (the slab's text or its ground).
 */
object TermPalette {
    fun color(colors: PaddockColors, c: TermColor, default: Color): Color = when {
        c.isDefault -> default
        c.isIndexed -> indexed(colors, c.index)
        else -> Color(0xFF000000.toInt() or c.rgb)
    }

    fun indexed(colors: PaddockColors, i: Int): Color = when {
        i in 0..15 -> AnsiPalette.table(colors.isDark).getValue(AnsiColor.entries[i])
        i in 16..231 -> {
            val n = i - 16
            Color(0xFF000000.toInt() or (level(n / 36) shl 16) or (level(n / 6 % 6) shl 8) or level(n % 6))
        }
        else -> {
            val v = 8 + 10 * (i.coerceIn(232, 255) - 232)
            Color(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
        }
    }

    private fun level(v: Int) = if (v == 0) 0 else 55 + 40 * v
}
