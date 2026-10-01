package io.github.tuthan.paddock.terminal

import io.github.tuthan.paddock.ports.TerminalEngine

/** Renders an engine's state in the format of `core/src/test/resources/terminal/expected/README.md`. */
object GridDump {
    fun of(engine: TerminalEngine): List<String> {
        val g = engine.grid()
        val out = ArrayList<String>()
        out += "size ${g.cols} ${g.rows}"
        val c = engine.cursor
        out += "cursor ${c.row + 1} ${c.col + 1} ${if (c.visible) "visible" else "hidden"}"
        out += "screen ${if (engine.altScreen) "alternate" else "primary"}"
        for (r in 0 until g.rows) {
            val text = g.rowText(r)
            if (text.isNotEmpty()) out += "text ${r + 1} |$text"
        }
        for (r in 0 until g.rows) out += styleRuns(g, r)
        for (r in 0 until g.rows) {
            val wide = (0 until g.cols).filter { g.isWide(r, it) }.map { it + 1 }
            if (wide.isNotEmpty()) out += "wide ${r + 1} ${wide.joinToString(" ")}"
        }
        return out
    }

    private data class Style(val fg: Int, val bg: Int, val flags: Int)

    private fun styleRuns(g: Grid, r: Int): List<String> {
        val runs = ArrayList<String>()
        var c = 0
        var start = -1; var end = -1; var cur: Style? = null
        fun close() { if (cur != null) runs += "style ${r + 1} ${if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"} ${describe(cur!!)}"; cur = null }
        while (c < g.cols) {
            if (g.isContinuation(r, c)) { c++; continue }
            val styled = !g.isBlank(r, c)
            val style = Style(g.fg(r, c).packed, g.bg(r, c).packed, g.flags(r, c) and CellFlags.STYLE_MASK)
            val width = if (g.isWide(r, c)) 2 else 1
            if (styled && style != Style(0, 0, 0)) {
                if (cur == style && end == c - 1) end = c + width - 1
                else { close(); cur = style; start = c; end = c + width - 1 }
            } else close()
            c += width
        }
        close()
        return runs
    }

    private fun describe(s: Style): String {
        val t = ArrayList<String>()
        val names = listOf(CellFlags.BOLD to "bold", CellFlags.DIM to "dim", CellFlags.ITALIC to "italic", CellFlags.UNDERLINE to "underline",
            CellFlags.BLINK to "blink", CellFlags.REVERSE to "reverse", CellFlags.HIDDEN to "hidden", CellFlags.STRIKE to "strike")
        for ((bit, name) in names) if (s.flags and bit != 0) t += name
        color("fg", TermColor(s.fg))?.let { t += it }
        color("bg", TermColor(s.bg))?.let { t += it }
        return t.joinToString(" ")
    }

    private fun color(name: String, c: TermColor): String? = when {
        c.isIndexed -> "$name=${c.index}"
        c.isRgb -> "$name=#%06x".format(c.rgb)
        else -> null
    }

    /** The lines of an expected-grid file: comments and blank lines dropped, whitespace trimmed at the ends. */
    fun parseExpected(text: String): List<String> = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.startsWith("#") }
}
