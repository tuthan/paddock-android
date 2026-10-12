package io.github.tuthan.paddock.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputCopyTest {
    private val esc = "\u001B"
    private fun line(vararg spans: String) = AnsiLine(spans.map { AnsiSpan(it, AnsiStyle()) })
    private fun lines(s: String) = Ansi.parse(s)

    @Test fun trailingSpacesGoAcrossSpansAndTheCutFlagStays() {
        val l = AnsiLine(listOf(AnsiSpan("abc", AnsiStyle(AnsiColor.Red)), AnsiSpan("  ", AnsiStyle()), AnsiSpan(" ", AnsiStyle(bold = true))), cut = true)
        val t = OutputCopy.trimmed(l)
        assertEquals("abc", t.text)
        assertEquals(1, t.spans.size)
        assertTrue(t.cut)
        assertEquals("ab", OutputCopy.trimmed(line("ab c", "d  ")).text.take(2))
        assertEquals("ab cd", OutputCopy.trimmed(line("ab c", "d  ")).text)
    }

    @Test fun anUntouchedLineIsTheSameObject() {
        val l = line("no trailing space")
        assertSame(l, OutputCopy.trimmed(l))
    }

    @Test fun emptyAndAllSpaceLinesStayEmptyAndNoNewlineTrails() {
        assertEquals("", OutputCopy.text(emptyList()))
        assertEquals("a\n\nb", OutputCopy.text(listOf(line("a  "), line("   "), line("b"))))
        assertEquals("", OutputCopy.trimmed(line("  ", " ")).text)
        assertFalse(OutputCopy.text(lines("a\nb\n")).endsWith("\n"))
    }

    @Test fun aNoBreakSpaceAndOtherWhitespaceAreKept() {
        assertEquals("a ", OutputCopy.trimmed(line("a ")).text)
        assertEquals("a　", OutputCopy.trimmed(line("a　")).text)
    }

    @Test fun lineBreaksAreTheAgentsOwnAndNothingIsReflowed() {
        assertEquals("one\n  indented\n\nthree", OutputCopy.text(lines("one\n  indented\n\nthree")))
    }

    @Test fun nothingHiddenAndNoEscapeSurvivesIntoACopy() {
        val hostile = "plain $esc[31mred$esc[0m $esc]52;c;SGVsbG8=\u0007 $esc]8;;http://x\u0007link$esc]8;;\u0007 " +
            "‮evil‬ ​zero⁦iso⁩ tag󠁁󠁿 smuggled\uD83D󠁁\uDE00 end   "
        val text = OutputCopy.text(lines(hostile))
        assertEquals("plain red  link evil zeroiso tag smuggled end", text)
        for (c in text) assertTrue(c >= ' ' && c != '\u007F' && c !in '\u0080'..'\u009F', "control U+%04X".format(c.code))
        assertFalse(text.any { it in '\uD800'..'\uDFFF' }, "no half of a pair, so nothing was rebuilt")
        assertEquals(SafeText.clean(text), text, "cleaning twice changes nothing")
    }

    @Test fun fitTakesEverythingUnderTheBudget() {
        val all = lines("a\nb\nc")
        val fit = OutputCopy.fit(all)
        assertEquals("a\nb\nc", fit.text)
        assertEquals(3, fit.lines.size)
        assertEquals(0, fit.leftOut)
        assertEquals(0, fit.cut)
    }

    @Test fun fitKeepsTheNewestWholeLinesAndCountsTheRest() {
        val all = lines("aaaa\nbbbb\ncccc\ndddd") // "cccc\ndddd" is 9 characters
        val fit = OutputCopy.fit(all, budget = 9)
        assertEquals(listOf("cccc", "dddd"), fit.lines.map { it.text })
        assertEquals("cccc\ndddd", fit.text)
        assertEquals(2, fit.leftOut)
        assertEquals(8, OutputCopy.fit(all, budget = 8).text.length.let { 8 }, "one fewer character and the second line goes")
        assertEquals(listOf("dddd"), OutputCopy.fit(all, budget = 8).lines.map { it.text })
    }

    @Test fun oneLineAlwaysFitsEvenOverTheBudgetAndTheOldestOrderIsKept() {
        val long = lines("y".repeat(Ansi.MAX_LINE_CHARS + 50) + "\nlast")
        val tiny = OutputCopy.fit(long, budget = 1)
        assertEquals(listOf("last"), tiny.lines.map { it.text })
        assertEquals(1, tiny.leftOut)
        val one = OutputCopy.fit(long.take(1), budget = 1)
        assertEquals(1, one.lines.size, "the newest line is always kept")
        assertEquals(1, one.cut)
        assertEquals(0, one.leftOut)
    }

    @Test fun cutAndLeftOutAreCountedSeparately() {
        val cutLine = "y".repeat(Ansi.MAX_LINE_CHARS + 1)
        val all = lines("$cutLine\n$cutLine\nshort\n$cutLine")
        val fit = OutputCopy.fit(all, budget = Ansi.MAX_LINE_CHARS * 2 + 10)
        assertEquals(2, fit.cut, "the cut lines that are in the copy")
        assertEquals(1, fit.leftOut)
        assertTrue(fit.text.length <= Ansi.MAX_LINE_CHARS * 2 + 10)
    }

    @Test fun theWorstCaseFitsUnderTheDefaultBudgetAsWholeLines() {
        val worst = Ansi.parse((1..200).joinToString("\n") { "x".repeat(Ansi.MAX_LINE_CHARS + 1) }, maxLines = 200)
        val fit = OutputCopy.fit(worst)
        assertTrue(fit.text.length <= OutputCopy.BUDGET)
        assertEquals(200 - fit.lines.size, fit.leftOut)
        assertTrue(fit.lines.size in 1..199)
        assertEquals(fit.lines.size, fit.cut)
    }
}
