package io.github.tuthan.paddock.output

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiTextTest {
    private val esc = "\u001B"
    private fun one(s: String) = Ansi.parse(s).single()

    @Test fun plainTextIsOneSpanPerLine() {
        val lines = Ansi.parse("alpha\nbeta\n")
        assertEquals(listOf("alpha", "beta"), lines.map { it.text })
        assertEquals(AnsiStyle(), lines[0].spans.single().style)
    }

    @Test fun anUnterminatedFinalLineIsStillALine() {
        assertEquals(listOf("a", "b"), Ansi.parse("a\nb").map { it.text })
        assertTrue(Ansi.parse("").isEmpty())
    }

    @Test fun sgrColoursAndWeightsBecomeSpans() {
        val line = one("plain $esc[31mred$esc[0m $esc[1;92mbright bold$esc[22;39m end")
        assertEquals(listOf("plain ", "red", " ", "bright bold", " end"), line.spans.map { it.text })
        assertEquals(AnsiColor.Red, line.spans[1].style.fg)
        assertEquals(AnsiStyle(AnsiColor.BrightGreen, bold = true), line.spans[3].style)
        assertEquals(AnsiStyle(), line.spans[4].style)
    }

    @Test fun brightBlackAndDimSurviveAsDataSoTheThemeCanMapThemToDim() {
        val line = one("$esc[90mgrey$esc[0m $esc[2mdim$esc[0m")
        assertEquals(AnsiColor.BrightBlack, line.spans[0].style.fg)
        assertTrue(line.spans[2].style.dim)
    }

    @Test fun anEmptySgrIsAReset() {
        val line = one("$esc[31mred$esc[mnone")
        assertNull(line.spans[1].style.fg)
    }

    @Test fun indexedColoursLandOnTheNearestOfTheSixteen() {
        assertEquals(AnsiColor.Red, one("$esc[38;5;1mx").spans.single().style.fg)
        assertEquals(AnsiColor.BrightRed, one("$esc[38;5;196mx").spans.single().style.fg) // cube 255,0,0
        assertEquals(AnsiColor.BrightBlack, one("$esc[38;5;244mx").spans.single().style.fg) // mid grey
        assertEquals(AnsiColor.Black, one("$esc[38;5;232mx").spans.single().style.fg)
    }

    @Test fun trueColourLandsOnTheNearestOfTheSixteenInBothSyntaxes() {
        assertEquals(AnsiColor.BrightGreen, one("$esc[38;2;10;250;5mx").spans.single().style.fg)
        assertEquals(AnsiColor.BrightGreen, one("$esc[38:2::10:250:5mx").spans.single().style.fg)
        assertEquals(AnsiColor.BrightGreen, one("$esc[38:2:10:250:5mx").spans.single().style.fg)
    }

    @Test fun backgroundArgumentsAreConsumedNotReadAsCodes() {
        // 48;5;31 must not leave "31" behind to be read as a red foreground; 48;2;1;2;3 must not leave 1 (bold) or 2 (dim).
        val a = one("$esc[48;5;31mx").spans.single().style
        assertEquals(AnsiStyle(), a)
        val b = one("$esc[48;2;1;2;3mx").spans.single().style
        assertEquals(AnsiStyle(), b)
        val c = one("$esc[48;5;31;32mx").spans.single().style
        assertEquals(AnsiColor.Green, c.fg)
    }

    @Test fun oscNeverReachesTheScreenWhetherBelOrStTerminated() {
        assertEquals("ab", one("a$esc]52;c;aGVsbG8=${'\u0007'}b").text)             // clipboard write
        assertEquals("ab", one("a$esc]8;;https://evil.example${esc}\\b").text)      // hyperlink open
        assertEquals("link", one("$esc]8;;https://evil.example${'\u0007'}link$esc]8;;${'\u0007'}").text)
        assertEquals("ab", one("a$esc]0;new title${'\u0007'}b").text)
    }

    @Test fun otherStringCommandsAreDropped() {
        assertEquals("ab", one("a${esc}Psixel-or-whatever$esc\\b").text)
        assertEquals("ab", one("a${esc}_apc payload$esc\\b").text)
        assertEquals("ab", one("a${esc}^pm payload$esc\\b").text)
    }

    @Test fun anUnterminatedStringCommandDoesNotSwallowTheWholeScreenForever() {
        val long = "x".repeat(10_000)
        val line = one("$esc]52;c;$long")
        assertFalse(line.text.contains(esc))
        assertTrue(line.text.length < 10_000) // the cap bounded what was dropped, the rest is just text
    }

    @Test fun cursorMovementEraseAndModeSequencesAreDropped() {
        assertEquals("ab", one("a$esc[2Jb").text)
        assertEquals("ab", one("a$esc[10;20Hb").text)
        assertEquals("ab", one("a$esc[?1049hb").text)
        assertEquals("ab", one("a${esc}7${esc}8b").text)
        assertEquals("ab", one("a$esc(Bb").text)
        assertEquals("ab", one("a${esc}cb").text)
    }

    @Test fun aCsiWithAnIntermediateByteIsNotSgr() {
        assertEquals(AnsiStyle(), one("$esc[31 mx").spans.single().style)
    }

    @Test fun controlCharactersAndBidiOverridesAreRemoved() {
        assertEquals("ab", one("a\u0007\u0008\rb").text)
        assertEquals("ab", one("a\u202Eb").text)
        assertEquals("ab", one("a\u009Bb").text) // 8-bit CSI as a decoded character
        assertEquals("a    b", one("a\tb").text)
    }

    @Test fun invisibleCharactersThatHideTextAreRemovedButJoinersStay() {
        assertEquals("ab", one("a\u200B\u2060\uFEFFb").text)
        assertEquals("ab", one("a\uDB40\uDC41\uDB40\uDC7Fb").text) // tag characters U+E0041, U+E007F
        assertEquals("ab", one("a\u2066\u2069\u200Fb").text)
        val coder = "\uD83D\uDC68\u200D\uD83D\uDCBB" // man technologist: a ZWJ sequence
        assertEquals("x${coder}y", one("x${coder}y").text)
    }

    @Test fun aCsiWithAPrivateMarkerIsNotSgrEvenWhenItEndsInM() {
        assertEquals(AnsiStyle(), one("$esc[>4;2mx").spans.single().style) // xterm modifyOtherKeys
        assertEquals(AnsiStyle(), one("$esc[?1;31mx").spans.single().style)
        assertEquals("x", one("$esc[>4;2mx").text)
    }

    @Test fun underlineColourArgumentsAreConsumedNotReadAsCodes() {
        assertEquals(AnsiStyle(), one("$esc[58;5;31mx").spans.single().style)
        assertEquals(AnsiStyle(bold = true), one("$esc[58;2;255;0;0;1mx").spans.single().style)
        assertEquals(AnsiStyle(fg = AnsiColor.Red), one("$esc[58:5:4;31mx").spans.single().style)
    }

    @Test fun safeTextKeepsWhatIsWrittenAndDropsWhatHidesOrReorders() {
        assertEquals("fix the build", SafeText.clean("fix the build"))
        assertEquals("ab", SafeText.clean("a\u202Eb"))
        assertEquals("a b", SafeText.clean("a\tb"))
        assertEquals("ab", SafeText.clean("a\u001B\u0007\u0085\u2028b"))
        assertEquals("ab", SafeText.clean("a\uDB40\uDC20b"))
        assertEquals("caf\u00E9 \u4F60\u597D \u05E9\u05DC\u05D5\u05DD", SafeText.clean("caf\u00E9 \u4F60\u597D \u05E9\u05DC\u05D5\u05DD"), "letters of any script stay")
    }

    @Test fun aTruncatedEscapeAtTheEndIsDropped() {
        assertEquals("a", one("a$esc").text)
        assertEquals("a", one("a$esc[").text)
        assertEquals("a", one("a$esc[3").text)
    }

    @Test fun veryLongLinesAndManyLinesAreBounded() {
        assertEquals(Ansi.MAX_LINE_CHARS, one("y".repeat(50_000)).text.length)
        val lines = Ansi.parse((1..800).joinToString("\n") { "l$it" }, maxLines = 200)
        assertEquals(200, lines.size)
        assertEquals("l800", lines.last().text)
        assertEquals("l601", lines.first().text)
    }

    @Test fun nothingThatComesInEverLeavesAnEscapeOrControlCharacterOut() {
        val rnd = Random(1234)
        val alphabet = "\u001B[]P_^X\\;:0123456789mHJ?>\u0007\r\n\tabc \u009B\u202E\u200B\uDB40\uDC41".toList()
        repeat(3_000) {
            val s = buildString { repeat(rnd.nextInt(0, 120)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            val out = Ansi.parse(s) // must not throw
            for (line in out) for (c in line.text) {
                assertTrue(c >= ' ' && c != '\u007F' && c !in '\u0080'..'\u009F' && c != '\u001B' && c != '\u202E' && c != '\u200B', "leaked U+%04X from %s".format(c.code, s.replace("\u001B", "<ESC>")))
            }
        }
    }
}
