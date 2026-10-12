package io.github.tuthan.paddock.output

/**
 * The text a copy of the Output tab takes (Phase 15, C3). It is the text the screen shows and nothing else: [Ansi] has already dropped every escape
 * sequence, control character and hidden character, so what is left to do here is small and exact. Trailing spaces go (a terminal pads with them),
 * lines are joined with `\n`, and nothing is reflowed or joined: a line the agent broke stays broken.
 */
object OutputCopy {
    /**
     * The most characters one copy holds. A parcel of 400,000 UTF-16 units (200 lines of 2,000) is about 800 KB, against a 1 MB binder buffer shared
     * by the whole process, so a copy takes the newest whole lines that fit under this and says how many it left out.
     * Spike S1 (`evidence/phase-15-2026-10-11/spike-s1.md`) measured it: the share sheet fails from 300,000 units on API 26 and from 400,000 on API 36
     * and 37 (`startActivity` throws "Failure from system"), and the clipboard from 600,000 on API 26 and not at all up to 600,000 on 36 and 37. The
     * rule is half the smallest size that failed, rounded down to 10,000: 150,000.
     */
    const val BUDGET = 150_000

    /** [line] without its trailing U+0020 characters, taken across spans; [AnsiLine.cut] is kept. Other whitespace, a no-break space included, stays. */
    fun trimmed(line: AnsiLine): AnsiLine {
        var spans = line.spans
        while (spans.isNotEmpty()) {
            val last = spans.last()
            val kept = last.text.trimEnd(' ')
            if (kept.length == last.text.length) break
            spans = if (kept.isEmpty()) spans.dropLast(1) else spans.dropLast(1) + AnsiSpan(kept, last.style)
            if (kept.isNotEmpty()) break
        }
        return if (spans === line.spans) line else AnsiLine(spans, line.cut)
    }

    /** The trimmed lines joined with `\n`, with no trailing newline. An empty line is empty. */
    fun text(lines: List<AnsiLine>): String = lines.joinToString("\n") { trimmed(it).text }

    /**
     * What a copy of [lines] holds. [lines] is the newest whole lines that fit [budget] (oldest first, so the order is the screen's), [text] is
     * their copy text, [cut] counts the kept lines that [Ansi] cut at its limit, and [leftOut] counts the older lines that did not fit.
     * One line always fits: a line is at most [Ansi.MAX_LINE_CHARS] and the budget is far above that.
     */
    data class Fit(val lines: List<AnsiLine>, val text: String, val cut: Int, val leftOut: Int)

    fun fit(lines: List<AnsiLine>, budget: Int = BUDGET): Fit {
        var used = 0
        var first = lines.size
        for (i in lines.indices.reversed()) {
            val cost = trimmed(lines[i]).text.length + if (first == lines.size) 0 else 1 // the "\n" before the line that follows
            if (first != lines.size && used + cost > budget) break
            used += cost
            first = i
        }
        val kept = lines.subList(first, lines.size).toList()
        return Fit(kept, text(kept), kept.count { it.cut }, first)
    }
}
