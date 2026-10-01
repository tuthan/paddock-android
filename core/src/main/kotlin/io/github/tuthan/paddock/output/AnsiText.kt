package io.github.tuthan.paddock.output

/**
 * The sixteen colours an agent can ask for. The UI maps each to a theme colour with at least 4.5:1 on the output slab:
 * [Black] and [BrightBlack] both become `dim`, never `faint`, because agents write meaningful lines in them.
 */
enum class AnsiColor { Black, Red, Green, Yellow, Blue, Magenta, Cyan, White, BrightBlack, BrightRed, BrightGreen, BrightYellow, BrightBlue, BrightMagenta, BrightCyan, BrightWhite }

data class AnsiStyle(val fg: AnsiColor? = null, val bold: Boolean = false, val dim: Boolean = false, val underline: Boolean = false)

data class AnsiSpan(val text: String, val style: AnsiStyle)

data class AnsiLine(val spans: List<AnsiSpan>) {
    val text: String get() = spans.joinToString("") { it.text }
}

/**
 * Agent output is untrusted display data. This keeps text, foreground colour, bold, dim and underline, and drops
 * everything else: no OSC (so no clipboard write, no hyperlink, no title change), no DCS or other string commands, no
 * cursor movement or erase, no control characters, no bidirectional overrides that could reorder what the user reads.
 * It never throws and never returns an ESC.
 */
object Ansi {
    const val MAX_LINE_CHARS = 2_000
    private const val MAX_CSI = 64
    private const val MAX_STRING = 4_096
    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    fun parse(input: String, maxLines: Int = 500): List<AnsiLine> {
        val lines = ArrayList<AnsiLine>()
        val spans = ArrayList<AnsiSpan>()
        val run = StringBuilder()
        var style = AnsiStyle()
        var lineChars = 0

        fun flush() { if (run.isNotEmpty()) { spans += AnsiSpan(run.toString(), style); run.clear() } }
        fun endLine() { flush(); lines += AnsiLine(spans.toList()); spans.clear(); lineChars = 0 }
        fun put(c: Char) { if (lineChars < MAX_LINE_CHARS) { run.append(c); lineChars++ } }

        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == ESC -> i = skipEscape(input, i, onSgr = { params -> flush(); style = applySgr(style, params) })
                c == '\n' -> { endLine(); i++ }
                c == '\t' -> { repeat(4) { put(' ') }; i++ }
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(input[i + 1]) -> {
                    // Tag characters (U+E0000 to U+E007F) are invisible and can smuggle text; drop them, keep the rest.
                    if (!SafeText.isUnsafe(Character.toCodePoint(c, input[i + 1]))) { put(c); put(input[i + 1]) }
                    i += 2
                }
                SafeText.isUnsafe(c.code) -> i++
                else -> { put(c); i++ }
            }
        }
        if (run.isNotEmpty() || spans.isNotEmpty()) endLine()
        // Keep the tail: the newest lines are the ones that matter.
        return if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size).toList() else lines
    }


    /** Consumes the escape sequence starting at [start] and returns the index after it. Only SGR reaches [onSgr]. */
    private inline fun skipEscape(s: String, start: Int, onSgr: (String) -> Unit): Int {
        var i = start + 1
        if (i >= s.length) return i
        when (s[i]) {
            '[' -> {
                i++
                val paramStart = i
                while (i < s.length && i - paramStart < MAX_CSI && s[i] in '0'..'?') i++
                val paramEnd = i
                while (i < s.length && i - paramStart < MAX_CSI && s[i] in ' '..'/') i++
                if (i < s.length && s[i] in '@'..'~') {
                    // An intermediate byte, or a private marker (`<`, `=`, `>`, `?`, as in `CSI > 4 ; 2 m`), makes it not SGR.
                    val params = s.substring(paramStart, paramEnd)
                    if (s[i] == 'm' && i == paramEnd && params.none { it in '<'..'?' }) onSgr(params)
                    return i + 1
                }
                return i // malformed or over-long: what was consumed is dropped, the rest reads as text
            }
            ']', 'P', 'X', '^', '_' -> {
                // String commands end at BEL or ST (ESC \). Unterminated ones are dropped up to a cap.
                i++
                val limit = minOf(s.length, i + MAX_STRING)
                while (i < limit) {
                    if (s[i] == BEL) return i + 1
                    if (s[i] == ESC && i + 1 < s.length && s[i + 1] == '\\') return i + 2
                    i++
                }
                return i
            }
            else -> {
                // ESC, optional intermediates, one final byte (charset selects, save/restore cursor, reset).
                while (i < s.length && s[i] in ' '..'/') i++
                return if (i < s.length && s[i] in '0'..'~') i + 1 else i
            }
        }
    }

    private fun applySgr(current: AnsiStyle, raw: String): AnsiStyle {
        if (raw.isEmpty()) return AnsiStyle()
        var s = current
        val groups = raw.split(';')
        var g = 0
        while (g < groups.size) {
            val group = groups[g]
            val parts = group.split(':')
            val code = parts[0].toIntOrNull() ?: if (parts[0].isEmpty()) 0 else { g++; continue }
            when (code) {
                0 -> s = AnsiStyle()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                22 -> s = s.copy(bold = false, dim = false)
                4 -> s = s.copy(underline = true)
                24 -> s = s.copy(underline = false)
                in 30..37 -> s = s.copy(fg = AnsiColor.entries[code - 30])
                in 90..97 -> s = s.copy(fg = AnsiColor.entries[8 + code - 90])
                39 -> s = s.copy(fg = null)
                38, 48, 58 -> {
                    // 38/48/58 take a following selector in either form (`38;5;n`, `38;2;r;g;b`, or colon-separated). The
                    // arguments must be consumed even for 48 and 58 (background and underline colour, not rendered), or
                    // they would read as codes.
                    val args: List<Int?>
                    if (parts.size > 1) {
                        args = parts.drop(1).map { it.toIntOrNull() }
                    } else {
                        val kind = groups.getOrNull(g + 1)?.toIntOrNull()
                        val need = when (kind) { 5 -> 2; 2 -> 4; else -> 0 }
                        args = (1..need).map { groups.getOrNull(g + it)?.toIntOrNull() }
                        g += need
                    }
                    if (code == 38) mapExtended(args)?.let { s = s.copy(fg = it) }
                }
                // Background, inverse, italic, blink and the rest are not rendered: contrast is the slab's job.
            }
            g++
        }
        return s
    }

    private fun mapExtended(args: List<Int?>): AnsiColor? {
        val kind = args.getOrNull(0) ?: return null
        return when (kind) {
            5 -> args.getOrNull(1)?.takeIf { it in 0..255 }?.let { fromIndex(it) }
            2 -> {
                // `38:2:cs:r:g:b` may carry a colour-space id; `38;2;r;g;b` does not.
                val rgb = if (args.size >= 5) args.subList(args.size - 3, args.size) else args.drop(1)
                if (rgb.size == 3 && rgb.all { it != null && it in 0..255 }) nearest(rgb[0]!!, rgb[1]!!, rgb[2]!!) else null
            }
            else -> null
        }
    }

    private fun fromIndex(n: Int): AnsiColor = when {
        n < 16 -> AnsiColor.entries[n]
        n < 232 -> {
            val level = intArrayOf(0, 95, 135, 175, 215, 255)
            val i = n - 16
            nearest(level[i / 36], level[(i / 6) % 6], level[i % 6])
        }
        else -> { val v = 8 + 10 * (n - 232); nearest(v, v, v) }
    }

    /** xterm's sixteen, by index; distance in plain RGB is enough to land a 256-colour or truecolor value on one of them. */
    private val REFERENCE = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(205, 0, 0), intArrayOf(0, 205, 0), intArrayOf(205, 205, 0),
        intArrayOf(0, 0, 238), intArrayOf(205, 0, 205), intArrayOf(0, 205, 205), intArrayOf(229, 229, 229),
        intArrayOf(127, 127, 127), intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(255, 255, 0),
        intArrayOf(92, 92, 255), intArrayOf(255, 0, 255), intArrayOf(0, 255, 255), intArrayOf(255, 255, 255),
    )

    private fun nearest(r: Int, g: Int, b: Int): AnsiColor {
        var best = 0
        var bestD = Int.MAX_VALUE
        for ((i, ref) in REFERENCE.withIndex()) {
            val d = (r - ref[0]).let { it * it } + (g - ref[1]).let { it * it } + (b - ref[2]).let { it * it }
            if (d < bestD) { bestD = d; best = i }
        }
        return AnsiColor.entries[best]
    }
}

/**
 * Untrusted text the UI shows outside the output slab (titles, working directories): no control characters, no
 * bidirectional overrides or isolates that reorder what is read, no zero-width or tag characters that hide text, and
 * no line or paragraph separators that break a one-line row. What is left is shown as written.
 */
object SafeText {
    fun isUnsafe(cp: Int): Boolean =
        cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C ||
            // Zero-width space and invisible operators go; the joiners (U+200C, U+200D) stay, as emoji and many scripts need them.
            cp == 0x200B || cp in 0x2060..0x2064 || cp == 0xFEFF || cp == 0x2028 || cp == 0x2029 ||
            cp in 0xE0000..0xE007F

    fun clean(s: String): String {
        if (s.codePoints().noneMatch(::isUnsafe)) return s
        val out = StringBuilder(s.length)
        s.codePoints().forEach { cp -> if (!isUnsafe(cp)) out.appendCodePoint(cp) else if (cp == '\t'.code) out.append(' ') }
        return out.toString()
    }
}
