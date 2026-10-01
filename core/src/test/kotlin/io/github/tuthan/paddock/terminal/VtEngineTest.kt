package io.github.tuthan.paddock.terminal

import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The engine beyond the fixtures: every sequence herdr or a future herdr may send, and everything it must never act on. */
class VtEngineTest {
    private fun engine(cols: Int = 20, rows: Int = 6) = VtEngine(cols, rows)
    private fun VtEngine.send(s: String) = feed(s.toByteArray(Charsets.UTF_8))
    private fun VtEngine.row(r: Int) = grid().rowText(r)
    private fun VtEngine.rows() = (0 until rows).map { grid().rowText(it) }
    private fun VtEngine.at(r: Int, c: Int) = grid().text(r, c)
    private val ESC = "\u001B"

    // --- text and controls ---

    @Test fun textGoesWhereTheCursorIs() {
        val e = engine(); e.send("hello"); assertEquals("hello", e.row(0)); assertEquals(Cursor(0, 5, true), e.cursor)
    }

    @Test fun carriageReturnLineFeedAndBackspace() {
        val e = engine(); e.send("ab\r\ncd\bX"); assertEquals(listOf("ab", "cX"), e.rows().take(2)); assertEquals(Cursor(1, 2, true), e.cursor)
    }

    @Test fun lineFeedKeepsTheColumnLikeARealTerminal() {
        val e = engine(); e.send("ab\ncd"); assertEquals(listOf("ab", "  cd"), e.rows().take(2))
    }

    @Test fun tabsStopEveryEightColumns() {
        val e = engine(); e.send("a\tb"); assertEquals("a       b", e.row(0))
    }

    @Test fun bellAndOtherControlsDoNothingVisible() {
        val e = engine(); e.send("a\u0007\u0000\u000E\u000Fb"); assertEquals("ab", e.row(0)); assertEquals(0, e.unhandled)
    }

    @Test fun textWrapsAtTheRightEdgeAndScrollsAtTheBottom() {
        val e = engine(5, 3); e.send("abcdefghij"); assertEquals(listOf("abcde", "fghij", ""), e.rows())
        e.send("klmnopqrst"); assertEquals(listOf("fghij", "klmno", "pqrst"), e.rows())
    }

    @Test fun theLastColumnWaitsForTheNextCharacterBeforeWrapping() {
        val e = engine(5, 3); e.send("abcde"); assertEquals(Cursor(0, 4, true), e.cursor)
        e.send("\r"); e.send("X"); assertEquals(listOf("Xbcde", "", ""), e.rows(), "a carriage return cancels the pending wrap")
    }

    @Test fun withAutowrapOffTheLastCellIsOverwritten() {
        val e = engine(5, 2); e.send("$ESC[?7labcdefg"); assertEquals(listOf("abcdg", ""), e.rows())
    }

    // --- cursor movement ---

    @Test fun cursorPositionDefaultsAndClamps() {
        val e = engine(10, 5)
        e.send("$ESC[3;4H"); assertEquals(Cursor(2, 3, true), e.cursor)
        e.send("$ESC[H"); assertEquals(Cursor(0, 0, true), e.cursor)
        e.send("$ESC[;5H"); assertEquals(Cursor(0, 4, true), e.cursor)
        e.send("$ESC[3;H"); assertEquals(Cursor(2, 0, true), e.cursor)
        e.send("$ESC[99;99H"); assertEquals(Cursor(4, 9, true), e.cursor)
        e.send("$ESC[0;0H"); assertEquals(Cursor(0, 0, true), e.cursor, "zero means one")
        e.send("$ESC[2;3f"); assertEquals(Cursor(1, 2, true), e.cursor)
    }

    @Test fun relativeMovesClampAtTheEdges() {
        val e = engine(10, 5); e.send("$ESC[3;5H$ESC[2A"); assertEquals(Cursor(0, 4, true), e.cursor)
        e.send("$ESC[9A"); assertEquals(Cursor(0, 4, true), e.cursor)
        e.send("$ESC[B$ESC[2C"); assertEquals(Cursor(1, 6, true), e.cursor)
        e.send("$ESC[99D"); assertEquals(Cursor(1, 0, true), e.cursor)
        e.send("$ESC[9;9H$ESC[E"); assertEquals(Cursor(4, 0, true), e.cursor)
        e.send("$ESC[3F"); assertEquals(Cursor(1, 0, true), e.cursor)
        e.send("$ESC[7G"); assertEquals(Cursor(1, 6, true), e.cursor)
        e.send("$ESC[4d"); assertEquals(Cursor(3, 6, true), e.cursor)
    }

    @Test fun saveAndRestoreCursorKeepThePenToo() {
        val e = engine(); e.send("$ESC[1;31m$ESC[2;3H${ESC}7$ESC[0m$ESC[5;5H${ESC}8X")
        assertEquals(Cursor(1, 3, true), e.cursor)
        assertEquals("X", e.at(1, 2)); assertEquals(TermColor.indexed(1), e.grid().fg(1, 2)); assertTrue(e.grid().flags(1, 2) and CellFlags.BOLD != 0)
        val f = engine(); f.send("$ESC[3;4H$ESC[s$ESC[H$ESC[u"); assertEquals(Cursor(2, 3, true), f.cursor)
    }

    // --- erase and edit ---

    @Test fun eraseInLine() {
        val e = engine(10, 3); e.send("0123456789$ESC[1;5H$ESC[K"); assertEquals("0123", e.row(0))
        e.send("\r0123456789$ESC[1;5H$ESC[1K"); assertEquals("     56789", e.row(0))
        e.send("$ESC[2K"); assertEquals("", e.row(0))
    }

    @Test fun eraseInDisplay() {
        val e = engine(6, 3); e.send("aaaaaa\r\nbbbbbb\r\ncccccc$ESC[2;3H$ESC[J"); assertEquals(listOf("aaaaaa", "bb", ""), e.rows())
        val f = engine(6, 3); f.send("aaaaaa\r\nbbbbbb\r\ncccccc$ESC[2;3H$ESC[1J"); assertEquals(listOf("", "   bbb", "cccccc"), f.rows())
        f.send("$ESC[2J"); assertEquals(listOf("", "", ""), f.rows())
        f.send("zz$ESC[3J"); assertEquals(listOf("", "", ""), f.rows())
    }

    @Test fun insertDeleteAndEraseCharacters() {
        val e = engine(8, 2); e.send("abcdefgh$ESC[1;3H$ESC[2@"); assertEquals("ab  cdef", e.row(0))
        e.send("$ESC[1;3H$ESC[3P"); assertEquals("abdef", e.row(0))
        e.send("$ESC[1;2H$ESC[2X"); assertEquals("a  ef", e.row(0))
    }

    @Test fun insertAndDeleteLinesWorkInsideTheScrollRegion() {
        val e = engine(4, 5); e.send("1\r\n2\r\n3\r\n4\r\n5$ESC[2;4r"); assertEquals(Cursor(0, 0, true), e.cursor)
        e.send("$ESC[2;1H$ESC[L"); assertEquals(listOf("1", "", "2", "3", "5"), e.rows())
        e.send("$ESC[M"); assertEquals(listOf("1", "2", "3", "", "5"), e.rows())
        e.send("$ESC[5;1H$ESC[L"); assertEquals(listOf("1", "2", "3", "", "5"), e.rows(), "outside the region nothing moves")
    }

    @Test fun scrollRegionScrollsOnLineFeedAndReverseIndex() {
        val e = engine(4, 4); e.send("a\r\nb\r\nc\r\nd$ESC[2;3r$ESC[3;1H\n"); assertEquals(listOf("a", "c", "", "d"), e.rows())
        e.send("$ESC[2;1H${ESC}M"); assertEquals(listOf("a", "", "c", "d"), e.rows())
        e.send("$ESC[S"); assertEquals(listOf("a", "c", "", "d"), e.rows())
        e.send("$ESC[T"); assertEquals(listOf("a", "", "c", "d"), e.rows())
    }

    @Test fun eraseUsesTheCurrentBackground() {
        val e = engine(4, 2); e.send("$ESC[44m$ESC[2J"); assertEquals(TermColor.indexed(4), e.grid().bg(1, 3))
    }

    // --- SGR ---

    private fun style(e: VtEngine, r: Int = 0, c: Int = 0) = Triple(e.grid().fg(r, c), e.grid().bg(r, c), e.grid().flags(r, c) and CellFlags.STYLE_MASK)

    @Test fun sgrAttributesSetAndClear() {
        val e = engine()
        e.send("$ESC[1;2;3;4;5;7;8;9mX"); val all = CellFlags.BOLD or CellFlags.DIM or CellFlags.ITALIC or CellFlags.UNDERLINE or CellFlags.BLINK or CellFlags.REVERSE or CellFlags.HIDDEN or CellFlags.STRIKE
        assertEquals(all, e.grid().flags(0, 0))
        e.send("$ESC[22;23;24;25;27;28;29mY"); assertEquals(0, e.grid().flags(0, 1))
        e.send("$ESC[1;31m$ESC[mZ"); assertEquals(Triple(TermColor.Default, TermColor.Default, 0), style(e, 0, 2))
        e.send("$ESC[1;31;0mW"); assertEquals(Triple(TermColor.Default, TermColor.Default, 0), style(e, 0, 3))
    }

    @Test fun sgrSixteenColoursBrightAndDefaults() {
        val e = engine()
        e.send("$ESC[31;42mA$ESC[91;102mB$ESC[39;49mC")
        assertEquals(TermColor.indexed(1), e.grid().fg(0, 0)); assertEquals(TermColor.indexed(2), e.grid().bg(0, 0))
        assertEquals(TermColor.indexed(9), e.grid().fg(0, 1)); assertEquals(TermColor.indexed(10), e.grid().bg(0, 1))
        assertEquals(TermColor.Default, e.grid().fg(0, 2)); assertEquals(TermColor.Default, e.grid().bg(0, 2))
    }

    @Test fun sgrExtendedColoursInEveryWrittenForm() {
        val e = engine(30, 2)
        e.send("$ESC[38;5;196mA$ESC[48;5;21mB$ESC[38;2;10;20;30mC$ESC[48;2;40;50;60mD")
        assertEquals(TermColor.indexed(196), e.grid().fg(0, 0)); assertEquals(TermColor.indexed(21), e.grid().bg(0, 1))
        assertEquals(TermColor.rgb(10, 20, 30), e.grid().fg(0, 2)); assertEquals(TermColor.rgb(40, 50, 60), e.grid().bg(0, 3))
        e.send("$ESC[0m$ESC[38:5:99mE$ESC[38:2:1:2:3mF$ESC[38:2::4:5:6mG$ESC[48:2:0:7:8:9mH")
        assertEquals(TermColor.indexed(99), e.grid().fg(0, 4)); assertEquals(TermColor.rgb(1, 2, 3), e.grid().fg(0, 5))
        assertEquals(TermColor.rgb(4, 5, 6), e.grid().fg(0, 6)); assertEquals(TermColor.rgb(7, 8, 9), e.grid().bg(0, 7))
    }

    @Test fun sgrExtendedColourArgumentsAreNeverReadAsCodes() {
        val e = engine()
        // 58 (underline colour) is skipped with its arguments; the 1 after it is bold, the 5 and 2 inside are not blink and dim.
        e.send("$ESC[58;5;2;1mX"); assertEquals(CellFlags.BOLD, e.grid().flags(0, 0)); assertEquals(TermColor.Default, e.grid().fg(0, 0))
        e.send("$ESC[0m$ESC[58;2;5;5;5mY"); assertEquals(0, e.grid().flags(0, 1))
        e.send("$ESC[0m$ESC[48;5;4;38;5;5mZ"); assertEquals(TermColor.indexed(4), e.grid().bg(0, 2)); assertEquals(TermColor.indexed(5), e.grid().fg(0, 2)); assertEquals(0, e.grid().flags(0, 2))
        e.send("$ESC[0m$ESC[38;5;999mW$ESC[38;2;1;2mV"); assertEquals(TermColor.Default, e.grid().fg(0, 3)); assertEquals(TermColor.Default, e.grid().fg(0, 4))
    }

    @Test fun underlineStylesAreUnderlineAndColonZeroTurnsItOff() {
        val e = engine(); e.send("$ESC[4:3mA$ESC[4:0mB$ESC[4mC$ESC[21mD$ESC[24mE")
        val f = (0..4).map { e.grid().flags(0, it) and CellFlags.UNDERLINE != 0 }
        assertEquals(listOf(true, false, true, true, false), f)
    }

    @Test fun anSgrWithTooManyParametersStillWorksAndNeverThrows() {
        val e = engine(); e.send("$ESC[" + (1..200).joinToString(";") { "1" } + "mX"); assertTrue(e.grid().flags(0, 0) and CellFlags.BOLD != 0)
        e.send("$ESC[99999999999999m"); e.send("Y")
    }

    // --- wide and combining characters ---

    @Test fun aWideCharacterTakesTwoCellsAndTheCursorMovesTwo() {
        val e = engine(); e.send("a日b"); assertEquals("a日b", e.row(0)); assertEquals(Cursor(0, 4, true), e.cursor)
        assertTrue(e.grid().isWide(0, 1)); assertTrue(e.grid().isContinuation(0, 2)); assertEquals("", e.at(0, 2)); assertEquals("b", e.at(0, 3))
    }

    @Test fun overwritingHalfAWideCharacterBlanksTheOtherHalf() {
        val e = engine(); e.send("日$ESC[1;2HX"); assertEquals(" X", e.row(0)); assertFalse(e.grid().isWide(0, 0))
        val f = engine(); f.send("日$ESC[1;1HX"); assertEquals("X", f.row(0)); assertFalse(f.grid().isContinuation(0, 1))
        val g = engine(); g.send("ab日$ESC[1;2H日"); assertEquals("a日", g.row(0).trimEnd()); assertTrue(g.grid().isBlank(0, 3), "the old continuation of the character that was cut is gone")
    }

    @Test fun aWideCharacterThatDoesNotFitWrapsToTheNextRow() {
        val e = engine(5, 3); e.send("abcd日"); assertEquals(listOf("abcd", "日", ""), e.rows())
    }

    @Test fun combiningMarksJoinThePreviousCell() {
        val e = engine(); e.send("éx"); assertEquals("éx", e.row(0)); assertEquals(Cursor(0, 2, true), e.cursor)
        val f = engine(); f.send("́"); assertEquals("", f.row(0), "a mark at the start of a line has nothing to join")
        val z = engine(); z.send("a‍b"); assertEquals("a‍b", z.row(0)); assertEquals(Cursor(0, 2, true), z.cursor, "a zero-width joiner takes no cell")
    }

    @Test fun anEmojiTakesTwoCells() {
        val e = engine(); e.send("😀!"); assertEquals(Cursor(0, 3, true), e.cursor); assertTrue(e.grid().isWide(0, 0))
    }

    // --- UTF-8 ---

    @Test fun invalidUtf8BecomesReplacementCharactersAndTheTextAfterSurvives() {
        val e = engine(); e.feed(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0xC3.toByte(), 'c'.code.toByte(), 0xE2.toByte(), 0x82.toByte(), 'd'.code.toByte()))
        assertEquals("a�b�c�d", e.row(0))
    }

    @Test fun overlongAndSurrogateEncodingsAreRefused() {
        val e = engine(); e.feed(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 'x'.code.toByte())); assertFalse('\u0000' in e.row(0)); assertTrue(e.row(0).endsWith("x"))
        val s = engine(); s.feed(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())); assertEquals("�", s.row(0))
        val big = engine(); big.feed(byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte())); assertEquals("�", big.row(0))
    }

    // --- modes ---

    @Test fun cursorVisibilityFollowsTheMode() {
        val e = engine(); e.send("$ESC[?25l"); assertFalse(e.cursor.visible); e.send("$ESC[?25h"); assertTrue(e.cursor.visible)
    }

    @Test fun theSynchronizedUpdateAndOtherModesChangeNoCell() {
        val e = engine(); e.send("hi$ESC[?2026h$ESC[?1h$ESC[?1000h$ESC[?1006h$ESC[?2004h$ESC[?1004h$ESC[?12l$ESC[?2026l$ESC[4h$ESC[20l")
        assertEquals("hi", e.row(0)); assertEquals(0, e.unhandled)
    }

    @Test fun theAlternateScreenIsASeparateBufferAndTheCursorComesBack() {
        val e = engine(); e.send("main$ESC[2;3H$ESC[?1049h"); assertTrue(e.altScreen); assertEquals(listOf("", "", "", "", "", ""), e.rows())
        e.send("alt"); assertEquals("alt", e.rows().first { it.isNotEmpty() }.trim(), "the cursor keeps its place on entering the alternate screen")
        e.send("$ESC[?1049l"); assertFalse(e.altScreen); assertEquals("main", e.row(0)); assertEquals(Cursor(1, 2, true), e.cursor)
        val f = engine(); f.send("keep$ESC[?47hjunk$ESC[?47l"); assertEquals("keep", f.row(0))
        f.send("$ESC[?1049h$ESC[?1049h"); assertTrue(f.altScreen)
    }

    // --- things it must never act on ---

    private fun untouched(seq: String) {
        val e = engine(); e.send("keep"); val before = e.rows(); val cursor = e.cursor
        e.send(seq)
        assertEquals(before, e.rows(), "grid changed by ${seq.replace(ESC, "ESC").take(40)}"); assertEquals(cursor, e.cursor)
    }

    @Test fun oscStringsOfEveryKindChangeNothingAndAreCountedAsDropped() {
        for (seq in listOf(
            "$ESC]52;c;SGVsbG8=\u0007", "$ESC]52;c;SGVsbG8=$ESC\\", "$ESC]0;title\u0007", "$ESC]2;title$ESC\\",
            "$ESC]8;;https://example.invalid/x$ESC\\link$ESC]8;;$ESC\\".replace("link", ""), "$ESC]8;;$ESC\\", "$ESC]7;file:///etc/passwd\u0007",
            "$ESC]1337;File=inline=1:AAAA\u0007", "$ESC]4;1;?\u0007", "$ESC]10;?\u0007",
        )) { val e = engine(); e.send("keep"); e.send(seq); assertEquals("keep", e.row(0), seq.take(20)); assertTrue(e.droppedStrings >= 1, seq.take(20)) }
    }

    @Test fun deviceControlStringsAndApplicationCommandsChangeNothing() {
        untouched("${ESC}Pq#0;2;0;0;0#1~-$ESC\\")          // sixel
        untouched("${ESC}_Gf=24,s=1,v=1;AAAA$ESC\\")        // kitty graphics
        untouched("${ESC}^privacy$ESC\\"); untouched("${ESC}Xsos$ESC\\")
        untouched("${ESC}P\$qm$ESC\\")                      // DECRQSS
    }

    @Test fun queriesAndReportsAreParsedButNeverShownOrAnswered() {
        for (q in listOf("$ESC[6n", "$ESC[5n", "$ESC[c", "$ESC[>c", "$ESC[=c", "$ESC[?1\$p", "$ESC[?u", "$ESC[>4;2m", "$ESC[?6n", "$ESC[18t", "$ESC[0 q", "$ESC[!p")) {
            val e = engine(); e.send("keep$q"); assertEquals("keep", e.row(0), q.replace(ESC, "ESC")); assertEquals(0, e.unhandled, q.replace(ESC, "ESC"))
        }
    }

    @Test fun charsetSelectionsAreAbsorbed() {
        val e = engine(); e.send("a$ESC(B${ESC})0${ESC}#8${ESC}=b${ESC}>c"); assertEquals("abc", e.row(0)); assertEquals(0, e.unhandled)
    }

    @Test fun unknownSequencesAreCountedAndNeverShownAsText() {
        val e = engine(); e.send("a$ESC[99zb${ESC}%Gc${ESC}Zd"); assertEquals("abcd", e.row(0)); assertTrue(e.unhandled >= 2, "${e.unhandled}")
        assertTrue(e.unhandledSamples.all { it.length <= 24 })
        e.clear(); assertEquals(0, e.unhandled); assertTrue(e.unhandledSamples.isEmpty())
    }

    @Test fun anUnterminatedStringOrSequenceIsDroppedWithItsFrame() {
        val e = engine(); e.send("a$ESC]0;never ends"); e.send("b"); assertEquals("ab", e.row(0), "the next frame starts clean")
        val f = engine(); f.send("a$ESC[12;"); f.send("b"); assertEquals("ab", f.row(0))
        val g = engine(); g.send("aâ\u0082"); g.send("b"); assertTrue(g.row(0).endsWith("b"))
    }

    @Test fun cancelAndSubstituteEndASequence() {
        val e = engine(); e.send("a$ESC[12\u0018b$ESC]0;t\u001Ac"); assertEquals("abc", e.row(0))
    }

    @Test fun anEscapeInsideACsiStartsAFreshSequence() {
        val e = engine(); e.send("$ESC[12$ESC[3;4HX"); assertEquals("X", e.at(2, 3))
    }

    @Test fun aHugeStringDoesNotGrowMemory() {
        val e = engine(); val big = ByteArray(3_000_000) { 'x'.code.toByte() }
        e.feed(byteArrayOf(0x1B, ']'.code.toByte(), '0'.code.toByte(), ';'.code.toByte())); e.feed(byteArrayOf(0x1B, ']'.code.toByte()) + big + byteArrayOf(7))
        e.send("ok"); assertEquals("ok", e.row(0))
    }

    @Test fun resetToInitialStateClearsEverything() {
        val e = engine(); e.send("junk$ESC[1;31m$ESC[?25l$ESC[?1049h"); e.send("${ESC}c"); assertEquals(listOf("", "", "", "", "", ""), e.rows()); assertEquals(Cursor(0, 0, true), e.cursor); assertFalse(e.altScreen)
        e.send("X"); assertEquals(Triple(TermColor.Default, TermColor.Default, 0), style(e))
    }

    // --- resize and clear ---

    @Test fun resizeKeepsWhatStillFitsAndClampsTheCursor() {
        val e = engine(10, 4); e.send("0123456789\r\nabcdefghij$ESC[4;9H"); e.resize(6, 2)
        assertEquals(listOf("012345", "abcdef"), e.rows()); assertEquals(Cursor(1, 5, true), e.cursor)
        e.resize(8, 3); assertEquals(listOf("012345", "abcdef", ""), e.rows()); assertEquals(8, e.grid().cols)
    }

    @Test fun shrinkingThroughAWideCharacterBlanksIt() {
        val e = engine(6, 1); e.send("abcd日"); e.resize(5, 1); assertEquals("abcd", e.row(0)); assertFalse(e.grid().isWide(0, 4))
    }

    @Test fun clearForgetsContentModesAndPen() {
        val e = engine(); e.send("junk$ESC[1;31m$ESC[?25l"); e.clear(); assertEquals("", e.row(0)); assertEquals(Cursor(0, 0, true), e.cursor)
        e.send("X"); assertEquals(Triple(TermColor.Default, TermColor.Default, 0), style(e))
    }

    @Test fun aSnapshotDoesNotChangeWhenTheEngineDoes() {
        val e = engine(); e.send("one"); val g = e.grid(); e.send("\r2"); assertEquals("one", g.rowText(0)); assertEquals("2ne", e.row(0))
    }

    @Test fun badGeometryIsRefused() {
        val e = engine(); for ((c, r) in listOf(0 to 5, 5 to 0, 1001 to 5)) { try { e.resize(c, r); error("accepted $c x $r") } catch (_: IllegalArgumentException) { } }
        try { VtEngine(0, 0); error("accepted 0x0") } catch (_: IllegalArgumentException) { }
    }

    // --- robustness ---

    @Test fun randomBytesNeverThrowAndNeverBreakTheGrid() {
        val rnd = Random(20261002)
        val e = VtEngine(30, 10)
        val alphabet = "\u001B[];:?0123456789mHJKABCDfhlrstuPX@LM \"#()\\]_^"
        repeat(2000) {
            val chunk = ByteArray(rnd.nextInt(1, 400)) { if (rnd.nextInt(3) == 0) rnd.nextInt(256).toByte() else alphabet[rnd.nextInt(alphabet.length)].code.toByte() }
            e.feed(chunk)
            val c = e.cursor
            assertTrue(c.row in 0 until e.rows && c.col in 0 until e.cols, "cursor $c")
            val g = e.grid()
            for (r in 0 until g.rows) for (col in 0 until g.cols) {
                val wide = g.isWide(r, col); val cont = g.isContinuation(r, col)
                if (wide) { assertTrue(col + 1 < g.cols && g.isContinuation(r, col + 1), "wide at $r,$col has no continuation") }
                if (cont) { assertTrue(col > 0 && g.isWide(r, col - 1), "continuation at $r,$col has no wide cell") }
                assertFalse(g.text(r, col).any { it.code < 0x20 }, "control character in a cell")
            }
        }
    }
}
